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

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.window.OnBackInvokedDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lujarch.linux.ILinux

/**
 * The desktop of Arch Linux full screen (LNX-6): KWin in Linux draws into buffers this window
 * allocates and shows with SurfaceControl (NativeDisplay), with touch, a mouse, hardware keys and
 * the phone's sound going both ways; text comes from Linux's own on-screen keyboard. The desktop
 * is on screen while this window is resumed and focused; otherwise KWin draws nothing and
 * lujarch_linux freezes it. While it is on screen the Mode Linux is on and the status bar is
 * locked; a swipe from the bottom returns to Android, and back does nothing.
 */
class DesktopActivity : LinuxActivity() {
    private lateinit var surface: SurfaceView
    private lateinit var message: LinearLayout
    private lateinit var messageText: TextView
    private val main = Handler(Looper.getMainLooper())

    private var handle = 0L
    private var width = 0
    private var height = 0
    private var resumed = false
    private var shown = false
    private var attaching: Job? = null
    private var opening: Job? = null
    private var sound: Sound? = null

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            handle = NativeDisplay.nativeCreate(holder.surface)
            if (handle == 0L) Log.e(TAG, "desktop: no presenter on the surface")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            width = w
            height = h
            configure()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            val h = handle
            handle = 0L
            if (h != 0L) NativeDisplay.nativeDestroy(h)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        surface = SurfaceView(this)
        surface.holder.addCallback(holderCallback)
        frame.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        messageText = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        }
        message.addView(messageText)
        message.addView(Button(this).apply {
            setText(R.string.open_settings)
            setOnClickListener { openSettings() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })
        frame.addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(frame)

        surface.setOnTouchListener { _, e ->
            if (isMouse(e)) pointer(e) else touch(e)
            true
        }
        // A mouse's hover and wheel are generic motion, not touch.
        surface.setOnHoverListener { _, e -> isMouse(e).also { if (it) pointer(e) } }
        surface.setOnGenericMotionListener { _, e -> isMouse(e).also { if (it) pointer(e) } }
        // KWin draws the desktop's own pointer.
        surface.pointerIcon = PointerIcon.getSystemIcon(this, PointerIcon.TYPE_NULL)
        surface.isFocusable = true
        surface.isFocusableInTouchMode = true
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) {}
    }

    override fun onStart() {
        super.onStart()
        attaching = scope.launch { keepAttached() }
        opening = scope.launch {
            try {
                val fifo = Linux.call("openSound") { it.openSound() }
                sound = Sound().also { it.start(fifo) }
            } catch (e: LinuxException) {
                Log.i(TAG, "the desktop has no sound: ${e.message}")
            }
        }
    }

    override fun onStop() {
        attaching?.cancel()
        attaching = null
        opening?.cancel()
        opening = null
        sound?.close()
        sound = null
        main.removeCallbacks(leave)
        leave.run()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        LinuxMode.enter(this)
        updateShown()
    }

    override fun onPause() {
        resumed = false
        updateShown()
        LinuxMode.leave(this)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && resumed) LinuxMode.enter(this)
        updateShown()
        // Android's clipboard goes to Linux when the desktop comes back in front; Android lets
        // only the focused window read it.
        if (hasFocus && Linux.lent(this, "clipboard")) {
            val text = clipboardText() ?: return
            LinuxActivity.background.execute {
                try {
                    Linux.blocking("setClipboard") { it.setClipboard(text) }
                } catch (e: LinuxException) {
                    Log.w(TAG, e.message ?: "setClipboard")
                }
            }
        }
    }

    /** While started: a connection to the desktop, made again when it ends; why there is none. */
    private suspend fun keepAttached() {
        var since = 0L
        var failed: Int? = null
        while (true) {
            val h = handle
            if (h != 0L && !NativeDisplay.nativeAttached(h)) {
                try {
                    val connection = Linux.call("attachDisplay") { it.attachDisplay() }
                    if (handle == h) NativeDisplay.nativeAttach(h, connection.detachFd()) else connection.close()
                    since = System.currentTimeMillis()
                    failed = null
                    message.visibility = View.GONE
                } catch (e: LinuxException) {
                    // Once for each reason, not at every retry.
                    if (failed != e.code) Log.w(TAG, e.message ?: "attachDisplay")
                    failed = e.code
                    show(
                        when (e.code) {
                            ILinux.ERROR_NOT_RUNNING -> R.string.state_stopped
                            ILinux.ERROR_NO_DESKTOP -> R.string.desktop_none
                            0 -> R.string.service_missing
                            else -> R.string.desktop_failed
                        },
                    )
                }
            } else if (h != 0L && !NativeDisplay.nativeDrawn(h) && since > 0 &&
                System.currentTimeMillis() - since > SILENT_MS
            ) {
                show(R.string.desktop_silent)
            } else if (h != 0L && NativeDisplay.nativeDrawn(h)) {
                message.visibility = View.GONE
            }
            delay(RETRY_MS)
        }
    }

    private fun show(text: Int) {
        messageText.setText(text)
        message.visibility = View.VISIBLE
    }

    /** On screen: resumed and focused. */
    private fun updateShown() {
        val now = resumed && hasWindowFocus()
        if (now != shown) {
            shown = now
            configure()
        }
    }

    private fun configure() {
        val h = handle
        if (h == 0L || width == 0 || height == 0) return
        val d = display
        val refreshMhz = ((d?.refreshRate ?: 60f) * 1000f).toInt()
        NativeDisplay.nativeConfigure(h, width, height, d?.rotation ?: 0, resources.displayMetrics.densityDpi, refreshMhz, shown)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** One MotionEvent as one touch frame: the pointer that went down or up, or every pointer that moved. */
    private fun touch(e: MotionEvent) {
        val h = handle
        if (h == 0L) return
        val n = e.pointerCount
        val types = IntArray(n)
        val ids = IntArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var count = 0
        fun add(i: Int, type: Int) {
            types[count] = type
            ids[count] = e.getPointerId(i)
            xs[count] = e.getX(i)
            ys[count] = e.getY(i)
            count++
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> add(e.actionIndex, NativeDisplay.TOUCH_DOWN)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> add(e.actionIndex, NativeDisplay.TOUCH_UP)
            MotionEvent.ACTION_MOVE -> for (i in 0 until n) add(i, NativeDisplay.TOUCH_MOTION)
            MotionEvent.ACTION_CANCEL -> add(0, NativeDisplay.TOUCH_CANCEL)
            else -> return
        }
        NativeDisplay.nativeTouch(h, e.eventTimeNanos, count, types, ids, xs, ys)
    }

    private fun isMouse(e: MotionEvent) = e.isFromSource(InputDevice.SOURCE_MOUSE)

    // The pointer is in the desktop since its last event; a leave lets KWin drop its pointer device.
    private var pointerIn = false
    private var pointerX = 0f
    private var pointerY = 0f
    private val leave = Runnable {
        val h = handle
        if (pointerIn && h != 0L) {
            NativeDisplay.nativePointer(h, System.nanoTime(), pointerX, pointerY, 0, 0, 0, NativeDisplay.POINTER_LEAVE)
        }
        pointerIn = false
    }

    /** One mouse event as one pointer message: position, the buttons held, the wheel in 120ths of a detent. */
    private fun pointer(e: MotionEvent) {
        val h = handle
        if (h == 0L) return
        var v120 = 0
        var h120 = 0
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_EXIT -> {
                // A button press ends hovering too, and its down follows at once: only a leave
                // with nothing after it is the pointer leaving.
                main.removeCallbacks(leave)
                main.postDelayed(leave, LEAVE_DELAY_MS)
                return
            }
            MotionEvent.ACTION_SCROLL -> {
                // Android: up and right positive; the protocol: down and right positive.
                v120 = Math.round(-e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 120f)
                h120 = Math.round(e.getAxisValue(MotionEvent.AXIS_HSCROLL) * 120f)
                if (v120 == 0 && h120 == 0) return
            }
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {}
            else -> return
        }
        main.removeCallbacks(leave)
        pointerIn = true
        pointerX = e.x
        pointerY = e.y
        val buttons = if (e.actionMasked == MotionEvent.ACTION_CANCEL) 0 else e.buttonState and BUTTONS
        NativeDisplay.nativePointer(h, e.eventTimeNanos, e.x, e.y, buttons, v120, h120, 0)
    }

    /** Hardware keys with a scan code (a Linux key code) go to Linux; volume and power stay Android's. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val h = handle
        val android = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER -> true
            else -> false
        }
        if (h == 0L || android || event.scanCode <= 0 ||
            (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP)
        ) {
            return super.dispatchKeyEvent(event)
        }
        NativeDisplay.nativeKey(h, event.eventTime * 1_000_000L, event.scanCode, event.action == KeyEvent.ACTION_DOWN)
        return true
    }

    private companion object {
        const val RETRY_MS = 1000L
        /** Attached this long without KWin: the desktop does not draw. */
        const val SILENT_MS = 20_000L
        /** A hover exit that a press does not follow within this is the pointer leaving. */
        const val LEAVE_DELAY_MS = 100L
        /** Primary, secondary, tertiary, back, forward: MotionEvent's bits are the protocol's. */
        const val BUTTONS = 0x1f
    }
}
