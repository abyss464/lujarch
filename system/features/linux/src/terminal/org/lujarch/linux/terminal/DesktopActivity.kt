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

import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.launch
import org.lujarch.linux.ILinux

/**
 * The desktop of Arch Linux full screen (LNX-6): the desktop Linux serves, with touch, the
 * keyboard and the phone's sound. It draws only while it is on screen; the connection ends when
 * it leaves the screen. While it is on screen the Mode Linux is on and the status bar is locked;
 * a swipe from the bottom returns to Android.
 */
class DesktopActivity : LinuxActivity(), Rfb.Listener {
    private lateinit var desktop: DesktopView
    private lateinit var message: LinearLayout
    private lateinit var messageText: TextView
    private lateinit var keyboard: ImageButton
    private var rfb: Rfb? = null
    private var sound: Sound? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val frame = FrameLayout(this)
        desktop = DesktopView(this)
        frame.addView(desktop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        messageText = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(0xffffffff.toInt())
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        }
        message.addView(messageText)
        message.addView(Button(this).apply {
            setText(R.string.open_terminal)
            setOnClickListener {
                startActivity(Intent(this@DesktopActivity, TerminalActivity::class.java))
                finish()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        frame.addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        keyboard = ImageButton(this).apply {
            setImageResource(R.drawable.ic_keyboard)
            contentDescription = getString(R.string.keyboard)
            alpha = 0.5f
            setBackgroundResource(0)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener {
                desktop.requestFocus()
                getSystemService(InputMethodManager::class.java)?.showSoftInput(desktop, 0)
            }
        }
        frame.addView(keyboard, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))
        setContentView(frame)
    }

    override fun onStart() {
        super.onStart()
        connect()
    }

    override fun onStop() {
        disconnect()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        LinuxMode.enter(this)
    }

    override fun onPause() {
        LinuxMode.leave(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) LinuxMode.enter(this)
    }

    private fun connect() {
        disconnect()
        desktop.post {
            val w = desktop.width
            val h = desktop.height
            scope.launch {
                val connection = try {
                    Linux.call("openDesktop") { it.openDesktop() }
                } catch (e: LinuxException) {
                    Log.w(TAG, e.message ?: "openDesktop")
                    show(
                        when (e.code) {
                            ILinux.ERROR_NOT_RUNNING -> R.string.state_stopped
                            ILinux.ERROR_NO_DESKTOP -> R.string.desktop_none
                            0 -> R.string.service_missing
                            else -> R.string.desktop_failed
                        },
                    )
                    return@launch
                }
                message.visibility = View.GONE
                val client = Rfb(this@DesktopActivity)
                rfb = client
                desktop.rfb = client
                client.start(connection, w, h)
                try {
                    val pcm = Linux.call("openSound") { it.openSound() }
                    sound = Sound().also { it.start(pcm) }
                } catch (e: LinuxException) {
                    Log.i(TAG, "the desktop has no sound: ${e.message}")
                }
            }
        }
    }

    private fun disconnect() {
        rfb?.close()
        rfb = null
        desktop.rfb = null
        sound?.close()
        sound = null
    }

    private fun show(text: Int) {
        messageText.setText(text)
        message.visibility = View.VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    // ---- Rfb.Listener, from the connection's thread ----

    override fun onFramebuffer(width: Int, height: Int) {
        runOnUiThread { desktop.setFramebuffer(width, height) }
    }

    override fun onUpdate(pixels: IntArray, x: Int, y: Int, width: Int, height: Int) {
        val client = rfb ?: return
        val stride = client.width
        val copy = IntArray(width * height)
        for (j in 0 until height) System.arraycopy(pixels, (y + j) * stride + x, copy, j * width, width)
        runOnUiThread { desktop.update(copy, x, y, width, height) }
    }

    override fun onClipboard(text: String) {
        runOnUiThread { copyToClipboard(text, fromLinux = true) }
    }

    override fun onBell() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    override fun onClosed(error: String?) {
        runOnUiThread {
            if (error != null) {
                Log.w(TAG, "desktop: $error")
                show(R.string.desktop_failed)
            }
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        // The Android clipboard goes to Linux when the desktop comes back in front.
        if (isTopResumedActivity && Linux.lent(this, "clipboard")) {
            clipboardText()?.let { rfb?.clipboard(it) }
        }
    }
}
