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

package org.lujarch.recorder.settings

import android.content.Context
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceScreen
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.FooterPreference
import java.util.Calendar
import java.util.Locale
import org.lujarch.recorder.RecorderProfiles
import org.lujarch.settings.Contribution
import org.lujarch.settings.SearchEntry
import org.lujarch.settings.pages.BaseActivity
import org.lujarch.settings.pages.BasePreferenceFragment
import org.lujarch.settings.pages.InputDialog
import org.lujarch.settings.pages.Pages
import org.lujarch.settings.pages.showDialog

/** Screen record's settings (target/recorder.md), each a Settings.Secure value (settings.toml). */
internal object Rec {
    const val TAG = "lujarch_recorder"
    const val ACTION = "org.lujarch.recorder.action.SCREEN_RECORD"
    const val PREFIX = RecorderProfiles.PREFIX
    const val CUSTOM = "custom"

    fun str(context: Context, key: String, default: String): String =
        Settings.Secure.getString(context.contentResolver, PREFIX + key) ?: default

    fun int(context: Context, key: String, default: Int): Int =
        Settings.Secure.getInt(context.contentResolver, PREFIX + key, default)

    fun bool(context: Context, key: String): Boolean = int(context, key, 0) != 0

    fun put(context: Context, key: String, value: String): Boolean =
        try {
            Settings.Secure.putString(context.contentResolver, PREFIX + key, value)
        } catch (e: SecurityException) {
            Log.e(TAG, "$PREFIX$key not written", e)
            false
        }

    fun put(context: Context, key: String, value: Int): Boolean = put(context, key, value.toString())
}

/** The phone's encoders and what the chosen one takes (REC-2). */
internal object Encoders {
    val CODECS =
        listOf(
            MediaFormat.MIMETYPE_VIDEO_AVC to "H.264",
            MediaFormat.MIMETYPE_VIDEO_HEVC to "H.265",
            MediaFormat.MIMETYPE_VIDEO_AV1 to "AV1",
            MediaFormat.MIMETYPE_VIDEO_VP8 to "VP8",
            MediaFormat.MIMETYPE_VIDEO_VP9 to "VP9",
        )
    private val WEBM_CODECS = setOf(MediaFormat.MIMETYPE_VIDEO_VP8, MediaFormat.MIMETYPE_VIDEO_VP9)

    private val infos: List<MediaCodecInfo> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter {
            it.isEncoder && !it.isAlias
        }
    }

    fun encodersOf(mime: String): List<MediaCodecInfo> =
        infos.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }

    fun codecName(mime: String): String = CODECS.firstOrNull { it.first == mime }?.second ?: mime

    fun takesWebm(mime: String): Boolean = mime in WEBM_CODECS

    fun defaultCodec(): String =
        if (encodersOf(MediaFormat.MIMETYPE_VIDEO_HEVC).any { it.isHardwareAccelerated }) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }

    fun codec(context: Context): String =
        Rec.str(context, "codec", "").ifEmpty { defaultCodec() }

    /** The encoder the settings choose, or null when the phone has none such. */
    fun encoder(context: Context): MediaCodecInfo? {
        val all = encodersOf(codec(context))
        val name = Rec.str(context, "encoder", "")
        return if (name.isEmpty()) all.firstOrNull { it.isHardwareAccelerated } ?: all.firstOrNull()
        else all.firstOrNull { it.name == name }
    }

    fun caps(context: Context): MediaCodecInfo.CodecCapabilities? =
        encoder(context)?.getCapabilitiesForType(codec(context))

    fun tenBitProfile(mime: String): Int =
        when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> CodecProfileLevel.AVCProfileHigh10
            MediaFormat.MIMETYPE_VIDEO_HEVC -> CodecProfileLevel.HEVCProfileMain10
            MediaFormat.MIMETYPE_VIDEO_AV1 -> CodecProfileLevel.AV1ProfileMain10
            MediaFormat.MIMETYPE_VIDEO_VP9 -> CodecProfileLevel.VP9Profile2
            else -> -1
        }

    private fun constantPrefixes(mime: String): Pair<List<String>, List<String>> =
        when (mime) {
            MediaFormat.MIMETYPE_VIDEO_AVC -> listOf("AVCProfile") to listOf("AVCLevel")
            MediaFormat.MIMETYPE_VIDEO_HEVC ->
                listOf("HEVCProfile") to listOf("HEVCMainTierLevel", "HEVCHighTierLevel")
            MediaFormat.MIMETYPE_VIDEO_AV1 -> listOf("AV1Profile") to listOf("AV1Level")
            MediaFormat.MIMETYPE_VIDEO_VP8 -> listOf("VP8Profile") to listOf("VP8Level")
            MediaFormat.MIMETYPE_VIDEO_VP9 -> listOf("VP9Profile") to listOf("VP9Level")
            else -> emptyList<String>() to emptyList()
        }

    /** CodecProfileLevel's name for a value, without the codec's prefix. */
    private fun constantName(prefixes: List<String>, value: Int): String {
        for (field in CodecProfileLevel::class.java.fields) {
            val prefix = prefixes.firstOrNull { field.name.startsWith(it) } ?: continue
            if (field.type == Int::class.javaPrimitiveType && field.getInt(null) == value) {
                val rest = field.name.removePrefix(prefix)
                return if (prefix.contains("HighTier")) "High tier $rest"
                else if (prefix.contains("MainTier")) "Main tier $rest" else rest
            }
        }
        return value.toString()
    }

    fun profileName(mime: String, profile: Int) = constantName(constantPrefixes(mime).first, profile)

    fun levelName(mime: String, level: Int) = constantName(constantPrefixes(mime).second, level)

    private fun align(value: Int, alignment: Int) = maxOf(alignment, value - value % alignment)

    /** The screen's size in pixels, as the orientation setting turns it, and its refresh rate. */
    fun screen(context: Context): Triple<Int, Int, Float> {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val m = DisplayMetrics()
        display.getRealMetrics(m)
        val (w, h) =
            when (Rec.int(context, "orientation", 0)) {
                1 -> minOf(m.widthPixels, m.heightPixels) to maxOf(m.widthPixels, m.heightPixels)
                2 -> maxOf(m.widthPixels, m.heightPixels) to minOf(m.widthPixels, m.heightPixels)
                else -> m.widthPixels to m.heightPixels
            }
        return Triple(w, h, display.refreshRate)
    }

    /** The size the settings ask for, and the size the encoder records, as SystemUI brings it. */
    fun size(context: Context): Pair<Pair<Int, Int>, Pair<Int, Int>?> {
        val (sw, sh) = screen(context)
        val resolution = Rec.int(context, "resolution", 100)
        val width = Rec.int(context, "width", 0)
        val height = Rec.int(context, "height", 0)
        val asked =
            if (resolution == 0 && width > 0 && height > 0) width to height
            else (sw * resolution.coerceAtLeast(1) / 100) to (sh * resolution.coerceAtLeast(1) / 100)
        val vc = caps(context)?.videoCapabilities ?: return asked to null
        var w = align(asked.first, vc.widthAlignment)
        var h = align(asked.second, vc.heightAlignment)
        if (!vc.isSizeSupported(w, h)) {
            val fit =
                minOf(1.0, vc.supportedWidths.upper.toDouble() / w,
                    vc.supportedHeights.upper.toDouble() / h)
            w = align((w * fit).toInt(), vc.widthAlignment)
            h = align((h * fit).toInt(), vc.heightAlignment)
            while (!vc.isSizeSupported(w, h) && w > vc.widthAlignment && h > vc.heightAlignment) {
                w = align(w * 15 / 16, vc.widthAlignment)
                h = align(h * 15 / 16, vc.heightAlignment)
            }
            if (!vc.isSizeSupported(w, h)) return asked to null
        }
        return asked to (w to h)
    }

    /** The most frames per second the encoder takes at the recorded size. */
    fun maxFrameRate(context: Context): Int? {
        val vc = caps(context)?.videoCapabilities ?: return null
        val (w, h) = size(context).second ?: return null
        return vc.getSupportedFrameRatesFor(w, h).upper.toInt()
    }
}

