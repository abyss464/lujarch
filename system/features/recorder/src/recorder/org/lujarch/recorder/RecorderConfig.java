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

package org.lujarch.recorder;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Range;
import android.view.Display;



import java.util.Calendar;
import java.util.Locale;

/**
 * Screen record's settings (target/recorder.md), each a Settings.Secure value of the user that
 * records, and what they come to on this phone's encoders.
 */
public final class RecorderConfig {
    public static final String TAG = "lujarch_recorder";
    public static final String PREFIX = "lujarch_recorder_";

    /** Settings > System > Screen record. */
    public static final String ACTION_SETTINGS = "org.lujarch.recorder.action.SCREEN_RECORD";

    public static final String KEY_AUDIO_SOURCE = PREFIX + "audio_source";
    public static final String KEY_COUNTDOWN = PREFIX + "countdown";

    /** The audio sources, in the order of SystemUI's ScreenRecordingAudioSource. */
    public static final int AUDIO_NONE = 0;
    public static final int AUDIO_DEVICE = 1;
    public static final int AUDIO_MIC = 2;
    public static final int AUDIO_BOTH = 3;

    public static final int BITRATE_MODE_CQ = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ;
    public static final int BITRATE_MODE_VBR = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
    public static final int BITRATE_MODE_CBR = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR;

    public String codec;
    public String encoder;
    public int profile;
    public int level;
    public int bitDepth;
    public int resolution;
    public int width;
    public int height;
    public int frameRate;
    public boolean everyFrame;
    public int bitrateKbps;
    public int bitrateMode;
    public int quality;
    public int keyInterval;
    public int bFrames;
    public int orientation;

    /** One of the AUDIO_ values. */
    public int audioSource;
    public boolean separateTracks;
    public int internalVolume;
    public int micVolume;
    public String audioCodec;
    public int sampleRate;
    public int channels;
    public int audioBitrateKbps;
    public boolean noiseSuppression;
    public boolean echoCancellation;

    public String format;
    public String folder;
    public String fileName;
    public int longestMinutes;
    public int largestMb;
    public boolean newFileAtLimit;

    public int countdownSeconds;
    public boolean keepScreenOn;
    public boolean doNotDisturb;

    private RecorderConfig() {
    }

    public static RecorderConfig read(Context context) {
        ContentResolver cr = context.getContentResolver();
        int user = context.getUserId();
        RecorderConfig c = new RecorderConfig();
        c.codec = string(cr, user, "codec", "");
        c.encoder = string(cr, user, "encoder", "");
        c.profile = integer(cr, user, "profile", -1);
        c.level = integer(cr, user, "level", -1);
        c.bitDepth = integer(cr, user, "bit_depth", 8);
        c.resolution = integer(cr, user, "resolution", 100);
        c.width = integer(cr, user, "width", 0);
        c.height = integer(cr, user, "height", 0);
        c.frameRate = integer(cr, user, "frame_rate", 0);
        c.everyFrame = integer(cr, user, "every_frame", 0) != 0;
        c.bitrateKbps = integer(cr, user, "bitrate", 0);
        c.bitrateMode = integer(cr, user, "bitrate_mode", BITRATE_MODE_VBR);
        c.quality = integer(cr, user, "quality", -1);
        c.keyInterval = integer(cr, user, "key_interval", 1);
        c.bFrames = integer(cr, user, "b_frames", 0);
        c.orientation = integer(cr, user, "orientation", 0);
        int source = integer(cr, user, "audio_source", AUDIO_NONE);
        c.audioSource = source >= AUDIO_NONE && source <= AUDIO_BOTH ? source : AUDIO_NONE;
        c.separateTracks = integer(cr, user, "separate_tracks", 0) != 0;
        c.internalVolume = integer(cr, user, "internal_volume", 100);
        c.micVolume = integer(cr, user, "mic_volume", 100);
        c.audioCodec = string(cr, user, "audio_codec", "aac_lc");
        c.sampleRate = integer(cr, user, "sample_rate", 44100);
        c.channels = integer(cr, user, "channels", 2);
        c.audioBitrateKbps = integer(cr, user, "audio_bitrate", 192);
        c.noiseSuppression = integer(cr, user, "noise_suppression", 0) != 0;
        c.echoCancellation = integer(cr, user, "echo_cancellation", 0) != 0;
        c.format = string(cr, user, "format", "mp4");
        c.folder = string(cr, user, "folder", "Movies");
        c.fileName = string(cr, user, "file_name", "screen-yyyyMMdd-HHmmss");
        c.longestMinutes = integer(cr, user, "longest", 60);
        c.largestMb = integer(cr, user, "largest", 5000);
        c.newFileAtLimit = integer(cr, user, "at_limit", 0) != 0;
        c.countdownSeconds = integer(cr, user, "countdown", 3);
        c.keepScreenOn = integer(cr, user, "keep_screen_on", 0) != 0;
        c.doNotDisturb = integer(cr, user, "dnd", 0) != 0;
        return c;
    }

