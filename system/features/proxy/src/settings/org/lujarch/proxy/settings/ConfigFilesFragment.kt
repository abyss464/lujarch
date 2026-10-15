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
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.TopIntroPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.R as PagesR
import org.lujarch.settings.pages.showDialog

/**
 * Every file the proxy is built from, each replaceable from a document on
 * this phone: the manager settings, the routing rules, the subscriptions,
 * the rule-set files; all of them as one bundle (import and export, the
 * subscriptions and node lists only when the owner confirms); the backups
 * that each import and restore leaves, with a one-tap restore of the
 * previous files; and the geodata. The manager validates an import before
 * it installs it, and the configuration is applied afterwards.
 */
class ConfigFilesFragment : BasePreferenceFragment() {
    private lateinit var taskPref: Preference
    private lateinit var configPref: Preference
    private lateinit var routingPref: Preference
    private lateinit var providersPref: Preference
    private lateinit var nodesPref: Preference
    private lateinit var locationsPref: Preference
    private lateinit var rulesetsCategory: PreferenceCategory
    private lateinit var backupsCategory: PreferenceCategory
    private lateinit var geoCategory: PreferenceCategory
    private lateinit var livePref: Preference

    private var files: FilesInfo? = null
    private var backups: List<BackupInfo> = emptyList()
    private var loadJob: Job? = null

    /** What the next picked document is imported as. */
    private data class PendingImport(val kind: ProxyManager.ImportKind, val whole: Boolean, val name: String?)

