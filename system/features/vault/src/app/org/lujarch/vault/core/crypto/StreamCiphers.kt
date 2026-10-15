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

package org.lujarch.vault.core.crypto

import org.lujarch.vault.core.Bytes

/**
 * Keystream generators used by KeePass: ChaCha20 (RFC 8439 layout: 96-bit
 * nonce, 32-bit block counter starting at 0) for the KDBX 4 payload and inner
 * stream, Salsa20/20 (64-bit nonce and counter) for the KDBX 3.1 inner
 * stream. Implemented here because the JCA names differ between Android and
 * the host JVM, and Salsa20 exists in neither.
 */
abstract class StreamCipher {
    private val block = ByteArray(64)
    private var used = 64

    protected abstract fun nextBlock(out: ByteArray)

    /** XORs [len] bytes of [data] from [off] with the keystream, in place. */
    fun xorInPlace(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        var i = off
        val end = off + len
        while (i < end) {
            if (used == 64) {
                nextBlock(block)
                used = 0
            }
            data[i] = (data[i].toInt() xor block[used].toInt()).toByte()
            used++
            i++
        }
    }

    open fun wipe() {
        block.fill(0)
        used = 64
    }
}

class ChaCha20(key: ByteArray, nonce: ByteArray, counter: Int = 0) : StreamCipher() {
    private val state = IntArray(16)
    private val work = IntArray(16)

    init {
        require(key.size == 32) { "ChaCha20 key must be 32 bytes" }
        require(nonce.size == 12) { "ChaCha20 nonce must be 12 bytes" }
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = Bytes.i32le(key, 4 * i)
        state[12] = counter
        for (i in 0 until 3) state[13 + i] = Bytes.i32le(nonce, 4 * i)
    }

    override fun nextBlock(out: ByteArray) {
        val x = work
        state.copyInto(x)
        repeat(10) {
            quarter(x, 0, 4, 8, 12)
            quarter(x, 1, 5, 9, 13)
            quarter(x, 2, 6, 10, 14)
            quarter(x, 3, 7, 11, 15)
            quarter(x, 0, 5, 10, 15)
            quarter(x, 1, 6, 11, 12)
            quarter(x, 2, 7, 8, 13)
            quarter(x, 3, 4, 9, 14)
        }
        for (i in 0 until 16) putLe(out, 4 * i, x[i] + state[i])
        state[12]++
    }

    override fun wipe() {
        super.wipe()
        state.fill(0)
        work.fill(0)
    }

    private fun quarter(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 16)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 12)
        x[a] += x[b]; x[d] = Integer.rotateLeft(x[d] xor x[a], 8)
        x[c] += x[d]; x[b] = Integer.rotateLeft(x[b] xor x[c], 7)
    }
}

class Salsa20(key: ByteArray, nonce: ByteArray) : StreamCipher() {
    private val state = IntArray(16)
    private val work = IntArray(16)

    init {
        require(key.size == 32) { "Salsa20 key must be 32 bytes" }
        require(nonce.size == 8) { "Salsa20 nonce must be 8 bytes" }
        state[0] = 0x61707865
        for (i in 0 until 4) state[1 + i] = Bytes.i32le(key, 4 * i)
        state[5] = 0x3320646e
        state[6] = Bytes.i32le(nonce, 0)
        state[7] = Bytes.i32le(nonce, 4)
        state[8] = 0
        state[9] = 0
        state[10] = 0x79622d32
        for (i in 0 until 4) state[11 + i] = Bytes.i32le(key, 16 + 4 * i)
        state[15] = 0x6b206574
    }

    override fun nextBlock(out: ByteArray) {
        salsaCore(state, work, 20)
        for (i in 0 until 16) putLe(out, 4 * i, work[i])
        state[8]++
        if (state[8] == 0) state[9]++
    }

    override fun wipe() {
        super.wipe()
        state.fill(0)
        work.fill(0)
    }

    companion object {
        /** Salsa20 core with [rounds] rounds; [out] receives input + permuted words. */
        fun salsaCore(input: IntArray, out: IntArray, rounds: Int) {
            val x = out
            input.copyInto(x)
            var i = 0
            while (i < rounds) {
                x[4] = x[4] xor Integer.rotateLeft(x[0] + x[12], 7)
                x[8] = x[8] xor Integer.rotateLeft(x[4] + x[0], 9)
                x[12] = x[12] xor Integer.rotateLeft(x[8] + x[4], 13)
                x[0] = x[0] xor Integer.rotateLeft(x[12] + x[8], 18)
                x[9] = x[9] xor Integer.rotateLeft(x[5] + x[1], 7)
                x[13] = x[13] xor Integer.rotateLeft(x[9] + x[5], 9)
                x[1] = x[1] xor Integer.rotateLeft(x[13] + x[9], 13)
                x[5] = x[5] xor Integer.rotateLeft(x[1] + x[13], 18)
                x[14] = x[14] xor Integer.rotateLeft(x[10] + x[6], 7)
                x[2] = x[2] xor Integer.rotateLeft(x[14] + x[10], 9)
                x[6] = x[6] xor Integer.rotateLeft(x[2] + x[14], 13)
                x[10] = x[10] xor Integer.rotateLeft(x[6] + x[2], 18)
                x[3] = x[3] xor Integer.rotateLeft(x[15] + x[11], 7)
                x[7] = x[7] xor Integer.rotateLeft(x[3] + x[15], 9)
                x[11] = x[11] xor Integer.rotateLeft(x[7] + x[3], 13)
                x[15] = x[15] xor Integer.rotateLeft(x[11] + x[7], 18)
                x[1] = x[1] xor Integer.rotateLeft(x[0] + x[3], 7)
                x[2] = x[2] xor Integer.rotateLeft(x[1] + x[0], 9)
                x[3] = x[3] xor Integer.rotateLeft(x[2] + x[1], 13)
                x[0] = x[0] xor Integer.rotateLeft(x[3] + x[2], 18)
                x[6] = x[6] xor Integer.rotateLeft(x[5] + x[4], 7)
                x[7] = x[7] xor Integer.rotateLeft(x[6] + x[5], 9)
                x[4] = x[4] xor Integer.rotateLeft(x[7] + x[6], 13)
                x[5] = x[5] xor Integer.rotateLeft(x[4] + x[7], 18)
                x[11] = x[11] xor Integer.rotateLeft(x[10] + x[9], 7)
                x[8] = x[8] xor Integer.rotateLeft(x[11] + x[10], 9)
                x[9] = x[9] xor Integer.rotateLeft(x[8] + x[11], 13)
                x[10] = x[10] xor Integer.rotateLeft(x[9] + x[8], 18)
                x[12] = x[12] xor Integer.rotateLeft(x[15] + x[14], 7)
                x[13] = x[13] xor Integer.rotateLeft(x[12] + x[15], 9)
                x[14] = x[14] xor Integer.rotateLeft(x[13] + x[12], 13)
                x[15] = x[15] xor Integer.rotateLeft(x[14] + x[13], 18)
                i += 2
            }
            for (j in 0 until 16) x[j] += input[j]
        }
    }
}

private fun putLe(out: ByteArray, off: Int, v: Int) {
    out[off] = v.toByte()
    out[off + 1] = (v ushr 8).toByte()
    out[off + 2] = (v ushr 16).toByte()
    out[off + 3] = (v ushr 24).toByte()
}
