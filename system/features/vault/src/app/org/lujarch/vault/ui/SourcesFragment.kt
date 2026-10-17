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

import android.app.Dialog
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import java.nio.CharBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.DialogScope
import org.lujarch.settings.pages.showDialog
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.core.Bytes
import org.lujarch.vault.core.PendingImport
import org.lujarch.vault.core.formats.CodeFiles
import org.lujarch.vault.data.SyncFiles
import org.lujarch.vault.data.DbSource
import org.lujarch.vault.data.DocumentInfo
import org.lujarch.vault.data.FoundDatabases
import org.lujarch.vault.data.KeyFileChoice
import org.lujarch.vault.data.PasswordNeededException
import org.lujarch.vault.data.SkippedDatabase
import org.lujarch.vault.data.SourceUnavailableException
import org.lujarch.vault.data.Sources
import org.lujarch.vault.data.Vault
import org.lujarch.vault.data.VaultState

/**
 * The vault's sources (VLT-2): the passwords' file (in a synced folder or a
 * document chosen with the system file picker; its format read from its
 * content), with its password and, for a KDBX database, its key file; the
 * 2FA accounts, taken from a file of an authenticator app into the vault's
 * own file, with its password (the picked file's, or a new one for a file
 * without). Each source can be turned off, replaced and set up again. A
 * setup opens the source to check the password, then only the derived key
 * is stored, wrapped by the Keystore (VLT-1). Passwords live in char arrays
 * wiped after use; a picked key file only as its derived key, in memory.
 * The dialogs are page dialogs: a recreation brings them back, the password
 * fields empty.
 */
class SourcesFragment : BasePreferenceFragment() {
    private lateinit var pwUse: SwitchPreferenceCompat
    private lateinit var pwFile: Preference
    private lateinit var pwKeyFile: Preference
    private lateinit var pwSetup: Preference
    private lateinit var cdUse: SwitchPreferenceCompat
    private lateinit var cdFile: Preference
    private lateinit var cdSetup: Preference

    /** The databases in the synced folders, searched when the page resumes; null when the search failed. */
    private var found: FoundDatabases? = null
    /** Facts of the chosen document, or why it cannot be read. */
    private var documentInfo: DocumentInfo? = null
    private var documentProblem: SourceUnavailableException.Reason? = null
    /** Chosen while the passwords are set up, and not opened by the stored key: waits for its password. */
    private var pendingSource: DbSource? = null
    /** What the chosen file that is not set up yet needs, read when the page resumes; null while unknown. */
    private var fileKind: Vault.PasswordFileKind? = null
    /** Why the chosen file that is not set up yet cannot be used. */
    private var fileProblem: Throwable? = null
    /** The key file of the next setup; null: the imported one when present, else none. */
    private var keyFile: KeyFileChoice? = null
    private var passwordsBusy = 0
    private var codesBusy = false
    /** Opened from the home page's "choose again": show the choice as soon as the page is ready. */
    private var rechoose = false

