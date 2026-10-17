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

package org.lujarch.linux.terminal

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.util.Log
import java.util.concurrent.Executor
import org.lujarch.linux.LinuxLocation
import android.location.Location as AndroidLocation
import org.lujarch.linux.client.Linux
import org.lujarch.linux.client.LinuxException
import org.lujarch.linux.client.TAG

/**
 * Android's position for Linux (LNX-7, Location): while a program in Linux reads
 * ILinux.STREAM_LOCATION, Android's location updates run from every provider the phone has of
 * fused, gps and network (the best fix wins), and each fix goes to lujarch_linux. Android's
 * permission and its location switch decide: without the permission Linux reads "permission"
 * (Android's own dialog asks the first time), with the location off "off".
 */
class Location(private val context: Context, private val parts: LinuxParts) {
    private val manager: LocationManager? = context.getSystemService(LocationManager::class.java)
    private val executor = Executor { parts.handler.post(it) }

    private var wanted = false
    private var updating = false
    private var watching = false
    private var error: String? = null
    private var best: AndroidLocation? = null
    private var asked = false

    private val listener = LocationListener { location -> accept(location) }

    private val switchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = update()
    }

    /** On the parts' thread: a program reads the location, or none does any more. */
    fun want(on: Boolean) {
        wanted = on && Linux.lent(context, "location")
        if (!wanted) asked = false
        watchSwitch(wanted)
        update()
    }

    fun stop() = want(false)

    private fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Updates on while Linux wants them, the permission is there and Android's location is on. */
    private fun update() {
        val m = manager
        val next: String? = when {
            !wanted -> null
            m == null -> "off"
            !permitted() -> "permission"
            !m.isLocationEnabled -> "off"
            else -> null
        }
        val run = wanted && next == null && m != null
        if (run && !updating) startUpdates(m!!)
        if (!run && updating) stopUpdates()
        if (!run) best = null
        if (next != error) {
            error = next
            if (next != null) {
                Log.i(TAG, "location: Linux gets none ($next)")
                send(LinuxLocation().apply { error = next })
            }
        }
        if (next == "permission" && !asked) {
            asked = true
            parts.requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            ) { granted -> if (granted && wanted) update() }
        }
    }

    private fun startUpdates(m: LocationManager) {
        val request = LocationRequest.Builder(INTERVAL_MS)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(INTERVAL_MS / 2)
            .build()
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { m.hasProvider(it) }
        var started = 0
        updating = true
        for (p in providers) {
            try {
                m.requestLocationUpdates(p, request, executor, listener)
                started++
                m.getLastKnownLocation(p)?.let { accept(it) }
            } catch (e: SecurityException) {
                Log.w(TAG, "location: $p: ${e.message}")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "location: $p: ${e.message}")
            }
        }
        Log.i(TAG, "location: updates for Linux from $started of ${providers.joinToString(",")}")
    }

    private fun stopUpdates() {
        try {
            manager?.removeUpdates(listener)
        } catch (e: RuntimeException) {
            Log.w(TAG, "location: ${e.message}")
        }
        updating = false
        Log.i(TAG, "location: updates for Linux stopped")
    }

    /** A newer fix replaces the best one unless it is less accurate within a few seconds of it. */
    private fun accept(location: AndroidLocation) {
        if (!updating) return
        val b = best
        val better = b == null || location.provider == b.provider ||
            location.time - b.time > STALE_MS ||
            (location.hasAccuracy() && (!b.hasAccuracy() || location.accuracy <= b.accuracy))
        if (!better) return
        best = location
        send(LinuxLocation().apply {
            latitude = location.latitude
            longitude = location.longitude
            time = location.time
            hasAltitude = location.hasAltitude()
            altitude = location.altitude
            hasAccuracy = location.hasAccuracy()
            accuracy = location.accuracy
            hasSpeed = location.hasSpeed()
            speed = location.speed
            hasBearing = location.hasBearing()
            bearing = location.bearing
        })
    }

    private fun send(fix: LinuxLocation) {
        try {
            Linux.blocking("setLocation") { it.setLocation(fix) }
        } catch (e: LinuxException) {
            Log.w(TAG, e.message ?: "setLocation")
        }
    }

    private fun watchSwitch(on: Boolean) {
        if (on == watching) return
        if (on) {
            val filter = IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            }
            context.registerReceiver(switchReceiver, filter, null, parts.handler, Context.RECEIVER_NOT_EXPORTED)
        } else {
            try {
                context.unregisterReceiver(switchReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "location: ${e.message}")
            }
        }
        watching = on
    }

    private companion object {
        const val INTERVAL_MS = 1000L
        const val STALE_MS = 5000L
    }
}
