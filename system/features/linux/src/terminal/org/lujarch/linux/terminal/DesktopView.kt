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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * The desktop's pixels, fitted to the screen, and the owner's touch and keys for it: a tap
 * clicks, a long press clicks the right button, one finger drags with the left button, two
 * fingers scroll; the keyboard types characters and keys.
 */
class DesktopView(context: Context) : View(context) {
    var rfb: Rfb? = null
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val toView = Matrix()
    private val toDesktop = Matrix()
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var downAt = intArrayOf(0, 0)
    private var scrollRemainder = 0f
    private var composing = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(0xff000000.toInt())
    }

    /** A new framebuffer; on the main thread. */
    fun setFramebuffer(width: Int, height: Int) {
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
        fit()
        invalidate()
    }

    /** Copies a changed rectangle, [pixels] row by row; on the main thread. */
    fun update(pixels: IntArray, x: Int, y: Int, w: Int, h: Int) {
        val b = bitmap ?: return
        if (x + w > b.width || y + h > b.height) return
        b.setPixels(pixels, 0, w, x, y, w, h)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fit()

    private fun fit() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        toView.setScale(scale, scale)
        toView.postTranslate((width - b.width * scale) / 2, (height - b.height * scale) / 2)
        toView.invert(toDesktop)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, toView, paint)
    }

    private fun desktopPoint(e: MotionEvent, index: Int = 0): IntArray {
        val p = floatArrayOf(e.getX(index), e.getY(index))
        toDesktop.mapPoints(p)
        return intArrayOf(p[0].toInt(), p[1].toInt())
    }

    private fun click(e: MotionEvent, button: Int) {
        val r = rfb ?: return
        val (x, y) = desktopPoint(e).let { it[0] to it[1] }
        r.pointer(0, x, y)
        r.pointer(button, x, y)
        r.pointer(0, x, y)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            click(e, BUTTON_LEFT)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            click(e, BUTTON_RIGHT)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val r = rfb
        if (event.pointerCount >= 2) {
            if (dragging) {
                val (x, y) = desktopPoint(event).let { it[0] to it[1] }
                r?.pointer(0, x, y)
                dragging = false
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE && event.historySize > 0) {
                scrollRemainder += event.getHistoricalY(0, event.historySize - 1) - event.getY(0)
                val step = SCROLL_STEP_DP * resources.displayMetrics.density
                val (x, y) = desktopPoint(event).let { it[0] to it[1] }
                while (scrollRemainder > step) {
                    r?.pointer(BUTTON_WHEEL_DOWN, x, y)
                    r?.pointer(0, x, y)
                    scrollRemainder -= step
                }
                while (scrollRemainder < -step) {
                    r?.pointer(BUTTON_WHEEL_UP, x, y)
                    r?.pointer(0, x, y)
                    scrollRemainder += step
                }
            }
            return true
        }
        gestures.onTouchEvent(event)
        val (x, y) = desktopPoint(event).let { it[0] to it[1] }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrollRemainder = 0f
                downX = event.x
                downY = event.y
                downAt = intArrayOf(x, y)
                r?.pointer(0, x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = DRAG_SLOP_DP * resources.displayMetrics.density
                if (!dragging && Math.hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > slop) {
                    dragging = true
                    r?.pointer(BUTTON_LEFT, downAt[0], downAt[1])
                }
                r?.pointer(if (dragging) BUTTON_LEFT else 0, x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) r?.pointer(0, x, y)
                dragging = false
            }
        }
        return true
    }

    // ---- keys ----

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = ""
                type(text.toString())
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                composing = text.toString()
                return true
            }

            override fun finishComposingText(): Boolean {
                if (composing.isNotEmpty()) type(composing)
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { press(Keysyms.BACKSPACE) }
                repeat(afterLength) { press(Keysyms.DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event) else onKeyUp(event.keyCode, event)
                return true
            }
        }
    }

    private fun type(text: String) {
        text.codePoints().forEach { press(Keysyms.ofCodePoint(it)) }
    }

    private fun press(keysym: Int) {
        rfb?.key(keysym, true)
        rfb?.key(keysym, false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = key(keyCode, event, true)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = key(keyCode, event, false)

    private fun key(keyCode: Int, event: KeyEvent, down: Boolean): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        var keysym = Keysyms.ofKey(keyCode)
        if (keysym == 0) {
            val c = event.getUnicodeChar(event.metaState and KeyEvent.META_SHIFT_MASK)
            if (c == 0) return false
            keysym = Keysyms.ofCodePoint(c)
        }
        rfb?.key(keysym, down)
        return true
    }

    private companion object {
        const val BUTTON_LEFT = 1
        const val BUTTON_RIGHT = 4
        const val BUTTON_WHEEL_UP = 8
        const val BUTTON_WHEEL_DOWN = 16
        const val SCROLL_STEP_DP = 24f
        const val DRAG_SLOP_DP = 8f
    }
}
