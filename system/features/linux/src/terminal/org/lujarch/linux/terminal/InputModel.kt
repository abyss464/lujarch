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

import java.io.ByteArrayOutputStream

/** Terminal keys the key strip and the input connection send. */
enum class Key { ESC, TAB, UP, DOWN, LEFT, RIGHT, ENTER, BACKSPACE, DELETE }

/**
 * What an input method edits in the terminal, and the bytes that result (LNX-3). No Android dependencies.
 *
 * To the keyboard the terminal is a text field that holds only the composing
 * text: the shell's own line is not ours to edit, so the keyboard never sees
 * it. Composing text (pinyin, glide, a handwriting candidate) stays here, is
 * drawn inline by the view, and goes to the shell as UTF-8 when committed; the
 * composition then resets, so the shell's line editing and the keyboard's idea
 * of the text cannot drift apart. Deletes beyond the composing text become
 * Backspace, cursor moves beyond it become arrow keys.
 *
 * Ctrl and Alt are latched by the key strip for the next key: the first
 * character of the next commit (or of composing text, which is then sent at
 * once: nobody composes with Ctrl held), or the next key.
 *
 * [send] receives the bytes; [applicationCursorKeys] says whether the
 * program set DECCKM (vim and less do), which changes the arrow keys.
 */
