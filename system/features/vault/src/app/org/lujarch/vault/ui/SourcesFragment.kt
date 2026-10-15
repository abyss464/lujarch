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
import org.lujarch.vault.core.PendingAegisImport
import org.lujarch.vault.core.aegis.AegisException
import org.lujarch.vault.core.aegis.AegisVault
import org.lujarch.vault.core.kdbx.KdbxException
import org.lujarch.vault.data.SyncFiles
import org.lujarch.vault.data.DbSource
import org.lujarch.vault.data.DocumentInfo
import org.lujarch.vault.data.FoundDatabases
import org.lujarch.vault.data.KeyFileChoice
import org.lujarch.vault.data.SkippedDatabase
import org.lujarch.vault.data.SourceUnavailableException
import org.lujarch.vault.data.Sources
import org.lujarch.vault.data.Vault
import org.lujarch.vault.data.VaultState

/**
 * The vault's sources (VLT-2): the KeePass database (a file in a synced
 * folder or a document chosen with the system file picker), its key file
 * and password; the Aegis vault file and its password. Each source can be
 * turned off, replaced and set up again. A setup opens the source to check
 * the password, then only the derived key is stored, wrapped by the
 * Keystore (VLT-1). Passwords live in char arrays wiped
 * after use; a picked key file only as its derived key, in memory. The
 * dialogs are page dialogs: a recreation brings them back, the
 * password fields empty.
 */
class SourcesFragment : BasePreferenceFragment() {
    private lateinit var kpUse: SwitchPreferenceCompat
    private lateinit var kpDatabase: Preference
    private lateinit var kpKeyFile: Preference
    private lateinit var kpSetup: Preference
    private lateinit var aeUse: SwitchPreferenceCompat
    private lateinit var aeFile: Preference
    private lateinit var aeSetup: Preference

    /** The databases in the synced folders, searched when the page resumes; null when the search failed. */
    private var found: FoundDatabases? = null
    /** Facts of the chosen document, or why it cannot be read. */
    private var documentInfo: DocumentInfo? = null
    private var documentProblem: SourceUnavailableException.Reason? = null
    /** Chosen while KeePass is set up, and not opened by the stored key: waits for its password. */
    private var pendingSource: DbSource? = null
    /** The key file of the next setup; null: the imported one when present, else none. */
    private var keyFile: KeyFileChoice? = null
    private var keepassBusy = 0
    private var aegisBusy = false
    /** Opened from the home page's "choose again": show the choice as soon as the page is ready. */
    private var rechoose = false

