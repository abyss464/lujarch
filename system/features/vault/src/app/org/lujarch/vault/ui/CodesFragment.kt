/*
 * Copyright (C) 2026 The Lujarch Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lujarch.vault.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.EditText
import android.widget.SearchView
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.ZeroStatePreference
import java.text.Collator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.showDialog
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.PendingImport
import org.lujarch.vault.core.aegis.AegisVault
import org.lujarch.vault.core.formats.CodeFiles
import org.lujarch.vault.core.otp.Base32
import org.lujarch.vault.core.otp.InvalidOtp
import org.lujarch.vault.core.otp.Otp
import org.lujarch.vault.core.otp.OtpParams
import org.lujarch.vault.core.otp.OtpType
import org.lujarch.vault.data.CodesData
import org.lujarch.vault.data.Clipboard
import org.lujarch.vault.data.PasswordData
import org.lujarch.vault.data.Source
import org.lujarch.vault.data.Sources
import org.lujarch.vault.data.Vault
import org.lujarch.vault.data.VaultState

/**
 * Every 2FA code with its countdown: the accounts of the 2FA codes first
 * (favorites on top), then the password entries with OTP settings. Tap
 * copies the code. Accounts are added (a QR code from the camera or an
 * image, a typed key, an otpauth link, a Google Authenticator export QR
 * code, another file of an authenticator app) and a long press on one
 * edits, favors, advances or deletes it; password entries are changed
 * where their file is edited.
 */
class CodesFragment : BasePreferenceFragment(), MenuProvider {
    private lateinit var accountsCategory: PreferenceCategory
    private lateinit var passwordsCategory: PreferenceCategory
    private lateinit var messagePref: ZeroStatePreference

    private var shownCodes: CodesData? = null
    private var shownPasswords: PasswordData? = null
    /** OTP settings parsed from password entries; wiped when the list is rebuilt. */
    private val passwordParams = ArrayList<OtpParams>()
    private val codePrefs = ArrayList<CodePreference>()
    private var query = ""

