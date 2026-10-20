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

package org.lujarch.recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.view.Display
import java.util.Calendar
import java.util.Locale
import org.json.JSONObject

/** The phone's encoders and what they are called (REC-2, REC-3). */
object Encoders {
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

    val hasOpus: Boolean by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 2)) != null
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
}

/**
 * The settings of the user [context] runs as, read once, and what they come to on this phone's
 * encoders and its screen.
 */
class RecorderState(val context: Context) {
    val config: RecorderConfig = RecorderConfig.read(context)
    val codec: String = config.codec.ifEmpty { RecorderConfig.defaultCodec() }
    val encoder: MediaCodecInfo? = RecorderConfig.findEncoder(codec, config.encoder)
    val caps: MediaCodecInfo.CodecCapabilities? = encoder?.getCapabilitiesForType(codec)
    val refreshRate: Int
    val screen: IntArray
    /** The size the settings ask for, and the size the encoder records, if it takes one. */
    val asked: IntArray
    val recorded: IntArray?
    /** The most frames per second the encoder takes at the recorded size. */
    val maxFrameRate: Int?
    /** What a recording gets, or why the settings cannot be used together. */
    val resolved: RecorderConfig.Resolved?
    val problem: String?

    init {
        val display = context.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        refreshRate = Math.round(display.refreshRate)
        screen = config.screenSize(metrics)
        asked = config.askedSize(screen)
        val vc = caps?.videoCapabilities
        recorded = vc?.let { RecorderConfig.fitSize(it, asked[0], asked[1]) }
        maxFrameRate = recorded?.let { vc!!.getSupportedFrameRatesFor(it[0], it[1]).upper.toInt() }
        var r: RecorderConfig.Resolved? = null
        var p: String? = null
        try {
            r = config.resolve(context, metrics, display.refreshRate)
        } catch (e: RecorderConfig.Unusable) {
            p = e.message
        }
        resolved = r
        problem = p
    }

    val webm: Boolean
        get() = config.format == "webm"

    val audioTracks: Int
        get() =
            when (config.audioSource) {
                RecorderConfig.AUDIO_NONE -> 0
                RecorderConfig.AUDIO_BOTH -> if (config.separateTracks) 2 else 1
                else -> 1
            }

    fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun plural(id: Int, n: Int): String = context.resources.getQuantityString(id, n, n)

    fun hardware(info: MediaCodecInfo): String =
        string(if (info.isHardwareAccelerated) R.string.rec_hardware else R.string.rec_software)

    fun audioSourceLabel(source: Int): String =
        string(
            when (source) {
                RecorderConfig.AUDIO_DEVICE -> R.string.rec_audio_device
                RecorderConfig.AUDIO_MIC -> R.string.rec_audio_mic
                RecorderConfig.AUDIO_BOTH -> R.string.rec_audio_both
                else -> R.string.rec_audio_none
            }
        )

    fun audioCodecLabel(codec: String): String =
        when (codec) {
            "aac_he" -> "HE-AAC"
            "opus" -> "Opus"
            else -> "AAC-LC"
        }

    fun countdownLabel(seconds: Int): String =
        if (seconds == 0) string(R.string.rec_countdown_none)
        else plural(R.plurals.rec_countdown_seconds, seconds)

    fun limitLabel(minutes: Int): String =
        if (minutes == 0) string(R.string.rec_no_limit) else plural(R.plurals.rec_minutes, minutes)

    fun mbps(kbps: Int): String =
        if (kbps % 1000 == 0) (kbps / 1000).toString()
        else String.format(Locale.ROOT, "%.1f", kbps / 1000.0)

    // -----------------------------------------------------------------------------------------
    // What the card and the settings page show above the pages (REC-1)

    /** The size a minute of recording takes, or that it follows the picture, or the problem. */
    fun sizePerMinute(): String {
        val r = resolved ?: return problem ?: ""
        val bytes = r.bytesPerMinute(audioTracks > 0, audioTracks)
        return if (bytes < 0) string(R.string.rec_size_follows_picture)
        else string(R.string.rec_size_about, Formatter.formatShortFileSize(context, bytes))
    }

