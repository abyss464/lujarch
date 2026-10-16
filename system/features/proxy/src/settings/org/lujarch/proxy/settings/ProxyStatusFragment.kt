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

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.settingslib.widget.FooterPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.Format
import org.lujarch.settings.pages.R as PagesR

/**
 * Details behind the Proxy page's status row: state, mode, core version,
 * the virtual network interface (TUN, explained here once), IPv6, traffic,
 * and which configuration the core runs.
 *
 * Each row shows as soon as its own source answers, and a slow or busy
 * source keeps only its own rows waiting: the service's state (state, core
 * version), the core's configuration (mode, TUN) and connections (traffic
 * since the start), polled every [POLL_MS]; the core's traffic stream
 * (speed), bounded by [Mihomo.TRAFFIC_FIRST_SAMPLE_MS] and
 * [Mihomo.TRAFFIC_SAMPLE_GAP_MS]; the manager's settings (IPv6) and files
 * (the configurations), read once per visit through the app's line of
 * manager commands. Everything runs in the view's scope while the page is
 * visible.
 */
class ProxyStatusFragment : BasePreferenceFragment() {
    private lateinit var statePref: Preference
    private lateinit var modePref: Preference
    private lateinit var corePref: Preference
    private lateinit var tunPref: Preference
    private lateinit var ipv6Pref: Preference
    private lateinit var speedPref: Preference
    private lateinit var totalPref: Preference
    private lateinit var livePref: Preference
    private lateinit var goodPref: Preference
    private lateinit var restartPref: Preference

    /** The service's state answered at least once on this visit. */
    private var polled = false
    /** The core's controller answers; null when the service's state could not be read. */
    private var up: Boolean? = null
    /** IPv6 is blocked now (PRX-5); null when the service's state could not be read. */
    private var ipv6Block: Boolean? = null
    private var filesJob: Job? = null
    private var trafficJob: Job? = null
    /** Read the manager's settings and files (again) at the next turn of the poll. */
    private var readsWanted = true
    private val wake = Channel<Unit>(Channel.CONFLATED)

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        val service = screen.add(PreferenceCategory(prefContext)) { key = "service" }
        statePref = service.add(info("state", R.string.px_status_title))
        modePref = service.add(info("mode", R.string.px_mode_category))
        corePref = service.add(info("core", R.string.px_status_core).apply { isCopyingEnabled = true })
        restartPref = service.add(info("restart", R.string.px_status_restart))

        val network = screen.add(PreferenceCategory(prefContext)) {
            key = "network"
            setTitle(R.string.px_status_network)
        }
        tunPref = network.add(info("tun", R.string.px_status_tun))
        ipv6Pref = network.add(info("ipv6", R.string.px_status_ipv6))
        speedPref = network.add(info("speed", R.string.px_status_speed))
        totalPref = network.add(info("total", R.string.px_status_total))

