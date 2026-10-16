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

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.util.Log
import java.io.IOException
import java.util.concurrent.Executors

/**
 * NFC card reading for Linux (LNX-7) while a window of the app is in front: the card held to the
 * phone is named to lujarch_linux, and Linux's commands to it go through IsoDep.
 */
class NfcReader(private val activity: Activity) : NfcAdapter.ReaderCallback {
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var card: IsoDep? = null

    fun start() {
        val nfc = adapter ?: return
        nfc.enableReaderMode(
            activity, this,
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
        worker.execute {
            close()
            name(null)
        }
        worker.shutdown()
    }

    override fun onTagDiscovered(tag: Tag) {
        worker.execute {
            close()
            val iso = IsoDep.get(tag)
            if (iso == null) {
                Log.i(TAG, "nfc: a card without ISO-DEP")
                return@execute
            }
            try {
                iso.connect()
                iso.timeout = TIMEOUT_MS
                card = iso
                name("IsoDep " + tag.id.joinToString("") { "%02x".format(it) })
            } catch (e: IOException) {
                Log.w(TAG, "nfc: ${e.message}")
            }
        }
    }

    fun transceive(id: Int, command: ByteArray) {
        worker.execute {
            val iso = card
            if (iso == null) {
                LinuxActivity.answerNfc(id, ByteArray(0), "no card")
                return@execute
            }
            try {
                LinuxActivity.answerNfc(id, iso.transceive(command), null)
            } catch (e: TagLostException) {
                close()
                name(null)
                LinuxActivity.answerNfc(id, ByteArray(0), "card lost")
            } catch (e: IOException) {
                LinuxActivity.answerNfc(id, ByteArray(0), e.message ?: "io")
            }
        }
    }

    private fun close() {
        try {
            card?.close()
        } catch (e: IOException) {
            Log.w(TAG, "nfc: ${e.message}")
        }
        card = null
    }

    private fun name(value: String?) {
        try {
            Linux.blocking("setNfcCard") { it.setNfcCard(value) }
        } catch (e: LinuxException) {
            Log.w(TAG, e.message ?: "setNfcCard")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5000
    }
}