    /** The codec, the size, the frame rate and the bitrate a recording gets. */
    fun spec(): String {
        val r = resolved ?: return ""
        val rate =
            if (r.bitrateMode == RecorderConfig.BITRATE_MODE_CQ) {
                string(R.string.rec_spec_quality, r.quality)
            } else {
                string(R.string.rec_mbps, mbps(r.bitrate / 1000))
            }
        return listOf(
                Encoders.codecName(r.videoMime),
                string(R.string.rec_size_value, r.width, r.height),
                string(R.string.rec_fps, r.frameRate),
                rate,
            )
            .joinToString(" · ")
    }

    /** Each part's two summary lines: Video, Audio, File, Recording. */
    fun summary(page: RecorderPage): Pair<String, String> =
        when (page) {
            RecorderPage.VIDEO -> {
                val first = Encoders.codecName(codec) + (encoder?.let { " · " + hardware(it) } ?: "")
                val size = recorded ?: asked
                val fps = resolved?.frameRate ?: config.frameRate.takeIf { it > 0 } ?: refreshRate
                first to (string(R.string.rec_size_value, size[0], size[1]) + " · " +
                    string(R.string.rec_fps, fps))
            }
            RecorderPage.AUDIO ->
                audioSourceLabel(config.audioSource) to
                    (audioCodecLabel(config.audioCodec) + " · " +
                        string(R.string.rec_kbps, config.audioBitrateKbps))
            RecorderPage.FILE ->
                (config.format.uppercase(Locale.ROOT) + " · " + config.folder) to
                    string(R.string.rec_longest_value, limitLabel(config.longestMinutes))
            RecorderPage.RECORDING -> {
                val second =
                    listOfNotNull(
                            string(R.string.rec_tile_keep_on).takeIf { config.keepScreenOn },
                            string(R.string.rec_tile_dnd).takeIf { config.doNotDisturb },
                        )
                        .joinToString(string(R.string.rec_list_separator))
                        .ifEmpty { string(R.string.rec_tile_as_usual) }
                string(R.string.rec_countdown_value, countdownLabel(config.countdownSeconds)) to
                    second
            }
        }

    // -----------------------------------------------------------------------------------------
    // Profiles (REC-1, REC-5)

    /** Every profile's name with the name it shows, the built-in ones first. */
    fun profiles(): List<Pair<String, String>> =
        RecorderProfiles.names(context).map { it to RecorderProfiles.label(context, it) }

    /** The profile last chosen, if it still exists. */
    val chosenProfile: String? =
        RecorderConfig.chosenProfile(context).takeIf { it in RecorderProfiles.names(context) }

    /** Whether a setting differs from the chosen profile's since it was chosen. */
    val profileChanged: Boolean
        get() {
            val name = chosenProfile ?: return false
            val profile: JSONObject = RecorderProfiles.all(context).optJSONObject(name) ?: return false
            return profile.keys().asSequence().any { key ->
                RecorderConfig.get(context, key) != profile.opt(key)?.toString()
            }
        }

    /** Sets every setting of the profile [name] and marks it chosen. */
    fun useProfile(name: String): Boolean =
        RecorderProfiles.use(context, name) and RecorderConfig.set(context, "profile_chosen", name)
}

/** The pages of screen record's settings, in the card and in Settings alike. */
enum class RecorderPage(val title: Int, val icon: Int) {
    VIDEO(R.string.rec_video, R.drawable.rec_ic_video),
    AUDIO(R.string.rec_audio, R.drawable.rec_ic_audio),
    FILE(R.string.rec_file, R.drawable.rec_ic_file),
    RECORDING(R.string.rec_recording, R.drawable.rec_ic_recording);

