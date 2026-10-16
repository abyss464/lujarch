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

package org.lujarch.vault.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import org.lujarch.vault.R
import org.lujarch.vault.TAG
import org.lujarch.vault.data.Vault

/**
 * Collapsing-toolbar page hosting one preference fragment, as in Settings.
 * FLAG_SECURE keeps passwords and codes out of screenshots and the recents
 * thumbnail. A started page holds the vault session: the decrypted vault
 * exists while at least one page is visible (a new page starts before the
 * previous one stops, so moving between pages keeps it) and is wiped when
 * the last one stops: home, another app, screen off.
 */
abstract class BaseActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(false)
        setContentView(R.layout.page_container)
        pageTitle()?.let { setTitle(it) }
        if (savedInstanceState == null) {
            val fragment = createFragment()
            if (fragment == null) {
                Toast.makeText(this, R.string.error_page, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            supportFragmentManager.beginTransaction()
                .replace(R.id.page_container, fragment)
                .commit()
        }
    }

    override fun onStart() {
        super.onStart()
        Vault.acquire("page visible")
    }

    override fun onResume() {
        super.onResume()
        Vault.checkForChanges()
    }

    override fun onStop() {
        super.onStop()
        Vault.release()
    }

    protected abstract fun pageTitle(): CharSequence?

    protected abstract fun createFragment(): Fragment?
}

/**
 * The vault's home (no launcher icon): the autofill service's settings, which
 * Settings opens from Passwords & passkeys > Preferred service
 *, and the Quick Settings tile's long press (an alias). Exported
 * behind the signature permission org.lujarch.vault.permission.OPEN.
 */
class VaultActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.app_name)

    override fun createFragment(): Fragment = HomeFragment()
}

/** Hosts every other page of the app; not exported. */
class SubPageActivity : BaseActivity() {
    override fun pageTitle(): CharSequence? {
        intent.getStringExtra(Pages.EXTRA_TITLE)?.let { return it }
        val res = intent.getIntExtra(Pages.EXTRA_TITLE_RES, 0)
        return if (res != 0) getText(res) else null
    }

    override fun createFragment(): Fragment? {
        val name = intent.getStringExtra(Pages.EXTRA_FRAGMENT)
        if (name == null || !name.startsWith("$packageName.")) {
            Log.e(TAG, "SubPageActivity started without a valid page: $name")
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

/** Opens pages in [SubPageActivity], one activity per page as in Settings. */
object Pages {
    const val EXTRA_FRAGMENT = "org.lujarch.vault.extra.FRAGMENT"
    const val EXTRA_ARGUMENTS = "org.lujarch.vault.extra.ARGUMENTS"
    const val EXTRA_TITLE_RES = "org.lujarch.vault.extra.TITLE_RES"
    const val EXTRA_TITLE = "org.lujarch.vault.extra.TITLE"

    fun open(
        context: Context,
        fragment: Class<out Fragment>,
        @StringRes titleRes: Int,
        title: String? = null,
        arguments: Bundle? = null,
    ) {
        context.startActivity(intent(context, fragment, titleRes, title, arguments))
    }

    /** The intent of a page; also for pending intents (Quick Settings tile) and the search result trampoline. */
    fun intent(
        context: Context,
        fragment: Class<out Fragment>,
        @StringRes titleRes: Int,
        title: String? = null,
        arguments: Bundle? = null,
    ): Intent {
        val intent = Intent(context, SubPageActivity::class.java)
            .putExtra(EXTRA_FRAGMENT, fragment.name)
            .putExtra(EXTRA_TITLE_RES, titleRes)
        if (title != null) intent.putExtra(EXTRA_TITLE, title)
        if (arguments != null) intent.putExtra(EXTRA_ARGUMENTS, arguments)
        return intent
    }
}

/**
 * Where Settings search results of the vault land (VaultSearchIndexablesProvider):
 * opens the page of the result's key and finishes. Exported only to holders
 * of READ_SEARCH_INDEXABLES (Settings' search and Settings), which start
 * search results; every page still decrypts only while the phone is unlocked.
 */
class SearchResultActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(EXTRA_FRAGMENT_ARG_KEY)
        val target = when (key) {
            SearchKeys.CODES -> Pages.intent(this, CodesFragment::class.java, R.string.codes_title)
            SearchKeys.PASSWORDS -> Pages.intent(this, PasswordsFragment::class.java, R.string.passwords_title)
            SearchKeys.SOURCES -> Pages.intent(this, SourcesFragment::class.java, R.string.sources_title)
            SearchKeys.SETTINGS, SearchKeys.CLIPBOARD, SearchKeys.FORGET_POLICY, SearchKeys.REVEAL,
            SearchKeys.COPY, SearchKeys.SEARCH_ITEM, SearchKeys.MATCH_HOST ->
                Pages.intent(this, SettingsFragment::class.java, R.string.settings_title,
                    arguments = Bundle().apply { putString(BasePreferenceFragment.ARG_HIGHLIGHT, key) })
            SearchKeys.EXCLUDED -> Pages.intent(this, ExclusionsFragment::class.java, R.string.settings_excluded)
            else -> Intent(this, VaultActivity::class.java)
        }
        try {
            startActivity(target)
        } catch (e: RuntimeException) {
            Log.e(TAG, "search result $key cannot be opened", e)
        }
        finish()
    }

    companion object {
        /** SettingsActivity.EXTRA_FRAGMENT_ARG_KEY: the key of the search result. */
        const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    }
}

/** Keys of the vault's rows in Settings search (VaultSearchIndexablesProvider). */
object SearchKeys {
    const val HOME = "lujarch_vault"
    const val CODES = "lujarch_vault_codes"
    const val PASSWORDS = "lujarch_vault_passwords"
    const val SOURCES = "lujarch_vault_sources"
    const val SETTINGS = "lujarch_vault_settings"
    // Rows of the settings page: their preferences' keys, which the page brings into view.
    const val CLIPBOARD = "clear_clipboard"
    const val FORGET_POLICY = "forget_policy"
    const val EXCLUDED = "lujarch_vault_excluded"
    const val REVEAL = "reveal_needs_auth"
    const val COPY = "copy_needs_auth"
    const val SEARCH_ITEM = "autofill_search_item"
    const val MATCH_HOST = SettingsFragment.KEY_MATCH_HOST
}
