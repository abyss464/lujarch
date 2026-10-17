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

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger

/**
 * The parts of the phone Linux is lent (LNX-7), served while the home screen shows Linux and keeps
 * this bound with its capabilities, so the microphone, the cameras and the location count as in
 * use by this app while Linux is on screen. The home screen tells it when Linux is in front, for
 * the Mode Linux (LNX-6). Only a holder of the status bar permission (the home screen) binds it.
 */
class LinuxPartsService : Service() {
    private var parts: LinuxParts? = null

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_MODE -> LinuxMode.set(this@LinuxPartsService, msg.arg1 == 1)
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        LinuxWindows.start()
        parts = LinuxParts(this).also { it.start() }
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        LinuxMode.set(this, false)
        LinuxWindows.stop()
        parts?.stop()
        parts = null
        super.onDestroy()
    }

    private companion object {
        const val MSG_MODE = 1
    }
}