/** Settings > System > Screen record (REC-2 to REC-6). */
class ScreenRecordActivity : BaseActivity() {
    override fun pageTitle(): CharSequence = getText(R.string.rec_title)

    override fun createFragment(): Fragment = ScreenRecordFragment()
}

/**
 * A page of screen record's settings: each row shows its setting, and a choice that cannot be
 * used says why and is not made.
 */
abstract class RecorderPage : BasePreferenceFragment() {
    private val binders = ArrayList<() -> Unit>()
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (uri?.lastPathSegment?.startsWith(Rec.PREFIX) != false) ifPageAlive { bindAll() }
            }
        }

    protected fun binds(binder: () -> Unit) {
        binders += binder
    }

    protected fun bindAll() {
        if (context == null) return
        binders.forEach { it() }
    }

    override fun onResume() {
        super.onResume()
        requireContext().contentResolver.registerContentObserver(
            Settings.Secure.CONTENT_URI, true, observer)
        bindAll()
    }

    override fun onPause() {
        super.onPause()
        requireContext().contentResolver.unregisterContentObserver(observer)
    }

    protected fun refuse(problem: String) {
        Toast.makeText(prefContext, problem, Toast.LENGTH_LONG).show()
        Log.i(Rec.TAG, "refused: $problem")
    }

    protected fun category(screen: PreferenceScreen, key: String, @StringRes title: Int) =
        screen.add(PreferenceCategory(prefContext)) {
            this.key = key
            setTitle(title)
        }

    /**
     * A choice among [choices] (value to label) for [key]; [check] says why a value cannot be
     * chosen, [pick] writes it (or opens a dialog for [Rec.CUSTOM]).
     */
    protected fun PreferenceGroup.choice(
        key: String,
        @StringRes title: Int,
        choices: () -> List<Pair<String, CharSequence>>,
        read: () -> String,
        summary: (String) -> CharSequence? = { null },
        check: (String) -> String? = { null },
        pick: (String) -> Boolean = { Rec.put(prefContext, key, it) },
    ): ListPreference =
        add(ListPreference(prefContext)) {
            this.key = Rec.PREFIX + key
            setTitle(title)
            setDialogTitle(title)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                val v = value as String
                val problem = check(v)
                if (problem != null) {
                    refuse(problem)
                    false
                } else {
                    pick(v)
                    bindAll()
                    false
                }
            }
        }.also { pref ->
            binds {
                val list = choices()
                pref.entries = list.map { it.second }.toTypedArray()
                pref.entryValues = list.map<Pair<String, CharSequence>, CharSequence> { it.first }.toTypedArray()
                val value = read()
                pref.value = if (list.any { it.first == value }) value else null
                pref.summary =
                    summary(value) ?: list.firstOrNull { it.first == value }?.second ?: value
            }
        }

    protected fun PreferenceGroup.toggle(
        key: String,
        @StringRes title: Int,
        @StringRes summary: Int,
        available: () -> Boolean = { true },
    ): SwitchPreferenceCompat =
        add(SwitchPreferenceCompat(prefContext)) {
            this.key = Rec.PREFIX + key
            setTitle(title)
            isPersistent = false
            setOnPreferenceChangeListener { _, value ->
                Rec.put(prefContext, key, if (value as Boolean) 1 else 0)
            }
        }.also { pref ->
            binds {
                val there = available()
                pref.isEnabled = there
                pref.isChecked = there && Rec.bool(prefContext, key)
                pref.setSummary(if (there) summary else R.string.rec_not_on_phone)
            }
        }

    /** A row that opens a text dialog for [key]; [validate] returns why a text cannot be used. */
    protected fun PreferenceGroup.entry(
        key: String,
        @StringRes title: Int,
        summary: () -> CharSequence,
        initial: () -> String,
        numeric: Boolean = false,
        @StringRes hint: Int? = null,
        @StringRes neutral: Int? = null,
        neutralValue: String = "0",
        validate: (Context, String) -> String?,
        write: (Context, String) -> Boolean = { c, v -> Rec.put(c, key, v) },
    ): Preference =
        add(Preference(prefContext)) {
            this.key = Rec.PREFIX + key
            setTitle(title)
            setOnPreferenceClickListener {
                openEntry(key, title, initial(), numeric, hint, neutral, neutralValue, validate, write)
                true
            }
        }.also { pref -> binds { pref.summary = summary() } }

    protected fun openEntry(
        key: String,
        @StringRes title: Int,
        initial: String,
        numeric: Boolean,
        @StringRes hint: Int?,
        @StringRes neutral: Int?,
        neutralValue: String,
        validate: (Context, String) -> String?,
        write: (Context, String) -> Boolean,
    ) {
        showDialog("entry_$key") { d ->
            val context = d.context
            InputDialog.create(
                context,
                title,
                initial = initial,
                hint = hint?.let { context.getString(it) },
                numeric = numeric,
                neutral = neutral,
                onNeutral = neutral?.let { { write(context, neutralValue); Unit } },
                validate = { validate(context, it) },
                onDone = { if (!write(context, it)) Log.e(Rec.TAG, "$key not written") },
            )
        }
    }

    protected fun open(fragment: Class<out Fragment>, @StringRes title: Int) =
        Pages.open(prefContext, fragment, title)
}

