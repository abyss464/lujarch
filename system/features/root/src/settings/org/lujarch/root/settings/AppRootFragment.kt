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

package org.lujarch.root.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.MainSwitchPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import org.lujarch.root.IRoot
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.OutlivesPage
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * Root access of one app, with every field KernelSU's app profile has (ROOT-1, ROOT-2): the switch
 * (a grant is confirmed, with the reach of a system or shell identity spelled out, and asks for
 * the owner's credential), and while allowed the root profile of its su sessions (user and group
 * ID, groups, capabilities, SELinux domain, no-new-privileges, mount namespace) with "Restore
 * default profile". Changing the profile warns once per page.
 */
class AppRootFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private lateinit var mainSwitch: MainSwitchPreference
    private lateinit var statePref: Preference
    private lateinit var sharedPref: Preference
    private lateinit var authPref: Preference
    private lateinit var profileCategory: PreferenceCategory
    private lateinit var sourcePref: Preference
    private lateinit var uidPref: Preference
    private lateinit var gidPref: Preference
    private lateinit var groupsPref: Preference
    private lateinit var capsPref: Preference
    private lateinit var domainPref: Preference
    private lateinit var noNewPrivsPref: SwitchPreferenceCompat
    private lateinit var resetPref: Preference
    private lateinit var namespaceCategory: PreferenceCategory
    private val namespacePrefs = LinkedHashMap<Int, SelectorWithWidgetPreference>()

    private var state: AppRootState? = null
    private var busy = false
    private var label: String = ""
    private var installed = true
    private var warned = false
    private var noKernelSu = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        packageName = requireArguments().getString(ARG_PACKAGE) ?: ""
        warned = savedInstanceState?.getBoolean(STATE_WARNED) ?: false
        label = appLabel(requireContext(), packageName) ?: requireArguments().getString(ARG_LABEL) ?: packageName
        screen.add(Preference(prefContext)) {
            key = "app"
            title = label
            summary = packageName
            isSelectable = false
            appIcon(requireContext(), packageName)?.let { icon = it }
        }
        mainSwitch = screen.add(MainSwitchPreference(prefContext)) {
            key = "allow"
            setTitle(R.string.root_allow_switch)
            isPersistent = false
            isEnabled = false
            setOnPreferenceChangeListener { _, value ->
                toggle(value as Boolean)
                false
            }
        }
        val status = screen.add(PreferenceCategory(prefContext)) { key = "status" }
        statePref = status.add(Preference(prefContext)) {
            key = "state"
            setTitle(R.string.root_state_title)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
        sharedPref = status.add(Preference(prefContext)) {
            key = "shared"
            setTitle(R.string.root_shared_title)
            isSelectable = false
            isVisible = false
        }
        // What a grant and a widening profile change ask for, and the
        // way to set a screen lock when there is none to ask.
        authPref = status.add(Preference(prefContext)) {
            key = "auth"
            setTitle(R.string.root_auth_title)
            setIcon(R.drawable.ic_key)
            setOnPreferenceClickListener {
                RootAuth.openScreenLock(this@AppRootFragment)
                true
            }
        }

        profileCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "profile"
            setTitle(R.string.root_profile_category)
            isVisible = false
        }
        sourcePref = profileCategory.add(Preference(prefContext)) {
            key = "profile_source"
            setTitle(R.string.root_profile_source)
            isSelectable = false
        }
        uidPref = profileCategory.add(Preference(prefContext)) {
            key = "profile_uid"
            setTitle(R.string.root_profile_uid)
            setOnPreferenceClickListener {
                editId(R.string.root_profile_uid, R.string.root_profile_uid_message, { it.uid }) { p, v -> p.copy(uid = v) }
                true
            }
        }
        gidPref = profileCategory.add(Preference(prefContext)) {
            key = "profile_gid"
            setTitle(R.string.root_profile_gid)
            setOnPreferenceClickListener {
                editId(R.string.root_profile_gid, R.string.root_profile_gid_message, { it.gid }) { p, v -> p.copy(gid = v) }
                true
            }
        }
        groupsPref = profileCategory.add(Preference(prefContext)) {
            key = "profile_groups"
            setTitle(R.string.root_profile_groups)
            setOnPreferenceClickListener {
                editGroups()
                true
            }
        }
        capsPref = profileCategory.add(Preference(prefContext)) {
            key = "profile_caps"
            setTitle(R.string.root_profile_capabilities)
            setOnPreferenceClickListener {
                editCapabilities()
                true
            }
        }
        domainPref = profileCategory.add(Preference(prefContext)) {
            key = "profile_domain"
            setTitle(R.string.root_profile_domain)
            setOnPreferenceClickListener {
                editDomain()
                true
            }
        }
        noNewPrivsPref = profileCategory.add(SwitchPreferenceCompat(prefContext)) {
            key = "profile_no_new_privs"
            setTitle(R.string.root_profile_no_new_privs)
            setSummary(R.string.root_profile_no_new_privs_summary)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                val on = value as Boolean
                edit { it.copy(noNewPrivileges = on) }
                false
            }
        }
        resetPref = profileCategory.add(Preference(prefContext)) {
            key = "profile_reset"
            setTitle(R.string.root_profile_reset)
            setSummary(R.string.root_profile_reset_summary)
            setIcon(R.drawable.ic_refresh)
            setOnPreferenceClickListener {
                confirmReset()
                true
            }
        }

        namespaceCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "namespace"
            setTitle(R.string.root_namespace_category)
            isVisible = false
        }
        for ((ns, title, summary) in listOf(
            Triple(NS_INHERITED, R.string.root_namespace_inherited, R.string.root_namespace_inherited_summary),
            Triple(NS_GLOBAL, R.string.root_namespace_global, R.string.root_namespace_global_summary),
            Triple(NS_INDIVIDUAL, R.string.root_namespace_individual, R.string.root_namespace_individual_summary),
        )) {
            namespacePrefs[ns] = namespaceCategory.add(SelectorWithWidgetPreference(prefContext)) {
                key = "ns_$ns"
                setTitle(title)
                setSummary(summary)
                isPersistent = false
                setOnClickListener { setNamespace(ns) }
            }
        }

        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.root_app_footer)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_WARNED, warned)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            installed = appLabel(requireContext(), packageName) != null
            val status = attempt("status", R.string.error_load_root) { RootClient.status() }
            noKernelSu = status?.available == false
            val s = if (status?.available == true) {
                attempt("profile", R.string.error_load_root) { RootClient.state(requireContext(), packageName) }
            } else {
                null
            }
            state = s
            render(s)
        }
    }

    private fun render(s: AppRootState?) {
        activity?.title = label
        val canGrant = s != null && s.rootAllowed && installed
        mainSwitch.isEnabled = s != null && !busy && (s.allowSu || canGrant)
        mainSwitch.isChecked = s?.allowSu == true
        statePref.summary = when {
            noKernelSu -> getString(R.string.root_ksu_unavailable)
            s == null -> getString(R.string.status_unknown)
            s.allowSu -> getString(R.string.root_state_allowed_detail)
            s.uidClass == AppRootState.UID_FORBIDDEN -> getString(R.string.root_state_forbidden, s.uid)
            !s.rootAllowed -> getString(R.string.root_state_settings_app)
            else -> getString(R.string.root_state_never_detail)
        }
        val shared = s?.sharedWith.orEmpty()
        sharedPref.isVisible = shared.isNotEmpty()
        if (shared.isNotEmpty()) {
            val names = shared.take(MAX_SHARED_NAMES).map { appLabel(requireContext(), it) ?: it }
            val more = shared.size - names.size
            // "and N more" belongs to the list of names, inside the sentence.
            val listed = Format.names(requireContext(), names) +
                if (more > 0) resources.getQuantityString(R.plurals.root_shared_more, more, more) else ""
            sharedPref.summary = getString(R.string.root_shared_summary, s!!.uid, listed)
        }

        val unavailable = RootAuth.unavailableReason(requireContext())
        authPref.summary = getString(unavailable ?: R.string.root_auth_state_ok)
        authPref.isSelectable = unavailable != null

        val root = s?.root
        profileCategory.isVisible = root != null
        namespaceCategory.isVisible = root != null
        if (root != null) {
            sourcePref.summary = getString(
                if (root.custom) R.string.root_profile_source_own else R.string.root_profile_source_default
            )
            uidPref.summary = idLabel(root.uid)
            gidPref.summary = idLabel(root.gid)
            groupsPref.summary = if (root.groups.isEmpty()) getString(R.string.root_profile_none)
            else root.groups.joinToString(getString(PagesR.string.list_separator)) { RootIds.groupLabel(it) }
            capsPref.summary = when {
                root.allCapabilities -> getString(R.string.root_profile_capabilities_all)
                root.capabilities == 0L -> getString(R.string.root_profile_none)
                else -> resources.getQuantityString(
                    R.plurals.root_profile_capabilities_count, java.lang.Long.bitCount(root.capabilities),
                    java.lang.Long.bitCount(root.capabilities),
                ) + "\n" + RootIds.capabilityNames(root.capabilities).joinToString(" ")
            }
            domainPref.summary = root.selinuxDomain
            noNewPrivsPref.isChecked = root.noNewPrivileges
            resetPref.isVisible = root.custom
            listOf(uidPref, gidPref, groupsPref, capsPref, domainPref, noNewPrivsPref, resetPref).forEach { it.isEnabled = !busy }
            namespacePrefs.forEach { (ns, pref) ->
                pref.isChecked = ns == root.namespace
                pref.isEnabled = !busy
            }
        }
    }

    private fun idLabel(id: Int): String = if (id == 0) getString(R.string.root_profile_root_id) else id.toString()

    // ---- grant and revoke ----

    private fun toggle(allow: Boolean) {
        if (!allow) {
            set(false)
            return
        }
        val s = state ?: return
        confirm("root-grant", R.string.root_grant_title, { grantMessage(s) }, R.string.root_grant_confirm) {
            // The owner's own credential, right before the grant is sent
            //; revoking asks for nothing.
            RootAuth.authenticate(
                this, getString(R.string.root_auth_grant_title),
                getString(R.string.root_auth_grant_subtitle, label),
            ) { set(true) }
        }
    }

    private fun grantMessage(s: AppRootState): CharSequence {
        val message = StringBuilder(
            when (s.uidClass) {
                AppRootState.UID_SYSTEM -> getString(R.string.root_grant_message_system, label)
                AppRootState.UID_SHELL -> getString(R.string.root_grant_message_shell, label)
                AppRootState.UID_OTHER -> getString(R.string.root_grant_message_other, label, s.uid)
                else -> getString(R.string.root_grant_message, label)
            }
        )
        if (s.sharedWith.isNotEmpty() && s.uidClass != AppRootState.UID_SYSTEM) {
            message.append("\n\n").append(
                resources.getQuantityString(R.plurals.root_grant_shared, s.sharedWith.size, s.sharedWith.size)
            )
        }
        return message
    }

    private fun set(allow: Boolean) {
        if (busy) return
        busy = true
        render(state)
        launchUi {
            act(if (allow) R.string.error_su_grant else R.string.error_su_revoke) { RootClient.setAllowed(packageName, allow) }
            busy = false
            refresh()
        }
    }

    // ---- the root profile ----

    /**
     * Runs [change] on the current profile after the one-time warning. The
     * warning keeps the profile it was asked for: a recreated page writes
     * the same change.
     */
    private fun edit(@OutlivesPage change: (RootProfile) -> RootProfile) {
        val root = state?.root ?: return
        if (busy) return
        if (warned) {
            write(change(root))
            return
        }
        showDialog("root-warning") { d ->
            d.onCancel { render(state) }
            AlertDialog.Builder(d.context)
                .setTitle(R.string.root_profile_warning_title)
                .setMessage(R.string.root_profile_warning_message)
                .setPositiveButton(R.string.root_profile_warning_continue) { _, _ ->
                    warned = true
                    write(change(root))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> render(state) }
                .create()
        }
    }

    /** Asks for the value first, then warns (the warning is about writing it). */
    private fun ask(open: (RootProfile) -> Unit) {
        val root = state?.root ?: return
        if (!busy) open(root)
    }

    private fun write(profile: RootProfile) {
        val current = state?.root
        if (current != null && RootAuth.widens(current, profile)) {
            RootAuth.authenticate(
                this, getString(R.string.root_auth_profile_title),
                getString(R.string.root_auth_profile_subtitle, label),
            ) { send(profile) }
            return
        }
        send(profile)
    }

    private fun send(profile: RootProfile) {
        busy = true
        render(state)
        launchUi {
            act(R.string.error_root_profile) { RootClient.setProfile(packageName, profile.copy(custom = true)) }
            busy = false
            refresh()
        }
    }

    private fun editId(title: Int, message: Int, get: (RootProfile) -> Int, @OutlivesPage put: (RootProfile, Int) -> RootProfile) = ask { root ->
        val initial = get(root).toString()
        showDialog("root-id") { d ->
            InputDialog.create(
                d.context, title,
                message = getString(message),
                initial = initial,
                numeric = true,
                validate = { text ->
                    val n = text.toLongOrNull()
                    if (n == null || n < 0 || n > MAX_ID) getString(R.string.root_profile_id_invalid) else null
                },
            ) { text -> edit { put(it, text.toInt()) } }
        }
    }

    private fun editGroups() = ask { root ->
        val known = RootIds.GROUPS.map { it.first }
        val ids = (known + root.groups.filter { it !in known }).distinct()
        // The dialog's own state: a recreated window shows the owner's checks.
        val checked = BooleanArray(ids.size) { ids[it] in root.groups }
        showDialog("root-groups") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(R.string.root_profile_groups)
                .setMultiChoiceItems(ids.map { RootIds.groupLabel(it) }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val chosen = ids.filterIndexed { i, _ -> checked[i] }
                    if (chosen.size > MAX_GROUPS) {
                        toast(getString(R.string.root_profile_groups_too_many, MAX_GROUPS))
                    } else if (chosen.sorted() != root.groups.sorted()) {
                        edit { it.copy(groups = chosen) }
                    }
                }
                .setNeutralButton(R.string.root_profile_groups_other) { _, _ -> addGroupIds(root) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun addGroupIds(root: RootProfile) = showDialog("root-group-ids") { d ->
        InputDialog.create(
            d.context, R.string.root_profile_groups_other,
            message = getString(R.string.root_profile_groups_other_message),
            validate = { text ->
                val ids = parseIds(text)
                when {
                    text.isEmpty() -> ""
                    ids == null -> getString(R.string.root_profile_id_invalid)
                    (root.groups + ids).distinct().size > MAX_GROUPS -> getString(R.string.root_profile_groups_too_many, MAX_GROUPS)
                    else -> null
                }
            },
        ) { text ->
            val ids = parseIds(text) ?: return@create
            edit { it.copy(groups = (it.groups + ids).distinct()) }
        }
    }

    private fun parseIds(text: String): List<Int>? {
        val parts = text.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.map { p -> p.toLongOrNull()?.takeIf { it in 0..MAX_ID }?.toInt() ?: return null }
    }

    private fun editCapabilities() = ask { root ->
        val names = RootIds.CAPABILITIES
        // The dialog's own state: a recreated window shows the owner's checks.
        val checked = BooleanArray(names.size) { root.capabilities and (1L shl it) != 0L }
        showDialog("root-capabilities") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(R.string.root_profile_capabilities)
                .setMultiChoiceItems(names.map { "CAP_$it" }.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    var mask = 0L
                    checked.forEachIndexed { i, on -> if (on) mask = mask or (1L shl i) }
                    if (mask != root.capabilities) edit { it.copy(capabilities = mask) }
                }
                .setNeutralButton(R.string.root_profile_capabilities_all) { _, _ ->
                    val all = (1L shl names.size) - 1
                    if (all != root.capabilities) edit { it.copy(capabilities = all) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun editDomain() = ask { root ->
        showDialog("root-domain") { d ->
            val presets = RootIds.DOMAINS
            val labels = presets.map { (domain, desc) -> getString(R.string.root_profile_domain_choice, domain, getString(desc)) } +
                getString(R.string.root_profile_domain_other)
            AlertDialog.Builder(d.context)
                .setTitle(R.string.root_profile_domain)
                .setSingleChoiceItems(labels.toTypedArray(), presets.indexOfFirst { it.first == root.selinuxDomain }) { dialog, which ->
                    dialog.dismiss()
                    if (which < presets.size) {
                        val domain = presets[which].first
                        if (domain != root.selinuxDomain) edit { it.copy(selinuxDomain = domain) }
                    } else {
                        editOtherDomain(root)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun editOtherDomain(root: RootProfile) = showDialog("root-domain-other") { d ->
        InputDialog.create(
            d.context, R.string.root_profile_domain,
            message = getString(R.string.root_profile_domain_message),
            initial = root.selinuxDomain,
            validate = { text ->
                when {
                    text.isEmpty() -> ""
                    !DOMAIN.matches(text) || text.length > 63 -> getString(R.string.root_profile_domain_invalid)
                    else -> null
                }
            },
        ) { text -> if (text != root.selinuxDomain) edit { it.copy(selinuxDomain = text) } }
    }

    private fun confirmReset() = confirm(
        "root-reset", R.string.root_profile_reset_title, { getString(R.string.root_profile_reset_message, label) },
        R.string.root_profile_reset_confirm,
    ) { reset() }

    private fun reset() {
        if (busy) return
        // KernelSU's default profile is full root (user and group 0, every
        // capability, its own unconfined domain), so restoring it over a
        // narrower profile widens what su sessions may do.
        if (state?.root != null) {
            RootAuth.authenticate(
                this, getString(R.string.root_auth_profile_title),
                getString(R.string.root_auth_profile_subtitle, label),
            ) { sendReset() }
            return
        }
        sendReset()
    }

    private fun sendReset() {
        if (busy) return
        busy = true
        render(state)
        launchUi {
            act(R.string.error_root_profile) { RootClient.resetProfile(packageName) }
            busy = false
            refresh()
        }
    }

    private fun setNamespace(ns: Int) {
        val root = state?.root ?: return
        if (busy || root.namespace == ns) return
        // The global namespace lets su sessions see and change every mount of
        // the system: a widening change. Narrower ones ask nothing.
        if (ns == RootAuth.NS_GLOBAL) {
            RootAuth.authenticate(
                this, getString(R.string.root_auth_profile_title),
                getString(R.string.root_auth_profile_subtitle, label),
                onCancelled = { render(state) },
            ) { sendNamespace(ns) }
            return
        }
        sendNamespace(ns)
    }

    private fun sendNamespace(ns: Int) {
        val root = state?.root ?: return
        if (busy) return
        busy = true
        render(state)
        launchUi {
            act(R.string.error_root_profile) { RootClient.setProfile(packageName, root.copy(namespace = ns, custom = true)) }
            busy = false
            refresh()
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(requireContext(), text, android.widget.Toast.LENGTH_LONG).show()
    }

    companion object {
        const val ARG_PACKAGE = "package"
        const val ARG_LABEL = "label"
        private const val STATE_WARNED = "warned"
        private const val NS_INHERITED = IRoot.NAMESPACE_INHERITED
        private const val NS_GLOBAL = IRoot.NAMESPACE_GLOBAL
        private const val NS_INDIVIDUAL = IRoot.NAMESPACE_INDIVIDUAL
        private const val MAX_ID = 2147483646L
        private const val MAX_GROUPS = 32
        private const val MAX_SHARED_NAMES = 5
        // The KernelSU manager's rule: user:role:type[:level].
        private val DOMAIN = Regex("^[a-z_]+:[a-z0-9_]+:[a-z0-9_]+(:[a-z0-9_]+)?$")

        fun open(context: Context, packageName: String, label: String) {
            Pages.open(context, AppRootFragment::class.java, R.string.root_title, title = label, arguments = args(packageName, label))
        }

        fun args(packageName: String, label: String?): Bundle = Bundle().apply {
            putString(ARG_PACKAGE, packageName)
            putString(ARG_LABEL, label)
        }

        private fun info(context: Context, packageName: String): ApplicationInfo? = try {
            context.packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        fun appLabel(context: Context, packageName: String): String? =
            info(context, packageName)?.loadLabel(context.packageManager)?.toString()

        private fun appIcon(context: Context, packageName: String) =
            info(context, packageName)?.loadIcon(context.packageManager)
    }
}