    /** The page's settings as they are now; a page is built again after every change. */
    fun settings(context: Context): List<Setting> {
        val s = RecorderState(context)
        return when (this) {
            VIDEO -> RecorderPages.video(s)
            AUDIO -> RecorderPages.audio(s)
            FILE -> RecorderPages.file(s)
            RECORDING -> RecorderPages.recording(s)
        }
    }
}

/** One setting of a page. */
sealed class Setting {
    abstract val key: String
    abstract val title: String
}

/** A choice that cannot be made says why ([problem]); one with an [entry] asks for a value. */
data class Option(
    val value: String,
    val label: String,
    val tag: String? = null,
    val problem: String? = null,
    val entry: Entry? = null,
)

class Choice(
    override val key: String,
    override val title: String,
    val options: List<Option>,
    val value: String,
    val summary: String,
    val write: (String) -> Boolean,
) : Setting() {
    val selected: Option?
        get() = options.firstOrNull { it.value == value }
}

class Toggle(
    override val key: String,
    override val title: String,
    val summary: String,
    val checked: Boolean,
    val available: Boolean,
    val write: (Boolean) -> Boolean,
) : Setting()

/** A value on a scale; with [automatic], the value 0 is Automatic. */
class Level(
    override val key: String,
    override val title: String,
    val value: Int,
    val min: Int,
    val max: Int,
    val step: Int,
    val label: (Int) -> String,
    val automatic: String? = null,
    val write: (Int) -> Boolean,
) : Setting()

/** A number counted up and down by [step]; with [noLimit], the value 0 is No limit. */
class Count(
    override val key: String,
    override val title: String,
    val value: Int,
    val min: Int,
    val max: Int,
    val step: Int,
    val label: (Int) -> String,
    val noLimit: String? = null,
    val write: (Int) -> Boolean,
) : Setting() {
    fun problem(context: Context, n: Int?): String? =
        if (n == null || (n != 0 || noLimit == null) && (n < min || n > max)) {
            context.getString(R.string.rec_error_range, min, max)
        } else {
            null
        }
}

/** A text the owner types; [validate] says why a text cannot be used. */
class Entry(
    override val key: String,
    override val title: String,
    val summary: String,
    val initial: String,
    val hint: String?,
    val numeric: Boolean = false,
    val validate: (String) -> String?,
    val write: (String) -> Boolean,
) : Setting()

class Group(override val key: String, override val title: String) : Setting()

private fun put(s: RecorderState, key: String, value: Any): Boolean =
    RecorderConfig.set(s.context, key, value.toString())

private fun intIn(s: RecorderState, text: String, min: Int, max: Int): String? {
    val n = text.toIntOrNull()
    return if (n == null || n < min || n > max) s.string(R.string.rec_error_range, min, max)
    else null
}

private fun parseSize(text: String): Pair<Int, Int>? {
    val parts = text.lowercase(Locale.ROOT).split('x', '×', '*', ' ').filter { it.isNotEmpty() }
    if (parts.size != 2) return null
    val w = parts[0].toIntOrNull() ?: return null
    val h = parts[1].toIntOrNull() ?: return null
    return if (w in 64..8192 && h in 64..8192) w to h else null
}

/** The settings of each page (REC-2 to REC-6). */
internal object RecorderPages {
    private val FRAME_RATES = listOf(24, 30, 60, 90, 120)
    private val KEY_INTERVALS = listOf(1, 2, 5, 10)
    private const val CUSTOM = "custom"