private fun intIn(context: Context, text: String, min: Int, max: Int): String? {
    val n = text.toIntOrNull()
    return if (n == null || n < min || n > max) {
        context.getString(R.string.rec_error_range, min, max)
    } else {
        null
    }
}

/** Settings > System > Screen record: its parts, each a page. */
class ScreenRecordFragment : RecorderPage() {
    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        fun part(key: String, @StringRes title: Int, page: Class<out Fragment>, summary: () -> String) =
            screen.add(Preference(prefContext)) {
                this.key = key
                setTitle(title)
                setOnPreferenceClickListener {
                    open(page, title)
                    true
                }
            }.also { pref -> binds { pref.summary = summary() } }
        part("video", R.string.rec_video, VideoFragment::class.java) {
            val (_, size) = Encoders.size(prefContext)
            val codec = Encoders.codecName(Encoders.codec(prefContext))
            if (size == null) codec else "$codec, ${size.first} × ${size.second}"
        }
        part("audio", R.string.rec_audio, AudioFragment::class.java) {
            getString(audioSourceLabel(Rec.int(prefContext, "audio_source", 0)))
        }
        part("file", R.string.rec_file, FileFragment::class.java) {
            "${Rec.str(prefContext, "format", "mp4").uppercase(Locale.ROOT)}, " +
                Rec.str(prefContext, "folder", "Movies")
        }
        part("profiles", R.string.rec_profiles, ProfilesFragment::class.java) {
            RecorderProfiles.names(prefContext).joinToString { profileLabel(prefContext, it) }
        }
        part("recording", R.string.rec_recording, RecordingFragment::class.java) {
            val s = Rec.int(prefContext, "countdown", 3)
            if (s == 0) getString(R.string.rec_countdown_none)
            else resources.getQuantityString(R.plurals.rec_countdown_seconds, s, s)
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.rec_footer)
        }
    }
}

internal fun audioSourceLabel(source: Int): Int =
    when (source) {
        1 -> R.string.rec_audio_device
        2 -> R.string.rec_audio_mic
        3 -> R.string.rec_audio_both
        else -> R.string.rec_audio_none
    }

internal fun profileLabel(context: Context, name: String): String =
    when (name) {
        RecorderProfiles.HIGH_QUALITY -> context.getString(R.string.rec_profile_high)
        RecorderProfiles.SMALL_FILE -> context.getString(R.string.rec_profile_small)
        else -> name
    }

