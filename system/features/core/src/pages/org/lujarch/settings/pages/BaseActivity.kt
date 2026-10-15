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

import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity

/** Collapsing-toolbar page hosting one preference fragment. */
abstract class BaseActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.page_container)
        pageTitle()?.let { setTitle(it) }
        if (savedInstanceState == null) {
            val fragment = createFragment()
            if (fragment == null) {
                Toast.makeText(this, R.string.error_page, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            // A Settings search result names the row to bring into view.
            intent.getStringExtra(EXTRA_HIGHLIGHT)?.let { key ->
                fragment.arguments = (fragment.arguments ?: Bundle()).apply { putString(EXTRA_HIGHLIGHT, key) }
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.page_container, fragment)
                .commit()
        }
    }

    protected abstract fun pageTitle(): CharSequence?

    protected abstract fun createFragment(): Fragment?

    companion object {
        /** What Settings search puts in the intent of a result: the key of the row it found. */
        const val EXTRA_HIGHLIGHT = ":settings:fragment_args_key"
    }
}
