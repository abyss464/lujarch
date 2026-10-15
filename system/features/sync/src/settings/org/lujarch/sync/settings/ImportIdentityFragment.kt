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

package org.lujarch.sync.settings

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.TopIntroPreference
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Arrays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.PageRequest
import org.lujarch.settings.pages.showDialog

/**
 * Imports a device identity from files the owner picks (the system's
 * document picker): the certificate (cert.pem), the private key (key.pem)
 * and, optionally, a configuration (config.xml) whose devices and folders
 * come along. The contents are read into memory only, sent to the system
 * service, which checks and installs them, and dropped; they are never
 * logged or stored by this app.
 */
class ImportIdentityFragment : SyncPageFragment() {
    private class Picked(val name: String, val bytes: ByteArray)

    private var cert: Picked? = null
    private var key: Picked? = null
    private var config: Picked? = null
    private var hasCurrentConfig = false
    private var importing = false

    /** Which file the picker is open for (PICK_*), in the saved state: a page recreated meanwhile puts it in its place. */
    private val pickFor = PageRequest(this, "identity-file")

    private lateinit var certPref: Preference
    private lateinit var keyPref: Preference
    private lateinit var configPref: Preference
    private lateinit var keepPref: SwitchPreferenceCompat
    private lateinit var importPref: Preference

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val what = pickFor.take()?.firstOrNull()?.toIntOrNull()
        when {
            uri == null -> Unit
            what == null -> Log.w(TAG, "identity import: a picked file came back without its request; ignored")
            else -> read(what, uri)
        }
    }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.sync_import_intro)
        }
        val files = screen.add(PreferenceCategory(prefContext)) {
            key = "files"
            setTitle(R.string.sync_import_files)
        }
        certPref = files.add(Preference(prefContext)) {
            key = "cert"
            setTitle(R.string.sync_import_cert)
            setIcon(R.drawable.ic_file)
            setOnPreferenceClickListener {
                pick(PICK_CERT)
                true
            }
        }
        keyPref = files.add(Preference(prefContext)) {
            key = "key"
            setTitle(R.string.sync_import_key)
            setIcon(R.drawable.ic_key)
            setOnPreferenceClickListener {
                pick(PICK_KEY)
                true
            }
        }
        configPref = files.add(Preference(prefContext)) {
            key = "config"
            setTitle(R.string.sync_import_config)
            setIcon(R.drawable.ic_file)
            setOnPreferenceClickListener {
                if (config == null) pick(PICK_CONFIG) else chooseConfig()
                true
            }
        }
        keepPref = files.add(SwitchPreferenceCompat(prefContext)) {
            key = "keep"
            setTitle(R.string.sync_identity_keep_config)
            setSummary(R.string.sync_identity_keep_config_summary)
            isPersistent = false
            isChecked = true
            isVisible = false
        }
        importPref = screen.add(PreferenceCategory(prefContext)) { key = "actions" }.add(Preference(prefContext)) {
            key = "import"
            setTitle(R.string.sync_import_action)
            setIcon(R.drawable.ic_check)
            setOnPreferenceClickListener {
                confirmImport()
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_import_footer)
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            hasCurrentConfig = attempt("identity", R.string.error_load_identity) { SyncClient.identity() }?.hasConfig ?: false
            render()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Key material leaves memory with the page.
        listOfNotNull(cert, key, config).forEach { Arrays.fill(it.bytes, 0) }
    }

    private fun render() {
        certPref.summary = cert?.name ?: getString(R.string.sync_import_not_chosen)
        keyPref.summary = key?.name ?: getString(R.string.sync_import_not_chosen)
        configPref.summary = config?.name ?: getString(R.string.sync_import_config_optional)
        keepPref.isVisible = config == null && hasCurrentConfig
        importPref.isEnabled = !importing && cert != null && key != null
    }

    private fun pick(what: Int) {
        pickFor.start(what.toString())
        // PEM and XML files come with many types (text/plain, application/x-pem-file,
        // application/octet-stream); the system service checks the content.
        picker.launch(arrayOf("*/*"))
    }

    private fun read(what: Int, uri: Uri) {
        val context = requireContext()
        launchUi {
            val picked = try {
                withContext(Dispatchers.IO) {
                    val limit = if (what == PICK_CONFIG) MAX_CONFIG else MAX_PEM
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: uri.lastPathSegment ?: "?"
                    val out = ByteArrayOutputStream()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > limit) throw IOException("larger than $limit bytes")
                        }
                    } ?: throw IOException("cannot open")
                    Picked(name, out.toByteArray())
                }
            } catch (e: IOException) {
                // The file's name and error only; never its content.
                Log.w(TAG, "identity import: cannot read the chosen file: ${e.message}")
                ensureActive()
                toastError(R.string.error_sync_import_read, e)
                return@launchUi
            } catch (e: SecurityException) {
                Log.w(TAG, "identity import: no access to the chosen file")
                ensureActive()
                toastError(R.string.error_sync_import_read, e)
                return@launchUi
            }
            when (what) {
                PICK_CERT -> cert?.let { Arrays.fill(it.bytes, 0) }.also { cert = picked }
                PICK_KEY -> key?.let { Arrays.fill(it.bytes, 0) }.also { key = picked }
                else -> config?.let { Arrays.fill(it.bytes, 0) }.also { config = picked }
            }
            render()
        }
    }

    /** Another configuration or none; a recreated page has none picked (the files go with the page), so none to change. */
    private fun chooseConfig() = showDialog("sync-import-config") { d ->
        if (config == null) return@showDialog null
        AlertDialog.Builder(d.context)
            .setTitle(R.string.sync_import_config)
            .setItems(arrayOf(getString(R.string.sync_import_config_other), getString(R.string.sync_import_config_none))) { _, which ->
                if (which == 0) pick(PICK_CONFIG) else {
                    config?.let { Arrays.fill(it.bytes, 0) }
                    config = null
                    render()
                }
            }
            .create()
    }

    /**
     * The picked files are wiped with the page (onDestroy), also when it is
     * recreated: a recreated page has nothing to import, and the question is
     * dropped rather than shown for files that are gone.
     */
    private fun confirmImport() = showDialog("sync-import") { d ->
        if (cert == null || key == null) return@showDialog null
        AlertDialog.Builder(d.context)
            .setTitle(R.string.sync_import_confirm_title)
            .setMessage(if (hasCurrentConfig) R.string.sync_import_confirm_replace else R.string.sync_import_confirm)
            .setPositiveButton(R.string.sync_import_action) { _, _ -> import() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private var staged: IdentityInstalled? = null

    private fun import() {
        val c = cert ?: return
        val k = key ?: return
        if (importing) return
        importing = true
        render()
        val keep = keepPref.isChecked
        change(R.string.error_sync_identity, {
            staged = SyncClient.importIdentity(
                String(c.bytes, Charsets.UTF_8),
                String(k.bytes, Charsets.UTF_8),
                config?.let { String(it.bytes, Charsets.UTF_8) },
                keep,
            )
        }) { ok ->
            importing = false
            render()
            if (ok) IdentityFragment.afterStaged(this, staged) { activity?.finish() }
        }
    }

    companion object {
        private const val PICK_CERT = 1
        private const val PICK_KEY = 2
        private const val PICK_CONFIG = 3
        private const val MAX_PEM = 16 * 1024
        private const val MAX_CONFIG = 48 * 1024
    }
}
