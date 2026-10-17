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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Display
import org.lujarch.linux.AndroidState

/**
 * Android's switches and readings as Linux's desktop shows them (LNX-7, Android's quick
 * settings). A part Android will not tell this app is not known, logged once, so the desktop
 * shows it as unknown, never as off.
 *
 * [start] and [stop] run on the [handler]'s thread, which every observer and callback uses too;
 * a change calls [onChange] there, after a short wait that folds a burst into one. While started
 * it watches the three settings tables, the broadcasts of Wi-Fi, Bluetooth, the hotspot,
 * airplane mode, Do Not Disturb and the battery, the default network and the torch, and reads the
 * radio's signal every few seconds (nothing pushes it to an app without more permissions).
 */
class AndroidWatch(
    private val context: Context,
    private val handler: Handler,
    private val onChange: () -> Unit,
) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val notifications = context.getSystemService(android.app.NotificationManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val hasTelephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    private var started = false
    private var pending = false
    private val warned = HashSet<String>()

    private var torchId: String? = null
    private var torchOn = false

    private val fire = Runnable {
        pending = false
        onChange()
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!started) return
            schedule()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = schedule()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = schedule()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = schedule()
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId != torchId) return
            torchOn = enabled
            schedule()
        }
    }

    private fun schedule() {
        if (pending) return
        pending = true
        handler.postDelayed(fire, DEBOUNCE_MS)
    }

    /** Starts watching; the caller reads the state itself right after, for the first report. */
    fun start() {
        if (started) return
        started = true
        warned.clear()
        val cr = context.contentResolver
        cr.registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver)
        cr.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
        cr.registerContentObserver(Settings.Secure.CONTENT_URI, true, settingsObserver)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WIFI_AP_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(android.app.NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
            addAction(SIM_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        try {
            connectivity.registerDefaultNetworkCallback(networkCallback, handler)
        } catch (e: RuntimeException) {
            warnOnce("the default network", e)
        }
        watchTorch()
        handler.postDelayed(poll, POLL_MS)
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(fire)
        handler.removeCallbacks(poll)
        pending = false
        context.contentResolver.unregisterContentObserver(settingsObserver)
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Android's state: ${e.message}")
        }
        try {
            connectivity.unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Android's state: ${e.message}")
        }
        try {
            camera.unregisterTorchCallback(torchCallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Android's state: ${e.message}")
        }
    }

    /** The torch of the first back camera that has a flash unit, as Android's own tile finds it. */
    private fun watchTorch() {
        try {
            torchId = camera.cameraIdList.firstOrNull { id ->
                val c = camera.getCameraCharacteristics(id)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            if (torchId == null) Log.i(TAG, "no camera with a flash unit: the flashlight is unavailable")
            camera.registerTorchCallback(torchCallback, handler)
        } catch (e: Exception) {
            warnOnce("the torch", e)
        }
    }

    private fun warnOnce(what: String, e: Throwable) {
        if (warned.add(what)) Log.w(TAG, "Android's state, $what: ${e.message ?: e.javaClass.simpleName}")
    }

    /** One part of the state; false (logged once) when Android refuses it. */
    private inline fun part(what: String, block: () -> Boolean): Boolean = try {
        block()
    } catch (e: SecurityException) {
        warnOnce(what, e)
        false
    } catch (e: RuntimeException) {
        warnOnce(what, e)
        false
    }

    fun read(): AndroidState {
        val s = AndroidState()
        s.wifiSsid = ""
        s.mobileOperator = ""
        s.mobileTech = ""
        s.bluetoothConnected = emptyArray()
        val cr = context.contentResolver
        s.wifiKnown = part("Wi-Fi") { readWifi(s) }
        s.mobileKnown = part("mobile data") { readMobile(s) }
        s.bluetoothKnown = part("Bluetooth") { readBluetooth(s) }
        s.airplaneKnown = part("airplane mode") {
            s.airplaneOn = Settings.Global.getInt(cr, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            true
        }
        s.hotspotKnown = part("the hotspot") {
            s.hotspotOn = Hidden.isWifiApEnabled(wifi)
            true
        }
        s.flashlightAvailable = torchId != null
        s.flashlightOn = torchOn
        s.batteryKnown = part("the battery") { readBattery(s) }
        s.rotationKnown = part("auto-rotate") {
            s.rotationAuto = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) != 0
            true
        }
        s.dndKnown = part("Do Not Disturb") {
            s.dndOn = Hidden.isManualDndOn(notifications)
            true
        }
        s.caffeineOn = Linux.caffeine(context)
        s.brightnessKnown = part("the brightness") { readBrightness(s) }
        s.transport = defaultTransport()
        return s
    }

    @Suppress("DEPRECATION") // getConnectionInfo: the SSID and RSSI without a location grant
    private fun readWifi(s: AndroidState): Boolean {
        val state = wifi.wifiState
        s.wifiOn = state == WifiManager.WIFI_STATE_ENABLED || state == WifiManager.WIFI_STATE_ENABLING
        if (state != WifiManager.WIFI_STATE_ENABLED) return true
        // The SSID is unredacted for a holder of NETWORK_SETTINGS.
        val info = wifi.connectionInfo ?: return true
        if (info.supplicantState != SupplicantState.COMPLETED) return true
        val raw = info.ssid
        s.wifiConnected = true
        s.wifiSsid = if (raw == null || raw == UNKNOWN_SSID) "" else raw.removeSurrounding("\"")
        s.wifiLevel = wifi.calculateSignalLevel(info.rssi).coerceIn(0, 4)
        return true
    }

    private fun readMobile(s: AndroidState): Boolean {
        if (!hasTelephony) return false
        val subId = SubscriptionManager.getDefaultDataSubscriptionId()
        val tm = if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) telephony.createForSubscriptionId(subId) else telephony
        for (slot in 0 until telephony.activeModemCount) {
            if (telephony.getSimState(slot) == TelephonyManager.SIM_STATE_READY) s.mobilePresent = true
        }
        if (!s.mobilePresent) return true
        s.mobileOn = tm.isDataEnabled
        s.mobileOperator = tm.networkOperatorName ?: ""
        s.mobileLevel = (tm.signalStrength?.level ?: 0).coerceIn(0, 4)
        s.mobileTech = techOf(tm.dataNetworkType)
        s.mobileConnected = tm.dataState == TelephonyManager.DATA_CONNECTED
        return true
    }

    /** The suffix of Plasma's network-mobile-<strength>-<tech> icons for a radio technology. */
    private fun techOf(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5g"
        TelephonyManager.NETWORK_TYPE_LTE, NETWORK_TYPE_LTE_CA -> "lte"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA -> "hspa"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "hsdpa"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "hsupa"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "umts"
        TelephonyManager.NETWORK_TYPE_EDGE -> "edge"
        TelephonyManager.NETWORK_TYPE_GPRS -> "gprs"
        else -> ""
    }

    private fun readBluetooth(s: AndroidState): Boolean {
        val adapter = bluetooth ?: return false
        val state = adapter.state
        s.bluetoothOn = state == BluetoothAdapter.STATE_ON || state == BluetoothAdapter.STATE_TURNING_ON
        if (state != BluetoothAdapter.STATE_ON) return true
        // The paired devices connected now (BLUETOOTH_CONNECT, granted by the image); without it
        // the list stays empty and the reason is logged once.
        val names = ArrayList<String>()
        try {
            for (d in adapter.bondedDevices.orEmpty()) {
                if (Hidden.isConnected(d)) names.add(d.alias ?: d.name ?: d.address)
            }
        } catch (e: SecurityException) {
            warnOnce("Bluetooth's devices", e)
        }
        s.bluetoothConnected = names.toTypedArray()
        return true
    }

    private fun readBattery(s: AndroidState): Boolean {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        s.batteryPlugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        s.batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            (status == BatteryManager.BATTERY_STATUS_FULL && s.batteryPlugged)
        s.batteryPercent = level * 100 / scale
        return true
    }

    private fun readBrightness(s: AndroidState): Boolean {
        val display = displays.getDisplay(Display.DEFAULT_DISPLAY) ?: return false
        val info = Hidden.brightnessInfo(display) ?: return false
        s.brightness = BrightnessCurve.toGamma(info[0], info[1], info[2])
        s.brightnessMax = BrightnessCurve.GAMMA_MAX
        return true
    }

    /** wifi, mobile, none or other: the default network's transport (a VPN reports its underlying ones). */
    private fun defaultTransport(): String {
        val caps = try {
            connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it) }
        } catch (e: RuntimeException) {
            warnOnce("the default network", e)
            null
        }
        return when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
        const val POLL_MS = 5000L

        // Hidden constants of broadcasts every app may receive.
        const val WIFI_AP_STATE_CHANGED_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val SIM_STATE_CHANGED_ACTION = "android.intent.action.SIM_STATE_CHANGED"
        const val UNKNOWN_SSID = "<unknown ssid>"
        const val NETWORK_TYPE_LTE_CA = 19
    }
}