    fun video(s: RecorderState): List<Setting> {
        val c = s.config
        val out = ArrayList<Setting>()
        out += Choice(
            "codec", s.string(R.string.rec_codec),
            options =
                listOf(Option("", s.string(R.string.rec_codec_auto,
                    Encoders.codecName(RecorderConfig.defaultCodec())))) +
                    Encoders.CODECS.mapNotNull { (mime, name) ->
                        val all = Encoders.encodersOf(mime)
                        if (all.isEmpty()) return@mapNotNull null
                        val problem =
                            if (s.webm != Encoders.takesWebm(mime)) {
                                s.string(if (s.webm) R.string.rec_error_codec_webm
                                    else R.string.rec_error_codec_mp4)
                            } else null
                        Option(mime, name, s.hardware(all.firstOrNull { it.isHardwareAccelerated }
                            ?: all[0]), problem)
                    },
            value = c.codec,
            summary = Encoders.codecName(s.codec) + (s.encoder?.let { " · " + s.hardware(it) } ?: ""),
        ) { v -> put(s, "codec", v) and put(s, "encoder", "") and put(s, "profile", -1) and
            put(s, "level", -1) }
        out += Choice(
            "encoder", s.string(R.string.rec_encoder),
            options = listOf(Option("", s.string(R.string.rec_encoder_auto))) +
                Encoders.encodersOf(s.codec).map { Option(it.name, it.name, s.hardware(it)) },
            value = c.encoder,
            summary = s.encoder?.name ?: s.string(R.string.rec_encoder_auto),
        ) { v -> put(s, "encoder", v) and put(s, "profile", -1) and put(s, "level", -1) }
        val levels = s.caps?.profileLevels.orEmpty().distinctBy { it.profile to it.level }
        val profileValue = "${c.profile}:${c.level}"
        out += Choice(
            "profile", s.string(R.string.rec_profile_level),
            options = listOf(Option("-1:-1", s.string(R.string.rec_automatic))) +
                levels.map {
                    Option("${it.profile}:${it.level}", s.string(R.string.rec_profile_level_value,
                        Encoders.profileName(s.codec, it.profile),
                        Encoders.levelName(s.codec, it.level)))
                },
            value = profileValue,
            summary =
                if (c.profile < 0) s.string(R.string.rec_automatic)
                else s.string(R.string.rec_profile_level_value, Encoders.profileName(s.codec, c.profile),
                    Encoders.levelName(s.codec, c.level)),
        ) { v ->
            val (p, l) = v.split(":")
            put(s, "profile", p) and put(s, "level", l)
        }
        val tenBit = RecorderConfig.tenBitProfile(s.codec)
        val hasTenBit = s.caps?.profileLevels.orEmpty().any { it.profile == tenBit }
        out += Choice(
            "bit_depth", s.string(R.string.rec_bit_depth),
            options = listOf(
                Option("8", s.string(R.string.rec_8_bit)),
                Option("10", s.string(R.string.rec_10_bit),
                    problem = if (hasTenBit) null else s.string(R.string.rec_error_10_bit)),
            ),
            value = c.bitDepth.toString(),
            summary = s.string(if (c.bitDepth == 10) R.string.rec_10_bit else R.string.rec_8_bit),
        ) { v -> put(s, "bit_depth", v) }

        val sizeEntry = Entry(
            "size", s.string(R.string.rec_resolution), "", "${s.asked[0]}x${s.asked[1]}",
            s.string(R.string.rec_size_hint),
            validate = { t -> if (parseSize(t) == null) s.string(R.string.rec_error_size) else null },
        ) { t ->
            val (w, h) = parseSize(t)!!
            put(s, "width", w) and put(s, "height", h) and put(s, "resolution", 0)
        }
        val resValue = if (c.resolution == 0) CUSTOM else c.resolution.toString()
        val resChoice = when (resValue) {
            "100" -> s.string(R.string.rec_same_as_screen)
            CUSTOM -> s.string(R.string.rec_size_value, s.asked[0], s.asked[1])
            else -> if (resValue == "75") "3/4" else "1/2"
        }
        val recorded = s.recorded
        out += Choice(
            "resolution", s.string(R.string.rec_resolution),
            options = listOf(
                Option("100", s.string(R.string.rec_same_as_screen)),
                Option("75", "3/4"),
                Option("50", "1/2"),
                Option(CUSTOM, s.string(R.string.rec_custom), entry = sizeEntry),
            ),
            value = resValue,
            summary = when {
                recorded == null -> s.string(R.string.rec_error_no_size, resChoice)
                recorded[0] != s.asked[0] || recorded[1] != s.asked[1] ->
                    s.string(R.string.rec_size_brought, resChoice, recorded[0], recorded[1])
                else -> s.string(R.string.rec_size_records, resChoice, recorded[0], recorded[1])
            },
        ) { v -> put(s, "resolution", v) }

        fun checkFrameRate(t: String): String? {
            intIn(s, t, 1, 480)?.let { return it }
            val max = s.maxFrameRate ?: return null
            return if (t.toInt() > max) s.string(R.string.rec_error_frame_rate, max) else null
        }
        val fpsEntry = Entry(
            "frame_rate", s.string(R.string.rec_frame_rate), "",
            c.frameRate.takeIf { it > 0 }?.toString() ?: "", s.string(R.string.rec_fps_hint),
            numeric = true, validate = ::checkFrameRate,
        ) { t -> put(s, "frame_rate", t) }
        val screenFps = s.maxFrameRate?.let { minOf(s.refreshRate, it) } ?: s.refreshRate
        out += Choice(
            "frame_rate", s.string(R.string.rec_frame_rate),
            options = listOf(Option("0", s.string(R.string.rec_same_as_screen))) +
                FRAME_RATES.map {
                    Option(it.toString(), it.toString(), problem = checkFrameRate(it.toString()))
                } + Option(CUSTOM, s.string(R.string.rec_custom), entry = fpsEntry),
            value = if (c.frameRate == 0 || c.frameRate in FRAME_RATES) c.frameRate.toString()
                else CUSTOM,
            summary =
                if (c.frameRate == 0) s.string(R.string.rec_frame_rate_screen, screenFps)
                else s.string(R.string.rec_fps, c.frameRate),
        ) { v -> put(s, "frame_rate", v) }
        out += Choice(
            "every_frame", s.string(R.string.rec_frames),
            options = listOf(
                Option("0", s.string(R.string.rec_frames_changes)),
                Option("1", s.string(R.string.rec_frames_every)),
            ),
            value = if (c.everyFrame) "1" else "0",
            summary = s.string(if (c.everyFrame) R.string.rec_frames_every
                else R.string.rec_frames_changes),
        ) { v -> put(s, "every_frame", v) }

        val vc = s.caps?.videoCapabilities
        val maxMbps = vc?.bitrateRange?.upper?.let { (it / 1_000_000).coerceIn(1, 200) } ?: 200
        val minMbps = vc?.bitrateRange?.lower?.let { maxOf(1, (it + 999_999) / 1_000_000) } ?: 1
        out += Level(
            "bitrate", s.string(R.string.rec_bitrate),
            value = (c.bitrateKbps + 500) / 1000, min = minMbps, max = maxMbps, step = 1,
            label = { s.string(R.string.rec_mbps, it.toString()) },
            automatic = s.resolved?.let { r ->
                s.string(R.string.rec_bitrate_auto, s.string(R.string.rec_mbps, s.mbps(r.bitrate / 1000)))
            } ?: s.string(R.string.rec_automatic),
        ) { v -> put(s, "bitrate", v * 1000) }
        val ec = s.caps?.encoderCapabilities
        fun modeProblem(mode: Int) =
            if (ec != null && !ec.isBitrateModeSupported(mode)) {
                s.string(R.string.rec_error_bitrate_mode)
            } else null
        out += Choice(
            "bitrate_mode", s.string(R.string.rec_bitrate_mode),
            options = listOf(
                Option("1", s.string(R.string.rec_mode_variable), problem = modeProblem(1)),
                Option("2", s.string(R.string.rec_mode_constant), problem = modeProblem(2)),
                Option("0", s.string(R.string.rec_mode_quality), problem = modeProblem(0)),
            ),
            value = c.bitrateMode.toString(),
            summary = s.string(when (c.bitrateMode) {
                0 -> R.string.rec_mode_quality
                2 -> R.string.rec_mode_constant
                else -> R.string.rec_mode_variable
            }),
        ) { v -> put(s, "bitrate_mode", v) }
        if (c.bitrateMode == RecorderConfig.BITRATE_MODE_CQ) {
            val range = ec?.qualityRange
            val min = range?.lower ?: 0
            val max = range?.upper ?: 100
            val current = s.resolved?.quality?.takeIf { it >= 0 } ?: c.quality.takeIf { it >= 0 }
                ?: (max * 3 / 4)
            out += Count(
                "quality", s.string(R.string.rec_quality),
                value = current, min = min, max = max, step = 1,
                label = { s.string(R.string.rec_quality_value, it, min, max) },
            ) { v -> put(s, "quality", v) }
        }
        val keyEntry = Entry(
            "key_interval", s.string(R.string.rec_key_interval), "", "",
            s.string(R.string.rec_seconds_hint), numeric = true,
            validate = { t -> intIn(s, t, 1, 3600) },
        ) { t -> put(s, "key_interval", t) }
        val key = c.keyInterval
        out += Choice(
            "key_interval", s.string(R.string.rec_key_interval),
            options = KEY_INTERVALS.map { Option(it.toString(), s.plural(R.plurals.rec_seconds, it)) } +
                listOf(
                    Option("0", s.string(R.string.rec_key_every)),
                    Option("-1", s.string(R.string.rec_key_first)),
                    Option(CUSTOM, s.string(R.string.rec_custom), entry = keyEntry),
                ),
            value = if (key in KEY_INTERVALS || key == 0 || key == -1) key.toString() else CUSTOM,
            summary = when (key) {
                0 -> s.string(R.string.rec_key_every)
                -1 -> s.string(R.string.rec_key_first)
                else -> s.plural(R.plurals.rec_seconds, key)
            },
        ) { v -> put(s, "key_interval", v) }
        out += Count(
            "b_frames", s.string(R.string.rec_b_frames),
            value = c.bFrames, min = 0, max = 16, step = 1, label = { it.toString() },
        ) { v -> put(s, "b_frames", v) }
        out += Choice(
            "orientation", s.string(R.string.rec_orientation),
            options = listOf(
                Option("0", s.string(R.string.rec_orientation_start)),
                Option("1", s.string(R.string.rec_orientation_portrait)),
                Option("2", s.string(R.string.rec_orientation_landscape)),
            ),
            value = c.orientation.toString(),
            summary = s.string(when (c.orientation) {
                1 -> R.string.rec_orientation_portrait
                2 -> R.string.rec_orientation_landscape
                else -> R.string.rec_orientation_start
            }),
        ) { v -> put(s, "orientation", v) }
        return out
    }