        val config = screen.add(PreferenceCategory(prefContext)) {
            key = "config"
            setTitle(R.string.px_status_config)
        }
        livePref = config.add(info("live", R.string.px_status_live))
        goodPref = config.add(info("good", R.string.px_status_good))
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.px_status_footer)
            order = Int.MAX_VALUE
        }
    }

    private fun info(key: String, title: Int) = Preference(prefContext).apply {
        this.key = key
        setTitle(title)
        setSummary(PagesR.string.loading)
        isSelectable = false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launchUi {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val pageScope = this
                readsWanted = true
                while (isActive) {
                    if (readsWanted) {
                        readsWanted = false
                        if (filesJob?.isActive != true) filesJob = pageScope.launch { loadFiles() }
                    }
                    poll(pageScope)
                    withTimeoutOrNull(POLL_MS) { wake.receive() }
                }
            }
        }
    }

    override fun refresh() {
        readsWanted = true
        wake.trySend(Unit)
    }

    // ---- the manager's settings and files ----

    private suspend fun loadFiles() {
        val context = requireContext()
        val files = attempt("files", R.string.px_error_load_files) { ProxyManager.files() } ?: run {
            livePref.setSummary(R.string.status_unknown)
            goodPref.setSummary(R.string.status_unknown)
            return
        }
        livePref.summary = files.live.modifiedAt?.let { getString(R.string.px_status_live_at, ProxyFormat.dateTime(context, it)) }
            ?: getString(R.string.px_status_none)
        goodPref.summary = when {
            !files.lastGood.present -> getString(R.string.px_status_none)
            files.lastGoodSameAsLive == true -> getString(R.string.px_status_good_same)
            else -> getString(R.string.px_status_good_differs, ProxyFormat.dateTime(context, files.lastGood.modifiedAt ?: 0))
        }
    }

    // ---- the service and the core, polled ----

    /** One turn: the service's state, then the core's configuration and connections side by side. */
    private suspend fun poll(pageScope: CoroutineScope) {
        val info = attempt("info", R.string.error_load_status) { ProxyClient.info() }
        polled = true
        up = info?.controllerUp
        ipv6Block = info?.status?.ipv6Blocked
        showInfo(info)
        showIpv6()
        if (up == true) {
            if (trafficJob?.isActive != true) trafficJob = pageScope.launch { collectTraffic() }
            coroutineScope {
                launch { showConfigs(attempt("configs", R.string.error_load_configs) { Mihomo.configs() }) }
                launch { corePref.summary = orNull("core version") { Mihomo.version() } ?: getString(R.string.status_unknown) }
                launch { showTotal(attempt("connections", R.string.error_load_connections) { Mihomo.connections() }) }
            }
        } else {
            trafficJob?.cancel()
            trafficJob = null
            // Not running, or not known while the service's state cannot be read.
            val text = getString(if (info == null) R.string.status_unknown else R.string.status_not_running)
            modePref.summary = text
            tunPref.summary = text
            speedPref.summary = text
            totalPref.summary = text
        }
    }

    private fun showInfo(info: ProxyInfo?) {
        val context = requireContext()
        val state = when {
            info == null -> getString(R.string.status_unknown)
            !info.mihomoEnabled -> getString(R.string.px_status_off)
            else -> ProxyFormat.stateLabel(context, info.mihomoState)
        }
        // Why the configuration did not pass the core's check (PRX-2, PRX-3).
        val check = info?.status?.checkError?.takeIf { it.isNotBlank() }
        statePref.summary = if (check != null) "$state\n$check" else state
        if (info == null || !info.controllerUp) {
            corePref.summary = getString(if (info == null) R.string.status_unknown else R.string.status_not_running)
        }
        val reason = when (info?.status?.lastRestart) {
            null -> null
            org.lujarch.proxy.IProxy.RESTART_REPAIR -> R.string.px_restart_repair
            org.lujarch.proxy.IProxy.RESTART_UNHEALTHY -> R.string.px_restart_unhealthy
            org.lujarch.proxy.IProxy.RESTART_INTERFACE -> R.string.px_restart_interface
            org.lujarch.proxy.IProxy.RESTART_EXITED -> R.string.px_restart_exited
            org.lujarch.proxy.IProxy.RESTART_NETWORK_STACK -> R.string.px_restart_network_stack
            else -> 0
        }
        restartPref.summary = when {
            info == null -> getString(R.string.status_unknown)
            reason == null || reason == 0 -> getString(R.string.px_status_restart_none)
            else -> getString(
                R.string.px_status_restart_at,
                getString(reason),
                ProxyFormat.dateTime(context, info.status.lastRestartAt * 1000),
            )
        }
    }

    private fun showConfigs(c: MihomoConfigs?) {
        val context = requireContext()
        modePref.summary = ProxyFormat.modeLabel(context, c?.mode) ?: getString(R.string.status_unknown)
        tunPref.summary = when {
            c == null -> getString(R.string.status_unknown)
            !c.tunEnabled -> getString(R.string.status_off)
            c.tunStack != null -> getString(R.string.px_status_tun_on, ProxyFormat.stackLabel(context, c.tunStack))
            else -> getString(R.string.status_on)
        }
    }

    private fun showTotal(c: ConnectionsSnapshot?) {
        val context = requireContext()
        totalPref.summary = if (c == null) {
            getString(R.string.status_unknown)
        } else {
            Format.upDown(context, Format.bytes(context, c.uploadTotal), Format.bytes(context, c.downloadTotal))
        }
    }

    /** IPv6 needs both the service's state and the manager's settings (while the core runs). */
    private fun showIpv6() {
        if (!polled) return
        ipv6Pref.setSummary(
            when {
                up == null -> R.string.status_unknown
                up == false -> R.string.px_status_ipv6_normal
                ipv6Block == null -> R.string.status_unknown
                ipv6Block == true -> R.string.px_status_ipv6_blocked
                else -> R.string.px_status_ipv6_open
            },
        )
    }

    // ---- speed ----

    /**
     * The core's /traffic stream (one sample a second). A stream that sends no
     * first sample within [Mihomo.TRAFFIC_FIRST_SAMPLE_MS], or none for
     * [Mihomo.TRAFFIC_SAMPLE_GAP_MS], is closed and a new one opened at once;
     * meanwhile the row says it waits for the core's figures. A stream that ends or fails is opened again
     * after [RETRY_MS] (a failure also shows in the banner).
     */
    private suspend fun collectTraffic() {
        val context = requireContext()
        var shown = false
        var stalls = 0
        while (true) {
            if (!shown) speedPref.setSummary(if (stalls == 0) R.string.px_status_speed_waiting else R.string.px_status_speed_stalled)
            try {
                Mihomo.traffic().collectWithin(Mihomo.TRAFFIC_FIRST_SAMPLE_MS, Mihomo.TRAFFIC_SAMPLE_GAP_MS) {
                    if (stalls > 0) Log.i(TAG, "ProxyStatusFragment: traffic figures again after $stalls silent stream(s)")
                    stalls = 0
                    clearError("traffic")
                    speedPref.summary = Format.upDown(context, Format.speed(context, it.up), Format.speed(context, it.down))
                    shown = true
                }
                // The stream ended: the core stopped or restarted (the poll sees which).
            } catch (e: CancellationException) {
                throw e
            } catch (e: StreamStalledException) {
                // Logged once per silence, not per new stream.
                if (stalls++ == 0) Log.w(TAG, "ProxyStatusFragment: traffic stream: ${e.message}; opening a new one")
                shown = false
                currentCoroutineContext().ensureActive()
                continue
            } catch (e: Exception) {
                setError("traffic", R.string.error_traffic, e)
                currentCoroutineContext().ensureActive()
            }
            delay(RETRY_MS)
        }
    }

    companion object {
        private const val POLL_MS = 3000L
        private const val RETRY_MS = 3000L
    }
}
