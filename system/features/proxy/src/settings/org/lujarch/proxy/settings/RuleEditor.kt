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

import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import org.lujarch.settings.pages.RebuiltWithPage
import org.lujarch.settings.pages.showDialog

/**
 * The rule form: type, pattern, where the traffic goes, and "no-resolve" for
 * address rules; for a new rule also the position in its set. The target is
 * chosen from the groups the configuration has (or typed, for a group that
 * does not exist yet). [onDone] gets the rule in manager syntax. The forms
 * are page dialogs: their fields have ids, so a recreated window
 * shows what the owner entered, and the chosen target is the form's.
 */
object RuleEditor {
    /** Types whose pattern is an address (they take no-resolve). */
    private val ADDRESS_TYPES = setOf("IP-CIDR", "IP-CIDR6", "IP-SUFFIX", "IP-ASN", "GEOIP")

    /** Types that need no pattern. */
    private const val MATCH = "MATCH"

    /** The target chosen in the form, which no field keeps; [shown] updates the window on screen. */
    private class Chosen(var target: String?) {
        var shown: ((String) -> Unit)? = null

        fun set(t: String) {
            target = t
            shown?.invoke(t)
        }
    }

    fun <P : Fragment> show(
        page: P,
        title: Int,
        types: List<String>,
        targets: List<RouteTarget>,
        current: RuleInfo?,
        askPosition: Boolean,
        @RebuiltWithPage onDone: P.(rule: String, position: Int?) -> Unit,
    ) {
        val chosen = Chosen(current?.target)
        page.showDialog("px-rule") { d ->
            val context = d.context
            val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
            val shownTypes = types.filter { it != MATCH }.ifEmpty { listOf("DOMAIN-SUFFIX") }
            form.addView(label(context, R.string.px_rule_type))
            val type = Spinner(context).apply {
                id = R.id.px_rule_type
                adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, shownTypes.map { typeLabel(context, it) })
                setSelection(shownTypes.indexOf(current?.type ?: "DOMAIN-SUFFIX").coerceAtLeast(0))
            }
            form.addView(type)
            val pattern = formField(context, R.string.px_rule_pattern_hint).apply {
                id = R.id.px_rule_pattern
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(current?.pattern ?: "")
            }
            form.addView(pattern)
            form.addView(label(context, R.string.px_rule_target))
            val targetButton = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                text = chosen.target?.let { targetText(context, it, targets) } ?: context.getString(R.string.px_rule_target_choose)
                isAllCaps = false
            }
            targetButton.setOnClickListener { chooseTarget(this, targets, chosen.target) { t -> chosen.set(t) } }
            chosen.shown = { t ->
                targetButton.text = targetText(context, t, targets)
                targetButton.error = null
            }
            form.addView(targetButton)
            val noResolve = CheckBox(context).apply {
                id = R.id.px_rule_no_resolve
                setText(R.string.px_rule_no_resolve)
                isChecked = current?.params?.split(',')?.contains("no-resolve") == true
            }
            form.addView(noResolve)
            type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    noResolve.visibility = if (shownTypes[position] in ADDRESS_TYPES) View.VISIBLE else View.GONE
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
            val position = formField(context, R.string.px_rule_position_hint).apply {
                id = R.id.px_rule_position
                inputType = InputType.TYPE_CLASS_NUMBER
                visibility = if (askPosition) View.VISIBLE else View.GONE
            }
            form.addView(position)
            val dialog = AlertDialog.Builder(context)
                .setTitle(title)
                .setView(form)
                .setPositiveButton(R.string.px_action_save_apply, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val t = shownTypes[type.selectedItemPosition]
                    val p = pattern.text.toString().trim()
                    val tgt = chosen.target
                    val pos = position.text.toString().trim()
                    when {
                        p.isEmpty() || p.contains('\n') || (!t.startsWith("AND") && !t.startsWith("OR") && !t.startsWith("NOT") &&
                            t != "SUB-RULE" && p.contains(',')) -> pattern.error = context.getString(R.string.px_rule_pattern_invalid)
                        tgt == null -> targetButton.error = context.getString(R.string.px_rule_target_missing)
                        askPosition && pos.isNotEmpty() && (pos.toIntOrNull() ?: 0) < 1 -> position.error = context.getString(R.string.px_rule_position_invalid)
                        else -> {
                            val params = current?.params?.split(',')?.filter { it != "no-resolve" && it.isNotBlank() }.orEmpty() +
                                if (noResolve.isChecked && t in ADDRESS_TYPES) listOf("no-resolve") else emptyList()
                            val rule = (listOf(t, p, tgt) + params).joinToString(",")
                            dialog.dismiss()
                            onDone(rule, pos.toIntOrNull()?.minus(1))
                        }
                    }
                }
            }
            pattern.requestFocus()
            dialog
        }
    }

    /** A single-choice list of the targets; the last entry types a group name. [onChosen] runs with the page that is there. */
    fun <P : Fragment> chooseTarget(page: P, targets: List<RouteTarget>, current: String?, @RebuiltWithPage onChosen: P.(String) -> Unit) {
        page.showDialog("px-rule-target") { d ->
            val context = d.context
            val shown = targets.filter { it.kind == RouteTarget.KIND_BUILTIN || it.isPrimary || it.target == current }
            val labels = shown.map { targetText(context, it.target, targets) } + context.getString(R.string.px_rule_target_other)
            AlertDialog.Builder(context)
                .setTitle(R.string.px_rule_target)
                .setSingleChoiceItems(labels.toTypedArray(), shown.indexOfFirst { it.target == current }) { dialog, which ->
                    dialog.dismiss()
                    if (which < shown.size) onChosen(shown[which].target) else askTargetName(this, current, onChosen)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun <P : Fragment> askTargetName(page: P, current: String?, @RebuiltWithPage onChosen: P.(String) -> Unit) {
        page.showDialog("px-rule-target-name") { d ->
            val context = d.context
            val input = formField(context, R.string.px_rule_target_name_hint).apply {
                id = R.id.px_rule_target_name
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setText(current ?: "")
            }
            val form = LayoutInflater.from(context).inflate(R.layout.px_dialog_form, null) as LinearLayout
            form.addView(input)
            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.px_rule_target_other)
                .setMessage(R.string.px_rule_target_name_message)
                .setView(form)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val t = input.text.toString().trim()
                    if (t.isEmpty() || t.startsWith("-") || t.any { it == ',' || it.isWhitespace() || it.isISOControl() } ||
                        t.toByteArray(Charsets.UTF_8).size > 128
                    ) {
                        input.error = context.getString(R.string.px_rule_target_invalid)
                    } else {
                        dialog.dismiss()
                        onChosen(t)
                    }
                }
            }
            dialog
        }
    }

    /** A target as the configuration names it: the group's name, DIRECT and REJECT in words. */
    fun targetText(context: Context, target: String, targets: List<RouteTarget>): String {
        val t = targets.firstOrNull { it.target == target }
        return when {
            t == null -> ProxyFormat.targetLabel(context, target)
            t.kind == RouteTarget.KIND_BUILTIN -> RulesFragment.targetLabel(context, target)
            else -> t.group
        }
    }

    /** A rule type in words where it has a common name, with the type itself. */
    fun typeLabel(context: Context, type: String): String {
        val res = when (type) {
            "DOMAIN" -> R.string.px_rule_type_domain
            "DOMAIN-SUFFIX" -> R.string.px_rule_type_suffix
            "DOMAIN-KEYWORD" -> R.string.px_rule_type_keyword
            "DOMAIN-WILDCARD" -> R.string.px_rule_type_wildcard
            "DOMAIN-REGEX" -> R.string.px_rule_type_regex
            "GEOSITE" -> R.string.px_rule_type_geosite
            "GEOIP" -> R.string.px_rule_type_geoip
            "IP-CIDR", "IP-CIDR6" -> R.string.px_rule_type_cidr
            "IP-ASN" -> R.string.px_rule_type_asn
            "DST-PORT" -> R.string.px_rule_type_port
            "NETWORK" -> R.string.px_rule_type_network
            "PROCESS-NAME" -> R.string.px_rule_type_app
            "PROCESS-NAME-REGEX" -> R.string.px_rule_type_process_name_regex
            "PROCESS-NAME-WILDCARD" -> R.string.px_rule_type_process_name_wildcard
            "PROCESS-PATH" -> R.string.px_rule_type_process_path
            "PROCESS-PATH-REGEX" -> R.string.px_rule_type_process_path_regex
            "PROCESS-PATH-WILDCARD" -> R.string.px_rule_type_process_path_wildcard
            "UID" -> R.string.px_rule_type_uid
            "SRC-IP-CIDR" -> R.string.px_rule_type_src_cidr
            "SRC-PORT" -> R.string.px_rule_type_src_port
            "SRC-GEOIP" -> R.string.px_rule_type_src_geoip
            "RULE-SET" -> R.string.px_rule_type_rule_set
            "AND", "OR", "NOT" -> R.string.px_rule_type_logic
            else -> return type
        }
        return context.getString(R.string.px_rule_type_named, context.getString(res), type)
    }

    private fun label(context: Context, text: Int) = TextView(context).apply {
        setText(text)
        setPadding(0, 24, 0, 0)
    }
}
