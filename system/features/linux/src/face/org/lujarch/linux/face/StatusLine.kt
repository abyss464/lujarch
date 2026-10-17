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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.system.Os
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/**
 * Linux's own status line where Android's status bar was (LNX-3): user@host on the left; the
 * default network's interface, the battery and the time on the right, read from Android.
 */
class StatusLine(context: Context) : LinearLayout(context) {
    private val identity = text(primary = true)
    private val net = text(primary = false)
    private val battery = text(primary = false)
    private val time = text(primary = true)
    private val main = Handler(Looper.getMainLooper())
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var registered = false
    private var iface: String? = null
    private var user: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) showBattery(intent) else showTime()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            main.post { iface = lp.interfaceName; showNet() }
        }

        override fun onLost(network: Network) {
            main.post { iface = null; showNet() }
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(16)
        setPadding(pad, 0, pad, 0)
        addView(identity)
        addView(View(context), LayoutParams(0, 1, 1f))
        for (v in listOf(net, battery, time)) {
            addView(v, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(14)
            })
        }
        showIdentity()
        showNet()
        showTime()
    }

    /** The owner's user in Linux, from lujarch_linux's status. */
    fun setUser(name: String?) {
        user = name
        showIdentity()
    }

    fun setColor(color: Int) {
        for (v in listOf(identity, net, battery, time)) v.setTextColor(color)
    }

    fun start() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)?.let { showBattery(it) }
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "status line: network callback: ${e.message}")
        }
        showTime()
    }

    fun stop() {
        if (!registered) return
        registered = false
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun showIdentity() {
        identity.text = "${user ?: "user"}@${Os.uname().nodename}"
    }

    private fun showNet() {
        net.text = iface ?: context.getString(R.string.status_offline)
    }

    private fun showBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        battery.text = "${level * 100 / scale}%" + if (charging) "+" else ""
    }

    private fun showTime() {
        time.text = DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", Date())
    }

    private fun text(primary: Boolean) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
        typeface = Typeface.MONOSPACE
        isSingleLine = true
        alpha = if (primary) 1f else 0.7f
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        // Android's status bar text size: the line stands where that bar would be.
        const val TEXT_SP = 14f
    }
}