    private var pendingImport: PendingImport? = null
    private var pendingExportSecrets = false

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val pending = pendingImport
        pendingImport = null
        if (uri != null && pending != null) launchUi { imported(uri, pending) }
    }

    private val createDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) launchUi { export(uri, pendingExportSecrets) }
    }

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        pendingImport = savedInstanceState?.getString(STATE_KIND)?.let {
            PendingImport(ProxyManager.ImportKind.valueOf(it), savedInstanceState.getBoolean(STATE_WHOLE), savedInstanceState.getString(STATE_NAME))
        }
        pendingExportSecrets = savedInstanceState?.getBoolean(STATE_SECRETS) ?: false
        screen.add(TopIntroPreference(prefContext)) {
            key = "intro"
            setTitle(R.string.px_files_intro)
        }
        val status = screen.add(PreferenceCategory(prefContext)) { key = "status" }
        taskPref = status.add(Preference(prefContext)) {
            key = "task"
            setIcon(R.drawable.ic_build)
            isVisible = false
            setOnPreferenceClickListener {
                TaskFragment.open(requireContext(), KEY)
                true
            }
        }

        val main = screen.add(PreferenceCategory(prefContext)) {
            key = "files"
            setTitle(R.string.px_files_category)
        }
        configPref = main.add(fileRow("config", R.string.px_file_config) { askConfig() })
        routingPref = main.add(fileRow("routing", R.string.px_file_routing) { askRouting() })
        providersPref = main.add(fileRow("providers", R.string.px_file_providers) { askProviders() })
        nodesPref = main.add(fileRow("nodes", R.string.px_file_nodes) { SubscriptionsFragment.open(requireContext()) })
        locationsPref = main.add(fileRow("locations", R.string.px_file_cache) { askLocations() })
        rulesetsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "rulesets"
            setTitle(R.string.px_files_rulesets)
        }

        val all = screen.add(PreferenceCategory(prefContext)) {
            key = "all"
            setTitle(R.string.px_files_all_category)
        }
        all.add(Preference(prefContext)) {
            key = "import_all"
            setTitle(R.string.px_files_import_all)
            setSummary(R.string.px_files_import_all_summary)
            setIcon(R.drawable.ic_px_import)
            setOnPreferenceClickListener {
                pick(PendingImport(ProxyManager.ImportKind.BUNDLE, false, null))
                true
            }
        }
        all.add(Preference(prefContext)) {
            key = "export_all"
            setTitle(R.string.px_files_export_all)
            setSummary(R.string.px_files_export_all_summary)
            setIcon(R.drawable.ic_px_export)
            setOnPreferenceClickListener {
                askExport()
                true
            }
        }

        backupsCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "backups"
            setTitle(R.string.px_files_backups)
        }
        geoCategory = screen.add(PreferenceCategory(prefContext)) {
            key = "geo"
            setTitle(R.string.px_files_geo)
        }
        val generated = screen.add(PreferenceCategory(prefContext)) {
            key = "generated"
            setTitle(R.string.px_files_generated)
        }
        livePref = generated.add(Preference(prefContext)) {
            key = "live"
            setTitle(R.string.px_status_live)
            setSummary(PagesR.string.loading)
            isSelectable = false
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingImport?.let {
            outState.putString(STATE_KIND, it.kind.name)
            outState.putBoolean(STATE_WHOLE, it.whole)
            outState.putString(STATE_NAME, it.name)
        }
        outState.putBoolean(STATE_SECRETS, pendingExportSecrets)
    }

    private fun fileRow(key: String, title: Int, onClick: () -> Unit) = Preference(prefContext).apply {
        this.key = key
        setTitle(title)
        setSummary(PagesR.string.loading)
        setOnPreferenceClickListener {
            onClick()
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var wasRunning = false
                ProxyTasks.tasks.collect {
                    showTask()
                    val running = ProxyTasks.running() != null
                    if (wasRunning && !running) refresh()
                    wasRunning = running
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        if (loadJob?.isActive == true) return
        loadJob = launchUi {
            files = attempt("files", R.string.px_error_load_files) { ProxyManager.files() } ?: return@launchUi
            backups = attempt("backups", R.string.px_error_load_backups) { ProxyManager.backups() }.orEmpty()
            render()
        }
    }

    // ---- rendering ----

    private fun describe(context: Context, f: FileInfo): String = if (!f.present) {
        getString(R.string.px_files_missing)
    } else {
        ProxyFormat.join(
            context,
            listOf(
                ProxyFormat.size(context, f.size ?: 0),
                f.modifiedAt?.let { getString(R.string.px_files_changed, ProxyFormat.dateTime(context, it)) },
            ),
        )
    }

    private fun render() {
        val context = context ?: return
        val f = files ?: return
        configPref.summary = describe(context, f.config)
        routingPref.summary = describe(context, f.routing)
        providersPref.summary = describe(context, f.providers)
        nodesPref.summary = ProxyFormat.join(
            context,
            listOf(
                resources.getQuantityString(R.plurals.px_file_node_lists, f.referenceCount, f.referenceCount),
                ProxyFormat.size(context, f.referenceSize),
                getString(R.string.px_files_nodes_hint),
            ),
        )
        locationsPref.summary = describe(context, f.cache)

        rulesetsCategory.removeAll()
        for (r in f.rulesets) {
            rulesetsCategory.add(Preference(prefContext)) {
                key = "ruleset:" + r.name
                title = r.name
                summary = describe(context, r)
                setOnPreferenceClickListener {
                    askRulesetAction(r.name)
                    true
                }
            }
        }
        rulesetsCategory.add(Preference(prefContext)) {
            key = "ruleset_update"
            setTitle(R.string.px_files_rulesets_update)
            setSummary(R.string.px_files_rulesets_update_summary)
            setIcon(R.drawable.ic_refresh)
            setOnPreferenceClickListener {
                updateRuleLists()
                true
            }
        }
        rulesetsCategory.add(Preference(prefContext)) {
            key = "ruleset_add"
            setTitle(R.string.px_files_ruleset_add)
            setSummary(R.string.px_files_ruleset_add_summary)
            setIcon(R.drawable.ic_px_add)
            setOnPreferenceClickListener {
                pick(PendingImport(ProxyManager.ImportKind.RULESET, false, null))
                true
            }
        }

        backupsCategory.removeAll()
        if (backups.isEmpty()) {
            backupsCategory.add(Preference(prefContext)) {
                key = "no_backups"
                setTitle(R.string.px_files_no_backups)
                setSummary(R.string.px_files_no_backups_summary)
                isSelectable = false
            }
        } else {
            val latest = backups.first()
            backupsCategory.add(Preference(prefContext)) {
                key = "undo"
                setTitle(R.string.px_files_undo)
                summary = getString(
                    R.string.px_files_undo_summary,
                    ProxyFormat.backupReason(context, latest.reason),
                    ProxyFormat.dateTime(context, latest.createdAt),
                )
                setIcon(R.drawable.ic_restore)
                setOnPreferenceClickListener {
                    askRestore(latest)
                    true
                }
            }
            for (b in backups.drop(1)) {
                backupsCategory.add(Preference(prefContext)) {
                    key = "backup:" + b.id
                    title = ProxyFormat.backupReason(context, b.reason)
                    summary = ProxyFormat.join(
                        context,
                        listOf(ProxyFormat.dateTime(context, b.createdAt), ProxyFormat.filesLabel(context, b.files)),
                    )
                    setOnPreferenceClickListener {
                        askRestore(b)
                        true
                    }
                }
            }
        }

        geoCategory.removeAll()
        // One row per database the core reads, present or not; each can be replaced from a file.
        for (kind in ProxyCommands.Geo.entries) {
            val file = f.geodata.firstOrNull { it.kind == kind.arg }
            geoCategory.add(Preference(prefContext)) {
                key = "geo:" + kind.arg
                title = getString(geoTitle(kind))
                summary = file?.let { ProxyFormat.join(context, listOf(it.name, describe(context, it))) }
                    ?: getString(R.string.px_files_geo_missing)
                setOnPreferenceClickListener {
                    askGeodata(kind, file)
                    true
                }
            }
        }
        for (g in f.geodata.filter { it.kind == null }) {
            geoCategory.add(Preference(prefContext)) {
                key = "geo:" + g.name
                title = g.name
                summary = describe(context, g)
                isSelectable = false
            }
        }
        geoCategory.add(Preference(prefContext)) {
            key = "geo_settings"
            setTitle(R.string.px_files_geo_settings)
            setSummary(R.string.px_files_geo_settings_summary)
            setIcon(R.drawable.ic_tune)
            setOnPreferenceClickListener {
                Pages.open(requireContext(), GeodataFragment::class.java, R.string.px_core_geo_category)
                true
            }
        }
        geoCategory.add(Preference(prefContext)) {
            key = "geo_update"
            setTitle(R.string.px_files_geo_update)
            setSummary(R.string.px_files_geo_update_summary)
            setIcon(R.drawable.ic_refresh)
            setOnPreferenceClickListener {
                updateGeo()
                true
            }
        }
        livePref.summary = when {
            !f.live.present -> getString(R.string.px_status_none)
            f.lastGoodSameAsLive == true -> getString(R.string.px_files_live_good, ProxyFormat.dateTime(context, f.live.modifiedAt ?: 0))
            else -> getString(R.string.px_files_live, ProxyFormat.dateTime(context, f.live.modifiedAt ?: 0))
        }
    }

    private fun showTask() {
        val context = context ?: return
        val task = ProxyTasks[KEY]
        taskPref.isVisible = task != null
        if (task != null) {
            taskPref.title = task.title
            taskPref.summary = taskStatus(context, task)
        }
    }

    // ---- import ----

    private fun pick(pending: PendingImport) {
        if (ProxyTasks.running() != null) {
            Toast.makeText(requireContext(), R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
            return
        }
        pendingImport = pending
        openDocument.launch(arrayOf("*/*"))
    }

    private fun askConfig() = showDialog("px-files-config") { d ->
        val choices = arrayOf(getString(R.string.px_files_config_sections), getString(R.string.px_files_config_whole))
        AlertDialog.Builder(d.context)
            .setTitle(R.string.px_files_replace_config)
            .setItems(choices) { _, which -> pick(PendingImport(ProxyManager.ImportKind.CONFIG, which == 1, null)) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun askRouting() = showDialog("px-files-routing") { d ->
        val choices = arrayOf(getString(R.string.px_files_routing_keep_apps), getString(R.string.px_files_routing_whole))
        AlertDialog.Builder(d.context)
            .setTitle(R.string.px_files_replace_routing)
            .setItems(choices) { _, which -> pick(PendingImport(ProxyManager.ImportKind.ROUTING, which == 1, null)) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun askProviders() = confirm(
        "px-files-providers", R.string.px_files_replace_providers, { getString(R.string.px_files_replace_providers_message) },
        R.string.px_action_choose_file,
    ) {
        pick(PendingImport(ProxyManager.ImportKind.PROVIDERS, false, null))
    }

    private fun askRuleset(name: String) = confirm(
        "px-files-ruleset", R.string.px_files_replace_ruleset, { getString(R.string.px_files_replace_ruleset_message, name) },
        R.string.px_action_choose_file,
    ) {
        pick(PendingImport(ProxyManager.ImportKind.RULESET, false, name))
    }

    private suspend fun imported(uri: Uri, pending: PendingImport) {
        val context = context ?: return
        val geodata = pending.kind == ProxyManager.ImportKind.GEODATA
        val bytes = readDocument(context, uri, if (geodata) MAX_GEODATA_BYTES else MAX_IMPORT_BYTES) ?: return // lifecycle: data, read with the page's context
        if (geodata) {
            confirmGeodata(pending, bytes, displayName(context, uri))
            return
        }
        var name = pending.name
        if (pending.kind == ProxyManager.ImportKind.RULESET && name == null) {
            name = displayName(context, uri)?.takeIf { isRulesetName(it) }
            if (name == null) {
                Toast.makeText(context, R.string.px_files_ruleset_name_invalid, Toast.LENGTH_LONG).show()
                return
            }
            if (files?.rulesets?.any { it.name == name } == true) {
                val existing = name
                confirm(
                    "px-files-ruleset-replace", R.string.px_files_replace_ruleset,
                    { getString(R.string.px_files_replace_ruleset_message, existing) }, R.string.px_action_replace,
                ) {
                    startImport(pending.copy(name = existing), bytes)
                }
                return
            }
        }
        if (pending.kind == ProxyManager.ImportKind.BUNDLE) {
            val facts = bundleFacts(bytes) ?: run { // lifecycle: data (the toast is for a failure)
                Toast.makeText(context, R.string.px_files_not_bundle, Toast.LENGTH_LONG).show()
                return
            }
            confirm("px-files-bundle", R.string.px_files_import_all, { bundleSummary(requireContext(), facts) }, R.string.px_action_import) {
                startImport(pending, bytes)
            }
            return
        }
        startImport(pending.copy(name = name), bytes)
    }

    private fun startImport(pending: PendingImport, bytes: ByteArray) {
        val context = requireContext()
        val title = when (pending.kind) {
            ProxyManager.ImportKind.CONFIG -> getString(R.string.px_files_importing_config)
            ProxyManager.ImportKind.ROUTING -> getString(R.string.px_files_importing_routing)
            ProxyManager.ImportKind.PROVIDERS -> getString(R.string.px_files_importing_providers)
            ProxyManager.ImportKind.BUNDLE -> getString(R.string.px_files_importing_all)
            ProxyManager.ImportKind.RULESET -> getString(R.string.px_files_importing_ruleset, pending.name)
            ProxyManager.ImportKind.CACHE -> getString(R.string.px_files_importing_cache)
            ProxyManager.ImportKind.GEODATA -> getString(R.string.px_files_importing_geodata)
        }
        val started = ProxyTasks.start(context, KEY, title) {
            step(this.context.getString(R.string.px_step_checking_file))
            val (report, output) = ProxyManager.import(pending.kind, bytes, pending.whole, pending.name)
            output(output)
            data(report)
            applyConfig()
            data(report)
            summary(this.context.getString(R.string.px_files_imported))
        }
        if (!started) {
            Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
            return
        }
        TaskFragment.open(context, KEY)
    }

    // ---- geodata, node locations, rule lists ----

    private fun geoTitle(kind: ProxyCommands.Geo): Int = when (kind) {
        ProxyCommands.Geo.MMDB -> R.string.px_geo_url_mmdb
        ProxyCommands.Geo.GEOIP -> R.string.px_geo_url_geoip
        ProxyCommands.Geo.GEOSITE -> R.string.px_geo_url_geosite
        ProxyCommands.Geo.ASN -> R.string.px_geo_url_asn
    }

    private fun askGeodata(kind: ProxyCommands.Geo, file: FileInfo?) {
        val add = file == null
        confirm(
            "px-files-geodata", geoTitle(kind),
            { getString(if (add) R.string.px_files_geo_add_message else R.string.px_files_geo_replace_message) },
            R.string.px_action_choose_file,
        ) { pick(PendingImport(ProxyManager.ImportKind.GEODATA, false, kind.arg)) }
    }

    /** The picked database: replace it, and restart the proxy now or at its next start. */
    private fun confirmGeodata(pending: PendingImport, bytes: ByteArray, fileName: String?) {
        val kind = ProxyCommands.Geo.entries.firstOrNull { it.arg == pending.name } ?: return
        showDialog("px-files-geodata-confirm") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(geoTitle(kind))
                .setMessage(getString(R.string.px_files_geo_confirm, fileName ?: "", ProxyFormat.size(d.context, bytes.size.toLong())))
                .setPositiveButton(R.string.px_files_geo_replace_restart) { _, _ -> startGeodata(kind, bytes, restart = true) }
                .setNeutralButton(R.string.px_files_geo_replace_only) { _, _ -> startGeodata(kind, bytes, restart = false) }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun startGeodata(kind: ProxyCommands.Geo, bytes: ByteArray, restart: Boolean) {
        val context = requireContext()
        val started = ProxyTasks.start(context, KEY, getString(R.string.px_files_importing_geodata)) {
            step(this.context.getString(R.string.px_step_checking_file))
            val (report, output) = ProxyCommands.importGeodata(kind, bytes)
            output(output)
            data(report)
            // The core reads geodata when it starts.
            if (restart && ProxyClient.info().mihomoEnabled) {
                step(this.context.getString(R.string.px_step_restarting))
                ProxyManager.coreRestart()
                summary(this.context.getString(R.string.px_files_geo_replaced_restarted))
            } else {
                summary(this.context.getString(R.string.px_files_geo_replaced))
            }
        }
        if (started) TaskFragment.open(context, KEY) else Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
    }

    private fun askLocations() = showDialog("px-files-locations") { d ->
        val items = arrayOf(getString(R.string.px_files_locations_open), getString(R.string.px_files_locations_replace))
        AlertDialog.Builder(d.context)
            .setTitle(R.string.px_file_cache)
            .setItems(items) { _, which ->
                if (which == 0) {
                    NodeLocationsFragment.open(requireContext(), null)
                } else {
                    confirm(
                        "px-files-locations-replace", R.string.px_files_locations_replace,
                        { getString(R.string.px_files_locations_replace_message) }, R.string.px_action_choose_file,
                    ) {
                        pick(PendingImport(ProxyManager.ImportKind.CACHE, false, null))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun askRulesetAction(name: String) = showDialog("px-files-ruleset-action") { d ->
        val items = arrayOf(getString(R.string.px_files_replace_ruleset), getString(R.string.px_files_ruleset_remove))
        AlertDialog.Builder(d.context)
            .setTitle(name)
            .setItems(items) { _, which ->
                if (which == 0) askRuleset(name) else askRemoveRuleset(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun askRemoveRuleset(name: String) = confirm(
        "px-files-ruleset-remove", R.string.px_files_ruleset_remove, { getString(R.string.px_files_ruleset_remove_confirm, name) },
        R.string.px_action_remove,
    ) {
        val context = requireContext()
        val started = ProxyTasks.start(context, KEY, getString(R.string.px_files_removing_ruleset, name)) {
            step(this.context.getString(R.string.px_step_checking_file))
            val (report, output) = ProxyCommands.removeRuleset(name)
            output(output)
            data(report)
            applyConfig()
            data(report)
            summary(this.context.getString(R.string.px_files_ruleset_removed, name))
        }
        if (started) TaskFragment.open(context, KEY) else Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
    }

    /** Downloads every rule list that has a download address again (the running core does it). */
    private fun updateRuleLists() {
        val context = requireContext()
        launchUi {
            val up = orNull("core state") { ProxyClient.info().controllerUp } ?: false
            if (!up) {
                Toast.makeText(context, R.string.px_needs_running, Toast.LENGTH_LONG).show()
                return@launchUi
            }
            confirm(
                "px-files-rulesets-update", R.string.px_files_rulesets_update, { getString(R.string.px_files_rulesets_update_confirm) },
                R.string.px_action_update,
            ) {
                val context = requireContext()
                val started = ProxyTasks.start(context, KEY, getString(R.string.px_files_rulesets_update)) {
                    step(this.context.getString(R.string.px_step_downloading))
                    val remote = Mihomo.ruleProviders().filter { it.remote }
                    var failed = 0
                    for (p in remote) {
                        try {
                            Mihomo.updateRuleProvider(p.name)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed++
                            output("${p.name}: ${e.message}")
                        }
                    }
                    val res = this.context.resources
                    summary(
                        when {
                            remote.isEmpty() -> this.context.getString(R.string.px_files_rulesets_none_remote)
                            failed == 0 -> res.getQuantityString(R.plurals.px_files_rulesets_updated, remote.size, remote.size)
                            else -> this.context.getString(R.string.px_files_rulesets_partly, remote.size - failed, remote.size)
                        },
                    )
                }
                if (!started) Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** What a bundle holds, or null when the bytes are not a bundle. */
    private class BundleFacts(val names: List<String>, val created: Long, val secrets: Boolean)

    private suspend fun bundleFacts(bytes: ByteArray): BundleFacts? = withContext(Dispatchers.Default) {
        try {
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            if (o.optString("format") != "lujarch-proxy") return@withContext null
            val names = o.optJSONObject("files")?.keys()?.asSequence()?.toList().orEmpty()
            BundleFacts(names, o.optLong("created") * 1000, o.optBoolean("secrets"))
        } catch (e: JSONException) {
            null
        }
    }

    /** "12 files, subscriptions and node lists included, made on ...". */
    private fun bundleSummary(context: Context, facts: BundleFacts): String = context.getString(
        if (facts.secrets) R.string.px_files_bundle_with_secrets else R.string.px_files_bundle_without_secrets,
        ProxyFormat.filesLabel(context, facts.names),
        ProxyFormat.dateTime(context, facts.created),
    )

    private fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: RuntimeException) {
        null
    }

    private fun isRulesetName(name: String): Boolean =
        isProviderName(name) && (name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".txt"))

    // ---- restore ----

    private fun askRestore(b: BackupInfo) {
        confirm(
            "px-files-restore", R.string.px_files_restore,
            {
                getString(
                    R.string.px_files_restore_message,
                    ProxyFormat.filesLabel(requireContext(), b.files),
                    ProxyFormat.dateTime(requireContext(), b.createdAt),
                )
            },
            R.string.px_action_restore,
        ) {
            val context = requireContext()
            val started = ProxyTasks.start(context, KEY, getString(R.string.px_files_restoring)) {
                step(this.context.getString(R.string.px_step_checking_file))
                val (report, output) = ProxyManager.restore(b.id)
                output(output)
                data(report)
                applyConfig()
                data(report)
                summary(this.context.getString(R.string.px_files_restored))
            }
            if (started) TaskFragment.open(context, KEY) else Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
        }
    }

    // ---- export ----

    private fun askExport() {
        // The choice is the dialog's own state: a recreated window shows it.
        val secrets = booleanArrayOf(false)
        showDialog("px-files-export") { d ->
            AlertDialog.Builder(d.context)
                .setTitle(R.string.px_files_export_all)
                .setMultiChoiceItems(arrayOf(getString(R.string.px_files_export_secrets)), secrets) { _, _, checked -> secrets[0] = checked }
                .setPositiveButton(R.string.px_action_next) { _, _ ->
                    if (secrets[0]) warnExportSecrets() else chooseTarget(false)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    /** The file then gives anyone who reads it the subscriptions and nodes. */
    private fun warnExportSecrets() = showDialog("px-files-export-secrets") { d ->
        AlertDialog.Builder(d.context)
            .setTitle(R.string.px_files_export_secrets_title)
            .setMessage(R.string.px_files_export_secrets_warning)
            .setPositiveButton(R.string.px_action_export_secrets) { _, _ -> chooseTarget(true) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun chooseTarget(secrets: Boolean) {
        pendingExportSecrets = secrets
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
        createDocument.launch(if (secrets) "lujarch-proxy-$stamp-full.json" else "lujarch-proxy-$stamp.json")
    }

    private suspend fun export(uri: Uri, secrets: Boolean) {
        val context = context?.applicationContext ?: return
        Toast.makeText(context, R.string.px_files_exporting, Toast.LENGTH_SHORT).show()
        act(R.string.px_error_export) {
            val bytes = ProxyManager.export(secrets)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                    ?: throw java.io.IOException("cannot open the chosen file")
            }
            Toast.makeText(context, R.string.px_files_exported, Toast.LENGTH_LONG).show()
        }
    }

    // ---- geodata ----

    private fun updateGeo() {
        val context = requireContext()
        launchUi {
            val up = orNull("core state") { ProxyClient.info().controllerUp } ?: false
            if (!up) {
                Toast.makeText(context, R.string.px_needs_running, Toast.LENGTH_LONG).show()
                return@launchUi
            }
            confirm(
                "px-files-geo-update", R.string.px_files_geo_update, { getString(R.string.px_files_geo_update_confirm) },
                R.string.px_action_update,
            ) {
                val context = requireContext()
                val started = ProxyTasks.start(context, KEY, getString(R.string.px_files_geo_update)) {
                    step(this.context.getString(R.string.px_step_downloading))
                    Mihomo.updateGeoData()
                    summary(this.context.getString(R.string.px_files_geo_updated))
                }
                if (!started) Toast.makeText(context, R.string.px_error_busy_task, Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        const val KEY = "files"
        private const val STATE_KIND = "pending_kind"
        private const val STATE_WHOLE = "pending_whole"
        private const val STATE_NAME = "pending_name"
        private const val STATE_SECRETS = "export_secrets"
    }
}
