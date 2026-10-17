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

/** One character of the composing text on the terminal's grid. */
data class ComposingCell(val codePoint: Int, val col: Int, val row: Int, val width: Int)

/** The composing text laid out from the cursor, and where its own cursor is. */
data class ComposingLayoutResult(val cells: List<ComposingCell>, val cursorCol: Int, val cursorRow: Int)

/**
 * Lays composing text out on the terminal grid from the cursor cell: wide
 * (CJK) characters take two cells and never straddle the right edge, a
 * character that does not fit starts the next row, combining characters
 * (width 0) sit on the cell before them. [widthOf] is the emulator's wcwidth.
 */
object ComposingLayout {
    fun layout(
        text: String,
        cursorIndex: Int,
        startCol: Int,
        startRow: Int,
        columns: Int,
        widthOf: (Int) -> Int,
    ): ComposingLayoutResult {
        val cells = ArrayList<ComposingCell>(text.length)
        var col = startCol
        var row = startRow
        var cursorCol = startCol
        var cursorRow = startRow
        var i = 0
        while (i <= text.length) {
            if (i == cursorIndex) {
                // A cursor past the last column is on the next row.
                if (col >= columns) {
                    cursorCol = 0
                    cursorRow = row + 1
                } else {
                    cursorCol = col
                    cursorRow = row
                }
            }
            if (i == text.length) break
            val cp = text.codePointAt(i)
            val w = widthOf(cp).coerceIn(0, 2)
            if (w == 0) {
                val last = cells.lastOrNull()
                cells.add(ComposingCell(cp, last?.col ?: col, last?.row ?: row, 0))
            } else {
                if (col + w > columns) {
                    col = 0
                    row++
                    if (i == cursorIndex) {
                        cursorCol = col
                        cursorRow = row
                    }
                }
                cells.add(ComposingCell(cp, col, row, w))
                col += w
            }
            i += Character.charCount(cp)
        }
        return ComposingLayoutResult(cells, cursorCol, cursorRow)
    }
}
