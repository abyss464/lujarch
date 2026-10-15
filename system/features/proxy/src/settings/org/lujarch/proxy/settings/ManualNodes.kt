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

package org.lujarch.proxy.settings

import android.app.Dialog
import android.content.Context
import android.net.Uri
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.HeldBytes
import org.lujarch.settings.pages.PageRequest
import org.lujarch.settings.pages.showDialog

/** Largest file the proxy pages read from a document (the manager takes 16 MiB). */
const val MAX_IMPORT_BYTES = 12 * 1024 * 1024

/** Largest geodata database (the manager takes 30 MiB). */
const val MAX_GEODATA_BYTES = 30 * 1024 * 1024

/** Reads a picked document, at most [limit] bytes; null (with a toast) when too large or unreadable. */
suspend fun readDocument(context: Context, uri: Uri, limit: Int = MAX_IMPORT_BYTES): ByteArray? {
    val bytes = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) return@withContext ByteArray(0) to true
                }
                out.toByteArray() to false
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "reading the picked document failed: ${e.message}")
            null
        }
    }
    return when {
        bytes == null -> {
            Toast.makeText(context, R.string.px_files_read_failed, Toast.LENGTH_LONG).show()
            null
        }
        bytes.second -> {
            Toast.makeText(context, R.string.px_files_too_large, Toast.LENGTH_LONG).show()
            null
        }
        else -> bytes.first
    }
}

/** A single-line field, optionally masked (node links hold credentials). */
fun formField(context: Context, hint: Int, masked: Boolean = false): EditText = EditText(context).apply {
    setHint(hint)
    importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
    isSingleLine = true
    inputType = if (masked) {
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    } else {
        InputType.TYPE_CLASS_TEXT
    }
    if (masked) imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
}

/**
 * A number field of a dialog form with its unit after it ("60 s"), in the field's size
 * and hint color on the field's baseline: a hint alone disappears once the field holds
 * a value, so the unit stands on its own and the field keeps no hint.
 */
fun withUnit(context: Context, input: EditText, unit: String): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    input.hint = null
    addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    addView(
        TextView(context).apply {
            text = unit
            setTextSize(TypedValue.COMPLEX_UNIT_PX, input.textSize)
            setTextColor(input.hintTextColors)
            setPaddingRelative((8 * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
        },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT),
    )
}

/** The unit after a number field, by the hint its row passes (the hint names the unit). */
fun numberUnit(hint: Int): Int? = when (hint) {
    R.string.px_opt_seconds_hint -> R.string.px_unit_seconds
    R.string.px_gt_ms_hint -> R.string.px_unit_ms
    R.string.px_ad_percent_hint -> R.string.px_unit_percent
    R.string.px_tun_mtu_hint -> R.string.px_unit_bytes
    R.string.px_geo_hours_hint -> R.string.px_unit_hours
    else -> null
}

/** A page that adds manual nodes: its [ManualNodes], whose dialogs a recreated page builds again with its own. */
interface ManualNodesPage {
    val manualNodes: ManualNodes
}

/** The manual nodes of the page a dialog is built for (the recreated one after a recreation). */
private fun Fragment.manual(): ManualNodes = (this as ManualNodesPage).manualNodes

/**
 * Manual nodes: a name, the nodes from a file or a pasted link, and where they
 * are, so that they join the country groups: located through the nodes (their
 * exit address, as for subscriptions) or a country and line type given by
 * hand. The work runs as a proxy task and applies the configuration. The
 * dialogs are page dialogs of [fragment]; the pasted link is a secret
 * field (never saved, empty in a recreated dialog).
 */