    fun audio(s: RecorderState): List<Setting> {
        val c = s.config
        val out = ArrayList<Setting>()
        val webmAudio = s.webm && c.audioCodec != "opus"
        out += Choice(
            "audio_source", s.string(R.string.rec_audio_source),
            options = (RecorderConfig.AUDIO_NONE..RecorderConfig.AUDIO_BOTH).map {
                Option(it.toString(), s.audioSourceLabel(it),
                    problem = if (it != RecorderConfig.AUDIO_NONE && webmAudio) {
                        s.string(R.string.rec_error_webm_audio)
                    } else null)
            },
            value = c.audioSource.toString(),
            summary = s.audioSourceLabel(c.audioSource),
        ) { v -> put(s, "audio_source", v) }
        if (c.audioSource == RecorderConfig.AUDIO_BOTH) {
            out += Choice(
                "separate_tracks", s.string(R.string.rec_both_sources),
                options = listOf(
                    Option("0", s.string(R.string.rec_mixed)),
                    Option("1", s.string(R.string.rec_separate),
                        problem = if (s.webm) s.string(R.string.rec_error_webm_tracks) else null),
                ),
                value = if (c.separateTracks) "1" else "0",
                summary = s.string(if (c.separateTracks) R.string.rec_separate else R.string.rec_mixed),
            ) { v -> put(s, "separate_tracks", v) }
        }
        val percent = { v: Int -> s.string(R.string.rec_percent, v) }
        if (c.audioSource == RecorderConfig.AUDIO_DEVICE || c.audioSource == RecorderConfig.AUDIO_BOTH) {
            out += Level("internal_volume", s.string(R.string.rec_device_volume),
                value = c.internalVolume, min = 0, max = 200, step = 5, label = percent) { v ->
                put(s, "internal_volume", v)
            }
        }
        if (c.audioSource == RecorderConfig.AUDIO_MIC || c.audioSource == RecorderConfig.AUDIO_BOTH) {
            out += Level("mic_volume", s.string(R.string.rec_mic_volume),
                value = c.micVolume, min = 0, max = 200, step = 5, label = percent) { v ->
                put(s, "mic_volume", v)
            }
        }
        out += Choice(
            "audio_codec", s.string(R.string.rec_audio_codec),
            options = listOf("aac_lc", "aac_he").map { v ->
                Option(v, s.audioCodecLabel(v),
                    problem = if (s.webm) s.string(R.string.rec_error_webm_audio) else null)
            } + (if (Encoders.hasOpus) {
                listOf(Option("opus", "Opus", problem =
                    if (c.sampleRate != 48000) s.string(R.string.rec_error_opus_rate) else null))
            } else emptyList()),
            value = c.audioCodec,
            summary = s.audioCodecLabel(c.audioCodec),
        ) { v -> put(s, "audio_codec", v) }
        out += Choice(
            "sample_rate", s.string(R.string.rec_sample_rate),
            options = listOf(
                Option("44100", "44.1 kHz",
                    problem = if (c.audioCodec == "opus") s.string(R.string.rec_error_opus_rate) else null),
                Option("48000", "48 kHz"),
            ),
            value = c.sampleRate.toString(),
            summary = if (c.sampleRate == 48000) "48 kHz" else "44.1 kHz",
        ) { v -> put(s, "sample_rate", v) }
        out += Choice(
            "channels", s.string(R.string.rec_channels),
            options = listOf(
                Option("1", s.string(R.string.rec_mono)),
                Option("2", s.string(R.string.rec_stereo)),
            ),
            value = c.channels.toString(),
            summary = s.string(if (c.channels == 1) R.string.rec_mono else R.string.rec_stereo),
        ) { v -> put(s, "channels", v) }
        out += Count(
            "audio_bitrate", s.string(R.string.rec_audio_bitrate),
            value = c.audioBitrateKbps, min = 8, max = 512, step = 32,
            label = { s.string(R.string.rec_kbps, it) },
        ) { v -> put(s, "audio_bitrate", v) }
        out += Group("microphone", s.string(R.string.rec_microphone))
        val ns = NoiseSuppressor.isAvailable()
        out += Toggle("noise_suppression", s.string(R.string.rec_noise_suppression),
            s.string(if (ns) R.string.rec_noise_suppression_summary else R.string.rec_not_on_phone),
            c.noiseSuppression && ns, ns) { v -> put(s, "noise_suppression", if (v) 1 else 0) }
        val aec = AcousticEchoCanceler.isAvailable()
        out += Toggle("echo_cancellation", s.string(R.string.rec_echo_cancellation),
            s.string(if (aec) R.string.rec_echo_cancellation_summary else R.string.rec_not_on_phone),
            c.echoCancellation && aec, aec) { v -> put(s, "echo_cancellation", if (v) 1 else 0) }
        return out
    }

