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

package org.lujarch.icons.settings

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.internal.lujarch.icons.LujarchIcons
import com.android.settingslib.widget.FooterPreference
import com.android.settingslib.widget.SelectorWithWidgetPreference
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.lujarch.settings.pages.BasePreferenceFragment

/**
 * One app's icon (ICON-1): Original, Fitted or Redrawn, each with a preview of how it looks.
 * While Fit app icons is off the choices wait, and a row leads to the switch.
 */
class AppIconFragment : BasePreferenceFragment() {
    private lateinit var packageName: String
    private var userId = 0
    private lateinit var offPref: Preference
    private val choices = LinkedHashMap<Int, SelectorWithWidgetPreference>()
    private var busy = false

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        packageName = requireArguments().getString(ARG_PACKAGE) ?: ""
        userId = requireArguments().getInt(ARG_USER)
        val app = Icons.appInfo(requireContext(), packageName, userId)
        screen.add(Preference(prefContext)) {
            key = "app"
            title = app?.loadLabel(requireContext().packageManager) ?: packageName
            summary = packageName
            isSelectable = false
            app?.loadIcon(requireContext().packageManager)?.let { icon = it }
        }
        offPref = screen.add(Preference(prefContext)) {
            key = "off"
            setTitle(R.string.icons_off)
            setSummary(R.string.icons_off_summary)
            isVisible = false
            setOnPreferenceClickListener {
                startActivity(FitIconsActivity.open())
                true
            }
        }
        val category = screen.add(PreferenceCategory(prefContext)) { key = "choices" }
        for (mode in Icons.MODES) {
            choices[mode] = category.add(SelectorWithWidgetPreference(prefContext)) {
                key = "mode_$mode"
                setTitle(Icons.label(mode))
                isPersistent = false
                isEnabled = false
                setOnClickListener { choose(mode) }
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.icons_app_icon_footer)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun refresh() {
        launchUi {
            val context = requireContext()
            val on = withContext(Dispatchers.IO) { Icons.isOn(context) }
            val mode = attempt("mode", R.string.icons_error_load) {
                withContext(Dispatchers.IO) { Icons.mode(packageName) }
            }
            offPref.isVisible = !on
            for ((m, pref) in choices) {
                pref.isChecked = m == mode
                pref.isEnabled = on && mode != null && !busy
            }
            val app = Icons.appInfo(context, packageName, userId) ?: return@launchUi
            val size = (PREVIEW_DP * resources.displayMetrics.density).roundToInt()
            val previews = orNull("previews") { withContext(Dispatchers.IO) { previews(context, app, size) } }
            previews?.forEach { (m, d) -> choices[m]?.icon = d }
        }
    }

    private fun choose(mode: Int) {
        if (busy) return
        busy = true
        launchUi {
            try {
                act(R.string.icons_error_set) { withContext(Dispatchers.IO) { Icons.setMode(packageName, mode) } }
            } finally {
                ifPageAlive { busy = false }
            }
            refresh()
        }
    }

    /** The three icons as the launcher draws them, each [size] pixels across. */
    private fun previews(context: Context, app: ApplicationInfo, size: Int): Map<Int, Drawable> {
        val res = context.packageManager.getResourcesForApplication(app)
        val original = legacy(res.getDrawableForDensity(app.icon, resources.displayMetrics.densityDpi, null)!!, size)
        val out = HashMap<Int, Drawable>()
        out[LujarchIcons.MODE_ORIGINAL] = BitmapDrawable(resources, original)
        for (mode in listOf(LujarchIcons.MODE_FIT, LujarchIcons.MODE_REDRAW)) {
            val layers = Icons.service().getPreview(packageName, app.icon, userId, mode)
            out[mode] = BitmapDrawable(
                resources,
                if (layers != null) circle(LujarchIcons.toDrawable(resources, layers), size) else original,
            )
        }
        return out
    }

    companion object {
        private const val ARG_PACKAGE = "package"
        private const val ARG_USER = "user"
        private const val PREVIEW_DP = 40

        fun args(packageName: String, userId: Int) = Bundle().apply {
            putString(ARG_PACKAGE, packageName)
            putInt(ARG_USER, userId)
        }

        /** A legacy icon as the launcher shows it: shrunk onto a white circle. */
        private fun legacy(d: Drawable, size: Int): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
            val inset = (size * 0.19f).roundToInt()
            d.setBounds(inset, inset, size - inset, size - inset)
            d.draw(c)
            return b
        }

        /** An adaptive icon in a circle: of its 108 dp canvas the middle 72 dp show. */
        private fun circle(d: AdaptiveIconDrawable, size: Int): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.clipPath(Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) })
            val extra = (size * 0.25f).roundToInt()
            for (layer in listOfNotNull(d.background, d.foreground)) {
                layer.setBounds(-extra, -extra, size + extra, size + extra)
                layer.draw(c)
            }
            return b
        }
    }
}