/** Settings > System > Screen record > Video (REC-2). */
class VideoFragment : RecorderPage() {
    private fun hwLabel(info: MediaCodecInfo) =
        getString(if (info.isHardwareAccelerated) R.string.rec_hardware else R.string.rec_software)

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        screen.choice(
            "codec",
            R.string.rec_codec,
            choices = {
                listOf("" to getString(R.string.rec_codec_auto,
                    Encoders.codecName(Encoders.defaultCodec()))) +
                    Encoders.CODECS.mapNotNull { (mime, name) ->
                        val all = Encoders.encodersOf(mime)
                        if (all.isEmpty()) null
                        else mime to "$name · " + hwLabel(all.firstOrNull { it.isHardwareAccelerated } ?: all[0])
                    }
            },
            read = { Rec.str(prefContext, "codec", "") },
            check = { v ->
                val mime = v.ifEmpty { Encoders.defaultCodec() }
                val webm = Rec.str(prefContext, "format", "mp4") == "webm"
                if (webm != Encoders.takesWebm(mime)) {
                    getString(if (webm) R.string.rec_error_codec_webm else R.string.rec_error_codec_mp4)
                } else {
                    null
                }
            },
            pick = { v ->
                Rec.put(prefContext, "codec", v) and Rec.put(prefContext, "encoder", "") and
                    Rec.put(prefContext, "profile", -1) and Rec.put(prefContext, "level", -1)
            },
        )
        screen.choice(
            "encoder",
            R.string.rec_encoder,
            choices = {
                listOf("" to getString(R.string.rec_encoder_auto)) +
                    Encoders.encodersOf(Encoders.codec(prefContext)).map {
                        it.name to "${it.name} · ${hwLabel(it)}"
                    }
            },
            read = { Rec.str(prefContext, "encoder", "") },
            pick = { v ->
                Rec.put(prefContext, "encoder", v) and Rec.put(prefContext, "profile", -1) and
                    Rec.put(prefContext, "level", -1)
            },
        )
        screen.choice(
            "profile",
            R.string.rec_profile_level,
            choices = {
                val mime = Encoders.codec(prefContext)
                val levels = Encoders.caps(prefContext)?.profileLevels.orEmpty()
                listOf("-1:-1" to getString(R.string.rec_automatic)) +
                    levels.distinctBy { it.profile to it.level }.map {
                        "${it.profile}:${it.level}" to getString(R.string.rec_profile_level_value,
                            Encoders.profileName(mime, it.profile), Encoders.levelName(mime, it.level))
                    }
            },
            read = {
                "${Rec.int(prefContext, "profile", -1)}:${Rec.int(prefContext, "level", -1)}"
            },
            pick = { v ->
                val (p, l) = v.split(":")
                Rec.put(prefContext, "profile", p) and Rec.put(prefContext, "level", l)
            },
        )
        screen.choice(
            "bit_depth",
            R.string.rec_bit_depth,
            choices = { listOf("8" to getString(R.string.rec_8_bit), "10" to getString(R.string.rec_10_bit)) },
            read = { Rec.int(prefContext, "bit_depth", 8).toString() },
            check = { v ->
                val mime = Encoders.codec(prefContext)
                val ten = Encoders.tenBitProfile(mime)
                if (v == "10" && Encoders.caps(prefContext)?.profileLevels.orEmpty().none { it.profile == ten }) {
                    getString(R.string.rec_error_10_bit)
                } else {
                    null
                }
            },
        )
        screen.choice(
            "resolution",
            R.string.rec_resolution,
            choices = {
                listOf(
                    "100" to getString(R.string.rec_same_as_screen),
                    "75" to "3/4",
                    "50" to "1/2",
                    Rec.CUSTOM to getString(R.string.rec_custom),
                )
            },
            read = {
                val r = Rec.int(prefContext, "resolution", 100)
                if (r == 0) Rec.CUSTOM else r.toString()
            },
            summary = { v ->
                val (asked, recorded) = Encoders.size(prefContext)
                val choice =
                    when (v) {
                        "100" -> getString(R.string.rec_same_as_screen)
                        Rec.CUSTOM -> "${asked.first} × ${asked.second}"
                        else -> if (v == "75") "3/4" else "1/2"
                    }
                when {
                    recorded == null -> getString(R.string.rec_error_no_size, choice)
                    recorded != asked ->
                        getString(R.string.rec_size_brought, choice, recorded.first, recorded.second)
                    else -> getString(R.string.rec_size_records, choice, recorded.first, recorded.second)
                }
            },
            pick = { v ->
                if (v == Rec.CUSTOM) {
                    val (asked, _) = Encoders.size(prefContext)
                    openEntry("size", R.string.rec_resolution, "${asked.first}x${asked.second}",
                        false, R.string.rec_size_hint, null, "", ::checkSize) { c, text ->
                        val (w, h) = parseSize(text)!!
                        Rec.put(c, "width", w) and Rec.put(c, "height", h) and
                            Rec.put(c, "resolution", 0)
                    }
                    true
                } else {
                    Rec.put(prefContext, "resolution", v)
                }
            },
        )
        screen.choice(
            "frame_rate",
            R.string.rec_frame_rate,
            choices = {
                listOf("0" to getString(R.string.rec_same_as_screen)) +
                    listOf(24, 30, 60, 90, 120).map { it.toString() to getString(R.string.rec_fps, it) } +
                    listOf(Rec.CUSTOM to getString(R.string.rec_custom))
            },
            read = {
                val f = Rec.int(prefContext, "frame_rate", 0)
                if (f in listOf(0, 24, 30, 60, 90, 120)) f.toString() else Rec.CUSTOM
            },
            summary = { v ->
                val max = Encoders.maxFrameRate(prefContext)
                if (v == "0") {
                    val refresh = Math.round(Encoders.screen(prefContext).third)
                    getString(R.string.rec_frame_rate_screen,
                        if (max != null) minOf(refresh, max) else refresh)
                } else {
                    getString(R.string.rec_fps, Rec.int(prefContext, "frame_rate", 0))
                }
            },
            check = { v -> v.toIntOrNull()?.let { checkFrameRate(prefContext, it.toString()) } },
            pick = { v ->
                if (v == Rec.CUSTOM) {
                    openEntry("frame_rate", R.string.rec_frame_rate,
                        Rec.int(prefContext, "frame_rate", 0).takeIf { it > 0 }?.toString() ?: "",
                        true, null, null, "", ::checkFrameRate) { c, t -> Rec.put(c, "frame_rate", t) }
                    true
                } else {
                    Rec.put(prefContext, "frame_rate", v)
                }
            },
        )
        screen.choice(
            "every_frame",
            R.string.rec_frames,
            choices = {
                listOf("0" to getString(R.string.rec_frames_changes), "1" to getString(R.string.rec_frames_every))
            },
            read = { Rec.int(prefContext, "every_frame", 0).toString() },
        )
        screen.entry(
            "bitrate",
            R.string.rec_bitrate,
            summary = {
                val kbps = Rec.int(prefContext, "bitrate", 0)
                if (kbps == 0) getString(R.string.rec_bitrate_auto)
                else getString(R.string.rec_mbps, formatMbps(kbps))
            },
            initial = {
                Rec.int(prefContext, "bitrate", 0).takeIf { it > 0 }?.let { formatMbps(it) } ?: ""
            },
            hint = R.string.rec_bitrate_hint,
            neutral = R.string.rec_automatic,
            validate = ::checkBitrate,
            write = { c, t -> Rec.put(c, "bitrate", if (t == "0") 0 else mbpsToKbps(t)!!) },
        )
        screen.choice(
            "bitrate_mode",
            R.string.rec_bitrate_mode,
            choices = {
                listOf(
                    "1" to getString(R.string.rec_mode_variable),
                    "2" to getString(R.string.rec_mode_constant),
                    "0" to getString(R.string.rec_mode_quality),
                )
            },
            read = { Rec.int(prefContext, "bitrate_mode", 1).toString() },
            check = { v ->
                val ec = Encoders.caps(prefContext)?.encoderCapabilities
                if (ec != null && !ec.isBitrateModeSupported(v.toInt())) {
                    getString(R.string.rec_error_bitrate_mode)
                } else {
                    null
                }
            },
        )
        val quality =
            screen.entry(
                "quality",
                R.string.rec_quality,
                summary = {
                    val q = Rec.int(prefContext, "quality", -1)
                    val range = Encoders.caps(prefContext)?.encoderCapabilities?.qualityRange
                    if (q < 0) getString(R.string.rec_automatic)
                    else if (range != null) getString(R.string.rec_quality_value, q, range.lower, range.upper)
                    else q.toString()
                },
                initial = { Rec.int(prefContext, "quality", -1).takeIf { it >= 0 }?.toString() ?: "" },
                numeric = true,
                neutral = R.string.rec_automatic,
                neutralValue = "-1",
                validate = { c, t ->
                    val range = Encoders.caps(c)?.encoderCapabilities?.qualityRange
                    if (range == null) null else intIn(c, t, range.lower, range.upper)
                },
            )
        binds { quality.isVisible = Rec.int(prefContext, "bitrate_mode", 1) == 0 }
        screen.choice(
            "key_interval",
            R.string.rec_key_interval,
            choices = {
                listOf(1, 2, 5, 10).map {
                    it.toString() to resources.getQuantityString(R.plurals.rec_seconds, it, it)
                } + listOf(
                    "0" to getString(R.string.rec_key_every),
                    "-1" to getString(R.string.rec_key_first),
                    Rec.CUSTOM to getString(R.string.rec_custom),
                )
            },
            read = {
                val k = Rec.int(prefContext, "key_interval", 1)
                if (k in listOf(1, 2, 5, 10, 0, -1)) k.toString() else Rec.CUSTOM
            },
            summary = { v ->
                val k = Rec.int(prefContext, "key_interval", 1)
                if (v == Rec.CUSTOM) resources.getQuantityString(R.plurals.rec_seconds, k, k) else null
            },
            pick = { v ->
                if (v == Rec.CUSTOM) {
                    openEntry("key_interval", R.string.rec_key_interval, "", true,
                        R.string.rec_seconds_hint, null, "", { c, t -> intIn(c, t, 1, 3600) }) { c, t ->
                        Rec.put(c, "key_interval", t)
                    }
                    true
                } else {
                    Rec.put(prefContext, "key_interval", v)
                }
            },
        )
        screen.entry(
            "b_frames",
            R.string.rec_b_frames,
            summary = { Rec.int(prefContext, "b_frames", 0).toString() },
            initial = { Rec.int(prefContext, "b_frames", 0).toString() },
            numeric = true,
            validate = { c, t -> intIn(c, t, 0, 16) },
        )
        screen.choice(
            "orientation",
            R.string.rec_orientation,
            choices = {
                listOf(
                    "0" to getString(R.string.rec_orientation_start),
                    "1" to getString(R.string.rec_orientation_portrait),
                    "2" to getString(R.string.rec_orientation_landscape),
                )
            },
            read = { Rec.int(prefContext, "orientation", 0).toString() },
        )
    }
}

