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

package org.lujarch.storage.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.fragment.app.Fragment
import com.android.internal.app.LujarchStorage
import org.lujarch.settings.pages.BaseActivity

/** An app's Storage limit (STO-1), opened from its Storage & cache page in Settings. */
class StorageLimitActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.storage_limit_title)

    override fun createFragment(): Fragment? {
        val pkg = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        if (pkg == null || !PACKAGE.matches(pkg)) {
            Log.w(TAG, "${LujarchStorage.ACTION_STORAGE_LIMIT} without a valid ${Intent.EXTRA_PACKAGE_NAME}")
            return null
        }
        try {
            packageManager.getApplicationInfo(pkg, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "$pkg is not installed", e)
            return null
        }
        return StorageLimitFragment().apply { arguments = StorageLimitFragment.args(pkg) }
    }

    private companion object {
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
