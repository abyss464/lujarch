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

package org.lujarch.settings.pages

import android.util.Log
import androidx.fragment.app.Fragment

/** Hosts any page of this app; not exported. */
class SubSettingsActivity : BaseActivity() {
    override fun pageTitle(): CharSequence? {
        intent.getStringExtra(Pages.EXTRA_TITLE)?.let { return it }
        val res = intent.getIntExtra(Pages.EXTRA_TITLE_RES, 0)
        return if (res != 0) getText(res) else null
    }

    override fun createFragment(): Fragment? {
        val name = intent.getStringExtra(Pages.EXTRA_FRAGMENT)
        // Only Lujarch's own pages: the activity is not exported, the check keeps it so.
        if (name == null || !name.startsWith("org.lujarch.")) {
            Log.e(TAG, "SubSettingsActivity started without a valid page: $name")
            return null
        }
        return try {
            supportFragmentManager.fragmentFactory.instantiate(classLoader, name).apply {
                arguments = intent.getBundleExtra(Pages.EXTRA_ARGUMENTS)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot create page $name", e)
            null
        }
    }
}
