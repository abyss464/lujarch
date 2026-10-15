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
import android.os.Bundle
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR

/**
 * An adaptive proxy group: the member it uses on the network the phone
 * is on and why, every member's success rate, time to the first byte and
 * expected time there (in the order a connection tries them), what happened on
 * each network the group remembers, pinning a member and going back to the
 * automatic choice, measuring every member, and the decision parameters. The
 * statistics are read every few seconds while the page is shown.
 */
class AdaptiveGroupFragment : BasePreferenceFragment() {
    private lateinit var groupName: String
    private lateinit var headerPref: Preference
    private lateinit var choicePref: Preference
    private lateinit var autoPref: Preference
    private lateinit var testPref: Preference
    private lateinit var networksCategory: PreferenceCategory
    private lateinit var membersCategory: PreferenceCategory
    private lateinit var recordPref: Preference
    private lateinit var paramsPref: Preference

    private val memberPrefs = HashMap<String, NodePreference>()
    private val netPrefs = HashMap<String, SelectorWithWidgetPreference>()
    private var group: ProxyItem? = null
    /** The network whose statistics the page shows; null: the current one. */
    private var shown: String? = null
    private var testing = false
    private var testedAt = 0L
    private var busy = false
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        groupName = requireArguments().getString(ARG_GROUP) ?: ""
        shown = savedInstanceState?.getString(STATE_SHOWN)
        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        headerPref = header.add(Preference(prefContext)) {
            key = "name"
            title = groupName
            setSummary(PagesR.string.loading)
            setIcon(R.drawable.ic_groups)
            isSelectable = false
            isCopyingEnabled = true
        }
        choicePref = header.add(Preference(prefContext)) {
            key = "choice"
            setTitle(R.string.px_ad_choice)
            isSelectable = false
            // Aligned with the rows of its card, which have icons.
            isIconSpaceReserved = true
        }
        autoPref = header.add(Preference(prefContext)) {
            key = "auto"
            setTitle(R.string.px_group_unfix)
            setIcon(R.drawable.ic_restore)
            isVisible = false
            setOnPreferenceClickListener {
                unpin()
                true
            }
        }
        testPref = header.add(Preference(prefContext)) {
            key = "test"
            setTitle(R.string.px_ad_test)
            setSummary(R.string.px_ad_test_summary)
            setIcon(R.drawable.ic_timer)
            setOnPreferenceClickListener {
                testAll()
                true
            }
        }
        membersCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "members"
            setTitle(R.string.px_ad_members)
            isOrderingAsAdded = false
        }
        networksCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "networks"
            setTitle(R.string.px_ad_networks)
            isOrderingAsAdded = false
        }
        val more = screen.add(PreferenceCategory(prefContext)) { key = "more" }
        recordPref = more.add(Preference(prefContext)) {
            key = "record"
            setTitle(R.string.px_ad_record)
            isSelectable = false
            isIconSpaceReserved = true
        }
        paramsPref = more.add(Preference(prefContext)) {
            key = "params"
            setTitle(R.string.px_ad_params)
            setIcon(R.drawable.ic_tune)
            setOnPreferenceClickListener {
                AdaptiveParamsFragment.open(requireContext())
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_ad_footer)
            order = Int.MAX_VALUE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SHOWN, shown)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    attempt("group", R.string.error_load_groups) { Mihomo.proxy(groupName) }?.let {
                        group = it
                        render()
                    }
                    withTimeoutOrNull(POLL_MS) { wake.receive() }
                }
            }
        }
    }

    override fun refresh() {
        wake.trySend(Unit)
    }

    private fun render() {
        val context = context ?: return
        val g = group ?: return
        val info = g.adaptive
        if (info == null) {
            // Not an adaptive group (any more): the ordinary group page shows it.
            headerPref.setSummary(R.string.px_group_missing)
            return
        }
        val current = info.current
        val net = info.networks.find { it.key == shown } ?: current ?: info.networks.firstOrNull()
        headerPref.summary = ProxyFormat.join(
            context,
            listOf(proxyTypeLabel(context, g.type), current?.let { networkLabel(context, it) }),
        )
        val pinned = g.fixed?.takeIf { it.isNotEmpty() }
        choicePref.summary = when {
            pinned != null -> getString(R.string.px_ad_choice_pinned, pinned)
            current?.now != null -> ProxyFormat.join(context, listOf(current.now, reasonText(current.reason)))
            else -> reasonText(current?.reason ?: "")
        }
        autoPref.isVisible = pinned != null
        autoPref.summary = pinned?.let { getString(R.string.px_group_unfix_summary, it) }
        autoPref.isEnabled = !busy
        testPref.isEnabled = !testing && !busy
        testPref.summary = when {
            testing -> getString(R.string.px_group_testing)
            testedAt > 0 -> getString(R.string.px_group_tested_at, Format.time(context, testedAt))
            else -> getString(R.string.px_ad_test_summary)
        }

        // Members of the network shown, in the order a connection tries them there.
        membersCategory.title = if (net == null || net.current) {
            getString(R.string.px_ad_members)
        } else {
            getString(R.string.px_ad_members_on, networkLabel(context, net))
        }
        val byName = net?.members.orEmpty().associateBy { it.name }
        val tryOrder = net?.order?.takeIf { it.isNotEmpty() } ?: g.members
        val names = tryOrder + g.members.filter { it !in tryOrder }
        memberPrefs.keys.filter { it !in names }.forEach { name ->
            memberPrefs.remove(name)?.let { membersCategory.removePreference(it) }
        }
        names.forEachIndexed { index, name ->
            val pref = memberPrefs.getOrPut(name) {
                membersCategory.add(NodePreference(prefContext)) {
                    key = "member:$name"
                    title = name
                    isPersistent = false
                    setOnClickListener { askPin(name) }
                }
            }
            pref.order = index
            pref.isChecked = if (pinned != null) name == pinned else name == net?.now
            pref.isEnabled = !busy
            pref.summary = memberSummary(context, byName[name], name == pinned)
        }

        // The networks the group remembers: the page shows one of them at a time.
        val networks = info.networks.sortedWith(compareByDescending<AdaptiveNetwork> { it.current }.thenByDescending { it.lastUsed })
        netPrefs.keys.filter { k -> networks.none { it.key == k } }.forEach { k ->
            netPrefs.remove(k)?.let { networksCategory.removePreference(it) }
        }
        networks.forEachIndexed { index, n ->
            val pref = netPrefs.getOrPut(n.key) {
                networksCategory.add(SelectorWithWidgetPreference(prefContext)) {
                    key = "net:" + n.key
                    isPersistent = false
                    setOnClickListener {
                        shown = if (group?.adaptive?.network == n.key) null else n.key
                        render()
                    }
                }
            }
            pref.title = networkLabel(context, n)
            pref.order = index
            pref.isChecked = n.key == net?.key
            pref.summary = ProxyFormat.join(
                context,
                listOf(
                    if (n.current) getString(R.string.px_ad_network_current) else null,
                    n.now?.let { getString(R.string.px_ad_network_first, it) },
                    if (!n.current && n.lastUsed > 0) getString(R.string.px_ad_network_used, ProxyFormat.ago(requireContext(), n.lastUsed)) else null,
                ),
            )
        }
        networksCategory.isVisible = networks.size > 1

        recordPref.title = if (net == null || net.current) getString(R.string.px_ad_record) else getString(R.string.px_ad_record_on, networkLabel(context, net))
        recordPref.summary = net?.let { recordSummary(context, it) }
        val p = info.params
        paramsPref.summary = ProxyFormat.join(
            context,
            listOf(
                p["race-cost"]?.let { getString(R.string.px_ad_param_race_short, it) },
                p["hysteresis"]?.let { getString(R.string.px_ad_param_hysteresis_short, it) },
                p["slow-half-life"]?.let { getString(R.string.px_ad_param_memory_short, AdaptiveParamsFragment.duration(context, it)) },
            ),
        )
    }

    private fun memberSummary(context: Context, m: AdaptiveMember?, pinned: Boolean): String {
        val parts = ArrayList<CharSequence?>()
        if (pinned) parts += getString(R.string.px_ad_member_pinned)
        if (m?.lastResort == true) parts += getString(R.string.px_ad_member_last_resort)
        if (m == null || !m.known) {
            parts += getString(R.string.px_ad_member_unknown)
        } else {
            if (m.state == "failing") parts += getString(R.string.px_ad_member_failing)
            parts += getString(R.string.px_ad_member_success, (m.success * 100).roundToInt())
            parts += getString(R.string.px_ad_member_latency, m.latency)
            parts += getString(R.string.px_ad_member_cost, m.cost)
        }
        return ProxyFormat.join(context, parts)
    }

    private fun recordSummary(context: Context, n: AdaptiveNetwork): String {
        val res = context.resources
        fun count(plural: Int, value: Long): String = res.getQuantityString(plural, value.toInt(), value.toInt())
        return ProxyFormat.join(
            context,
            listOf(
                count(R.plurals.px_ad_connections, n.connections),
                count(R.plurals.px_ad_raced, n.raced),
                count(R.plurals.px_ad_not_first, n.notFirst),
                count(R.plurals.px_ad_failed, n.failed),
                count(R.plurals.px_ad_probes, n.probes),
            ),
        )
    }

    private fun reasonText(reason: String): String = when (reason) {
        "best" -> getString(R.string.px_ad_reason_best)
        "kept" -> getString(R.string.px_ad_reason_kept, (group?.adaptive?.params?.get("hysteresis") ?: 20L).toInt())
        "learning" -> getString(R.string.px_ad_reason_learning)
        "last-resort" -> getString(R.string.px_ad_reason_last_resort)
        "pinned" -> getString(R.string.px_ad_member_pinned)
        else -> reason
    }

    /** Pinning stops the automatic choice for every network: asks first. */
    private fun askPin(member: String) {
        val g = group ?: return
        if (busy || g.fixed == member) {
            render()
            return
        }
        confirm("px-adaptive-pin", R.string.px_ad_pin, { getString(R.string.px_ad_pin_message, member) }, R.string.px_ad_pin_action) {
            busy = true
            render()
            launchUi {
                act(R.string.error_select) { Mihomo.select(groupName, member) }
                busy = false
                refresh()
            }
        }
        // The radio follows the group, not the tap.
        render()
    }

    private fun unpin() {
        busy = true
        render()
        launchUi {
            act(R.string.error_unfix) { Mihomo.unfix(groupName) }
            busy = false
            refresh()
        }
    }

    /** Measures every member now (the group's own probes); the pin stays. */
    private fun testAll() {
        if (testing) return
        testing = true
        render()
        launchUi {
            try {
                if (act(R.string.error_delay) { Mihomo.groupDelay(groupName, group?.testUrl) }) {
                    testedAt = System.currentTimeMillis()
                }
            } finally {
                testing = false
                ifPageAlive { refresh() }
            }
        }
    }

    companion object {
        private const val ARG_GROUP = "group"
        private const val STATE_SHOWN = "shown"
        private const val POLL_MS = 5_000L

        fun open(context: Context, group: String) {
            Pages.open(
                context,
                AdaptiveGroupFragment::class.java,
                R.string.px_group_page_title,
                arguments = Bundle().apply { putString(ARG_GROUP, group) },
            )
        }

        /** "WLAN (192.168.110.0/24)", "Mobile data": a network the group keeps statistics for. */
        fun networkLabel(context: Context, n: AdaptiveNetwork): String {
            val base = when (n.networkClass) {
                "wifi" -> context.getString(R.string.px_ad_net_wifi)
                "cellular" -> context.getString(R.string.px_ad_net_cellular)
                "ethernet" -> context.getString(R.string.px_ad_net_ethernet)
                else -> return n.key
            }
            val detail = n.key.substringAfter(' ', "")
            return if (detail.isEmpty()) base else context.getString(R.string.px_ad_net_detail, base, detail)
        }
    }
}