    private val pickDatabase = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onDatabasePicked(uri)
    }
    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onKeyFilePicked(uri)
    }
    private val pickCodes = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onCodesPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rechoose = savedInstanceState == null && arguments?.getBoolean(ARG_RECHOOSE) == true
        // A recreation hands the picked key file on in memory (never in the saved state), so a
        // restored password dialog sets up with the key file it names.
        keyFile = savedInstanceState?.getString(STATE_KEY_FILE)?.let { takeHandedOn(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (keyFile as? KeyFileChoice.Picked)?.let { picked ->
            val token = handedOn?.takeIf { it.second === picked }?.first ?: "${System.nanoTime()}"
            handedOn = token to picked
            outState.putString(STATE_KEY_FILE, token)
        }
    }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val pw = screen.add(PreferenceCategory(prefContext)) {
            key = "passwords"
            setTitle(R.string.sources_passwords)
        }
        pwUse = pw.add(SwitchPreferenceCompat(prefContext)) {
            key = "pw_use"
            setTitle(R.string.sources_passwords_use)
            setIcon(R.drawable.ic_key)
            isPersistent = false
            setOnPreferenceChangeListener { _, v ->
                Vault.setPasswordsEnabled(v as Boolean)
                render()
                true
            }
        }
        pwFile = pw.add(Preference(prefContext)) {
            key = "pw_file"
            setTitle(R.string.sources_file)
            setIcon(R.drawable.ic_folder)
            setOnPreferenceClickListener {
                showDatabaseChooser()
                true
            }
        }
        pwKeyFile = pw.add(Preference(prefContext)) {
            key = "pw_key_file"
            setTitle(R.string.sources_key_file)
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                showKeyFileChooser()
                true
            }
        }
        pwSetup = pw.add(Preference(prefContext)) {
            key = "pw_setup"
            setIcon(R.drawable.ic_check)
            setOnPreferenceClickListener {
                setUpPasswords()
                true
            }
        }

        val cd = screen.add(PreferenceCategory(prefContext)) {
            key = "codes"
            setTitle(R.string.sources_codes)
        }
        cdUse = cd.add(SwitchPreferenceCompat(prefContext)) {
            key = "cd_use"
            setTitle(R.string.sources_codes_use)
            setIcon(R.drawable.ic_codes)
            isPersistent = false
            setOnPreferenceChangeListener { _, v ->
                Vault.setCodesEnabled(v as Boolean)
                render()
                true
            }
        }
        cdFile = cd.add(Preference(prefContext)) {
            key = "cd_accounts"
            setTitle(R.string.sources_accounts)
            setIcon(R.drawable.ic_folder)
            setOnPreferenceClickListener {
                launchPicker { pickCodes.launch(CODE_FILE_TYPES) }
                true
            }
        }
        cdSetup = cd.add(Preference(prefContext)) {
            key = "cd_setup"
            setIcon(R.drawable.ic_check)
            setOnPreferenceClickListener {
                askCodesPassword()
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.setup_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        // Recreated: the next page takes the picked key file (onSaveInstanceState); any other end wipes it.
        val picked = keyFile as? KeyFileChoice.Picked
        if (picked != null && activity?.isChangingConfigurations != true) {
            if (handedOn?.second === picked) handedOn = null
            picked.wipe()
        }
        keyFile = null
        super.onDestroy()
    }

    override fun onDialogLost(key: String) {
        if (key == DIALOG_CODES_IMPORT || key == DIALOG_CODES_NEW_PASSWORD) {
            // The picked file went with the restarted process before its password was given.
            Log.i(TAG, "SourcesFragment: the 2FA import's password dialog was not restored (restarted process); the file must be picked again")
            Toast.makeText(requireContext(), R.string.sources_codes_pick_again, Toast.LENGTH_LONG).show()
        }
    }

    override fun onVaultState(state: VaultState) = render()

    private fun currentSource(): DbSource? = pendingSource ?: Vault.prefs.passwordsSource

    /** What the current file needs: as read for a file not set up yet, as recorded for the one set up. */
    private fun currentKind(): Vault.PasswordFileKind? =
        if (pendingSource != null || !Vault.passwordsSetUp()) {
            fileKind
        } else {
            when (Vault.prefs.passwordsKind) {
                Vault.KIND_PLAIN -> Vault.PasswordFileKind.PLAIN
                Vault.KIND_ENCRYPTED -> Vault.PasswordFileKind.ENCRYPTED
                else -> Vault.PasswordFileKind.KDBX
            }
        }

    // ---- state of the page ----

    /** Searches the synced folders and asks the chosen document for its facts, then renders. */
    private fun refresh() {
        launchUi {
            val ctx = requireContext()
            try {
                found = SyncFiles.find()
                showBanner(null, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "searching the synced folders failed: ${e.message}")
                ensureActive()
                found = null
                // Only a problem when the database is to come from a synced folder.
                if (currentSource() !is DbSource.Document) {
                    showBanner(getString(R.string.error_list_folder), Errors.describe(ctx, e))
                } else {
                    showBanner(null, null)
                }
            }
            documentInfo = null
            documentProblem = null
            (currentSource() as? DbSource.Document)?.let { doc ->
                try {
                    documentInfo = withContext(Dispatchers.IO) { Sources.documentInfo(ctx, doc.uri) }
                    if (!Sources.hasGrant(ctx, doc.uri)) documentProblem = SourceUnavailableException.Reason.PERMISSION
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SourceUnavailableException) {
                    ensureActive()
                    documentProblem = e.reason
                } catch (e: Exception) {
                    Log.w(TAG, "reading the facts of the chosen database failed: ${e.javaClass.simpleName}: ${e.message}")
                    ensureActive()
                }
            }
            fileKind = null
            fileProblem = null
            val chosen = currentSource()
            if (chosen != null && (pendingSource != null || !Vault.passwordsSetUp()) && documentProblem == null) {
                try {
                    fileKind = Vault.passwordFileKind(chosen)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "the chosen password file cannot be used: ${e.javaClass.simpleName}: ${e.message}")
                    ensureActive()
                    fileProblem = e
                }
            }
            render()
            if (rechoose) {
                rechoose = false
                if (currentSource() is DbSource.Document) launchPicker { pickDatabase.launch(DATABASE_TYPES) } else showDatabaseChooser()
            }
        }
    }

    private fun render() {
        if (!isAdded) return
        val ctx = requireContext()
        val prefs = Vault.prefs

        val pwSetUp = Vault.passwordsSetUp()
        pwUse.isVisible = pwSetUp
        pwUse.isChecked = prefs.passwordsEnabled
        pwUse.setSummary(if (prefs.passwordsEnabled) R.string.sources_passwords_on else R.string.sources_passwords_off)
        pwUse.isEnabled = passwordsBusy == 0
        pwFile.summary = if (passwordsBusy == R.string.sources_switching) getString(passwordsBusy) else databaseSummary()
        pwFile.isEnabled = passwordsBusy == 0
        val kind = currentKind()
        pwKeyFile.summary = keyFileSummary()
        pwKeyFile.isEnabled = passwordsBusy == 0
        // A key file belongs to KDBX databases only.
        pwKeyFile.isVisible = kind == null || kind == Vault.PasswordFileKind.KDBX
        pwSetup.setTitle(if (pwSetUp) R.string.sources_passwords_setup_again else R.string.sources_passwords_setup)
        pwSetup.summary = when {
            passwordsBusy == R.string.setup_checking -> getString(R.string.setup_checking)
            currentSource() == null -> getString(R.string.sources_file_none)
            kind == Vault.PasswordFileKind.PLAIN -> getString(R.string.sources_passwords_setup_plain_summary)
            pwSetUp && kind == Vault.PasswordFileKind.ENCRYPTED -> getString(R.string.sources_passwords_setup_again_encrypted_summary)
            pwSetUp -> getString(R.string.sources_passwords_setup_again_summary)
            else -> getString(R.string.sources_passwords_setup_summary)
        }
        pwSetup.isEnabled = passwordsBusy == 0 && currentSource() != null

        val cdSetUp = Vault.codesSetUp()
        cdUse.isVisible = cdSetUp
        cdUse.isChecked = prefs.codesEnabled
        cdUse.setSummary(if (prefs.codesEnabled) R.string.sources_codes_on else R.string.sources_codes_off)
        cdUse.isEnabled = !codesBusy
        val f = Vault.codesFile
        cdFile.summary = if (f.isFile) {
            getString(
                R.string.sources_accounts_present,
                prefs.codesSourceName.ifEmpty { getString(R.string.sources_accounts_unnamed) },
                Format.dateTime(ctx, f.lastModified()),
            )
        } else {
            getString(R.string.sources_accounts_missing)
        }
        cdFile.isEnabled = !codesBusy
        cdSetup.isVisible = f.isFile
        cdSetup.setTitle(if (cdSetUp) R.string.sources_codes_setup_again else R.string.sources_codes_setup)
        cdSetup.summary = getString(if (codesBusy) R.string.setup_checking else R.string.sources_codes_setup_summary)
        cdSetup.isEnabled = !codesBusy
    }

    private fun folderLabel(folder: String, result: FoundDatabases? = found): String = result?.labels?.get(folder) ?: folder

    private fun databaseSummary(): String {
        val src = currentSource() ?: return getString(R.string.sources_file_none)
        val ctx = requireContext()
        val lines = ArrayList<String>()
        when (src) {
            is DbSource.Synced -> {
                lines += getString(R.string.sources_file_synced, src.path, folderLabel(src.folder))
                val list = found
                val file = list?.files?.firstOrNull { it.folder == src.folder && it.path == src.path }
                val skipped = list?.skipped?.firstOrNull { it.folder == src.folder && it.path == src.path }
                when {
                    file != null -> lines += getString(R.string.sources_modified, Format.dateTime(ctx, file.mtime))
                    skipped != null -> lines += getString(R.string.sources_file_unavailable, skipReason(skipped))
                    list != null -> lines += getString(R.string.sources_file_missing)
                }
            }
            is DbSource.Document -> {
                val provider = Sources.providerLabel(ctx, src.uri)
                lines += if (provider != null) {
                    getString(R.string.sources_file_document, src.fileName, provider)
                } else {
                    getString(R.string.sources_file_document_plain, src.fileName)
                }
                when (documentProblem) {
                    SourceUnavailableException.Reason.PERMISSION -> lines += getString(R.string.sources_file_no_access)
                    SourceUnavailableException.Reason.MISSING -> lines += getString(R.string.sources_file_missing)
                    else -> documentInfo?.takeIf { it.mtime > 0 }?.let {
                        lines += getString(R.string.sources_modified, Format.dateTime(ctx, it.mtime))
                    }
                }
            }
        }
        fileProblem?.let { lines += Errors.describe(ctx, it) }
        if (pendingSource != null) lines += getString(R.string.sources_file_pending)
        return lines.joinToString("\n")
    }

    private fun effectiveKeyFile(): KeyFileChoice =
        keyFile ?: if (Vault.importedKeyFile.isFile) KeyFileChoice.Imported else KeyFileChoice.None

    private fun keyFileSummary(): String {
        val ctx = requireContext()
        return when (val k = effectiveKeyFile()) {
            KeyFileChoice.None -> getString(
                if (Vault.passwordsSetUp() && Vault.prefs.usesKeyFile) R.string.key_file_summary_stored else R.string.key_file_summary_none,
            )
            KeyFileChoice.Imported -> getString(R.string.key_file_summary_imported, Format.bytes(ctx, Vault.importedKeyFile.length()))
            is KeyFileChoice.Picked -> getString(R.string.key_file_summary_picked, k.name, Format.bytes(ctx, k.size))
        }
    }

    private fun keyFileName(): String = when (val k = effectiveKeyFile()) {
        KeyFileChoice.None -> getString(R.string.key_file_summary_none)
        KeyFileChoice.Imported -> getString(R.string.key_file_imported_item, Format.bytes(requireContext(), Vault.importedKeyFile.length()))
        is KeyFileChoice.Picked -> k.name
    }

    // ---- the database ----

    /** Why a database in a synced folder cannot be used, in the user's words. */
    private fun skipReason(s: SkippedDatabase): String {
        val ctx = requireContext()
        return when (s.reason) {
            SyncFiles.SKIP_NAME -> getString(R.string.skip_reason_name)
            SyncFiles.SKIP_TOO_LARGE -> getString(
                R.string.skip_reason_too_large, Format.bytes(ctx, s.size), Format.bytes(ctx, Sources.MAX_DATABASE_BYTES.toLong()),
            )
            SyncFiles.SKIP_TOO_DEEP -> getString(R.string.skip_reason_too_deep, SyncFiles.MAX_DEPTH)
            SyncFiles.SKIP_SYMLINK -> getString(R.string.skip_reason_symlink)
            SyncFiles.SKIP_NOT_REGULAR -> getString(R.string.skip_reason_not_regular)
            else -> getString(R.string.skip_reason_other)
        }
    }

    /** One row of the database choice; [action] null: shown but not selectable. */
    private class ChoiceRow(val text: CharSequence, val action: (() -> Unit)?)

    /**
     * The databases of the synced folders, then the ones that cannot be used
     * (not selectable, with the reason), then "choose from another location".
     * The search result is the dialog's: a recreated page shows the same
     * choice before its own search ends.
     */
    private fun showDatabaseChooser() {
        val result = found
        showDialog("database-choice") { d -> databaseChooser(d, result) }
    }

    private fun databaseChooser(d: DialogScope, result: FoundDatabases?): Dialog {
        val ctx = d.context
        val list = result?.files.orEmpty()
        val skipped = result?.skipped.orEmpty()
        val title = if (Vault.prefs.passwordsSource != null) R.string.sources_change_file else R.string.sources_choose_file
        if (list.isEmpty() && skipped.isEmpty() && result?.truncated != true) {
            return AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(if (result == null) R.string.error_list_folder else R.string.sources_found_none)
                .setPositiveButton(R.string.sources_choose_other) { _, _ -> launchPicker { pickDatabase.launch(DATABASE_TYPES) } }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
        val current = currentSource()
        val rows = ArrayList<ChoiceRow>()
        for (f in list) {
            rows += ChoiceRow(
                twoLines(f.path, getString(R.string.sources_found_item, folderLabel(f.folder, result), Format.bytes(ctx, f.size), Format.dateTime(ctx, f.mtime))),
            ) { chooseSource(DbSource.Synced(f.folder, f.path)) }
        }
        for (f in skipped) {
            rows += ChoiceRow(twoLines(f.path, getString(R.string.sources_skipped_item, folderLabel(f.folder, result), skipReason(f))), null)
        }
        if (result?.truncated == true) rows += ChoiceRow(getString(R.string.sources_found_truncated), null)
        rows += ChoiceRow(getString(R.string.sources_choose_other)) { launchPicker { pickDatabase.launch(DATABASE_TYPES) } }
        val checked = list.indexOfFirst { current is DbSource.Synced && current.folder == it.folder && current.path == it.path }
        val builder = AlertDialog.Builder(ctx).setTitle(title)
        // The dialog's own single-choice row layout, inflated in the dialog's theme (the page's
        // theme lacks the dialog attributes it uses); rows without an action are drawn disabled.
        val adapter = object : ArrayAdapter<CharSequence>(
            builder.context, androidx.appcompat.R.layout.select_dialog_singlechoice_material, android.R.id.text1,
            rows.map { it.text },
        ) {
            override fun areAllItemsEnabled() = false

            override fun isEnabled(position: Int) = rows[position].action != null

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                super.getView(position, convertView, parent).also { it.isEnabled = isEnabled(position) }
        }
        return builder
            .setSingleChoiceItems(adapter, checked) { dialog, which ->
                val action = rows[which].action ?: return@setSingleChoiceItems
                dialog.dismiss()
                action()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    /** A name and a smaller, secondary line, for the items of a choice dialog. */
    private fun twoLines(first: String, second: String): CharSequence {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        val color = if (tv.resourceId != 0) requireContext().getColor(tv.resourceId) else tv.data
        return SpannableStringBuilder(first).append("\n").also { b ->
            val start = b.length
            b.append(second)
            b.setSpan(RelativeSizeSpan(0.85f), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(ForegroundColorSpan(color), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun launchPicker(launch: () -> Unit) {
        try {
            launch()
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "no document picker", e)
            Toast.makeText(requireContext(), R.string.error_no_settings, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDatabasePicked(uri: Uri) {
        launchUi {
            val ctx = requireContext()
            // Read again on every open, also after a restart: the grant has to outlive this process.
            if (!Sources.takeGrant(ctx, uri)) {
                Toast.makeText(ctx, R.string.sources_no_grant, Toast.LENGTH_LONG).show()
                return@launchUi
            }
            val info = try {
                withContext(Dispatchers.IO) { Sources.documentInfo(ctx, uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "the chosen document has no readable facts: ${e.javaClass.simpleName}")
                ensureActive()
                null
            }
            chooseSource(DbSource.Document(uri, info?.name ?: ""))
        }
    }

    /**
     * Not set up yet: the choice is remembered for the setup. Set up: the
     * stored key is tried on the new database (the same database elsewhere
     * keeps its key); when it does not open it, its password is asked for.
     */
    private fun chooseSource(source: DbSource) {
        val ctx = requireContext()
        if (!Vault.passwordsSetUp()) {
            Vault.prefs.passwordsSource = source
            Sources.releaseGrantsExcept(ctx, (source as? DbSource.Document)?.uri)
            pendingSource = null
            refresh()
            return
        }
        if (source == Vault.prefs.passwordsSource) {
            // Chosen again (a revoked grant taken anew): open it again.
            pendingSource = null
            Vault.requestLoad("database chosen again")
            refresh()
            return
        }
        passwordsBusy = R.string.sources_switching
        render()
        launchUi {
            var needsPassword = false
            try {
                act(R.string.error_switch_file) {
                    try {
                        val r = Vault.switchPasswordsSource(source)
                        pendingSource = null
                        Toast.makeText(ctx, resources.getQuantityString(R.plurals.sources_switched, r.entries, r.entries), Toast.LENGTH_LONG).show()
                    } catch (e: PasswordNeededException) {
                        pendingSource = source
                        needsPassword = true
                    }
                }
            } finally {
                passwordsBusy = 0
                ifPageAlive { refresh() }
            }
            if (needsPassword) {
                Toast.makeText(ctx, R.string.sources_switch_needs_password, Toast.LENGTH_LONG).show()
                setUpPasswords()
            }
        }
    }

    // ---- the key file ----

    private fun showKeyFileChooser() = showDialog("key-file-choice") { d ->
        val ctx = d.context
        val current = effectiveKeyFile()
        val labels = ArrayList<CharSequence>()
        val actions = ArrayList<() -> Unit>()
        var checked = -1
        labels += getString(R.string.key_file_none)
        actions += { setKeyFile(KeyFileChoice.None) }
        if (current == KeyFileChoice.None) checked = 0
        if (Vault.importedKeyFile.isFile) {
            if (current == KeyFileChoice.Imported) checked = labels.size
            labels += getString(R.string.key_file_imported_item, Format.bytes(ctx, Vault.importedKeyFile.length()))
            actions += { setKeyFile(KeyFileChoice.Imported) }
        }
        if (current is KeyFileChoice.Picked) {
            checked = labels.size
            labels += twoLines(current.name, Format.bytes(ctx, current.size))
            actions += {}
        }
        labels += getString(R.string.key_file_choose)
        actions += { launchPicker { pickKeyFile.launch(ANY_TYPE) } }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.sources_key_file)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                actions[which]()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun setKeyFile(choice: KeyFileChoice) {
        val old = keyFile
        if (old is KeyFileChoice.Picked && old !== choice) old.wipe()
        keyFile = choice
        render()
    }

    /** Reads the picked key file and keeps only the key it contributes; the file content is wiped at once. */
    private fun onKeyFilePicked(uri: Uri) {
        launchUi {
            act(R.string.error_read_key_file) {
                val ctx = requireContext()
                val picked = withContext(Dispatchers.IO) {
                    val raw = Sources.readDocument(ctx, uri, Vault.MAX_KEY_FILE_BYTES)
                    try {
                        val name = try {
                            Sources.documentInfo(ctx, uri).name
                        } catch (e: Exception) {
                            ""
                        }
                        KeyFileChoice.Picked(name.ifEmpty { uri.lastPathSegment ?: "?" }, raw.size.toLong(), Vault.keyFileKey(raw))
                    } finally {
                        Bytes.wipe(raw)
                    }
                }
                setKeyFile(picked)
            }
        }
    }

    // ---- setting up the passwords ----

    /**
     * Reads the chosen file for what it needs: a file without a password is
     * set up at once, any other asks for its password (and names the key
     * file of a KDBX database).
     */
    private fun setUpPasswords() {
        val source = currentSource() ?: return
        passwordsBusy = R.string.setup_checking
        render()
        launchUi {
            var kind: Vault.PasswordFileKind? = null
            try {
                act(R.string.error_setup_passwords) { kind = Vault.passwordFileKind(source) }
            } finally {
                passwordsBusy = 0
                ifPageAlive { render() }
            }
            val k = kind ?: return@launchUi
            fileKind = k
            if (k == Vault.PasswordFileKind.PLAIN) runPasswordsSetup(source, CharArray(0)) else askFilePassword(source, k)
        }
    }

    private fun askFilePassword(source: DbSource, kind: Vault.PasswordFileKind) {
        showDialog("file-password") { d ->
            val message = if (kind == Vault.PasswordFileKind.KDBX) {
                getString(R.string.sources_password_message, source.fileName, keyFileName())
            } else {
                getString(R.string.sources_file_message, source.fileName)
            }
            passwordDialog(d, R.string.setup_file_password, message) { chars -> runPasswordsSetup(source, chars) }
        }
    }

    private fun runPasswordsSetup(source: DbSource, chars: CharArray) {
        val password = utf8(chars)
        Bytes.wipe(chars)
        val choice = if (currentKind() == Vault.PasswordFileKind.KDBX) effectiveKeyFile() else KeyFileChoice.None
        passwordsBusy = R.string.setup_checking
        render()
        launchUi {
            try {
                val ok = act(R.string.error_setup_passwords) {
                    val r = Vault.setupPasswords(source, choice, password)
                    Toast.makeText(
                        requireContext(),
                        resources.getQuantityString(R.plurals.setup_passwords_ok, r.entries, r.entries),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                if (ok) {
                    pendingSource = null
                    (keyFile as? KeyFileChoice.Picked)?.wipe()
                    keyFile = null
                }
            } finally {
                Bytes.wipe(password)
                passwordsBusy = 0
                ifPageAlive { refresh() }
            }
        }
    }

    // ---- 2FA codes ----

    /** Sets up the vault's own file of 2FA accounts again (after the keys were forgotten). */
    private fun askCodesPassword() = showDialog("codes-password") { d ->
        passwordDialog(d, R.string.setup_codes_password, null) { chars -> runCodesSetup(chars, null, "", fromFile = false) }
    }

    /**
     * A newly picked file of 2FA accounts replaces the current ones only
     * after it has been opened: an encrypted one with its password, which
     * then protects the accounts too; one without asks for a new password
     * for them. Cancelling leaves everything as it was.
     */
    private fun onCodesPicked(uri: Uri) {
        launchUi {
            val ctx = requireContext()
            val holder = arrayOfNulls<ByteArray>(1)
            var encrypted = false
            var store = false
            val name = try {
                val ok = act(R.string.error_read_file) {
                    val bytes = withContext(Dispatchers.IO) { Sources.readDocument(ctx, uri, MAX_CODE_FILE_BYTES) }
                    holder[0] = bytes
                    withContext(Dispatchers.Default) {
                        val detected = CodeFiles.detect(bytes)
                        encrypted = detected.encrypted
                        store = detected.store
                    }
                }
                if (!ok) {
                    holder[0]?.let { Bytes.wipe(it) }
                    return@launchUi
                }
                try {
                    withContext(Dispatchers.IO) { Sources.documentInfo(ctx, uri).name }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "the chosen 2FA file has no readable name: ${e.javaClass.simpleName}")
                    ensureActive()
                    ""
                }
            } catch (e: CancellationException) {
                // The page went away before the password was asked for: the file's bytes go with it.
                holder[0]?.let { Bytes.wipe(it) }
                throw e
            }
            val pending = PendingImport(holder[0] ?: return@launchUi)
            val shownName = name.ifEmpty { getString(R.string.sources_accounts_unnamed) }
            // The bytes stay with the dialog (a recreation brings it back) and are wiped when it
            // ends unanswered: cancelled, dismissed, or the page gone (PendingImport).
            if (encrypted) {
                showDialog(DIALOG_CODES_IMPORT, onEnd = { Log.i(TAG, "SourcesFragment: " + pending.end()) }) { d ->
                    passwordDialog(d, R.string.sources_codes_file_password, getString(R.string.sources_codes_message, shownName)) { chars ->
                        val bytes = pending.take()
                        if (bytes == null) {
                            Bytes.wipe(chars)
                            Log.w(TAG, "SourcesFragment: 2FA import answered after its file was wiped")
                        } else {
                            runCodesSetup(chars, bytes, name, fromFile = !store)
                        }
                    }
                }
            } else {
                showDialog(DIALOG_CODES_NEW_PASSWORD, onEnd = { Log.i(TAG, "SourcesFragment: " + pending.end()) }) { d ->
                    newPasswordDialog(d, R.string.sources_codes_new_password, getString(R.string.sources_codes_new_password_message, shownName)) { chars ->
                        val bytes = pending.take()
                        if (bytes == null) {
                            Bytes.wipe(chars)
                            Log.w(TAG, "SourcesFragment: 2FA import answered after its file was wiped")
                        } else {
                            runCodesSetup(chars, bytes, name, fromFile = true, plain = true)
                        }
                    }
                }
            }
        }
    }

    /**
     * [bytes] null: the vault's own file again with its password. [fromFile]:
     * the accounts of another format are taken into a new file of the
     * vault's own, protected with [chars] (the picked file's own password, or
     * with [plain] the new one for a file without); otherwise [bytes] is an
     * encrypted Aegis vault, which becomes the vault's own file as it is.
     */
    private fun runCodesSetup(chars: CharArray, bytes: ByteArray?, name: String, fromFile: Boolean, plain: Boolean = false) {
        val password = utf8(chars)
        Bytes.wipe(chars)
        codesBusy = true
        render()
        launchUi {
            try {
                act(R.string.error_setup_codes) {
                    val r = when {
                        bytes == null -> Vault.setupCodes(password)
                        fromFile -> Vault.setupCodesFrom(bytes, name, if (plain) null else password, password)
                        else -> Vault.setupCodes(password, bytes, name)
                    }
                    var text = resources.getQuantityString(R.plurals.setup_codes_ok, r.entries, r.entries)
                    if (r.skipped > 0) text += "\n" + resources.getQuantityString(R.plurals.codes_not_readable, r.skipped, r.skipped)
                    Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show()
                }
            } finally {
                Bytes.wipe(password)
                bytes?.let { Bytes.wipe(it) }
                codesBusy = false
                ifPageAlive { render() }
            }
        }
    }

    companion object {
        /** Open the database choice at once (the home page's one-tap fix for a lost file). */
        const val ARG_RECHOOSE = "rechoose"

        private const val DIALOG_CODES_IMPORT = "codes-import-password"
        private const val DIALOG_CODES_NEW_PASSWORD = "codes-new-password"
        private const val STATE_KEY_FILE = "key_file"

        /** The picked key file a recreated page takes from the one before (by the token in its saved state). */
        private var handedOn: Pair<String, KeyFileChoice.Picked>? = null

        private fun takeHandedOn(token: String): KeyFileChoice.Picked? {
            val h = handedOn?.takeIf { it.first == token } ?: return null
            handedOn = null
            return h.second
        }

        private const val MAX_CODE_FILE_BYTES = 16 * 1024 * 1024
        /** Password files, key files and the files of authenticator apps have no one registered type: any file. */
        private val DATABASE_TYPES = arrayOf("*/*")
        private val ANY_TYPE = arrayOf("*/*")
        private val CODE_FILE_TYPES = arrayOf("*/*")
    }
}