private fun parseSize(text: String): Pair<Int, Int>? {
    val parts = text.lowercase(Locale.ROOT).split('x', '×', '*', ' ').filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val w = parts[0].toIntOrNull() ?: return null
    val h = parts[1].toIntOrNull() ?: return null
    return if (w in 64..8192 && h in 64..8192) w to h else null
}

private fun checkSize(context: Context, text: String): String? =
    if (parseSize(text) == null) context.getString(R.string.rec_error_size) else null

private fun checkFrameRate(context: Context, text: String): String? {
    intIn(context, text, 1, 480)?.let { return it }
    val max = Encoders.maxFrameRate(context) ?: return null
    return if (text.toInt() > max) context.getString(R.string.rec_error_frame_rate, max) else null
}

private fun mbpsToKbps(text: String): Int? =
    text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }?.let { Math.round(it * 1000).toInt() }

private fun formatMbps(kbps: Int): String =
    if (kbps % 1000 == 0) (kbps / 1000).toString() else String.format(Locale.ROOT, "%.1f", kbps / 1000.0)

private fun checkBitrate(context: Context, text: String): String? {
    val kbps = mbpsToKbps(text) ?: return context.getString(R.string.rec_error_number)
    val range = Encoders.caps(context)?.videoCapabilities?.bitrateRange ?: return null
    return if (kbps * 1000L < range.lower || kbps * 1000L > range.upper) {
        context.getString(R.string.rec_error_bitrate,
            formatMbps(range.lower / 1000), formatMbps(range.upper / 1000))
    } else {
        null
    }
}