    fun file(s: RecorderState): List<Setting> {
        val c = s.config
        val webmCodec = Encoders.takesWebm(s.codec)
        val audio = c.audioSource != RecorderConfig.AUDIO_NONE
        val out = ArrayList<Setting>()
        out += Choice(
            "format", s.string(R.string.rec_format),
            options = listOf(
                Option("mp4", "MP4",
                    problem = if (webmCodec) s.string(R.string.rec_error_format_mp4) else null),
                Option("webm", "WebM", problem = when {
                    !webmCodec -> s.string(R.string.rec_error_format_webm)
                    audio && c.audioCodec != "opus" -> s.string(R.string.rec_error_webm_audio)
                    audio && c.separateTracks && c.audioSource == RecorderConfig.AUDIO_BOTH ->
                        s.string(R.string.rec_error_webm_tracks)
                    else -> null
                }),
            ),
            value = c.format,
            summary = s.string(if (s.webm) R.string.rec_format_webm_takes
                else R.string.rec_format_mp4_takes),
        ) { v -> put(s, "format", v) }
        out += Entry(
            "folder", s.string(R.string.rec_folder), c.folder, c.folder,
            s.string(R.string.rec_folder_hint),
            validate = { t ->
                val f = t.trim('/')
                val ok = listOf("DCIM", "Movies", "Pictures").any { f == it || f.startsWith("$it/") }
                if (ok && !f.contains("..")) null else s.string(R.string.rec_error_folder)
            },
        ) { t -> put(s, "folder", t.trim('/')) }
        out += Entry(
            "file_name", s.string(R.string.rec_file_name),
            s.string(R.string.rec_file_name_next,
                nextName(c.fileName) + if (s.webm) ".webm" else ".mp4"),
            c.fileName, s.string(R.string.rec_file_name_hint),
            validate = { t ->
                if (t.isEmpty() || t.contains('/')) s.string(R.string.rec_error_file_name) else null
            },
        ) { t -> put(s, "file_name", t) }
        out += Count(
            "longest", s.string(R.string.rec_longest),
            value = c.longestMinutes, min = 1, max = 100000, step = 5,
            label = { s.limitLabel(it) }, noLimit = s.string(R.string.rec_no_limit),
        ) { v -> put(s, "longest", v) }
        out += Count(
            "largest", s.string(R.string.rec_largest),
            value = c.largestMb, min = 1, max = 10000000, step = 500,
            label = { if (it == 0) s.string(R.string.rec_no_limit) else s.string(R.string.rec_mb, it) },
            noLimit = s.string(R.string.rec_no_limit),
        ) { v -> put(s, "largest", v) }
        out += Choice(
            "at_limit", s.string(R.string.rec_at_limit),
            options = listOf(
                Option("0", s.string(R.string.rec_limit_stop)),
                Option("1", s.string(R.string.rec_limit_new_file)),
            ),
            value = if (c.newFileAtLimit) "1" else "0",
            summary = s.string(if (c.newFileAtLimit) R.string.rec_limit_new_file
                else R.string.rec_limit_stop),
        ) { v -> put(s, "at_limit", v) }
        return out
    }

    fun recording(s: RecorderState): List<Setting> {
        val c = s.config
        return listOf(
            Choice(
                "countdown", s.string(R.string.rec_countdown),
                options = listOf(0, 3, 5, 10).map { Option(it.toString(), s.countdownLabel(it)) },
                value = c.countdownSeconds.toString(),
                summary = s.countdownLabel(c.countdownSeconds),
            ) { v -> put(s, "countdown", v) },
            Toggle("keep_screen_on", s.string(R.string.rec_keep_screen_on),
                s.string(R.string.rec_keep_screen_on_summary), c.keepScreenOn, true) { v ->
                put(s, "keep_screen_on", if (v) 1 else 0)
            },
            Toggle("dnd", s.string(R.string.rec_dnd), s.string(R.string.rec_dnd_summary),
                c.doNotDisturb, true) { v -> put(s, "dnd", if (v) 1 else 0) },
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

/** Why a profile name cannot be used, or null. */
fun profileNameProblem(context: Context, name: String): String? =
    when {
        name.isEmpty() || name.startsWith("@") -> context.getString(R.string.rec_error_profile_name)
        name in RecorderProfiles.names(context) -> context.getString(R.string.rec_error_profile_exists)
        else -> null
    }
