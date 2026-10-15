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

import android.os.Bundle
import android.widget.CheckBox
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.TopIntroPreference
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog

/**
 * This device's identity in the sync mesh (certificate and private key):
 * its device ID, importing one from files, creating a new one, and the
 * previous identities kept as backups, each of which can be restored.
 */
class IdentityFragment : SyncPageFragment() {
    private lateinit var currentPref: Preference
    private lateinit var backupsCategory: PreferenceCategory
    private var identity: IdentityState? = null
    private var deviceId: String? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.sync_identity_intro)
        }
        val current = screen.add(PreferenceCategory(prefContext)) {
            key = "current"
            setTitle(R.string.sync_identity_current)
        }
        currentPref = current.add(Preference(prefContext)) {
            key = "device_id"
            setTitle(R.string.sync_device_id)
            setSummary(PagesR.string.loading)
            setOnPreferenceClickListener {
                deviceId?.let { SyncFormat.showDeviceId(this@IdentityFragment, it) }
                true
            }
        }
        val replace = screen.add(PreferenceCategory(prefContext)) {
            key = "replace"
            setTitle(R.string.sync_identity_replace_category)
        }
        replace.add(Preference(prefContext)) {
            key = "import"
            setTitle(R.string.sync_identity_import)
            setSummary(R.string.sync_identity_import_summary)
            setIcon(R.drawable.ic_file)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), ImportIdentityFragment::class.java, R.string.sync_identity_import)
                true
            }
        }
        replace.add(Preference(prefContext)) {
            key = "generate"
            setTitle(R.string.sync_identity_generate)
            setSummary(R.string.sync_identity_generate_summary)
            setIcon(R.drawable.ic_add)
            setOnPreferenceClickListener {
                generate()
                true
            }
        }
        backupsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "backups"
            setTitle(R.string.sync_identity_backups)
            isVisible = false
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.sync_identity_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            val id = attempt("identity", R.string.error_load_identity) { SyncClient.identity() } ?: return@launchUi
            identity = id
            // Without Syncthing running the page shows the identity without its ID.
            deviceId = if (id.present) orNull("device ID") { Syncthing.myId() } else null
            render(id)
        }
    }

    private fun render(id: IdentityState) {
        currentPref.summary = when {
            !id.present -> getString(R.string.sync_identity_missing)
            else -> deviceId?.let { SyncFormat.twoLines(it) } ?: getString(R.string.sync_identity_present_idle)
        }
        currentPref.isSelectable = deviceId != null
        backupsCategory.removeAll()
        id.backups.forEach { b ->
            backupsCategory.add(Preference(prefContext)) {
                key = "backup:" + b.name
                title = backupTime(b.name)
                summary = listOfNotNull(
                    b.deviceId?.let { getString(R.string.sync_identity_backup_device, it.substringBefore('-')) },
                    if (b.complete) null else getString(R.string.sync_identity_backup_incomplete),
                ).joinToString("\n").ifEmpty { getString(R.string.sync_identity_backup_summary) }
                isEnabled = b.complete
                setIcon(R.drawable.ic_restore)
                setOnPreferenceClickListener {
                    restore(b)
                    true
                }
            }
        }
        backupsCategory.isVisible = id.backups.isNotEmpty()
    }

    private fun backupTime(name: String): String = try {
        val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).parse(name.take(15))
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date!!)
    } catch (e: Exception) {
        name
    }

    private fun generate() {
        val id = identity ?: return
        showDialog("sync-identity-generate") { d ->
            val context = d.context
            val keep = CheckBox(context).apply {
                // An id: the window's saved state keeps the owner's choice across a recreation.
                this.id = android.R.id.checkbox
                setText(R.string.sync_identity_keep_config)
                isChecked = true
            }
            val frame = FrameLayout(context).apply {
                val pad = InputDialog.dp(context, 20)
                setPadding(pad, 0, pad, 0)
                addView(keep)
            }
            val builder = AlertDialog.Builder(context)
                .setTitle(R.string.sync_identity_generate_title)
                .setMessage(if (id.present) R.string.sync_identity_generate_message_replace else R.string.sync_identity_generate_message)
                .setPositiveButton(R.string.sync_identity_generate_confirm) { _, _ ->
                    val keepConfig = keep.isChecked && id.hasConfig
                    change(R.string.error_sync_identity, {
                        val staged = SyncClient.generateIdentity(keepConfig)
                        lastStaged = staged
                    }) { ok -> if (ok) afterStaged(this, lastStaged) }
                }
                .setNegativeButton(android.R.string.cancel, null)
            if (id.hasConfig) builder.setView(frame)
            builder.create()
        }
    }

    private var lastStaged: IdentityInstalled? = null

    private fun restore(b: IdentityBackup) = confirm(
        "sync-identity-restore", R.string.sync_identity_restore_title,
        { getString(R.string.sync_identity_restore_message, backupTime(b.name)) }, R.string.sync_identity_restore,
    ) {
        change(R.string.error_sync_identity, { lastStaged = SyncClient.restoreIdentity(b.name) }) { ok ->
            if (ok) afterStaged(this, lastStaged)
        }
    }

    companion object {
        /**
         * Tells the owner when the new identity takes effect; offers to turn
         * sync on. [onClosed] runs when the dialog closes (after turning sync
         * on, when asked), with the page that is there.
         */
        fun <P : SyncPageFragment> afterStaged(page: P, staged: IdentityInstalled?, @RebuiltWithPage onClosed: P.() -> Unit = {}) {
            staged ?: return
            page.showDialog("sync-identity-staged") { d ->
                val builder = AlertDialog.Builder(d.context).setTitle(R.string.sync_identity_staged_done)
                val message = StringBuilder()
                staged.deviceId?.let { message.append(getString(R.string.sync_identity_new_device, it)).append("\n\n") }
                if (staged.folders.isNotEmpty()) {
                    message.append(getString(R.string.sync_identity_folders_moved)).append('\n')
                    staged.folders.forEach { (_, dir) ->
                        message.append("• ").append(getString(R.string.sync_local_folder_value, dir)).append('\n')
                    }
                    message.append('\n')
                }
                // Closing the page waits for turning sync on, which runs in the page's scope.
                var turningOn = false
                if (staged.restarted) {
                    message.append(getString(R.string.sync_identity_restarted))
                    builder.setPositiveButton(android.R.string.ok, null)
                } else if (staged.enabled) {
                    message.append(getString(R.string.sync_identity_used_when_running))
                    builder.setPositiveButton(android.R.string.ok, null)
                } else {
                    message.append(getString(R.string.sync_identity_used_when_on))
                    builder.setPositiveButton(R.string.sync_turn_on) { _, _ ->
                        turningOn = true
                        change(R.string.error_sync_on, { SyncClient.syncEnable(true) }) { onClosed() }
                    }
                    builder.setNegativeButton(R.string.sync_later, null)
                }
                d.onDismiss { if (!turningOn) onClosed() }
                builder.setMessage(message.toString().trim()).create()
            }
        }
    }
}