/** Settings > System > Screen record > Audio (REC-3). */
class AudioFragment : RecorderPage() {
    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        screen.choice(
            "audio_source",
            R.string.rec_audio_source,
            choices = { (0..3).map { it.toString() to getString(audioSourceLabel(it)) } },
            read = { Rec.int(prefContext, "audio_source", 0).toString() },
            check = { v ->
                if (v != "0" && Rec.str(prefContext, "format", "mp4") == "webm" &&
                    Rec.str(prefContext, "audio_codec", "aac_lc") != "opus") {
                    getString(R.string.rec_error_webm_audio)
                } else {
                    null
                }
            },
        )
        screen.choice(
            "separate_tracks",
            R.string.rec_both_sources,
            choices = {
                listOf("0" to getString(R.string.rec_mixed), "1" to getString(R.string.rec_separate))
            },
            read = { Rec.int(prefContext, "separate_tracks", 0).toString() },
            check = { v ->
                if (v == "1" && Rec.str(prefContext, "format", "mp4") == "webm") {
                    getString(R.string.rec_error_webm_tracks)
                } else {
                    null
                }
            },
        )
        fun volume(key: String, @StringRes title: Int) =
            screen.add(SeekBarPreference(prefContext)) {
                this.key = Rec.PREFIX + key
                setTitle(title)
                isPersistent = false
                min = 0
                max = 200
                seekBarIncrement = 5
                showSeekBarValue = true
                updatesContinuously = false
                setOnPreferenceChangeListener { _, value -> Rec.put(prefContext, key, value as Int) }
            }.also { pref ->
                binds {
                    val v = Rec.int(prefContext, key, 100)
                    pref.value = v
                    pref.summary = getString(R.string.rec_percent, v)
                }
            }
        volume("internal_volume", R.string.rec_device_volume)
        volume("mic_volume", R.string.rec_mic_volume)
        screen.choice(
            "audio_codec",
            R.string.rec_audio_codec,
            choices = {
                listOf("aac_lc" to "AAC-LC", "aac_he" to "HE-AAC") +
                    (if (hasOpus()) listOf("opus" to "Opus") else emptyList())
            },
            read = { Rec.str(prefContext, "audio_codec", "aac_lc") },
            check = { v ->
                when {
                    v == "opus" && Rec.int(prefContext, "sample_rate", 44100) != 48000 ->
                        getString(R.string.rec_error_opus_rate)
                    v != "opus" && Rec.str(prefContext, "format", "mp4") == "webm" ->
                        getString(R.string.rec_error_webm_audio)
                    else -> null
                }
            },
        )
        screen.choice(
            "sample_rate",
            R.string.rec_sample_rate,
            choices = { listOf("44100" to "44.1 kHz", "48000" to "48 kHz") },
            read = { Rec.int(prefContext, "sample_rate", 44100).toString() },
            check = { v ->
                if (v != "48000" && Rec.str(prefContext, "audio_codec", "aac_lc") == "opus") {
                    getString(R.string.rec_error_opus_rate)
                } else {
                    null
                }
            },
        )
        screen.choice(
            "channels",
            R.string.rec_channels,
            choices = { listOf("1" to getString(R.string.rec_mono), "2" to getString(R.string.rec_stereo)) },
            read = { Rec.int(prefContext, "channels", 2).toString() },
        )
        screen.entry(
            "audio_bitrate",
            R.string.rec_audio_bitrate,
            summary = { getString(R.string.rec_kbps, Rec.int(prefContext, "audio_bitrate", 192)) },
            initial = { Rec.int(prefContext, "audio_bitrate", 192).toString() },
            numeric = true,
            hint = R.string.rec_kbps_hint,
            validate = { c, t -> intIn(c, t, 8, 512) },
        )
        val mic = category(screen, "microphone", R.string.rec_microphone)
        mic.toggle("noise_suppression", R.string.rec_noise_suppression,
            R.string.rec_noise_suppression_summary) { NoiseSuppressor.isAvailable() }
        mic.toggle("echo_cancellation", R.string.rec_echo_cancellation,
            R.string.rec_echo_cancellation_summary) { AcousticEchoCanceler.isAvailable() }
    }

    private fun hasOpus(): Boolean =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2)) != null
}

