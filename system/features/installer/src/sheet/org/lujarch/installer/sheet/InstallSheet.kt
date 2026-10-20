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

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package org.lujarch.installer.sheet

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lujarch.installer.InstallFailures
import org.lujarch.installer.InstallOptions
import org.lujarch.installer.OptionControl
import org.lujarch.installer.R as SharedR

/** Where the sheet is: the app details and their pages, or the list of several apps. */
sealed interface InsRoute {
    data object Batch : InsRoute

    data class Details(val pkg: String) : InsRoute

    data class Page(val pkg: String?, val page: InsPage) : InsRoute
}

enum class InsPage { PERMISSIONS, PARTS, COMPONENTS, SIGNATURE, OPTIONS, FAILURE }

/** What the sheet shows, from the work and the route; the work page rises from below. */
private sealed interface Shown {
    data object Reading : Shown

    data class Gated(val gate: Gate) : Shown

    data object Unreadable : Shown

    data class Route(val route: InsRoute, val depth: Int) : Shown

    data class Work(val pkg: String) : Shown
}

/** The installer's sheet for [work] (INS-1 to INS-5); [onClose] lets it fall and ends it. */
@Composable
fun InstallSheet(work: Work, visible: Boolean, onClose: () -> Unit, onGone: () -> Unit, onUnknownSettings: (String) -> Unit) {
    val context = LocalContext.current
    val reading = work.reading
    val single = reading != null && reading.apps.size == 1 && reading.unreadable.isEmpty()
    var route by remember { mutableStateOf<InsRoute?>(null) }
    val base: InsRoute? =
        when {
            reading == null || reading.apps.isEmpty() -> null
            single -> InsRoute.Details(reading.apps[0].packageName)
            else -> InsRoute.Batch
        }
    val current = route ?: base
    val shown: Shown =
        when {
            work.gate != null -> Shown.Gated(work.gate!!)
            reading == null -> Shown.Reading
            reading.apps.isEmpty() -> Shown.Unreadable
            single && (work.running || work.finished) -> Shown.Work(reading.apps[0].packageName)
            else -> Shown.Route(current!!, depthOf(current, single))
        }
    fun back() {
        val r = current
        when {
            r is InsRoute.Page && !single && r.pkg != null && r.page != InsPage.FAILURE -> route = InsRoute.Details(r.pkg)
            r is InsRoute.Page && single -> route = null
            r is InsRoute.Page || (r is InsRoute.Details && !single) -> route = InsRoute.Batch
            else -> onClose()
        }
    }
    BackHandler(enabled = visible) {
        if (shown is Shown.Route) back() else onClose()
    }
    // After installing: Open or App info, for one app (INS-3).
    val onlyStatus = if (single) work.status[reading!!.apps[0].packageName] else null
    LaunchedEffect(onlyStatus) {
        if (onlyStatus is AppStatus.Done && onlyStatus.notes.isEmpty() && work.options.after != InstallOptions.AFTER_DONE) {
            if (work.openAfter(reading!!.apps[0].packageName, work.options.after)) onClose()
        }
    }
    SheetFrame(visible = visible, dismissable = true, onDismiss = onClose, onGone = onGone) {
        SheetPages(
            route = shown,
            depth = { (it as? Shown.Route)?.depth ?: 0 },
            rising = { it is Shown.Work },
            modifier = Modifier.weight(1f, fill = false),
        ) { s ->
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                when (s) {
                    Shown.Reading -> ReadingPage(work)
                    is Shown.Gated -> GatePage(s.gate)
                    Shown.Unreadable -> UnreadablePage(reading!!.unreadable)
                    is Shown.Work -> WorkPage(work, work.app(s.pkg)!!)
                    is Shown.Route ->
                        when (val r = s.route) {
                            InsRoute.Batch -> BatchPage(work, onOpen = { route = InsRoute.Details(it) }, onFailure = { route = InsRoute.Page(it, InsPage.FAILURE) }, onOptions = { route = InsRoute.Page(null, InsPage.OPTIONS) })
                            is InsRoute.Details ->
                                DetailsPage(work, work.app(r.pkg)!!, single) { page -> route = InsRoute.Page(if (page == InsPage.OPTIONS && !single) null else r.pkg, page) }
                            is InsRoute.Page -> {
                                val app = r.pkg?.let { work.app(it) }
                                when (r.page) {
                                    InsPage.PERMISSIONS -> PermissionsPage(app!!, ::back)
                                    InsPage.PARTS -> PartsPage(work, app!!, ::back)
                                    InsPage.COMPONENTS -> ComponentsPage(app!!, ::back)
                                    InsPage.SIGNATURE -> SignaturePage(app!!, ::back)
                                    InsPage.OPTIONS -> OptionsPage(work, ::back)
                                    InsPage.FAILURE -> {
                                        PageHeader(app!!.label, ::back)
                                        (work.status[app.packageName] as? AppStatus.Failed)?.let { FailureBlock(app, it.failure) }
                                    }
                                }
                            }
                        }
                }
                SheetSpacer()
            }
        }
        Actions(work, shown, single, onClose, onUnknownSettings) { route = it }
    }
}

