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

package org.lujarch.vault.autofill

import android.app.assist.AssistStructure
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import java.util.Locale

/** The login fields of a screen and where it comes from. */
class ParsedForm(
    val packageName: String,
    /** Web domain of a browser page (or WebView); null for native app screens. */
    val webDomain: String?,
    val username: List<AutofillId>,
    val password: List<AutofillId>,
    val otp: List<AutofillId>,
) {
    val allIds: List<AutofillId>
        get() = username + password + otp

    val isEmpty: Boolean
        get() = username.isEmpty() && password.isEmpty() && otp.isEmpty()
}

/**
 * Finds user name, password and one-time code fields in an autofill
 * structure: autofill hints first, then HTML attributes (browsers), input
 * types, and finally id, hint and description words. A password field
 * without a user name field takes the closest unclassified text field
 * before it as user name, the usual layout of login forms.
 */
object FormParser {
    private enum class Kind { USERNAME, PASSWORD, OTP, TEXT, NONE }

    // Field words in Chinese and Japanese are written as escapes: verification code, dynamic
    // code, password, user, account, mailbox, mobile phone, login name, search.
    private val OTP_WORDS = listOf(
        "otp", "totp", "2fa", "mfa", "one-time", "onetime", "one_time", "verification", "verifycode",
        "verify_code", "authcode", "auth_code", "\u9a8c\u8bc1\u7801", "\u9a57\u8b49\u78bc", "\u52a8\u6001\u7801", "\u52d5\u614b\u78bc", "\u6821\u9a8c\u7801",
    )
    private val PASSWORD_WORDS = listOf("password", "passwd", "passwort", "pwd", "pass", "\u5bc6\u7801", "\u5bc6\u78bc", "\u53e3\u4ee4")
    private val USERNAME_WORDS = listOf(
        "user", "login", "email", "e-mail", "mail", "account", "acct", "phone", "mobile", "identifier",
        "\u7528\u6237", "\u7528\u6236", "\u8d26\u53f7", "\u5e10\u53f7", "\u5e33\u865f", "\u8d26\u6237", "\u8cec\u6236", "\u90ae\u7bb1", "\u90f5\u7bb1", "\u624b\u673a", "\u624b\u6a5f", "\u767b\u5f55\u540d", "\u767b\u5165",
    )
    private val IGNORED_WORDS = listOf("search", "captcha", "query", "\u641c\u7d22", "\u641c\u5c0b")
    private val NOT_LOGIN_HINTS = listOf("creditcard", "postal", "address", "birth", "giftcard", "promo", "name", "person")
    private val HTML_IGNORED_TYPES = setOf(
        "hidden", "submit", "button", "checkbox", "radio", "search", "file", "image", "reset", "range",
        "color", "date", "datetime-local", "month", "week", "time", "number", "url",
    )

    fun parse(structure: AssistStructure): ParsedForm {
        val packageName = structure.activityComponent?.packageName ?: ""
        var webDomain: String? = null
        val username = ArrayList<AutofillId>()
        val password = ArrayList<AutofillId>()
        val otp = ArrayList<AutofillId>()
        val candidates = ArrayList<Pair<Int, AutofillId>>()
        var firstPassword = -1
        var index = 0

        fun walk(node: AssistStructure.ViewNode) {
            if (webDomain == null) node.webDomain?.takeIf { it.isNotBlank() }?.let { webDomain = it }
            val id = node.autofillId
            if (id != null && node.autofillType == View.AUTOFILL_TYPE_TEXT && node.visibility == View.VISIBLE && node.isEnabled) {
                when (classify(node)) {
                    Kind.USERNAME -> username.add(id)
                    Kind.PASSWORD -> {
                        if (firstPassword < 0) firstPassword = index
                        password.add(id)
                    }
                    Kind.OTP -> otp.add(id)
                    Kind.TEXT -> candidates.add(index to id)
                    Kind.NONE -> {}
                }
            }
            index++
            for (i in 0 until node.childCount) walk(node.getChildAt(i))
        }
        for (i in 0 until structure.windowNodeCount) walk(structure.getWindowNodeAt(i).rootViewNode)

        if (password.isNotEmpty() && username.isEmpty()) {
            candidates.lastOrNull { it.first < firstPassword }?.let { username.add(it.second) }
        }
        return ParsedForm(packageName, webDomain, username, password, otp)
    }

    private fun classify(node: AssistStructure.ViewNode): Kind {
        node.autofillHints?.let { hints ->
            for (raw in hints) {
                val h = raw.lowercase(Locale.ROOT).replace("-", "").replace("_", "")
                when {
                    h.contains("password") -> return Kind.PASSWORD
                    h.contains("otp") || h.contains("onetimecode") || h == "2fa" -> return Kind.OTP
                    h.contains("username") || h.contains("email") || h == "phone" || h.contains("phonenumber") -> return Kind.USERNAME
                    NOT_LOGIN_HINTS.any { h.contains(it) } -> return Kind.NONE
                }
            }
        }

        val html = node.htmlInfo
        if (html != null) {
            if (!html.tag.equals("input", ignoreCase = true)) return Kind.NONE
            val attrs = HashMap<String, String>()
            html.attributes?.forEach { attrs[it.first.lowercase(Locale.ROOT)] = it.second ?: "" }
            val type = attrs["type"]?.lowercase(Locale.ROOT)?.ifEmpty { null } ?: "text"
            val auto = attrs["autocomplete"]?.lowercase(Locale.ROOT) ?: ""
            when {
                type == "password" -> return Kind.PASSWORD
                auto.contains("current-password") || auto.contains("new-password") -> return Kind.PASSWORD
                auto.contains("one-time-code") -> return Kind.OTP
                auto.contains("username") || auto.contains("email") -> return Kind.USERNAME
                type == "email" -> return Kind.USERNAME
                type in HTML_IGNORED_TYPES -> return Kind.NONE
            }
            val words = listOfNotNull(attrs["name"], attrs["id"], attrs["placeholder"], attrs["aria-label"], node.hint)
            fromWords(words)?.let { return it }
            return if (type == "text" || type == "tel") Kind.TEXT else Kind.NONE
        }

        val inputType = node.inputType
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT) {
            when (variation) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> return Kind.PASSWORD
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> return Kind.USERNAME
            }
        } else if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
            return Kind.PASSWORD
        }
        val words = listOfNotNull(node.idEntry, node.hint, node.contentDescription?.toString())
        fromWords(words)?.let { return it }
        val editable = inputType != InputType.TYPE_NULL || node.className?.endsWith("EditText") == true
        return if (editable && (cls == InputType.TYPE_CLASS_TEXT || cls == InputType.TYPE_CLASS_PHONE || cls == 0)) Kind.TEXT else Kind.NONE
    }

    private fun fromWords(parts: List<String>): Kind? {
        val w = parts.joinToString(" ").lowercase(Locale.ROOT)
        if (w.isBlank()) return null
        return when {
            IGNORED_WORDS.any { w.contains(it) } -> Kind.NONE
            OTP_WORDS.any { w.contains(it) } -> Kind.OTP
            PASSWORD_WORDS.any { w.contains(it) } -> Kind.PASSWORD
            USERNAME_WORDS.any { w.contains(it) } -> Kind.USERNAME
            else -> null
        }
    }
}