/** Settings > System > Screen record > File (REC-4). */
class FileFragment : RecorderPage() {
    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        screen.choice(
            "format",
            R.string.rec_format,
            choices = { listOf("mp4" to "MP4", "webm" to "WebM") },
            read = { Rec.str(prefContext, "format", "mp4") },
            check = { v ->
                val webmCodec = Encoders.takesWebm(Encoders.codec(prefContext))
                val audio = Rec.int(prefContext, "audio_source", 0) != 0
                when {
                    v == "webm" && !webmCodec -> getString(R.string.rec_error_format_webm)
                    v == "mp4" && webmCodec -> getString(R.string.rec_error_format_mp4)
                    v == "webm" && audio && Rec.str(prefContext, "audio_codec", "aac_lc") != "opus" ->
                        getString(R.string.rec_error_webm_audio)
                    v == "webm" && audio && Rec.bool(prefContext, "separate_tracks") ->
                        getString(R.string.rec_error_webm_tracks)
                    else -> null
                }
            },
        )
        screen.entry(
            "folder",
            R.string.rec_folder,
            summary = { Rec.str(prefContext, "folder", "Movies") },
            initial = { Rec.str(prefContext, "folder", "Movies") },
            hint = R.string.rec_folder_hint,
            validate = { c, t ->
                val f = t.trim('/')
                val ok = listOf("DCIM", "Movies", "Pictures").any { f == it || f.startsWith("$it/") }
                if (ok && !f.contains("..")) null else c.getString(R.string.rec_error_folder)
            },
            write = { c, t -> Rec.put(c, "folder", t.trim('/')) },
        )
        screen.entry(
            "file_name",
            R.string.rec_file_name,
            summary = {
                getString(R.string.rec_file_name_next,
                    nextName(Rec.str(prefContext, "file_name", "screen-yyyyMMdd-HHmmss")) +
                        if (Rec.str(prefContext, "format", "mp4") == "webm") ".webm" else ".mp4")
            },
            initial = { Rec.str(prefContext, "file_name", "screen-yyyyMMdd-HHmmss") },
            hint = R.string.rec_file_name_hint,
            validate = { c, t ->
                if (t.isEmpty() || t.contains('/')) c.getString(R.string.rec_error_file_name) else null
            },
        )
        screen.entry(
            "longest",
            R.string.rec_longest,
            summary = {
                val m = Rec.int(prefContext, "longest", 60)
                if (m == 0) getString(R.string.rec_no_limit)
                else resources.getQuantityString(R.plurals.rec_minutes, m, m)
            },
            initial = { Rec.int(prefContext, "longest", 60).takeIf { it > 0 }?.toString() ?: "" },
            numeric = true,
            hint = R.string.rec_minutes_hint,
            neutral = R.string.rec_no_limit,
            validate = { c, t -> intIn(c, t, 1, 100000) },
        )
        screen.entry(
            "largest",
            R.string.rec_largest,
            summary = {
                val mb = Rec.int(prefContext, "largest", 5000)
                if (mb == 0) getString(R.string.rec_no_limit) else getString(R.string.rec_mb, mb)
            },
            initial = { Rec.int(prefContext, "largest", 5000).takeIf { it > 0 }?.toString() ?: "" },
            numeric = true,
            hint = R.string.rec_mb_hint,
            neutral = R.string.rec_no_limit,
            validate = { c, t -> intIn(c, t, 1, 10000000) },
        )
        screen.choice(
            "at_limit",
            R.string.rec_at_limit,
            choices = {
                listOf("0" to getString(R.string.rec_limit_stop), "1" to getString(R.string.rec_limit_new_file))
            },
            read = { Rec.int(prefContext, "at_limit", 0).toString() },
        )
    }

    /** The name the next recording gets (REC-4). */
    private fun nextName(pattern: String): String {
        val c = Calendar.getInstance()
        fun two(v: Int) = String.format(Locale.ROOT, "%02d", v)
        return pattern
            .replace("yyyy", String.format(Locale.ROOT, "%04d", c.get(Calendar.YEAR)))
            .replace("MM", two(c.get(Calendar.MONTH) + 1))
            .replace("dd", two(c.get(Calendar.DAY_OF_MONTH)))
            .replace("HH", two(c.get(Calendar.HOUR_OF_DAY)))
            .replace("mm", two(c.get(Calendar.MINUTE)))
            .replace("ss", two(c.get(Calendar.SECOND)))
    }
}

