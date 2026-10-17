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

package org.lujarch.linux.face

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import com.termux.view.TerminalView

/**
 * Termux's cell renderer, scrolling and selection, with the phone keyboard as a text editor
 * (LNX-3): the keyboard composes, glides and types pinyin as in any text field, [InputModel] turns
 * what it does into the shell's bytes, and the composing text is drawn here, inline at the cursor
 * and underlined, and reaches the shell only when committed.
 */
class ComposingTerminalView(context: Context, val model: InputModel) : TerminalView(context, null) {
    private val imm = context.getSystemService(InputMethodManager::class.java)
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // ---- the input connection ----

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A text editor, not TYPE_NULL (which turns off the keyboard's composition, glide and
        // pinyin), without suggestions; with IME_FLAG_NO_ENTER_ACTION, Enter is an ordinary key.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION
        outAttrs.initialSelStart = model.reportedSelection
        outAttrs.initialSelEnd = model.reportedSelection
        return TerminalInputConnection()
    }

    /** Tells the keyboard where its cursor is after the model changed under it. */
    private fun syncIme() {
        val sel = model.reportedSelection
        val compEnd = model.composing.length
        imm.updateSelection(this, sel, sel, if (compEnd > 0) 0 else -1, if (compEnd > 0) compEnd else -1)
        if (model.consumeResync()) post { imm.restartInput(this) }
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@ComposingTerminalView, false) {
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = model.textBeforeCursor(n)

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = model.textAfterCursor(n)

        override fun getSelectedText(flags: Int): CharSequence? = null

        override fun getCursorCapsMode(reqModes: Int): Int = 0

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            stopTextSelectionMode()
            model.setComposingText(text, newCursorPosition)
            syncIme()
            return true
        }

        // The editor holds only the composition: there is nothing outside it to recompose.
        override fun setComposingRegion(start: Int, end: Int): Boolean = true

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            stopTextSelectionMode()
            model.commitText(text)
            syncIme()
            return true
        }

        override fun finishComposingText(): Boolean {
            model.finishComposingText()
            syncIme()
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            model.deleteSurroundingText(beforeLength, afterLength)
            syncIme()
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
            model.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
            syncIme()
            return true
        }

        override fun setSelection(start: Int, end: Int): Boolean {
            model.setSelection(start)
            syncIme()
            return true
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            model.key(Key.ENTER)
            syncIme()
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val key = when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> Key.BACKSPACE
                KeyEvent.KEYCODE_FORWARD_DEL -> Key.DELETE
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.ENTER
                KeyEvent.KEYCODE_DPAD_UP -> Key.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
                KeyEvent.KEYCODE_TAB -> Key.TAB
                KeyEvent.KEYCODE_ESCAPE -> Key.ESC
                else -> null
            }
            // Keys the model knows go through it, so a composition and the latches hold; the rest,
            // with their meta state, through Termux's key handling. Auto-repeats count.
            if (key != null && (event.metaState and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON)) == 0) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    stopTextSelectionMode()
                    model.key(key)
                    syncIme()
                }
                return true
            }
            return super.sendKeyEvent(event)
        }

        override fun performContextMenuAction(id: Int): Boolean {
            if (id == android.R.id.paste) {
                val cm = context.getSystemService(ClipboardManager::class.java)
                val text = cm?.primaryClip?.getItemAt(0)?.coerceToText(context)
                if (!text.isNullOrEmpty()) mEmulator?.paste(text.toString())
                return true
            }
            return false
        }

        override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false

        override fun closeConnection() {
            model.finishComposingText()
            super.closeConnection()
        }
    }

    /** Drops what was composing and has the keyboard start over (the shown session changed). */
    fun resetInput() {
        model.cancelComposing()
        model.consumeResync()
        imm.restartInput(this)
        invalidate()
    }

    // ---- keys ----

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val handled = super.onKeyDown(keyCode, event)
        // Termux reads the latches several times per key: they are cleared once the key is done.
        if (!isModifierKey(keyCode)) model.clearLatches()
        return handled
    }

    private fun isModifierKey(keyCode: Int) = when (keyCode) {
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
        KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_FUNCTION -> true
        else -> false
    }

    // ---- the composing text, inline at the cursor ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawComposing(canvas)
    }

    private fun drawComposing(canvas: Canvas) {
        val text = model.composing
        val em = mEmulator ?: return
        val r = mRenderer ?: return
        if (text.isEmpty()) return

        val cursorRow = em.cursorRow - topRow
        val layout = ComposingLayout.layout(text, model.composingCursor, em.cursorCol, cursorRow, em.mColumns) { WcWidth.width(it) }
        val colors = em.mColors.mCurrentColors
        val fg = colors[TextStyle.COLOR_INDEX_FOREGROUND]
        val bg = colors[TextStyle.COLOR_INDEX_BACKGROUND]
        val accent = colors[TextStyle.COLOR_INDEX_CURSOR]
        val cw = r.fontWidth
        val spacing = r.fontLineSpacing
        val ascent = r.fontAscent
        val underlineGap = resources.displayMetrics.density * 2f
        val stroke = maxOf(1f, resources.displayMetrics.density * 1.5f)

        overlayPaint.typeface = r.typeface
        overlayPaint.textSize = r.textSize.toFloat()
        // The background first, so the shell's own cursor and any text under the composition are covered.
        for (cell in layout.cells) {
            if (cell.width == 0) continue
            val left = cell.col * cw
            val baseline = ((cell.row + 1) * spacing).toFloat()
            overlayPaint.color = bg
            canvas.drawRect(left, baseline + ascent, left + cell.width * cw, baseline + ascent + spacing, overlayPaint)
        }
        for (cell in layout.cells) {
            val left = cell.col * cw
            val baseline = ((cell.row + 1) * spacing).toFloat()
            overlayPaint.color = fg
            canvas.drawText(String(Character.toChars(cell.codePoint)), left, baseline, overlayPaint)
            if (cell.width > 0) {
                overlayPaint.color = accent
                overlayPaint.strokeWidth = stroke
                val y = baseline + underlineGap
                canvas.drawLine(left, y, left + cell.width * cw, y, overlayPaint)
            }
        }
        // The composing cursor, a thin bar.
        val bx = layout.cursorCol * cw
        val by = ((layout.cursorRow + 1) * spacing).toFloat()
        overlayPaint.color = accent
        overlayPaint.strokeWidth = stroke
        canvas.drawLine(bx, by + ascent, bx, by + ascent + spacing, overlayPaint)
    }
}
