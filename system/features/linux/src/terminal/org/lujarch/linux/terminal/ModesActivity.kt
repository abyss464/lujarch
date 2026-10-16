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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * The Mode Linux's own settings, the Linux development environment's page in Settings; the
 * tile's long press, the page of Remote access.
 */
class ModesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tile = intent.action == "android.service.quicksettings.action.QS_TILE_PREFERENCES"
        try {
            startActivity(Intent(if (tile) Linux.ACTION_REMOTE else Linux.ACTION_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "no page of the Linux development environment in Settings")
        }
        finish()
    }
}