/** Settings > System > Screen record > Profiles (REC-5). */
class ProfilesFragment : RecorderPage() {
    private lateinit var list: PreferenceCategory

    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        list = category(screen, "profiles", R.string.rec_profiles)
        screen.add(Preference(prefContext)) {
            key = "save"
            setTitle(R.string.rec_profile_save)
            setOnPreferenceClickListener {
                openEntry("save", R.string.rec_profile_save, "", false, R.string.rec_profile_name,
                    null, "", ::checkName) { c, name -> RecorderProfiles.saveCurrent(c, name) }
                true
            }
        }
        screen.add(FooterPreference(prefContext)) {
            key = "footer"
            setTitle(R.string.rec_profiles_footer)
        }
        binds { fill() }
    }

    private fun fill() {
        list.removeAll()
        for ((i, name) in RecorderProfiles.names(prefContext).withIndex()) {
            list.add(Preference(prefContext)) {
                key = "profile_$i"
                order = i
                title = profileLabel(prefContext, name)
                setSummary(
                    when (name) {
                        RecorderProfiles.HIGH_QUALITY -> R.string.rec_profile_high_summary
                        RecorderProfiles.SMALL_FILE -> R.string.rec_profile_small_summary
                        else -> R.string.rec_profile_saved_summary
                    })
                setOnPreferenceClickListener {
                    actions(name)
                    true
                }
            }
        }
    }

    private fun actions(name: String) {
        showDialog("profile") { d ->
            val context = d.context
            val builtIn = RecorderProfiles.isBuiltIn(name)
            val items =
                if (builtIn) arrayOf(context.getString(R.string.rec_profile_use))
                else arrayOf(
                    context.getString(R.string.rec_profile_use),
                    context.getString(R.string.rec_profile_rename),
                    context.getString(R.string.rec_profile_delete),
                )
            AlertDialog.Builder(context)
                .setTitle(profileLabel(context, name))
                .setItems(items) { _, which ->
                    when (which) {
                        0 ->
                            if (RecorderProfiles.use(context, name)) {
                                Toast.makeText(context, context.getString(R.string.rec_profile_used,
                                    profileLabel(context, name)), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, R.string.rec_error_profile, Toast.LENGTH_LONG).show()
                            }
                        1 ->
                            openEntry("rename", R.string.rec_profile_rename, name, false,
                                R.string.rec_profile_name, null, "", ::checkName) { c, newName ->
                                RecorderProfiles.rename(c, name, newName)
                            }
                        2 -> RecorderProfiles.delete(context, name)
                    }
                }
                .create()
        }
    }
}

private fun checkName(context: Context, name: String): String? =
    when {
        name.isEmpty() || name.startsWith("@") -> context.getString(R.string.rec_error_profile_name)
        name in RecorderProfiles.names(context) -> context.getString(R.string.rec_error_profile_exists)
        else -> null
    }

/** Settings > System > Screen record > Recording (REC-6). */
class RecordingFragment : RecorderPage() {
    override fun buildScreen(screen: PreferenceScreen, savedInstanceState: Bundle?) {
        screen.choice(
            "countdown",
            R.string.rec_countdown,
            choices = {
                listOf("0" to getString(R.string.rec_countdown_none)) +
                    listOf(3, 5, 10).map {
                        it.toString() to resources.getQuantityString(R.plurals.rec_countdown_seconds, it, it)
                    }
            },
            read = { Rec.int(prefContext, "countdown", 3).toString() },
        )
        screen.toggle("keep_screen_on", R.string.rec_keep_screen_on, R.string.rec_keep_screen_on_summary)
        screen.toggle("dnd", R.string.rec_dnd, R.string.rec_dnd_summary)
    }
}

/** The pages in Settings search (CORE-5). */
class ScreenRecordContribution : Contribution {
    override fun searchEntries(context: Context): List<SearchEntry> {
        val screen = context.getString(R.string.rec_title)
        val keywords = context.getString(R.string.rec_keywords)
        fun entry(key: String, @StringRes title: Int, @StringRes summary: Int) =
            SearchEntry(
                key = key,
                title = context.getString(title),
                summary = context.getString(summary),
                keywords = keywords,
                screenTitle = screen,
                action = Rec.ACTION,
            )
        return listOf(
            SearchEntry(
                key = "lujarch_recorder",
                title = screen,
                summary = context.getString(R.string.rec_summary),
                keywords = keywords,
                screenTitle = context.getString(R.string.rec_system),
                action = Rec.ACTION,
            ),
            entry("video", R.string.rec_video, R.string.rec_video_search),
            entry("audio", R.string.rec_audio, R.string.rec_audio_search),
            entry("file", R.string.rec_file, R.string.rec_file_search),
            entry("profiles", R.string.rec_profiles, R.string.rec_profiles_search),
            entry("recording", R.string.rec_recording, R.string.rec_recording_search),
        )
    }
}