    private static String string(ContentResolver cr, int user, String key, String def) {
        String v = Settings.Secure.getStringForUser(cr, PREFIX + key, user);
        return v == null ? def : v;
    }

    private static int integer(ContentResolver cr, int user, String key, int def) {
        return Settings.Secure.getIntForUser(cr, PREFIX + key, def, user);
    }

    /** The user's audio choice, one of the AUDIO_ values (REC-1, REC-3). */
    public static int audioSource(Context context) {
        return read(context).audioSource;
    }

    public static void setAudioSource(Context context, int source) {
        put(context, KEY_AUDIO_SOURCE, String.valueOf(source));
    }

    /** The countdown before a recording starts, in seconds (REC-6). */
    public static int countdownSeconds(Context context) {
        return read(context).countdownSeconds;
    }

    /** The setting [name] (without the prefix) as it is stored, or null when it is not set. */
    public static String get(Context context, String name) {
        return Settings.Secure.getStringForUser(context.getContentResolver(), PREFIX + name,
                context.getUserId());
    }

    /** The profile last chosen (REC-1), or an empty string. */
    public static String chosenProfile(Context context) {
        String name = get(context, "profile_chosen");
        return name == null ? "" : name;
    }

    /** Writes the setting [name] (without the prefix) of the user [context] runs as. */
    public static boolean set(Context context, String name, String value) {
        return put(context, PREFIX + name, value);
    }

    public static boolean put(Context context, String key, String value) {
        try {
            return Settings.Secure.putStringForUser(context.getContentResolver(), key, value,
                    context.getUserId());
        } catch (SecurityException e) {
            Log.e(TAG, key + " not written", e);
            return false;
        }
    }

    // ---------------------------------------------------------------------------------------
    // What the settings come to on this phone (REC-2, REC-3, REC-4)

    /** A setting that cannot be used, named for the owner. */
    public static final class Unusable extends java.io.IOException {
        public Unusable(String message) {
            super(message);
        }
    }

    /** The video and sound a recording will have. */
    public static final class Resolved {
        public String videoMime;
        public MediaCodecInfo videoEncoder;
        public int width;
        public int height;
        public int frameRate;
        public int bitrate;
        public int bitrateMode = -1;
        public int quality = -1;
        public int profile = -1;
        public int level = -1;
        public int muxerFormat;
        public String fileMime;
        public String extension;
        public String audioMime;
        public int aacProfile;
        public int sampleRate;
        public int audioBitrate;

        /** The bytes a minute takes, or -1 when it follows the picture's quality. */
        public long bytesPerMinute(boolean withAudio, int audioTracks) {
            if (bitrateMode == BITRATE_MODE_CQ) return -1;
            long bits = bitrate + (withAudio ? (long) audioBitrate * audioTracks : 0);
            return bits * 60 / 8;
        }
    }

