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
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.ZeroStatePreference
import kotlinx.coroutines.Job
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR

/**
 * The members of one proxy group: choose one (groups that allow it), test
 * all or one node, open a nested group. The page title is fixed ("Proxy
 * group") and the group's name heads the list, as on every proxy page.
 */
class GroupFragment : BasePreferenceFragment() {
    private lateinit var groupName: String
    private lateinit var headerPref: Preference
    private lateinit var testPref: Preference
    private lateinit var autoPref: Preference
    private lateinit var nodesCategory: PreferenceCategory
    private lateinit var nestedCategory: PreferenceCategory
    private lateinit var emptyPref: ZeroStatePreference
    private val nodePrefs = HashMap<String, NodePreference>()

    private var snapshot: ProxySnapshot? = null
    /** Delays measured on this page; a delay the page did not measure shows as not tested. */
    private val testedDelays = HashMap<String, Int>()
    private val testingNodes = HashSet<String>()
    private var testedAt = 0L
    private var testing = false
    private var selecting = false
    private var redirected = false
    private var loadJob: Job? = null

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        groupName = requireArguments().getString(ARG_GROUP) ?: ""
        val header = screen.add(PreferenceCategory(prefContext)) { key = "header" }
        headerPref = header.add(Preference(prefContext)) {
            key = "name"
            title = groupName
            setSummary(PagesR.string.loading)
            setIcon(R.drawable.ic_groups)
            isSelectable = false
            isCopyingEnabled = true
        }
        testPref = header.add(Preference(prefContext)) {
            key = "test"
            setTitle(R.string.px_group_test)
            setSummary(R.string.px_group_test_summary)
            setIcon(R.drawable.ic_timer)
            setOnPreferenceClickListener {
                testAll()
                true
            }
        }
        autoPref = header.add(Preference(prefContext)) {
            key = "auto"
            setTitle(R.string.px_group_unfix)
            setIcon(R.drawable.ic_restore)
            isVisible = false
            setOnPreferenceClickListener {
                unfix()
                true
            }
        }
        nodesCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "nodes"
            setTitle(R.string.px_group_members)
            isOrderingAsAdded = false
        }
        // Groups among the members open from rows of their own: a member row has one
        // affordance besides its radio (a node's delay test), never "open" as well.
        nestedCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "nested"
            setTitle(R.string.px_group_nested)
            isOrderingAsAdded = false
            isVisible = false
        }
        emptyPref = screen.add(ZeroStatePreference(prefContext)) {
            key = "empty"
            setIcon(R.drawable.ic_groups)
            setTitle(R.string.px_group_empty)
            isVisible = false
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = launchUi {
            snapshot = attempt("proxies", R.string.error_load_groups) { Mihomo.proxies() } ?: return@launchUi
            render()
        }
    }

    private fun render() {
        val context = context ?: return
        val snapshot = snapshot ?: return
        val group = snapshot.items[groupName]
        if (group == null) {
            headerPref.setSummary(R.string.px_group_missing)
            testPref.isEnabled = false
            autoPref.isVisible = false
            nodesCategory.isVisible = false
            emptyPref.setTitle(R.string.px_group_missing)
            emptyPref.isVisible = true
            return
        }
        if (group.isAdaptive && !redirected) {
            // An adaptive group has a page of its own.
            redirected = true
            AdaptiveGroupFragment.open(context, groupName)
            requireActivity().finish()
            return
        }
        headerPref.summary = ProxyFormat.join(
            context,
            listOf(
                proxyTypeLabel(context, group.type),
                group.now?.takeIf { it.isNotEmpty() }?.let { getString(R.string.px_group_now, it) },
            ),
        )
        testPref.isEnabled = !testing && group.members.isNotEmpty()
        testPref.summary = when {
            testing -> getString(R.string.px_group_testing)
            testedAt > 0 -> getString(R.string.px_group_tested_at, Format.time(context, testedAt))
            else -> getString(R.string.px_group_test_summary)
        }
        autoPref.isVisible = !group.fixed.isNullOrEmpty()
        autoPref.summary = group.fixed?.let { getString(R.string.px_group_unfix_summary, it) }

        val keep = group.members.toHashSet()
        nodePrefs.keys.filter { it !in keep }.forEach { name ->
            nodePrefs.remove(name)?.let { nodesCategory.removePreference(it) }
        }
        group.members.forEachIndexed { index, member ->
            val item = snapshot.items[member]
            val nested = item != null && item.isGroup
            val pref = nodePrefs.getOrPut(member) { createNodePref(member) }
            pref.order = index
            pref.isChecked = member == group.now
            pref.isEnabled = !selecting
            val parts = ArrayList<CharSequence?>(3)
            if (item != null) parts.add(proxyTypeLabel(context, item.type))
            if (nested && !item?.now.isNullOrEmpty()) parts.add(getString(R.string.px_group_now, item?.now))
            if (!nested) {
                parts.add(
                    when {
                        member in testingNodes -> getString(R.string.px_group_testing)
                        member in testedDelays -> ProxyFormat.delay(context, testedDelays[member], tested = true)
                        // A delay the core measured itself (automatic groups) counts; its failures do not
                        // mean "timeout" before anyone tested here.
                        else -> ProxyFormat.delay(context, item?.delay, tested = false)
                    }
                )
            }
            pref.summary = ProxyFormat.join(context, parts)
            pref.widgetIcon = R.drawable.ic_timer
            pref.setExtraWidgetContentDescription(getString(R.string.px_group_test_one, member))
            pref.setExtraWidgetOnClickListener(if (nested) null else View.OnClickListener { testOne(member) })
        }
        nestedCategory.removeAll()
        val nestedMembers = group.members.filter { snapshot.items[it]?.isGroup == true }
        nestedMembers.forEachIndexed { index, member ->
            nestedCategory.add(Preference(prefContext)) {
                key = "open:$member"
                title = member
                summary = snapshot.items[member]?.now?.takeIf { it.isNotEmpty() }?.let { getString(R.string.px_group_now, it) }
                setIcon(R.drawable.ic_groups)
                order = index
                setOnPreferenceClickListener {
                    snapshot.items[member]?.let { open(context, it) } ?: open(context, member)
                    true
                }
            }
        }
        nestedCategory.isVisible = nestedMembers.isNotEmpty()
        nodesCategory.title = resources.getQuantityString(R.plurals.px_group_members_count, group.members.size, group.members.size)
        nodesCategory.isVisible = group.members.isNotEmpty()
        emptyPref.isVisible = group.members.isEmpty()
    }

    private fun createNodePref(member: String): NodePreference =
        nodesCategory.add(NodePreference(prefContext)) {
            key = "node:$member"
            title = member
            isPersistent = false
            setOnClickListener { select(member) }
        }

    private fun select(member: String) {
        val group = snapshot?.items?.get(groupName) ?: return
        if (!group.isSelectable) {
            Toast.makeText(requireContext(), R.string.px_group_not_selectable, Toast.LENGTH_SHORT).show()
            render()
            return
        }
        if (selecting || group.now == member) return
        selecting = true
        render()
        launchUi {
            act(R.string.error_select) { Mihomo.select(groupName, member) }
            selecting = false
            loadJob?.cancel()
            refresh()
        }
    }

    private fun unfix() {
        launchUi {
            act(R.string.error_unfix) { Mihomo.unfix(groupName) }
            loadJob?.cancel()
            refresh()
        }
    }

    private fun testAll() {
        if (testing) return
        testing = true
        render()
        launchUi {
            try {
                val testUrl = snapshot?.items?.get(groupName)?.testUrl
                val ok = act(R.string.error_delay) {
                    val delays = Mihomo.groupDelay(groupName, testUrl)
                    // Members missing from the answer failed the test.
                    snapshot?.items?.get(groupName)?.members?.forEach { testedDelays[it] = delays[it] ?: 0 }
                }
                if (ok) testedAt = System.currentTimeMillis()
            } finally {
                testing = false
                ifPageAlive { render() }
            }
            // Automatic groups may switch nodes after a test.
            loadJob?.cancel()
            refresh()
        }
    }

    private fun testOne(member: String) {
        if (!testingNodes.add(member)) return
        render()
        launchUi {
            try {
                val testUrl = snapshot?.items?.get(groupName)?.testUrl
                act(R.string.error_delay) { testedDelays[member] = Mihomo.proxyDelay(member, testUrl) }
            } finally {
                testingNodes.remove(member)
                ifPageAlive { render() }
            }
        }
    }

    companion object {
        private const val ARG_GROUP = "group"

        /** The page of [group]: an adaptive group has its own. */
        fun open(context: Context, group: ProxyItem) {
            if (group.isAdaptive) AdaptiveGroupFragment.open(context, group.name) else open(context, group.name)
        }

        fun open(context: Context, group: String) {
            Pages.open(
                context,
                GroupFragment::class.java,
                R.string.px_group_page_title,
                arguments = Bundle().apply { putString(ARG_GROUP, group) },
            )
        }
    }
}