private fun depthOf(route: InsRoute, single: Boolean): Int =
    when (route) {
        InsRoute.Batch -> 0
        is InsRoute.Details -> if (single) 0 else 1
        is InsRoute.Page -> if (single || route.pkg == null) 1 else 2
    }

@Composable
private fun ReadingPage(work: Work) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 12.dp)) {
        Text(stringResource(R.string.ins_reading), style = InsText.title, color = MaterialTheme.colorScheme.onSurface)
        val total = work.readTotal
        LinearWavyProgressIndicator(progress = { if (total > 0) work.readDone.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
        if (total > 0) {
            Text(
                stringResource(R.string.ins_amount, Texts.size(context, work.readDone), Texts.size(context, total)),
                style = InsText.t2,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (s in work.sources) Text(s.name, style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GatePage(gate: Gate) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 12.dp)) {
        Text(stringResource(R.string.ins_unknown_title), style = InsText.title, color = MaterialTheme.colorScheme.onSurface)
        val text =
            when (gate) {
                is Gate.UnknownSource -> gate.label + "\n\n" + stringResource(R.string.ins_unknown_body)
                is Gate.NoRequest -> stringResource(R.string.ins_unknown_no_request, gate.label)
                Gate.Blocked -> stringResource(R.string.ins_blocked)
            }
        Text(text, style = InsText.t1.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun UnreadablePage(unreadable: List<Unreadable>) {
    Text(
        stringResource(R.string.ins_cannot_read),
        style = InsText.title,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 8.dp),
    )
    for (u in unreadable) SheetRow(u.name, u.reason)
}

/** The app's name and icon, package name, kind of install and signature line (INS-1). */
@Composable
private fun AppHead(app: AppFile, look: BadgeLook = BadgeLook.APP, title: String = app.label, sub: String = app.packageName) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 14.dp),
    ) {
        AppBadge(app.icon, look)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text(title, style = InsText.title, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailsPage(work: Work, app: AppFile, single: Boolean, onPage: (InsPage) -> Unit) {
    val context = LocalContext.current
    AppHead(app)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tag(Texts.kind(context, app.kind), TagLook.ASK)
            Text(Texts.versions(context, app), style = InsText.t1, color = MaterialTheme.colorScheme.onSurface)
            Text(Texts.codes(context, app), style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val bad = app.signatureMatches == false || app.certificates.isEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (bad) InsIcons.shieldAlert else InsIcons.shieldCheck,
                contentDescription = null,
                tint = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(signatureLine(app), style = InsText.t2, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    Facts(
        listOf(
            stringResource(R.string.ins_fact_size) to Texts.size(context, work.size(app)),
            stringResource(R.string.ins_fact_android) to
                stringResource(R.string.ins_android_range, Texts.android(app.minSdk), Texts.android(app.targetSdk)),
            stringResource(R.string.ins_fact_abi) to (app.abis.joinToString(", ").ifEmpty { stringResource(R.string.ins_abi_any) }),
            stringResource(R.string.ins_fact_build) to build(app),
        )
    )
    val newCount = app.permissions.count { it.new }
    SheetRow(
        stringResource(SharedR.string.ins_permissions),
        permissionsSummary(app),
        icon = InsIcons.lock,
        onClick = { onPage(InsPage.PERMISSIONS) },
    ) {
        if (newCount > 0) Tag(stringResource(R.string.ins_tag_new_count, newCount), TagLook.NEW)
    }
    if (app.isBundle) {
        val chosen = app.partsToInstall(work.parts[app.packageName].orEmpty())
        val fitsAll = chosen.map { it.key }.toSet() == app.partsToInstall(app.defaultParts()).map { it.key }.toSet()
        SheetRow(
            stringResource(R.string.ins_parts),
            stringResource(
                R.string.ins_parts_summary, chosen.size, app.parts.size,
                if (fitsAll) stringResource(R.string.ins_part_fits) else Texts.size(context, work.size(app)),
            ),
            icon = InsIcons.parts,
            onClick = if (work.session != null) null else ({ onPage(InsPage.PARTS) }),
        )
    }
    val c = app.components
    SheetRow(
        stringResource(R.string.ins_components),
        listOf(
                R.string.ins_activities to c.activities, R.string.ins_services to c.services,
                R.string.ins_receivers to c.receivers, R.string.ins_providers to c.providers,
            )
            .joinToString(" · ") { (id, n) -> context.getString(R.string.ins_count, context.getString(id), n) },
        icon = InsIcons.components,
        onClick = { onPage(InsPage.COMPONENTS) },
    )
    SheetRow(
        stringResource(R.string.ins_signature),
        app.certificates.firstOrNull()?.let { "SHA-256 " + shortHash(it) } ?: stringResource(R.string.ins_sig_unsigned),
        icon = InsIcons.shield,
        onClick = { onPage(InsPage.SIGNATURE) },
    )
    SheetRow(
        stringResource(R.string.ins_options),
        optionsSummary(work),
        icon = InsIcons.options,
        onClick = if (work.session != null) null else ({ onPage(InsPage.OPTIONS) }),
    )
}

@Composable
private fun signatureLine(app: AppFile): String {
    val head =
        when {
            app.certificates.isEmpty() -> stringResource(R.string.ins_sig_unsigned)
            app.signatureMatches == true -> stringResource(R.string.ins_sig_matches)
            app.signatureMatches == false -> stringResource(R.string.ins_sig_differs)
            else -> stringResource(R.string.ins_sig_signed)
        }
    return (listOf(head) + app.schemes.map { it.shown }).joinToString(" · ")
}

@Composable
private fun build(app: AppFile): String {
    val parts = ArrayList<String>()
    if (app.debuggable) parts += stringResource(R.string.ins_build_debuggable)
    if (app.testOnly) parts += stringResource(R.string.ins_build_test_only)
    return if (parts.isEmpty()) stringResource(R.string.ins_build_release) else parts.joinToString(" · ")
}

@Composable
private fun permissionsSummary(app: AppFile): String {
    if (app.permissions.isEmpty()) return stringResource(R.string.ins_perms_none)
    val consent = app.permissions.count { it.consent }
    val all = pluralStringResource(R.plurals.ins_perms_count, app.permissions.size, app.permissions.size)
    return if (consent == 0) all else all + " · " + pluralStringResource(R.plurals.ins_perms_consent, consent, consent)
}

@Composable
private fun optionsSummary(work: Work): String {
    val context = LocalContext.current
    if (work.session != null) {
        val pkg = work.origin.callingPackage
        val label = pkg?.let {
            try {
                context.packageManager.getApplicationInfo(it, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(context.packageManager).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        } ?: pkg.orEmpty()
        return stringResource(R.string.ins_options_by_app, label)
    }
    val list = work.options.summary(context)
    return if (list.isEmpty()) stringResource(R.string.ins_options_default) else list.joinToString(" · ")
}

private fun shortHash(sha: String): String {
    val b = sha.split(":")
    return if (b.size < 6) sha else (b.take(3) + "…" + b.takeLast(2)).joinToString(":")
}

private fun shortPermission(name: String): String = name.removePrefix("android.permission.")

@Composable
private fun PermissionsPage(app: AppFile, onBack: () -> Unit) {
    PageHeader(stringResource(SharedR.string.ins_permissions), onBack)
    val added = app.permissions.filter { it.new }
    val consent = app.permissions.filter { !it.new && it.consent }
    val other = app.permissions.filter { !it.new && !it.consent }
    @Composable
    fun rows(list: List<Permission>) {
        for (p in list) {
            SheetRow(p.label ?: shortPermission(p.name), shortPermission(p.name), monoSummary = true) {
                if (p.new) Tag(stringResource(R.string.ins_tag_new), TagLook.NEW)
                if (p.consent) Tag(stringResource(R.string.ins_tag_consent), TagLook.ASK)
            }
        }
    }
    if (added.isNotEmpty()) {
        GroupTitle(stringResource(R.string.ins_perms_added))
        rows(added)
    }
    if (consent.isNotEmpty()) {
        GroupTitle(stringResource(R.string.ins_perms_needs_consent))
        rows(consent)
    }
    if (other.isNotEmpty()) {
        GroupTitle(stringResource(R.string.ins_perms_other))
        var all by remember { mutableStateOf(other.size <= 4) }
        rows(if (all) other else other.take(3))
        if (!all) {
            SheetRow(
                pluralStringResource(R.plurals.ins_show_more, other.size - 3, other.size - 3),
                null,
                titleColor = MaterialTheme.colorScheme.primary,
                onClick = { all = true },
                chevron = false,
            )
        }
    }
    if (app.permissions.isEmpty()) SheetRow(stringResource(R.string.ins_perms_none), null)
}

@Composable
private fun PartsPage(work: Work, app: AppFile, onBack: () -> Unit) {
    val context = LocalContext.current
    PageHeader(stringResource(R.string.ins_parts), onBack)
    val chosen = work.parts[app.packageName].orEmpty()
    val groups =
        listOf(
            PartKind.BASE to R.string.ins_part_base, PartKind.FEATURE to R.string.ins_part_features,
            PartKind.LANGUAGE to R.string.ins_part_languages, PartKind.DENSITY to R.string.ins_part_densities,
            PartKind.ABI to R.string.ins_part_abis, PartKind.OTHER to R.string.ins_part_other,
        )
    for ((kind, title) in groups) {
        val parts = app.parts.filter { it.kind == kind }
        if (parts.isEmpty()) continue
        GroupTitle(stringResource(title))
        for (p in parts) {
            val base = p.kind == PartKind.BASE
            val summary =
                listOfNotNull(
                    if (base) stringResource(R.string.ins_part_required) else null,
                    p.detail?.takeIf { p.kind == PartKind.LANGUAGE },
                    if (!base && p.fits && p.kind in setOf(PartKind.DENSITY, PartKind.ABI, PartKind.LANGUAGE)) stringResource(R.string.ins_part_fits) else null,
                    Texts.size(context, p.size),
                ).joinToString(" · ")
            SwitchRow(p.shownName, summary, checked = base || p.key in chosen, enabled = !base, monoTitle = true) { on ->
                work.parts[app.packageName] = if (on) chosen + p.key else chosen - p.key
            }
        }
    }
    if (app.obbs.isNotEmpty()) {
        GroupTitle(stringResource(R.string.ins_part_expansion))
        for (o in app.obbs) {
            SheetRow(o.name, stringResource(R.string.ins_part_expansion_summary) + " · " + Texts.size(context, o.size), monoTitle = true)
        }
    }
}

@Composable
private fun ComponentsPage(app: AppFile, onBack: () -> Unit) {
    val context = LocalContext.current
    PageHeader(stringResource(R.string.ins_components), onBack)
    val c = app.components
    fun fact(id: Int, kind: ComponentKind) =
        context.getString(id) to context.getString(R.string.ins_component_counts, c.count(kind), c.exported.count { it.kind == kind })
    Facts(
        listOf(
            fact(R.string.ins_activities, ComponentKind.ACTIVITY), fact(R.string.ins_services, ComponentKind.SERVICE),
            fact(R.string.ins_receivers, ComponentKind.RECEIVER), fact(R.string.ins_providers, ComponentKind.PROVIDER),
        )
    )
    GroupTitle(stringResource(R.string.ins_usable))
    if (c.exported.isEmpty()) SheetRow(stringResource(R.string.ins_usable_none), null)
    for (e in c.exported) {
        val kind =
            stringResource(
                when (e.kind) {
                    ComponentKind.ACTIVITY -> R.string.ins_activity
                    ComponentKind.SERVICE -> R.string.ins_service
                    ComponentKind.RECEIVER -> R.string.ins_receiver
                    ComponentKind.PROVIDER -> R.string.ins_provider
                }
            )
        val guard = e.guard?.let { stringResource(R.string.ins_guarded, shortPermission(it)) } ?: stringResource(R.string.ins_open_to_all)
        SheetRow(e.name, "$kind · $guard", monoTitle = true)
    }
}

@Composable
private fun SignaturePage(app: AppFile, onBack: () -> Unit) {
    PageHeader(stringResource(R.string.ins_signature), onBack)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)) {
        Text(stringResource(R.string.ins_sig_certificate), style = InsText.t1, color = MaterialTheme.colorScheme.onSurface)
        if (app.certificates.isEmpty()) {
            Text(stringResource(R.string.ins_sig_unsigned), style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (sha in app.certificates) {
            Text(sha.split(':').chunked(8).joinToString(":\n") { it.joinToString(":") }, style = InsText.mono.copy(fontSize = 13.sp, lineHeight = 20.sp), color = MaterialTheme.colorScheme.onSurface)
        }
    }
    val context = LocalContext.current
    SheetRow(
        stringResource(R.string.ins_sig_schemes),
        Scheme.entries.joinToString(" · ") { s ->
            if (s in app.schemes) s.shown else context.getString(R.string.ins_scheme_absent, s.shown)
        },
    )
    if (app.signatureMatches != null) {
        val ok = app.signatureMatches
        SheetRow(
            stringResource(if (ok) R.string.ins_sig_matches else R.string.ins_sig_differs),
            stringResource(if (ok) R.string.ins_sig_matches_summary else R.string.ins_sig_differs_summary),
            icon = if (ok) InsIcons.check else InsIcons.shieldAlert,
            iconTint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
    SheetRow(stringResource(R.string.ins_debuggable), stringResource(if (app.debuggable) R.string.ins_yes else R.string.ins_no))
    SheetRow(stringResource(R.string.ins_test_only), stringResource(if (app.testOnly) R.string.ins_yes else R.string.ins_no))
}

/** This install's options, starting from the defaults (INS-3). */
@Composable
private fun OptionsPage(work: Work, onBack: () -> Unit) {
    val context = LocalContext.current
    PageHeader(stringResource(R.string.ins_options), onBack)
    val files = work.sources.joinToString(", ") { it.name }
    for (control in work.options.controls(context)) {
        when (control) {
            is OptionControl.Pick ->
                Choices(control.title, control.choices, control.value, mono = false) { work.options = work.options.with(control.key, it.toString()) }
            is OptionControl.Abi ->
                Choices(control.title, control.choices, control.value, mono = true) { work.options = work.options.with(control.key, it) }
            is OptionControl.Flag ->
                SwitchRow(
                    control.title,
                    if (control.key == "delete_file") files else control.summary,
                    control.checked,
                ) { work.options = work.options.with(control.key, if (it) "1" else "0") }
        }
    }
}

/** A choice of [choices]: side by side while they fit, else one row each. */
@Composable
private fun <T> Choices(title: String, choices: List<Pair<T, String>>, value: T, mono: Boolean, onPick: (T) -> Unit) {
    if (choices.size <= 3) {
        Segments(title, choices, value, onPick)
        return
    }
    GroupTitle(title)
    for ((v, label) in choices) {
        SheetRow(label, null, monoTitle = mono && v != choices.first().first, chevron = false, onClick = { onPick(v) }) {
            if (v == value) Icon(InsIcons.check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Several apps: each with its kind, versions, size and, while installing, its progress (INS-4). */
@Composable
private fun BatchPage(work: Work, onOpen: (String) -> Unit, onFailure: (String) -> Unit, onOptions: () -> Unit) {
    val context = LocalContext.current
    val apps = work.apps
    val todo = apps.filter { work.status[it.packageName] !is AppStatus.Skipped }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 16.dp)) {
        Text(
            if (work.finished) {
                stringResource(R.string.ins_batch_result, todo.count { work.status[it.packageName] is AppStatus.Done }, todo.size)
            } else {
                pluralStringResource(R.plurals.ins_batch_title, apps.size, apps.size)
            },
            style = InsText.title,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(stringResource(R.string.ins_batch_note), style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        apps.forEachIndexed { i, app ->
            val status = work.status[app.packageName]
            val first = i == 0
            val last = i == apps.lastIndex
            val shape =
                RoundedCornerShape(
                    topStart = if (first) 20.dp else 6.dp, topEnd = if (first) 20.dp else 6.dp,
                    bottomStart = if (last) 20.dp else 6.dp, bottomEnd = if (last) 20.dp else 6.dp,
                )
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable {
                            if (status is AppStatus.Failed) onFailure(app.packageName) else onOpen(app.packageName)
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                AppBadge(app.icon, BadgeLook.APP, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(app.label, style = InsText.t1, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(Texts.kind(context, app.kind), Texts.versions(context, app), Texts.size(context, work.size(app))).joinToString(" · "),
                        style = InsText.t2,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (status is AppStatus.Working) {
                        LinearProgressIndicator(
                            progress = { status.progress },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp),
                        )
                    }
                }
                when (status) {
                    null ->
                        Checkbox(
                            checked = work.chosen[app.packageName] == true,
                            onCheckedChange = { work.chosen[app.packageName] = it },
                        )
                    AppStatus.Waiting -> Text(stringResource(R.string.ins_waiting), style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is AppStatus.Working ->
                        Text("${(status.progress * 100).toInt()}%", style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
                    is AppStatus.Done ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(InsIcons.check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.ins_succeeded), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                    is AppStatus.Failed -> Tag(stringResource(R.string.ins_failed), TagLook.BAD)
                    AppStatus.Stopped -> Text(stringResource(R.string.ins_stopped), style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppStatus.Skipped -> Text(stringResource(R.string.ins_skipped), style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val unreadable = work.reading?.unreadable.orEmpty()
    if (unreadable.isNotEmpty()) {
        GroupTitle(stringResource(R.string.ins_cannot_read_some))
        for (u in unreadable) SheetRow(u.name, u.reason)
    }
    SheetSpacer()
    SheetRow(
        stringResource(R.string.ins_options),
        optionsSummary(work),
        icon = InsIcons.options,
        iconTint = MaterialTheme.colorScheme.primary,
        onClick = if (work.running || work.finished) null else onOptions,
    )
}

/** One app installing, installed or not installed (INS-5). */
@Composable
private fun WorkPage(work: Work, app: AppFile) {
    val context = LocalContext.current
    val status = work.status[app.packageName]
    val look =
        when (status) {
            is AppStatus.Done -> BadgeLook.DONE
            is AppStatus.Failed -> BadgeLook.FAILED
            else -> BadgeLook.APP
        }
    val version = app.versionName ?: app.versionCode.toString()
    AppHead(
        app,
        look,
        title = Texts.workTitle(context, app, status),
        sub =
            when (status) {
                is AppStatus.Done -> "${app.label} $version · ${Texts.size(context, work.size(app))}"
                is AppStatus.Failed -> "${app.label} $version"
                else -> "${app.label} ${Texts.versions(context, app)}"
            },
    )
    when (status) {
        is AppStatus.Failed -> FailureBlock(app, status.failure)
        is AppStatus.Done ->
            if (status.notes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
                    for (n in status.notes) Text(n, style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        else -> {
            val step = (status as? AppStatus.Working)?.step
            val p = (status as? AppStatus.Working)?.progress ?: 0f
            Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                LinearWavyProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                Text(
                    step?.let { Texts.step(context, it) } ?: stringResource(R.string.ins_waiting),
                    style = InsText.t2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Android's reason in words, its code and the system's message (INS-5). */
@Composable
private fun FailureBlock(app: AppFile, failure: Outcome.Failed) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Text(
            InstallFailures.reason(context, failure.legacy, failure.status),
            style = InsText.t1.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (failure.legacy != 0) Row { Tag(InstallFailures.code(failure.legacy), TagLook.BAD, mono = true) }
        if (!failure.message.isNullOrBlank()) {
            Text(
                failure.message,
                style = InsText.mono,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
        if (canUninstallAndInstall(app, failure)) {
            Text(stringResource(R.string.ins_data_deleted), style = InsText.t2, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun canUninstallAndInstall(app: AppFile, failure: Outcome.Failed) =
    app.installed != null && !app.installed.system && InstallFailures.signatureDiffers(failure.legacy)

enum class Look { FILL, TONAL, OUTLINE, ERROR }

@Composable
fun RowScope.SheetButton(text: String, look: Look, grow: Boolean = look == Look.FILL || look == Look.ERROR, enabled: Boolean = true, onClick: () -> Unit) {
    SheetButtonBody(text, look, if (grow) Modifier.weight(1f) else Modifier, enabled, onClick)
}

@Composable
fun ColumnScope.SheetButtonFull(text: String, look: Look, onClick: () -> Unit) {
    SheetButtonBody(text, look, Modifier.fillMaxWidth(), true, onClick)
}

@Composable
private fun SheetButtonBody(text: String, look: Look, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val m = modifier.height(48.dp)
    val label: @Composable RowScope.() -> Unit = { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
    when (look) {
        Look.FILL -> Button(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = m, enabled = enabled, content = label)
        Look.TONAL -> FilledTonalButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = m, enabled = enabled, content = label)
        Look.OUTLINE -> OutlinedButton(onClick = onClick, shapes = ButtonDefaults.shapes(), modifier = m, enabled = enabled, content = label)
        Look.ERROR ->
            Button(
                onClick = onClick,
                shapes = ButtonDefaults.shapes(),
                modifier = m,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                content = label,
            )
    }
}

@Composable
private fun Actions(work: Work, shown: Shown, single: Boolean, onClose: () -> Unit, onUnknownSettings: (String) -> Unit, onRoute: (InsRoute?) -> Unit) {
    val context = LocalContext.current
    when (shown) {
        Shown.Reading, Shown.Unreadable ->
            SheetActions { SheetButton(stringResource(if (shown == Shown.Reading) R.string.ins_cancel else R.string.ins_close), Look.OUTLINE, grow = true, onClick = onClose) }
        is Shown.Gated ->
            SheetActions {
                if (shown.gate is Gate.UnknownSource) {
                    SheetButton(stringResource(R.string.ins_cancel), Look.OUTLINE, onClick = onClose)
                    SheetButton(stringResource(R.string.ins_settings), Look.FILL) { onUnknownSettings(shown.gate.packageName) }
                } else {
                    SheetButton(stringResource(R.string.ins_close), Look.OUTLINE, grow = true, onClick = onClose)
                }
            }
        is Shown.Work -> {
            val app = work.app(shown.pkg)!!
            when (val status = work.status[app.packageName]) {
                is AppStatus.Done ->
                    SheetActions {
                        SheetButton(stringResource(SharedR.string.ins_after_done), Look.OUTLINE, onClick = onClose)
                        SheetButton(stringResource(SharedR.string.ins_after_info), Look.TONAL) {
                            if (work.openAfter(app.packageName, InstallOptions.AFTER_INFO)) onClose()
                        }
                        if (context.packageManager.getLaunchIntentForPackage(app.packageName) != null) {
                            SheetButton(stringResource(SharedR.string.ins_after_open), Look.FILL) {
                                if (work.openAfter(app.packageName, InstallOptions.AFTER_OPEN)) onClose()
                            }
                        }
                    }
                is AppStatus.Failed ->
                    if (canUninstallAndInstall(app, status.failure)) {
                        SheetActionsColumn {
                            SheetButtonFull(stringResource(R.string.ins_uninstall_and_install), Look.ERROR) { work.uninstallAndInstall(app) }
                            SheetButtonFull(stringResource(R.string.ins_close), Look.OUTLINE, onClose)
                        }
                    } else {
                        SheetActions { SheetButton(stringResource(R.string.ins_close), Look.OUTLINE, grow = true, onClick = onClose) }
                    }
                else -> SheetActions { SheetButton(stringResource(R.string.ins_background), Look.OUTLINE, grow = true, onClick = onClose) }
            }
        }
        is Shown.Route -> {
            val r = shown.route
            when {
                !single && work.running ->
                    SheetActions { SheetButton(stringResource(R.string.ins_stop_rest), Look.OUTLINE, grow = true) { work.stopRest() } }
                !single && work.finished ->
                    SheetActions { SheetButton(stringResource(SharedR.string.ins_after_done), Look.FILL, onClick = onClose) }
                r is InsRoute.Page && r.page == InsPage.FAILURE -> {
                    val app = work.app(r.pkg!!)!!
                    val failure = (work.status[app.packageName] as? AppStatus.Failed)?.failure
                    if (failure != null && canUninstallAndInstall(app, failure)) {
                        SheetActionsColumn {
                            SheetButtonFull(stringResource(R.string.ins_uninstall_and_install), Look.ERROR) {
                                onRoute(InsRoute.Batch)
                                work.uninstallAndInstall(app)
                            }
                            SheetButtonFull(stringResource(R.string.ins_close), Look.OUTLINE) { onRoute(InsRoute.Batch) }
                        }
                    } else {
                        SheetActions { SheetButton(stringResource(R.string.ins_close), Look.OUTLINE, grow = true) { onRoute(InsRoute.Batch) } }
                    }
                }
                single -> {
                    val app = work.apps[0]
                    SheetActions {
                        SheetButton(stringResource(R.string.ins_cancel), Look.OUTLINE, onClick = onClose)
                        SheetButton(Texts.installButton(context, app), Look.FILL) {
                            onRoute(null)
                            work.install()
                        }
                    }
                }
                else -> {
                    val count = work.apps.count { work.chosen[it.packageName] == true }
                    SheetActions {
                        SheetButton(stringResource(R.string.ins_cancel), Look.OUTLINE, onClick = onClose)
                        SheetButton(
                            stringResource(if (count == work.apps.size) R.string.ins_install_all else R.string.ins_install),
                            Look.FILL,
                            enabled = count > 0,
                        ) {
                            onRoute(InsRoute.Batch)
                            work.install()
                        }
                    }
                }
            }
        }
    }
}