    public static boolean isVideoMime(String mime) {
        return MediaFormat.MIMETYPE_VIDEO_AVC.equals(mime)
                || MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime)
                || MediaFormat.MIMETYPE_VIDEO_AV1.equals(mime)
                || MediaFormat.MIMETYPE_VIDEO_VP8.equals(mime)
                || MediaFormat.MIMETYPE_VIDEO_VP9.equals(mime);
    }

    /** The codec when none is chosen: H.265 with a hardware encoder, H.264 otherwise. */
    public static String defaultCodec() {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated() || info.isAlias()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                    return MediaFormat.MIMETYPE_VIDEO_HEVC;
                }
            }
        }
        return MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    /** The encoder [name] of [mime], or the codec's first hardware encoder when [name] is empty. */
    public static MediaCodecInfo findEncoder(String mime, String name) {
        MediaCodecInfo software = null;
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS)
                .getCodecInfos()) {
            if (!info.isEncoder() || info.isAlias()) continue;
            boolean takes = false;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) takes = true;
            }
            if (!takes) continue;
            if (!name.isEmpty()) {
                if (info.getName().equals(name)) return info;
                continue;
            }
            if (info.isHardwareAccelerated()) return info;
            if (software == null) software = info;
        }
        return name.isEmpty() ? software : null;
    }

    public static int tenBitProfile(String mime) {
        switch (mime) {
            case MediaFormat.MIMETYPE_VIDEO_AVC: return CodecProfileLevel.AVCProfileHigh10;
            case MediaFormat.MIMETYPE_VIDEO_HEVC: return CodecProfileLevel.HEVCProfileMain10;
            case MediaFormat.MIMETYPE_VIDEO_AV1: return CodecProfileLevel.AV1ProfileMain10;
            case MediaFormat.MIMETYPE_VIDEO_VP9: return CodecProfileLevel.VP9Profile2;
            default: return -1;
        }
    }

    public static int align(int value, int alignment) {
        return Math.max(alignment, value - value % alignment);
    }

    /** The screen's width and height in pixels, as the orientation setting turns them. */
    public int[] screenSize(DisplayMetrics metrics) {
        int small = Math.min(metrics.widthPixels, metrics.heightPixels);
        int large = Math.max(metrics.widthPixels, metrics.heightPixels);
        if (orientation == 1) return new int[] {small, large};
        if (orientation == 2) return new int[] {large, small};
        return new int[] {metrics.widthPixels, metrics.heightPixels};
    }

    /** The size the settings ask for on a screen of [screen]. */
    public int[] askedSize(int[] screen) {
        if (resolution == 0 && width > 0 && height > 0) return new int[] {width, height};
        int scale = resolution > 0 ? resolution : 100;
        return new int[] {screen[0] * scale / 100, screen[1] * scale / 100};
    }

    /** The size nearest [w] x [h] that the encoder takes, or null when it takes none near it. */
    public static int[] fitSize(MediaCodecInfo.VideoCapabilities vc, int w, int h) {
        w = align(w, vc.getWidthAlignment());
        h = align(h, vc.getHeightAlignment());
        if (vc.isSizeSupported(w, h)) return new int[] {w, h};
        int maxW = vc.getSupportedWidths().getUpper();
        int maxH = vc.getSupportedHeights().getUpper();
        double fit = Math.min(1.0, Math.min((double) maxW / w, (double) maxH / h));
        int fw = align((int) (w * fit), vc.getWidthAlignment());
        int fh = align((int) (h * fit), vc.getHeightAlignment());
        while (!vc.isSizeSupported(fw, fh) && fw > vc.getWidthAlignment()
                && fh > vc.getHeightAlignment()) {
            fw = align(fw * 15 / 16, vc.getWidthAlignment());
            fh = align(fh * 15 / 16, vc.getHeightAlignment());
        }
        return vc.isSizeSupported(fw, fh) ? new int[] {fw, fh} : null;
    }

    /** What these settings come to on the display [displayId]. */
    public Resolved resolve(Context context, int displayId) throws Unusable {
        DisplayMetrics metrics = new DisplayMetrics();
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        display.getRealMetrics(metrics);
        return resolve(context, metrics, display.getRefreshRate());
    }

    /**
     * What these settings come to for a screen of [metrics] at [refreshRate]: the encoder, the
     * size, rate and bitrate it takes, the format and the sound. A setting that cannot be used
     * throws [Unusable] naming it.
     */
    public Resolved resolve(Context context, DisplayMetrics metrics, float refreshRate)
            throws Unusable {
        Resolved r = new Resolved();
        r.videoMime = codec.isEmpty() ? defaultCodec() : codec;
        if (!isVideoMime(r.videoMime)) {
            throw new Unusable(context.getString(R.string.rec_error_codec, codec));
        }
        boolean webm = "webm".equals(format);
        boolean webmCodec = MediaFormat.MIMETYPE_VIDEO_VP8.equals(r.videoMime)
                || MediaFormat.MIMETYPE_VIDEO_VP9.equals(r.videoMime);
        if (webm != webmCodec) {
            throw new Unusable(context.getString(R.string.rec_error_format));
        }
        r.muxerFormat = webm ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
        r.fileMime = webm ? "video/webm" : "video/mp4";
        r.extension = webm ? ".webm" : ".mp4";

        r.videoEncoder = findEncoder(r.videoMime, encoder);
        if (r.videoEncoder == null) {
            throw new Unusable(context.getString(R.string.rec_error_encoder,
                    encoder.isEmpty() ? r.videoMime : encoder));
        }
        MediaCodecInfo.CodecCapabilities caps =
                r.videoEncoder.getCapabilitiesForType(r.videoMime);
        MediaCodecInfo.VideoCapabilities vc = caps.getVideoCapabilities();

        // The size.
        int[] asked = askedSize(screenSize(metrics));
        int[] fit = fitSize(vc, asked[0], asked[1]);
        if (fit == null) {
            throw new Unusable(context.getString(R.string.rec_error_no_size_near, asked[0],
                    asked[1]));
        }
        if (fit[0] != asked[0] || fit[1] != asked[1]) {
            Log.i(TAG, "size " + asked[0] + "x" + asked[1] + " brought to " + fit[0] + "x"
                    + fit[1]);
        }
        int w = fit[0];
        int h = fit[1];
        r.width = w;
        r.height = h;

        // The frame rate.
        int fps = frameRate > 0 ? frameRate : Math.round(refreshRate);
        Range<Double> rates = vc.getSupportedFrameRatesFor(w, h);
        if (fps > rates.getUpper()) {
            Log.i(TAG, "frame rate " + fps + " brought to " + rates.getUpper().intValue());
            fps = rates.getUpper().intValue();
        }
        r.frameRate = Math.max(1, fps);

        // The bitrate.
        Range<Integer> bitrates = vc.getBitrateRange();
        int bitrate = bitrateKbps > 0 ? bitrateKbps * 1000 : w * h * r.frameRate / 30 * 6;
        r.bitrate = bitrates.clamp(bitrate);
        MediaCodecInfo.EncoderCapabilities ec = caps.getEncoderCapabilities();
        if (bitrateMode >= 0) {
            if (!ec.isBitrateModeSupported(bitrateMode)) {
                throw new Unusable(context.getString(R.string.rec_error_bitrate_mode));
            }
            r.bitrateMode = bitrateMode;
        }
        if (r.bitrateMode == BITRATE_MODE_CQ) {
            Range<Integer> q = ec.getQualityRange();
            r.quality = quality >= 0 ? q.clamp(quality) : q.getUpper() * 3 / 4;
        }

        // Profile and level.
        r.profile = profile;
        if (r.profile < 0 && bitDepth == 10) {
            r.profile = tenBitProfile(r.videoMime);
            if (r.profile < 0) {
                throw new Unusable(context.getString(R.string.rec_error_10_bit));
            }
        }
        if (r.profile >= 0) {
            boolean found = false;
            for (CodecProfileLevel pl : caps.profileLevels) {
                if (pl.profile == r.profile && (level < 0 || pl.level >= level)) found = true;
            }
            if (!found) {
                throw new Unusable(context.getString(R.string.rec_error_profile_level));
            }
            r.level = level;
        }

        // The sound.
        r.sampleRate = sampleRate;
        r.audioBitrate = audioBitrateKbps * 1000;
        switch (audioCodec) {
            case "opus":
                r.audioMime = MediaFormat.MIMETYPE_AUDIO_OPUS;
                if (sampleRate != 48000) {
                    throw new Unusable(context.getString(R.string.rec_error_opus_rate));
                }
                break;
            case "aac_he":
                r.audioMime = MediaFormat.MIMETYPE_AUDIO_AAC;
                r.aacProfile = CodecProfileLevel.AACObjectHE;
                break;
            default:
                r.audioMime = MediaFormat.MIMETYPE_AUDIO_AAC;
                r.aacProfile = CodecProfileLevel.AACObjectLC;
                break;
        }
        if (webm && audioSource != AUDIO_NONE
                && !MediaFormat.MIMETYPE_AUDIO_OPUS.equals(r.audioMime)) {
            throw new Unusable(context.getString(R.string.rec_error_format));
        }
        return r;
    }

    // ---------------------------------------------------------------------------------------
    // The file (REC-4)

    /** The file name of a recording started at [millis], [part] 1 for the first file. */
    public String fileName(long millis, int part, String extension) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        String name = fileName
                .replace("yyyy", String.format(Locale.ROOT, "%04d", c.get(Calendar.YEAR)))
                .replace("MM", String.format(Locale.ROOT, "%02d", c.get(Calendar.MONTH) + 1))
                .replace("dd", String.format(Locale.ROOT, "%02d", c.get(Calendar.DAY_OF_MONTH)))
                .replace("HH", String.format(Locale.ROOT, "%02d", c.get(Calendar.HOUR_OF_DAY)))
                .replace("mm", String.format(Locale.ROOT, "%02d", c.get(Calendar.MINUTE)))
                .replace("ss", String.format(Locale.ROOT, "%02d", c.get(Calendar.SECOND)))
                .replace('/', '_');
        if (name.isEmpty()) name = "screen";
        return (part > 1 ? name + "-" + part : name) + extension;
    }

    /** The folder, under DCIM, Movies or Pictures, as MediaStore takes it. */
    public String relativePath() {
        String f = folder.replaceAll("^/+|/+$", "");
        if (!(f.equals("DCIM") || f.startsWith("DCIM/") || f.equals("Movies")
                || f.startsWith("Movies/") || f.equals("Pictures") || f.startsWith("Pictures/"))) {
            Log.w(TAG, "folder " + folder + " is not under DCIM, Movies or Pictures; Movies");
            f = "Movies";
        }
        return f + "/";
    }
}