class InputModel(
    private val send: (ByteArray) -> Unit,
    private val applicationCursorKeys: () -> Boolean = { false },
) {
    /** The composing text, drawn inline; never sent before it is committed. */
    var composing: String = ""
        private set

    /** The cursor inside [composing], a UTF-16 index in 0..length. */
    var composingCursor: Int = 0
        private set

    var ctrlLatched: Boolean = false
        private set
    var altLatched: Boolean = false
        private set

    /** Called whenever what the view draws (composition, latches) changes. */
    var onChanged: (() -> Unit)? = null

    private var resync = false

    /**
     * True once after an operation that leaves the keyboard's idea of the
     * text stale (composing text sent at once, a cursor move that went to the
     * shell): the view then restarts the input connection.
     */
    fun consumeResync(): Boolean {
        val r = resync
        resync = false
        return r
    }

    /** The cursor the keyboard is told: its position inside the composing text. */
    val reportedSelection: Int get() = composingCursor

    fun textBeforeCursor(n: Int): String = composing.substring(maxOf(0, composingCursor - n), composingCursor)

    fun textAfterCursor(n: Int): String = composing.substring(composingCursor, minOf(composing.length, composingCursor + n))

    // --- latches -------------------------------------------------------

    fun toggleCtrl() {
        ctrlLatched = !ctrlLatched
        onChanged?.invoke()
    }

    fun toggleAlt() {
        altLatched = !altLatched
        onChanged?.invoke()
    }

    fun clearLatches() {
        if (!ctrlLatched && !altLatched) return
        ctrlLatched = false
        altLatched = false
        onChanged?.invoke()
    }

    // --- the input connection ------------------------------------------

    /** InputConnection.setComposingText: replaces the composing text. */
    fun setComposingText(text: CharSequence, newCursorPosition: Int) {
        val t = text.toString()
        if (t.isEmpty()) {
            setComposing("", 0)
            return
        }
        if ((ctrlLatched || altLatched) && composing.isEmpty()) {
            sendText(t)
            resync = true
            onChanged?.invoke()
            return
        }
        // "cursor = start + length + (n - 1)" for n > 0, "start + n" otherwise, the region starting at 0.
        val cursor = if (newCursorPosition > 0) t.length + newCursorPosition - 1 else newCursorPosition
        setComposing(t, cursor.coerceIn(0, t.length))
    }

    /** InputConnection.commitText: the text replaces the composition and goes to the shell. */
    fun commitText(text: CharSequence) {
        setComposing("", 0)
        if (text.isNotEmpty()) sendText(text.toString())
        onChanged?.invoke()
    }

    /** Drops the composing text without sending it (the shown session changed). */
    fun cancelComposing() {
        if (composing.isEmpty()) return
        setComposing("", 0)
        resync = true
    }

    /** InputConnection.finishComposingText: what is composed goes to the shell as typed. */
    fun finishComposingText() {
        if (composing.isEmpty()) return
        val t = composing
        setComposing("", 0)
        sendText(t)
        onChanged?.invoke()
    }

    /** InputConnection.deleteSurroundingText (UTF-16 units). */
    fun deleteSurroundingText(before: Int, after: Int) = deleteSurrounding(before, after, false)

    /** InputConnection.deleteSurroundingTextInCodePoints. */
    fun deleteSurroundingTextInCodePoints(before: Int, after: Int) = deleteSurrounding(before, after, true)

    private fun deleteSurrounding(before: Int, after: Int, codePoints: Boolean) {
        var b = maxOf(0, before)
        var a = maxOf(0, after)
        if (composing.isNotEmpty()) {
            var start = composingCursor
            var end = composingCursor
            while (b > 0 && start > 0) {
                start -= if (codePoints) Character.charCount(composing.codePointBefore(start)) else 1
                b--
            }
            while (a > 0 && end < composing.length) {
                end += if (codePoints) Character.charCount(composing.codePointAt(end)) else 1
                a--
            }
            // A unit-wise delete must not split a surrogate pair.
            if (start > 0 && Character.isLowSurrogate(composing[start]) && Character.isHighSurrogate(composing[start - 1])) start--
            if (end < composing.length && end > 0 && Character.isLowSurrogate(composing[end]) && Character.isHighSurrogate(composing[end - 1])) end++
            val rest = composing.substring(0, start) + composing.substring(minOf(end, composing.length))
            setComposing(rest, start.coerceIn(0, rest.length))
        }
        // The rest is the shell's own line: one Backspace per character before the cursor, one Delete after.
        repeat(b) { send(BACKSPACE) }
        repeat(a) { send(DELETE_SEQ) }
        if (b > 0 || a > 0) resync = true
    }

    /**
     * InputConnection.setSelection: inside the composing text it moves the
     * composing cursor; what falls outside moves the shell's cursor, one arrow
     * key per character.
     */
    fun setSelection(start: Int) {
        val len = composing.length
        val overflow = when {
            start < 0 -> start
            start > len -> start - len
            else -> 0
        }
        if (composing.isNotEmpty()) {
            composingCursor = start.coerceIn(0, len)
            onChanged?.invoke()
        }
        if (overflow != 0) {
            val arrow = arrow(if (overflow < 0) Key.LEFT else Key.RIGHT, 0)
            repeat(kotlin.math.abs(overflow)) { send(arrow) }
            resync = true
        }
    }

    // --- keys (the key strip, the editor action) --------------------------

    /** A terminal key: the strip's, or Enter from the keyboard's action key. */
    fun key(key: Key) {
        if (composing.isNotEmpty()) {
            when (key) {
                Key.BACKSPACE -> { deleteSurroundingText(1, 0); return }
                Key.LEFT -> { setSelection(composingCursor - 1); return }
                Key.RIGHT -> { setSelection(composingCursor + 1); return }
                else -> { finishComposingText(); resync = true }
            }
        }
        val mod = 1 + (if (altLatched) 2 else 0) + (if (ctrlLatched) 4 else 0)
        val bytes: ByteArray = when (key) {
            Key.UP, Key.DOWN, Key.LEFT, Key.RIGHT -> arrow(key, mod)
            Key.DELETE -> if (mod == 1) DELETE_SEQ else "\u001b[3;$mod~".toByteArray()
            Key.ESC -> withAlt(byteArrayOf(0x1b))
            Key.TAB -> withAlt(byteArrayOf(0x09))
            Key.ENTER -> withAlt(byteArrayOf(0x0d))
            Key.BACKSPACE -> withAlt(BACKSPACE)
        }
        send(bytes)
        clearLatches()
    }

    private fun withAlt(b: ByteArray): ByteArray = if (altLatched) byteArrayOf(0x1b) + b else b

    /**
     * An arrow key: `ESC [ A` normally, `ESC O A` while the program has
     * application cursor keys set, `ESC [ 1 ; m A` with modifiers (xterm's m:
     * 1 + Alt 2 + Ctrl 4).
     */
    private fun arrow(key: Key, mod: Int): ByteArray {
        val c = when (key) {
            Key.UP -> 'A'
            Key.DOWN -> 'B'
            Key.RIGHT -> 'C'
            else -> 'D'
        }
        return when {
            mod > 1 -> "\u001b[1;$mod$c"
            applicationCursorKeys() -> "\u001bO$c"
            else -> "\u001b[$c"
        }.toByteArray()
    }

    // --- internals -----------------------------------------------------

    private fun setComposing(text: String, cursor: Int) {
        if (text == composing && cursor == composingCursor) return
        composing = text
        composingCursor = cursor
        onChanged?.invoke()
    }

    /**
     * UTF-8 of [text] with the latches applied to its first character; a
     * newline is Enter (CR).
     */
    private fun sendText(text: String) {
        val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
        val out = ByteArrayOutputStream(normalized.length * 3)
        val first = normalized.codePointAt(0)
        val firstLen = Character.charCount(first)
        if (altLatched) out.write(0x1b)
        val ctrlByte = if (ctrlLatched) controlCode(first) else -1
        if (ctrlByte >= 0) out.write(ctrlByte) else out.write(String(Character.toChars(first)).toByteArray(Charsets.UTF_8))
        if (firstLen < normalized.length) out.write(normalized.substring(firstLen).toByteArray(Charsets.UTF_8))
        send(out.toByteArray())
        clearLatches()
    }

    companion object {
        private val BACKSPACE = byteArrayOf(0x7f)
        private val DELETE_SEQ = "\u001b[3~".toByteArray()

        /** The control code of Ctrl+[cp], as Termux's view maps it, or -1 when there is none. */
        fun controlCode(cp: Int): Int = when {
            cp in 'a'.code..'z'.code -> cp - 'a'.code + 1
            cp in 'A'.code..'Z'.code -> cp - 'A'.code + 1
            cp == ' '.code || cp == '2'.code || cp == '@'.code -> 0
            cp == '['.code || cp == '3'.code -> 27
            cp == '\\'.code || cp == '4'.code -> 28
            cp == ']'.code || cp == '5'.code -> 29
            cp == '^'.code || cp == '6'.code -> 30
            cp == '_'.code || cp == '7'.code || cp == '/'.code -> 31
            cp == '8'.code -> 127
            else -> -1
        }
    }
}