class ManualNodes(private val fragment: Fragment, private val onStarted: (key: String) -> Unit) {
    /**
     * Which provider the picked file is for, and whether it may append, while
     * the picker is open: in the page's saved state (a name, no secret), so
     * a page recreated meanwhile, or restored in a restarted process, takes
     * the file. The file itself stays in memory only.
     */
    private val pickFor = PageRequest(fragment, "manual-nodes-file")
    private val picker: ActivityResultLauncher<Array<String>> =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val request = pickFor.take()
            val name = request?.firstOrNull()
            if (name == null) {
                if (uri != null) Log.w(TAG, "manual nodes: a picked file came back without its request; ignored")
                return@registerForActivityResult
            }
            if (uri == null) return@registerForActivityResult
            val appendAllowed = request.getOrNull(1) == APPEND
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val bytes = readDocument(fragment.requireContext(), uri) ?: return@launch
                askPlacement(name, bytes, appendAllowed)
            }
        }

    /** A new manual provider: asks for its name first. */
    fun addNew(existing: Collection<String>) {
        fragment.showDialog("px-manual-name") { d -> manual().nameDialog(d.context, existing) }
    }

    private fun nameDialog(context: Context, existing: Collection<String>): Dialog {
        val input = formField(context, R.string.px_sub_name_hint).apply { id = R.id.px_manual_name }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.px_sub_add_manual)
            .setMessage(R.string.px_manual_name_message)
            .setView(wrap(context, input))
            .setPositiveButton(R.string.px_action_next, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                when {
                    !isProviderName(name) -> input.error = context.getString(R.string.px_sub_name_invalid)
                    name in existing -> input.error = context.getString(R.string.px_sub_exists, name)
                    else -> {
                        dialog.dismiss()
                        chooseSource(name, appendAllowed = false)
                    }
                }
            }
        }
        return dialog
    }

    /** New nodes for an existing manual provider. */
    fun replace(name: String) = chooseSource(name, appendAllowed = true)

    private fun chooseSource(name: String, appendAllowed: Boolean) {
        fragment.showDialog("px-manual-source") { d -> manual().sourceDialog(d.context, name, appendAllowed) }
    }

    private fun sourceDialog(context: Context, name: String, appendAllowed: Boolean): Dialog =
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.px_manual_source_title, name))
            .setItems(arrayOf(context.getString(R.string.px_manual_from_file), context.getString(R.string.px_manual_paste))) { _, which ->
                if (which == 0) {
                    pickFor.start(name, if (appendAllowed) APPEND else "")
                    picker.launch(arrayOf("*/*"))
                } else {
                    paste(name, appendAllowed)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

    private fun paste(name: String, appendAllowed: Boolean) {
        fragment.showDialog("px-manual-paste") { d -> manual().pasteDialog(d.context, name, appendAllowed) }
    }

    private fun pasteDialog(context: Context, name: String, appendAllowed: Boolean): Dialog {
        val input = formField(context, R.string.px_manual_link_hint, masked = true)
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.px_manual_paste)
            .setMessage(R.string.px_manual_paste_message)
            .setView(wrap(context, input))
            .setPositiveButton(R.string.px_action_next, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val link = input.text.toString().trim()
                if (!link.contains("://") || link.any { it.isWhitespace() }) {
                    input.error = context.getString(R.string.px_manual_link_invalid)
                } else {
                    input.text.clear()
                    dialog.dismiss()
                    askPlacement(name, link.toByteArray(Charsets.UTF_8), appendAllowed)
                }
            }
        }
        return dialog
    }

    /**
     * Where the nodes are. They hold credentials: kept by the dialog in memory
     * (a recreation keeps them), taken by Save and wiped once the manager has
     * them, wiped by every other end of the dialog.
     */
    private fun askPlacement(name: String, nodes: ByteArray, appendAllowed: Boolean) {
        val held = HeldBytes(nodes)
        fragment.showDialog("px-manual-placement", onEnd = { held.wipe() }) { d -> manual().placementDialog(d.context, name, held, appendAllowed) }
    }

    private fun placementDialog(context: Context, name: String, held: HeldBytes, appendAllowed: Boolean): Dialog {
        val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        // Located through the nodes (their exit address), or by hand.
        val how = RadioGroup(context).apply {
            addView(RadioButton(context).apply {
                id = R.id.px_manual_probe
                setText(R.string.px_manual_locate_probe)
            })
            addView(RadioButton(context).apply {
                id = R.id.px_manual_hand
                setText(R.string.px_manual_locate_hand)
            })
            check(R.id.px_manual_hand)
        }
        form.addView(how)
        val country = formField(context, R.string.px_manual_country_hint).apply {
            id = R.id.px_manual_country
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        form.addView(country)
        val netTypeLabel = TextView(context).apply {
            setText(R.string.px_manual_net_type)
            setPadding(0, 24, 0, 0)
        }
        form.addView(netTypeLabel)
        val types = listOf("residential", "datacenter", "starlink")
        val typeIds = listOf(R.id.px_manual_residential, R.id.px_manual_datacenter, R.id.px_manual_starlink)
        val group = RadioGroup(context)
        types.forEachIndexed { i, t ->
            group.addView(RadioButton(context).apply {
                id = typeIds[i]
                text = ProxyFormat.netTypeLabel(context, t)
            })
        }
        group.check(typeIds[1])
        form.addView(group)
        how.setOnCheckedChangeListener { _, id ->
            val byHand = if (id == R.id.px_manual_hand) android.view.View.VISIBLE else android.view.View.GONE
            country.visibility = byHand
            netTypeLabel.visibility = byHand
            group.visibility = byHand
        }
        val append = CheckBox(context).apply {
            id = R.id.px_manual_append
            setText(R.string.px_manual_append)
            visibility = if (appendAllowed) android.view.View.VISIBLE else android.view.View.GONE
        }
        form.addView(append)
        val dialog = AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.px_manual_placement_title, name))
            .setMessage(R.string.px_manual_placement_message)
            .setView(wrap(context, form))
            .setPositiveButton(R.string.px_action_save_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val probe = how.checkedRadioButtonId == R.id.px_manual_probe
                val cc = country.text.toString().trim().uppercase()
                if (!probe && (cc.length != 2 || !cc.all { it in 'A'..'Z' })) {
                    country.error = context.getString(R.string.px_manual_country_invalid)
                    return@setOnClickListener
                }
                val netType = types[typeIds.indexOf(group.checkedRadioButtonId).coerceIn(0, types.size - 1)]
                val appendNodes = append.isChecked
                val nodes = held.take()
                dialog.dismiss()
                if (nodes == null) {
                    Log.w(TAG, "manual nodes: saved after the picked nodes were wiped; nothing saved")
                    return@setOnClickListener
                }
                val key = SubscriptionFragment.KEY_PREFIX + name
                val started = ProxyTasks.start(context, key, context.getString(R.string.px_manual_saving, name)) {
                    step(this.context.getString(R.string.px_step_saving))
                    try {
                        ProxyManager.setManualNodes(name, nodes, appendNodes, if (probe) null else cc, if (probe) null else netType)
                    } finally {
                        nodes.fill(0)
                    }
                    if (probe) locateStep()
                    applyConfig()
                    summary(this.context.getString(R.string.px_manual_saved))
                }
                if (started) {
                    onStarted(key)
                } else {
                    nodes.fill(0)
                    Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
                }
            }
        }
        return dialog
    }

    private fun wrap(context: Context, view: android.view.View): android.view.View =
        LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null).also {
            (it as LinearLayout).addView(view)
        }

    private companion object {
        const val APPEND = "append"
    }
}
