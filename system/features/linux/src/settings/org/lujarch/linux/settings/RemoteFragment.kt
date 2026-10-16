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

package org.lujarch.linux.settings

import android.content.Context
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import org.lujarch.linux.ILinux
import org.lujarch.linux.RemoteConfig
import org.lujarch.linux.RemoteStatus
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * Remote access (LNX-9): when it runs, the owner's relay server (address, port, token), the
 * entry (name, secret) and the public keys allowed to log in. The token and the secret are set
 * here and never shown again.
 */
class RemoteFragment : LinuxPage() {
    private lateinit var statePref: Preference
    private val modePrefs = LinkedHashMap<String, SelectorWithWidgetPreference>()
    private lateinit var serverPref: Preference
    private lateinit var portPref: Preference
    private lateinit var tokenPref: Preference
    private lateinit var namePref: Preference
    private lateinit var secretPref: Preference
    private lateinit var keysCategory: PreferenceCategory
    private val keyPrefs = ArrayList<Preference>()
    private var remote: RemoteStatus? = null
    private var busy = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        statePref = screen.add(Preference(prefContext)) {
            key = "state"
            setTitle(R.string.lnx_state_title)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        val mode = screen.add(PreferenceCategory(prefContext)) {
            key = "mode"
            setTitle(R.string.lnx_remote_mode)
        }
        for ((value, label) in MODES) {
            modePrefs[value] = mode.add(SelectorWithWidgetPreference(prefContext)) {
                key = "mode_$value"
                setTitle(label)
                isPersistent = false
                setOnClickListener { setMode(value) }
            }
        }
        val server = screen.add(PreferenceCategory(prefContext)) {
            key = "server_category"
            setTitle(R.string.lnx_remote_server_category)
        }
        serverPref = server.add(Preference(prefContext)) {
            key = "server"
            setTitle(R.string.lnx_remote_server)
            setOnPreferenceClickListener {
                edit("lnx-server", R.string.lnx_remote_server, remote?.server.orEmpty(), Field.SERVER)
                true
            }
        }
        portPref = server.add(Preference(prefContext)) {
            key = "port"
            setTitle(R.string.lnx_remote_port)
            setOnPreferenceClickListener {
                edit("lnx-port", R.string.lnx_remote_port, (remote?.port ?: DEFAULT_PORT).toString(), Field.PORT)
                true
            }
        }
        tokenPref = server.add(Preference(prefContext)) {
            key = "token"
            setTitle(R.string.lnx_remote_token)
            setOnPreferenceClickListener {
                edit("lnx-token", R.string.lnx_remote_token, "", Field.TOKEN)
                true
            }
        }
        namePref = server.add(Preference(prefContext)) {
            key = "name"
            setTitle(R.string.lnx_remote_name)
            setOnPreferenceClickListener {
                edit("lnx-name", R.string.lnx_remote_name, remote?.name.orEmpty(), Field.NAME)
                true
            }
        }
        secretPref = server.add(Preference(prefContext)) {
            key = "secret"
            setTitle(R.string.lnx_remote_secret)
            setOnPreferenceClickListener {
                edit("lnx-secret", R.string.lnx_remote_secret, "", Field.SECRET)
                true
            }
        }
        keysCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "keys"
            setTitle(R.string.lnx_remote_keys)
            isOrderingAsAdded = false
        }
        keysCategory.add(Preference(prefContext)) {
            key = "add_key"
            order = ORDER_ADD
            setTitle(R.string.lnx_remote_add_key)
            setIcon(R.drawable.ic_lnx_add)
            setOnPreferenceClickListener {
                edit("lnx-key", R.string.lnx_remote_add_key, "", Field.KEY)
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.lnx_remote_footer)
        }
    }

    override suspend fun load() {
        remote = attempt("remote", R.string.lnx_error_load) { LinuxClient.call("getRemote") { it.remote } }
        render()
    }

    private fun render() {
        val context = context ?: return
        val r = remote
        statePref.summary = r?.let { state(context, it) } ?: getString(R.string.lnx_status_unknown)
        for ((value, pref) in modePrefs) {
            pref.isChecked = r?.mode == value
            pref.isEnabled = r != null && !busy
        }
        val set = getString(R.string.lnx_remote_set)
        val notSet = getString(R.string.lnx_remote_not_set)
        serverPref.summary = r?.server?.ifEmpty { notSet }
        portPref.summary = r?.port?.toString()
        tokenPref.summary = if (r?.tokenSet == true) set else notSet
        namePref.summary = r?.name
        secretPref.summary = if (r?.secretSet == true) set else notSet
        for (pref in listOf(serverPref, portPref, tokenPref, namePref, secretPref)) pref.isEnabled = r != null && !busy
        val keys = r?.keys.orEmpty().toList()
        while (keyPrefs.size > keys.size) keysCategory.removePreference(keyPrefs.removeAt(keyPrefs.size - 1))
        keys.forEachIndexed { index, key ->
            val pref = if (index < keyPrefs.size) {
                keyPrefs[index]
            } else {
                keysCategory.add(Preference(prefContext)) {
                    this.key = "key_$index"
                    setIcon(R.drawable.ic_lnx_key)
                }.also { keyPrefs.add(it) }
            }
            val fields = key.split(' ').filter { it.isNotEmpty() }
            pref.order = index
            pref.title = fields.drop(2).joinToString(" ").ifEmpty { fields.firstOrNull().orEmpty() }
            pref.summary = fields.firstOrNull().orEmpty() + " …" + fields.getOrNull(1)?.takeLast(12).orEmpty()
            pref.setOnPreferenceClickListener {
                confirm("lnx-remove-key", R.string.lnx_remote_remove_key, { getString(R.string.lnx_remote_remove_key_message) },
                    R.string.lnx_remote_remove) { removeKey(index) }
                true
            }
        }
    }

    private enum class Field { SERVER, PORT, TOKEN, NAME, SECRET, KEY }

    private fun edit(dialog: String, title: Int, initial: String, field: Field) {
        showDialog(dialog) { d ->
            InputDialog.create(
                d.context, title,
                initial = initial,
                password = field == Field.TOKEN || field == Field.SECRET,
                numeric = field == Field.PORT,
                singleLine = field != Field.KEY,
                validate = { text -> problem(d.context, field, text) },
            ) { text -> save(field, text) }
        }
    }

    private fun problem(context: Context, field: Field, text: String): String? =
        when (field) {
            Field.SERVER -> if (text.isEmpty() || HOST.matches(text)) null else context.getString(R.string.lnx_remote_invalid_server)
            Field.PORT -> if (text.toIntOrNull() in 1..65535) null else context.getString(R.string.lnx_remote_invalid_port)
            Field.NAME -> if (NAME.matches(text)) null else context.getString(R.string.lnx_remote_invalid_name)
            Field.TOKEN, Field.SECRET -> if (text.isEmpty() || SECRET.matches(text)) null else context.getString(R.string.lnx_remote_invalid_secret)
            Field.KEY -> if (KEY.matches(text.trim())) null else context.getString(R.string.lnx_remote_invalid_key)
        }

    private fun save(field: Field, text: String) {
        val r = remote ?: return
        val config = RemoteConfig().apply {
            server = r.server
            port = r.port
            name = r.name
            keys = r.keys
            token = null
            secret = null
        }
        when (field) {
            Field.SERVER -> config.server = text
            Field.PORT -> config.port = text.toInt()
            Field.TOKEN -> config.token = text
            Field.NAME -> config.name = text
            Field.SECRET -> config.secret = text
            Field.KEY -> config.keys = r.keys + text.trim()
        }
        apply(config)
    }

    private fun removeKey(index: Int) {
        val r = remote ?: return
        if (index >= r.keys.size) return
        apply(RemoteConfig().apply {
            server = r.server
            port = r.port
            name = r.name
            keys = r.keys.filterIndexed { i, _ -> i != index }.toTypedArray()
            token = null
            secret = null
        })
    }

    private fun apply(config: RemoteConfig) {
        if (busy) return
        busy = true
        render()
        launchUi {
            act(R.string.lnx_error_remote) { LinuxClient.call("setRemote") { it.setRemote(config) } }
            busy = false
            refresh()
        }
    }

    private fun setMode(mode: String) {
        if (busy) return
        busy = true
        render()
        launchUi {
            act(R.string.lnx_error_switch) { LinuxClient.setGlobal(requireContext(), "remote", mode) }
            busy = false
            refresh()
        }
    }

    companion object {
        private const val ORDER_ADD = 10_000
        private const val DEFAULT_PORT = 7000
        private val HOST = Regex("^[A-Za-z0-9.:\\[\\]-]{1,253}$")
        private val NAME = Regex("^[A-Za-z0-9._-]{1,64}$")
        private val SECRET = Regex("^[\\x21-\\x7e]{1,256}$")
        private val KEY = Regex("^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-ssh-ed25519@openssh\\.com|sk-ecdsa-sha2-nistp256@openssh\\.com) [A-Za-z0-9+/=]+( .*)?$")

        val MODES = linkedMapOf(
            ILinux.REMOTE_CHARGING to R.string.lnx_remote_charging,
            ILinux.REMOTE_ALWAYS to R.string.lnx_remote_always,
            ILinux.REMOTE_OFF to R.string.lnx_remote_off,
        )

        fun modeLabel(context: Context, mode: String?): String =
            context.getString(MODES[mode] ?: R.string.lnx_remote_off)

        fun state(context: Context, r: RemoteStatus): String =
            when (r.state) {
                ILinux.RELAY_RUNNING -> context.getString(R.string.lnx_remote_running)
                ILinux.RELAY_WAITING -> context.getString(R.string.lnx_remote_waiting)
                ILinux.RELAY_NOT_SET_UP -> context.getString(R.string.lnx_remote_not_set_up)
                ILinux.RELAY_NO_LINUX -> context.getString(R.string.lnx_remote_no_linux)
                ILinux.RELAY_NO_OPENSSH -> context.getString(R.string.lnx_remote_no_openssh)
                ILinux.RELAY_FAILED -> context.getString(R.string.lnx_remote_failed)
                else -> context.getString(R.string.lnx_remote_off)
            }

        /** The row's summary in the Linux development environment's page and the tile's subtitle. */
        fun summary(context: Context, r: RemoteStatus): String =
            if (r.mode == ILinux.REMOTE_OFF) {
                modeLabel(context, r.mode)
            } else {
                modeLabel(context, r.mode) + context.getString(R.string.lnx_separator) + state(context, r)
            }
    }
}