    private val scanCamera = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) takePending()
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImagePicked(uri)
    }
    private val pickCodes = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onCodesPicked(uri)
    }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        query = savedInstanceState?.getString(STATE_QUERY) ?: ""
        messagePref = screen.add(ZeroStatePreference(prefContext)) {
            key = "message"
            setIcon(R.drawable.ic_codes)
            isVisible = false
        }
        accountsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "accounts"
            setTitle(R.string.codes_accounts)
            isOrderingAsAdded = true
        }
        passwordsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "passwords"
            setTitle(R.string.codes_passwords)
            isOrderingAsAdded = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_QUERY, query)
    }

    override fun onViewCreated(view: android.view.View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Codes change every period: redraw every second while the page is visible.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    val now = System.currentTimeMillis()
                    codePrefs.forEach { if (it.isVisible) it.tick(now) }
                    delay(1000 - now % 1000)
                }
            }
        }
        if (savedInstanceState == null && arguments?.getBoolean(ARG_TAKE_PENDING) == true) takePending()
    }

    override fun onDestroy() {
        wipeParams()
        super.onDestroy()
    }

    private fun wipeParams() {
        passwordParams.forEach { it.wipe() }
        passwordParams.clear()
    }

    override fun onVaultState(state: VaultState) {
        val codes = state.codesData
        val passwords = state.passwordsData
        if (codes !== shownCodes || passwords !== shownPasswords) rebuild(codes, passwords)
        val message = when {
            codes != null || passwords != null -> if (codePrefs.isEmpty()) R.string.codes_none else 0
            !state.anySetUp -> R.string.status_not_set_up
            !state.anyActive -> R.string.status_off
            state.loading -> R.string.status_unlocking
            state.codes is Source.Failed || state.passwords is Source.Failed -> R.string.status_failed
            else -> R.string.status_locked
        }
        messagePref.isVisible = message != 0
        if (message != 0) messagePref.setTitle(message)
    }

    private fun rebuild(codes: CodesData?, passwords: PasswordData?) {
        shownCodes = codes
        shownPasswords = passwords
        accountsCategory.removeAll()
        passwordsCategory.removeAll()
        codePrefs.clear()
        wipeParams()
        val collator = Collator.getInstance()

        codes?.entries
            ?.sortedWith(compareBy<org.lujarch.vault.core.aegis.AegisEntry> { !it.favorite }
                .thenComparator { a, b -> collator.compare(a.issuer.ifEmpty { a.name }, b.issuer.ifEmpty { b.name }) })
            ?.forEach { e ->
                val label = listOf(e.issuer, e.name).filter { it.isNotEmpty() }.joinToString(" · ")
                val account = AccountRef(e.uuid, e.issuer, e.name, label, e.favorite, e.otp?.type == OtpType.HOTP)
                addCode(accountsCategory, e.otp, label, "accounts:${e.uuid}").onLongClick = { showActions(account) }
            }

        passwords?.db?.entries
            ?.filter { Otp.hasOtp(it) }
            ?.sortedWith { a, b -> collator.compare(a.title, b.title) }
            ?.forEach { e ->
                val params = try {
                    Otp.fromEntry(e)
                } catch (ex: Exception) {
                    // A broken otp field is shown as unsupported; its content is never logged.
                    Log.w(TAG, "OTP settings of a password entry cannot be read: ${ex.message}")
                    null
                }
                params?.let { passwordParams.add(it) }
                val label = listOf(e.title, e.username).filter { it.isNotEmpty() }.joinToString(" · ")
                addCode(passwordsCategory, params, label, "passwords:${e.uuid}")
            }
        applyFilter()
    }

    private fun addCode(category: PreferenceCategory, params: OtpParams?, label: String, key: String): CodePreference {
        val pref = CodePreference(prefContext, params, label).apply {
            this.key = key
            setOnPreferenceClickListener {
                copy(this)
                true
            }
        }
        category.addPreference(pref)
        codePrefs.add(pref)
        pref.tick(System.currentTimeMillis())
        return pref
    }

    private fun copy(pref: CodePreference) {
        val code = pref.currentCode ?: return
        Clipboard.copy(requireContext(), getString(R.string.clip_label_code), code)
        Toast.makeText(requireContext(), R.string.copied_code, Toast.LENGTH_SHORT).show()
    }

    private fun applyFilter() {
        val needle = query.trim().lowercase()
        codePrefs.forEach { p ->
            p.isVisible = needle.isEmpty() || p.summary?.toString()?.lowercase()?.contains(needle) == true
        }
        accountsCategory.isVisible = codePrefs.any { it.parent == accountsCategory && it.isVisible }
        passwordsCategory.isVisible = codePrefs.any { it.parent == passwordsCategory && it.isVisible }
    }

    // ---- menu ----

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menu.add(Menu.NONE, MENU_ADD, 1, R.string.codes_add).setIcon(R.drawable.ic_add)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        val item = menu.add(Menu.NONE, MENU_SEARCH, 0, R.string.menu_search).setIcon(R.drawable.ic_search)
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        val searchView = SearchView(requireActivity().actionBar?.themedContext ?: requireContext())
        searchView.queryHint = getString(R.string.codes_search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(text: String?): Boolean {
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(text: String?): Boolean {
                query = text ?: ""
                applyFilter()
                return true
            }
        })
        item.actionView = searchView
        if (query.isNotEmpty()) {
            val saved = query
            item.expandActionView()
            searchView.setQuery(saved, false)
        }
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != MENU_ADD) return false
        showAddChoice()
        return true
    }

    // ---- adding and changing accounts ----

    /** What a long press needs of an account: plain names, never its key. */
    private class AccountRef(
        val uuid: String,
        val issuer: String,
        val name: String,
        val label: String,
        val favorite: Boolean,
        val hotp: Boolean,
    )

    private fun showAddChoice() {
        if (!Vault.codesActive()) {
            Toast.makeText(requireContext(), R.string.codes_add_needs_source, Toast.LENGTH_LONG).show()
            return
        }
        showDialog("code-add") { d ->
            val choices = arrayOf(
                getString(R.string.codes_add_scan), getString(R.string.codes_add_image),
                getString(R.string.codes_add_manual), getString(R.string.codes_add_import),
            )
            AlertDialog.Builder(d.context)
                .setTitle(R.string.codes_add)
                .setItems(choices) { _, which ->
                    when (which) {
                        0 -> scanCamera.launch(Intent(requireContext(), ScanActivity::class.java))
                        1 -> pickImage.launch(IMAGE_TYPES)
                        2 -> showEntryDialog(null)
                        else -> pickCodes.launch(CODE_FILE_TYPES)
                    }
                }
                .create()
        }
    }

    /**
     * Another file of an authenticator app (any format CodeFiles reads): its
     * accounts that the 2FA codes do not hold yet are added, after its
     * password opened an encrypted one; the 2FA codes keep their own
     * password. The picked bytes stay with the password dialog and are wiped
     * when it ends unanswered.
     */
    private fun onCodesPicked(uri: Uri) {
        launchUi {
            val ctx = requireContext()
            val holder = arrayOfNulls<ByteArray>(1)
            var encrypted = true
            try {
                val ok = act(R.string.error_read_file) {
                    val bytes = withContext(Dispatchers.IO) { Sources.readDocument(ctx, uri, MAX_CODE_FILE_BYTES) }
                    holder[0] = bytes
                    encrypted = withContext(Dispatchers.Default) { CodeFiles.detect(bytes).encrypted }
                }
                if (!ok) {
                    holder[0]?.let { Bytes.wipe(it) }
                    return@launchUi
                }
            } catch (e: CancellationException) {
                holder[0]?.let { Bytes.wipe(it) }
                throw e
            }
            val bytes = holder[0] ?: return@launchUi
            if (!encrypted) {
                runMerge(bytes, null)
                return@launchUi
            }
            val pending = PendingImport(bytes)
            showDialog(DIALOG_IMPORT, onEnd = { Log.i(TAG, "CodesFragment: " + pending.end()) }) { d ->
                passwordDialog(d, R.string.setup_file_password, getString(R.string.codes_import_message)) { chars ->
                    val taken = pending.take()
                    val password = utf8(chars)
                    Bytes.wipe(chars)
                    if (taken == null) {
                        Bytes.wipe(password)
                        Log.w(TAG, "CodesFragment: 2FA import answered after its file was wiped")
                    } else {
                        runMerge(taken, password)
                    }
                }
            }
        }
    }

    private fun runMerge(bytes: ByteArray, password: ByteArray?) {
        launchUi {
            try {
                act(R.string.error_import_accounts) {
                    val r = Vault.importCodes(bytes, password)
                    imported(r)
                }
            } finally {
                Bytes.wipe(bytes)
                password?.let { Bytes.wipe(it) }
            }
        }
    }

    private fun imported(r: Vault.MergeResult) {
        Log.i(TAG, "CodesFragment: ${r.added} of ${r.total} accounts imported, ${r.skipped} not readable")
        ifPageAlive {
            var text = resources.getQuantityString(R.plurals.codes_imported, r.added, r.added, r.total - r.added)
            if (r.skipped > 0) text += "\n" + resources.getQuantityString(R.plurals.codes_not_readable, r.skipped, r.skipped)
            Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
        }
    }

    /** A QR code read by the scanner, or an otpauth link opened from elsewhere (PendingOtp). */
    private fun takePending() {
        val bytes = PendingOtp.take()?.take() ?: return
        try {
            onScanned(String(bytes, Charsets.UTF_8))
        } finally {
            Bytes.wipe(bytes)
        }
    }

    private fun onImagePicked(uri: Uri) {
        launchUi {
            val ctx = requireContext()
            var text: String? = null
            if (!act(R.string.error_read_image) { text = withContext(Dispatchers.Default) { QrImage.decode(ctx, uri) } }) return@launchUi
            val found = text
            if (found == null) {
                Log.i(TAG, "CodesFragment: no QR code in the picked image")
                Toast.makeText(ctx, R.string.codes_no_qr, Toast.LENGTH_LONG).show()
            } else {
                onScanned(found)
            }
        }
    }

    /**
     * A scanned text: an otpauth link opens the account dialog filled in, a
     * Google Authenticator export (otpauth-migration) lists its accounts to
     * add, anything else is refused.
     */
    private fun onScanned(text: String) {
        if (CodeFiles.isMigration(text)) {
            val accounts = try {
                CodeFiles.migration(text)
            } catch (e: Exception) {
                toastError(R.string.codes_add, e)
                return
            }
            if (accounts.entries.isEmpty()) {
                toastError(R.string.codes_add, InvalidOtp("no account in the export"))
                return
            }
            confirmAccounts(accounts)
            return
        }
        val params = try {
            Otp.parseUri(text)
        } catch (e: InvalidOtp) {
            toastError(R.string.codes_add, e)
            return
        }
        showEntryDialog(params)
    }

    /**
     * The new account's dialog, filled from [prefill] (a scanned link; wiped
     * when the dialog ends) or empty for a typed key. It stays open on an
     * invalid entry.
     */
    private fun showEntryDialog(prefill: OtpParams?) {
        showDialog("code-entry", onEnd = { prefill?.wipe() }) { d ->
            val view = LayoutInflater.from(d.context).inflate(R.layout.dialog_code_entry, null)
            val fields = EntryFields(view)
            d.secret(fields.secret)
            prefill?.let { fields.fill(it) }
            val dialog = AlertDialog.Builder(d.context)
                .setTitle(R.string.codes_add)
                .setView(view)
                .setPositiveButton(R.string.codes_add_confirm, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val params = try {
                        fields.read()
                    } catch (e: InvalidOtp) {
                        Toast.makeText(d.context, Errors.describe(d.context, e), Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    addAccount(params)
                }
            }
            dialog
        }
    }

    /** The accounts of an export QR code, by name, to add after a confirmation. */
    private fun confirmAccounts(accounts: CodeFiles.Accounts) {
        val names = accounts.entries.joinToString("\n") { e ->
            listOf(e["issuer"] as? String, e["name"] as? String).filter { !it.isNullOrEmpty() }.joinToString(" · ")
        }
        val n = accounts.entries.size
        showDialog("code-add-several") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(resources.getQuantityString(R.plurals.codes_add_several, n, n))
                .setMessage(names)
                .setPositiveButton(R.string.codes_add_confirm) { _, _ ->
                    launchUi { act(R.string.error_change_codes) { imported(Vault.addCodes(accounts)) } }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun addAccount(params: OtpParams) {
        launchUi {
            try {
                changeCodes(R.string.codes_added, "account added") { entries -> entries.add(AegisVault.newEntry(params)) }
            } finally {
                params.wipe()
            }
        }
    }

    private fun showActions(a: AccountRef) {
        showDialog("code-actions") { d ->
            val actions = listOfNotNull(
                R.string.codes_edit,
                if (a.favorite) R.string.codes_unfavorite else R.string.codes_favorite,
                R.string.codes_next.takeIf { a.hotp },
                R.string.codes_delete,
            )
            AlertDialog.Builder(d.context)
                .setTitle(a.label)
                .setItems(actions.map { getString(it) }.toTypedArray()) { _, which ->
                    when (actions[which]) {
                        R.string.codes_edit -> showEditDialog(a)
                        R.string.codes_next -> changeEntry(a.uuid, 0, "HOTP counter advanced") { e ->
                            @Suppress("UNCHECKED_CAST")
                            val info = e["info"] as? MutableMap<String, Any?> ?: throw IllegalStateException("account without info")
                            info["counter"] = ((info["counter"] as? Long) ?: 0L) + 1
                        }
                        R.string.codes_delete -> confirm(
                            "code-delete", R.string.codes_delete_title,
                            { getString(R.string.codes_delete_message, a.label) }, R.string.codes_delete,
                        ) {
                            launchUi {
                                changeCodes(R.string.codes_deleted, "account deleted") { entries ->
                                    if (!entries.removeAll { it["uuid"] == a.uuid }) throw IllegalStateException("the account is no longer in the vault")
                                }
                            }
                        }
                        else -> changeEntry(a.uuid, 0, "favorite changed") { e -> e["favorite"] = !a.favorite }
                    }
                }
                .create()
        }
    }

    private fun showEditDialog(a: AccountRef) {
        showDialog("code-edit") { d ->
            val view = LayoutInflater.from(d.context).inflate(R.layout.dialog_code_entry, null)
            view.findViewById<View>(R.id.otp_group).visibility = View.GONE
            val issuer = view.findViewById<EditText>(R.id.issuer)
            val account = view.findViewById<EditText>(R.id.account)
            // A recreated dialog gets the texts typed so far back over these.
            issuer.setText(a.issuer)
            account.setText(a.name)
            AlertDialog.Builder(d.context)
                .setTitle(R.string.codes_edit)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val newIssuer = issuer.text.toString().trim()
                    val newName = account.text.toString().trim()
                    changeEntry(a.uuid, R.string.codes_saved, "account renamed") { e ->
                        e["issuer"] = newIssuer
                        e["name"] = newName
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun changeEntry(uuid: String, @StringRes done: Int, what: String, change: (MutableMap<String, Any?>) -> Unit) {
        launchUi {
            changeCodes(done, what) { entries ->
                change(entries.firstOrNull { it["uuid"] == uuid } ?: throw IllegalStateException("the account is no longer in the vault"))
            }
        }
    }

    private suspend fun changeCodes(@StringRes done: Int, what: String, change: (MutableList<MutableMap<String, Any?>>) -> Unit) {
        if (act(R.string.error_change_codes) { Vault.changeCodes(what, change) } && done != 0) {
            ifPageAlive { Toast.makeText(requireContext(), done, Toast.LENGTH_SHORT).show() }
        }
    }

    companion object {
        /** Opened for an otpauth link (OtpLinkActivity): take it from PendingOtp. */
        const val ARG_TAKE_PENDING = "take_pending"

        private const val STATE_QUERY = "query"
        private const val MENU_SEARCH = 1
        private const val MENU_ADD = 2
        private val IMAGE_TYPES = arrayOf("image/*")
        /** The files of authenticator apps have no one registered type: any file. */
        private val CODE_FILE_TYPES = arrayOf("*/*")
        private const val MAX_CODE_FILE_BYTES = 16 * 1024 * 1024
        private const val DIALOG_IMPORT = "code-import-password"
    }
}

/** The fields of the new account's dialog (dialog_code_entry). */
private class EntryFields(view: View) {
    val secret: EditText = view.findViewById(R.id.secret)
    private val issuer: EditText = view.findViewById(R.id.issuer)
    private val account: EditText = view.findViewById(R.id.account)
    private val type: Spinner = view.findViewById(R.id.type)
    private val algorithm: Spinner = view.findViewById(R.id.algorithm)
    private val digits: EditText = view.findViewById(R.id.digits)
    private val period: EditText = view.findViewById(R.id.period)
    private val counter: EditText = view.findViewById(R.id.counter)
    private val periodLabel: View = view.findViewById(R.id.period_label)
    private val counterLabel: View = view.findViewById(R.id.counter_label)

    init {
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) = showFor(TYPES[position])
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        showFor(OtpType.TOTP)
    }

    /** HOTP counts, the others follow the clock. */
    private fun showFor(t: OtpType) {
        val hotp = t == OtpType.HOTP
        listOf(period, periodLabel).forEach { it.visibility = if (hotp) View.GONE else View.VISIBLE }
        listOf(counter, counterLabel).forEach { it.visibility = if (hotp) View.VISIBLE else View.GONE }
    }

    fun fill(p: OtpParams) {
        issuer.setText(p.issuer)
        account.setText(p.account)
        val key = p.secret.revealBytes()
        try {
            secret.setText(Base32.encode(key))
        } finally {
            Bytes.wipe(key)
        }
        type.setSelection(TYPES.indexOf(p.type))
        algorithm.setSelection(ALGORITHMS.indexOf(p.algorithm.uppercase().replace("-", "").removePrefix("HMAC")).coerceAtLeast(0))
        digits.setText(p.digits.toString())
        period.setText(p.period.toString())
        counter.setText(p.counter.toString())
        showFor(p.type)
    }

    /** The account as entered; an otpauth link in the key field brings its own parameters, typed names win. */
    fun read(): OtpParams {
        val key = secret.text.toString().trim()
        val issuerText = issuer.text.toString().trim()
        val accountText = account.text.toString().trim()
        if (key.startsWith("otpauth://", ignoreCase = true)) {
            val p = Otp.parseUri(key)
            return OtpParams(
                p.type, p.secret, p.algorithm, p.digits, p.period, p.counter,
                issuerText.ifEmpty { p.issuer }, accountText.ifEmpty { p.account },
            )
        }
        val t = TYPES[type.selectedItemPosition]
        return Otp.manual(
            type = t,
            secretBase32 = key,
            algorithm = ALGORITHMS[algorithm.selectedItemPosition],
            // Steam codes are always five characters.
            digits = if (t == OtpType.STEAM) 5 else digits.text.toString().toIntOrNull() ?: throw InvalidOtp("digits missing"),
            period = period.text.toString().toIntOrNull() ?: 30,
            counter = counter.text.toString().toLongOrNull() ?: 0,
            issuer = issuerText,
            account = accountText,
        )
    }

    companion object {
        /** In the order of @array/code_types and @array/code_algorithms. */
        val TYPES = listOf(OtpType.TOTP, OtpType.HOTP, OtpType.STEAM)
        val ALGORITHMS = listOf("SHA1", "SHA256", "SHA512")
    }
}