    private val pickDatabase = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onDatabasePicked(uri)
    }
    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onKeyFilePicked(uri)
    }
    private val pickAegis = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onAegisPicked(uri)
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
        val kp = screen.add(PreferenceCategory(prefContext)) {
            key = "keepass"
            setTitle(R.string.sources_keepass)
        }
        kpUse = kp.add(SwitchPreferenceCompat(prefContext)) {
            key = "kp_use"
            setTitle(R.string.sources_keepass_use)
            setIcon(R.drawable.ic_key)
            isPersistent = false
            setOnPreferenceChangeListener { _, v ->
                Vault.setKeePassEnabled(v as Boolean)
                render()
                true
            }
        }
        kpDatabase = kp.add(Preference(prefContext)) {
            key = "kp_database"
            setTitle(R.string.sources_database)
            setIcon(R.drawable.ic_folder)
            setOnPreferenceClickListener {
                showDatabaseChooser()
                true
            }
        }
        kpKeyFile = kp.add(Preference(prefContext)) {
            key = "kp_key_file"
            setTitle(R.string.sources_key_file)
            isIconSpaceReserved = true
            setOnPreferenceClickListener {
                showKeyFileChooser()
                true
            }
        }
        kpSetup = kp.add(Preference(prefContext)) {
            key = "kp_setup"
            setIcon(R.drawable.ic_check)
            setOnPreferenceClickListener {
                askKeePassPassword()
                true
            }
        }

        val ae = screen.add(PreferenceCategory(prefContext)) {
            key = "aegis"
            setTitle(R.string.sources_aegis)
        }
        aeUse = ae.add(SwitchPreferenceCompat(prefContext)) {
            key = "ae_use"
            setTitle(R.string.sources_aegis_use)
            setIcon(R.drawable.ic_codes)
            isPersistent = false
            setOnPreferenceChangeListener { _, v ->
                Vault.setAegisEnabled(v as Boolean)
                render()
                true
            }
        }
        aeFile = ae.add(Preference(prefContext)) {
            key = "ae_file"
            setTitle(R.string.sources_aegis_file)
            setIcon(R.drawable.ic_folder)
            setOnPreferenceClickListener {
                launchPicker { pickAegis.launch(AEGIS_TYPES) }
                true
            }
        }
        aeSetup = ae.add(Preference(prefContext)) {
            key = "ae_setup"
            setIcon(R.drawable.ic_check)
            setOnPreferenceClickListener {
                askAegisPassword()
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
        if (key == DIALOG_AEGIS_IMPORT) {
            // The picked file went with the restarted process before its password was given.
            Log.i(TAG, "SourcesFragment: the Aegis import's password dialog was not restored (restarted process); the file must be picked again")
            Toast.makeText(requireContext(), R.string.sources_aegis_pick_again, Toast.LENGTH_LONG).show()
        }
    }

    override fun onVaultState(state: VaultState) = render()

    private fun currentSource(): DbSource? = pendingSource ?: Vault.prefs.keepassSource

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

        val kpSetUp = Vault.keePassSetUp()
        kpUse.isVisible = kpSetUp
        kpUse.isChecked = prefs.keepassEnabled
        kpUse.setSummary(if (prefs.keepassEnabled) R.string.sources_keepass_on else R.string.sources_keepass_off)
        kpUse.isEnabled = keepassBusy == 0
        kpDatabase.summary = if (keepassBusy == R.string.sources_switching) getString(keepassBusy) else databaseSummary()
        kpDatabase.isEnabled = keepassBusy == 0
        kpKeyFile.summary = keyFileSummary()
        kpKeyFile.isEnabled = keepassBusy == 0
        kpSetup.setTitle(if (kpSetUp) R.string.sources_keepass_setup_again else R.string.sources_keepass_setup)
        kpSetup.summary = when {
            keepassBusy == R.string.setup_checking -> getString(R.string.setup_checking)
            currentSource() == null -> getString(R.string.sources_database_none)
            kpSetUp -> getString(R.string.sources_keepass_setup_again_summary)
            else -> getString(R.string.sources_keepass_setup_summary)
        }
        kpSetup.isEnabled = keepassBusy == 0 && currentSource() != null

        val aeSetUp = Vault.aegisSetUp()
        aeUse.isVisible = aeSetUp
        aeUse.isChecked = prefs.aegisEnabled
        aeUse.setSummary(if (prefs.aegisEnabled) R.string.sources_aegis_on else R.string.sources_aegis_off)
        aeUse.isEnabled = !aegisBusy
        val f = Vault.aegisFile
        aeFile.summary = if (f.isFile) {
            getString(
                R.string.sources_aegis_file_present,
                prefs.aegisSourceName.ifEmpty { getString(R.string.sources_aegis_file_unnamed) },
                Format.bytes(ctx, f.length()), Format.dateTime(ctx, f.lastModified()),
            )
        } else {
            getString(R.string.sources_aegis_file_missing)
        }
        aeFile.isEnabled = !aegisBusy
        aeSetup.isVisible = f.isFile
        aeSetup.setTitle(if (aeSetUp) R.string.sources_aegis_setup_again else R.string.sources_aegis_setup)
        aeSetup.summary = getString(if (aegisBusy) R.string.setup_checking else R.string.sources_aegis_setup_summary)
        aeSetup.isEnabled = !aegisBusy
    }

    private fun folderLabel(folder: String, result: FoundDatabases? = found): String = result?.labels?.get(folder) ?: folder

    private fun databaseSummary(): String {
        val src = currentSource() ?: return getString(R.string.sources_database_none)
        val ctx = requireContext()
        val lines = ArrayList<String>()
        when (src) {
            is DbSource.Synced -> {
                lines += getString(R.string.sources_database_synced, src.path, folderLabel(src.folder))
                val list = found
                val file = list?.files?.firstOrNull { it.folder == src.folder && it.path == src.path }
                val skipped = list?.skipped?.firstOrNull { it.folder == src.folder && it.path == src.path }
                when {
                    file != null -> lines += getString(R.string.sources_modified, Format.dateTime(ctx, file.mtime))
                    skipped != null -> lines += getString(R.string.sources_database_unavailable, skipReason(skipped))
                    list != null -> lines += getString(R.string.sources_database_missing)
                }
            }
            is DbSource.Document -> {
                val provider = Sources.providerLabel(ctx, src.uri)
                lines += if (provider != null) {
                    getString(R.string.sources_database_document, src.fileName, provider)
                } else {
                    getString(R.string.sources_database_document_plain, src.fileName)
                }
                when (documentProblem) {
                    SourceUnavailableException.Reason.PERMISSION -> lines += getString(R.string.sources_database_no_access)
                    SourceUnavailableException.Reason.MISSING -> lines += getString(R.string.sources_database_missing)
                    else -> documentInfo?.takeIf { it.mtime > 0 }?.let {
                        lines += getString(R.string.sources_modified, Format.dateTime(ctx, it.mtime))
                    }
                }
            }
        }
        if (pendingSource != null) lines += getString(R.string.sources_database_pending)
        return lines.joinToString("\n")
    }

    private fun effectiveKeyFile(): KeyFileChoice =
        keyFile ?: if (Vault.importedKeyFile.isFile) KeyFileChoice.Imported else KeyFileChoice.None

    private fun keyFileSummary(): String {
        val ctx = requireContext()
        return when (val k = effectiveKeyFile()) {
            KeyFileChoice.None -> getString(
                if (Vault.keePassSetUp() && Vault.prefs.usesKeyFile) R.string.key_file_summary_stored else R.string.key_file_summary_none,
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
        val title = if (Vault.prefs.keepassSource != null) R.string.sources_change_database else R.string.sources_choose_database
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
        if (!Vault.keePassSetUp()) {
            Vault.prefs.keepassSource = source
            Sources.releaseGrantsExcept(ctx, (source as? DbSource.Document)?.uri)
            pendingSource = null
            refresh()
            return
        }
        if (source == Vault.prefs.keepassSource) {
            // Chosen again (a revoked grant taken anew): open it again.
            pendingSource = null
            Vault.requestLoad("database chosen again")
            refresh()
            return
        }
        keepassBusy = R.string.sources_switching
        render()
        launchUi {
            var needsPassword = false
            try {
                act(R.string.error_switch_database) {
                    try {
                        val r = Vault.switchKeePassSource(source)
                        pendingSource = null
                        Toast.makeText(ctx, resources.getQuantityString(R.plurals.sources_switched, r.entries, r.entries), Toast.LENGTH_LONG).show()
                    } catch (e: KdbxException) {
                        if (e.reason != KdbxException.Reason.WRONG_KEY) throw e
                        pendingSource = source
                        needsPassword = true
                    }
                }
            } finally {
                keepassBusy = 0
                ifPageAlive { refresh() }
            }
            if (needsPassword) {
                Toast.makeText(ctx, R.string.sources_switch_needs_password, Toast.LENGTH_LONG).show()
                askKeePassPassword()
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

    // ---- KeePass setup ----

    private fun askKeePassPassword() {
        val source = currentSource() ?: return
        showDialog("keepass-password") { d ->
            passwordDialog(d, R.string.setup_keepass_password, getString(R.string.sources_password_message, source.fileName, keyFileName())) { chars ->
                runKeePassSetup(source, chars)
            }
        }
    }

    private fun runKeePassSetup(source: DbSource, chars: CharArray) {
        val password = utf8(chars)
        Bytes.wipe(chars)
        val choice = effectiveKeyFile()
        keepassBusy = R.string.setup_checking
        render()
        launchUi {
            try {
                val ok = act(R.string.error_setup_keepass) {
                    val r = Vault.setupKeePass(source, choice, password)
                    Toast.makeText(
                        requireContext(),
                        resources.getQuantityString(R.plurals.setup_keepass_ok, r.entries, r.entries),
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
                keepassBusy = 0
                ifPageAlive { refresh() }
            }
        }
    }

    // ---- Aegis ----

    /** Sets up the vault file already imported again (its password changed). */
    private fun askAegisPassword() = showDialog("aegis-password") { d ->
        passwordDialog(d, R.string.setup_aegis_password, null) { chars -> runAegisSetup(chars, null, "") }
    }

    /**
     * A newly picked vault file replaces the current one only after its
     * password has opened it; cancelling leaves everything as it was.
     */
    private fun onAegisPicked(uri: Uri) {
        launchUi {
            val ctx = requireContext()
            val holder = arrayOfNulls<ByteArray>(1)
            val name = try {
                val ok = act(R.string.error_import_aegis) {
                    val bytes = withContext(Dispatchers.IO) { Sources.readDocument(ctx, uri, MAX_AEGIS_BYTES) }
                    holder[0] = bytes
                    withContext(Dispatchers.Default) {
                        if (!AegisVault.parse(bytes).encrypted) {
                            throw AegisException(AegisException.Reason.NO_PASSWORD_SLOT, "the vault file is not encrypted")
                        }
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
                    Log.w(TAG, "the chosen vault file has no readable name: ${e.javaClass.simpleName}")
                    ensureActive()
                    ""
                }
            } catch (e: CancellationException) {
                // The page went away before the password was asked for: the file's bytes go with it.
                holder[0]?.let { Bytes.wipe(it) }
                throw e
            }
            val pending = PendingAegisImport(holder[0] ?: return@launchUi)
            // The bytes stay with the dialog (a recreation brings it back) and are wiped when it
            // ends unanswered: cancelled, dismissed, or the page gone (PendingAegisImport).
            showDialog(DIALOG_AEGIS_IMPORT, onEnd = { Log.i(TAG, "SourcesFragment: " + pending.end()) }) { d ->
                passwordDialog(
                    d, R.string.sources_aegis_new_password,
                    getString(R.string.sources_aegis_message, name.ifEmpty { getString(R.string.sources_aegis_file_unnamed) }),
                ) { chars ->
                    val bytes = pending.take()
                    if (bytes == null) {
                        Bytes.wipe(chars)
                        Log.w(TAG, "SourcesFragment: Aegis import answered after its file was wiped")
                    } else {
                        runAegisSetup(chars, bytes, name)
                    }
                }
            }
        }
    }

    private fun runAegisSetup(chars: CharArray, bytes: ByteArray?, name: String) {
        val password = utf8(chars)
        Bytes.wipe(chars)
        aegisBusy = true
        render()
        launchUi {
            try {
                act(R.string.error_setup_aegis) {
                    val r = Vault.setupAegis(password, bytes, name)
                    Toast.makeText(
                        requireContext(),
                        resources.getQuantityString(R.plurals.setup_aegis_ok, r.entries, r.entries),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                Bytes.wipe(password)
                bytes?.let { Bytes.wipe(it) }
                aegisBusy = false
                ifPageAlive { render() }
            }
        }
    }

    companion object {
        /** Open the database choice at once (the home page's one-tap fix for a lost file). */
        const val ARG_RECHOOSE = "rechoose"

        private const val DIALOG_AEGIS_IMPORT = "aegis-import-password"
        private const val STATE_KEY_FILE = "key_file"

        /** The picked key file a recreated page takes from the one before (by the token in its saved state). */
        private var handedOn: Pair<String, KeyFileChoice.Picked>? = null

        private fun takeHandedOn(token: String): KeyFileChoice.Picked? {
            val h = handedOn?.takeIf { it.first == token } ?: return null
            handedOn = null
            return h.second
        }

        private const val MAX_AEGIS_BYTES = 16 * 1024 * 1024
        /** KeePass databases and key files have no registered type: any file. */
        private val DATABASE_TYPES = arrayOf("*/*")
        private val ANY_TYPE = arrayOf("*/*")
        private val AEGIS_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")
    }
}
