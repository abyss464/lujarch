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

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Screen record's profiles (REC-5), for the recording dialog and the settings page alike: the
 * built-in ones, named with @, and those the owner saved in lujarch_recorder_profiles.
 */
public final class RecorderProfiles {
    private static final String TAG = "lujarch_recorder";
    public static final String PREFIX = "lujarch_recorder_";
    public static final String KEY_PROFILES = PREFIX + "profiles";

    public static final String HIGH_QUALITY = "@high";
    public static final String SMALL_FILE = "@small";

    /** The settings a profile holds: every setting of the video, the sound and the file. */
    public static final String[] KEYS = {
            "codec", "encoder", "profile", "level", "bit_depth", "resolution", "width", "height",
            "frame_rate", "every_frame", "bitrate", "bitrate_mode", "quality", "key_interval",
            "b_frames", "orientation", "audio_source", "separate_tracks", "internal_volume",
            "mic_volume", "audio_codec", "sample_rate", "channels", "audio_bitrate",
            "noise_suppression", "echo_cancellation", "format", "folder", "file_name", "longest",
            "largest", "at_limit",
    };

    // High quality: the screen's size and refresh rate at a high bitrate; Small file: half the
    // size at 30 frames per second at a low bitrate.
    private static final String BUILT_IN = "{\"" + HIGH_QUALITY + "\":{\"resolution\":100,"
            + "\"frame_rate\":0,\"bitrate\":40000,\"bitrate_mode\":1,\"audio_bitrate\":256},"
            + "\"" + SMALL_FILE + "\":{\"resolution\":50,\"frame_rate\":30,\"bitrate\":3000,"
            + "\"bitrate_mode\":1,\"audio_bitrate\":96}}";

    private RecorderProfiles() {
    }

    /** The name a profile shows. */
    public static String label(Context context, String name) {
        switch (name) {
            case HIGH_QUALITY:
                return context.getString(R.string.rec_profile_high);
            case SMALL_FILE:
                return context.getString(R.string.rec_profile_small);
            default:
                return name;
        }
    }

    public static boolean isBuiltIn(String name) {
        return name.startsWith("@");
    }

    /** Every profile's name, the built-in ones first. */
    public static List<String> names(Context context) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> i = all(context).keys(); i.hasNext(); ) names.add(i.next());
        return names;
    }

    /** Every profile by name with the settings it holds. */
    public static JSONObject all(Context context) {
        JSONObject all = new JSONObject();
        try {
            copy(new JSONObject(BUILT_IN), all);
            copy(saved(context), all);
        } catch (JSONException e) {
            Log.e(TAG, "profiles not read", e);
        }
        return all;
    }

    private static void copy(JSONObject from, JSONObject to) throws JSONException {
        for (Iterator<String> i = from.keys(); i.hasNext(); ) {
            String name = i.next();
            to.put(name, from.get(name));
        }
    }

    private static JSONObject saved(Context context) {
        String s = Settings.Secure.getStringForUser(context.getContentResolver(), KEY_PROFILES,
                context.getUserId());
        if (s == null || s.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            Log.e(TAG, "saved profiles not read", e);
            return new JSONObject();
        }
    }

    private static boolean writeSaved(Context context, JSONObject saved) {
        return put(context, KEY_PROFILES, saved.toString());
    }

    private static boolean put(Context context, String key, String value) {
        try {
            return Settings.Secure.putStringForUser(context.getContentResolver(), key, value,
                    context.getUserId());
        } catch (SecurityException e) {
            Log.e(TAG, key + " not written", e);
            return false;
        }
    }

    /** Sets every setting the profile [name] holds; returns whether all were written. */
    public static boolean use(Context context, String name) {
        JSONObject profile = all(context).optJSONObject(name);
        if (profile == null) {
            Log.e(TAG, "no profile " + name);
            return false;
        }
        boolean ok = true;
        for (Iterator<String> i = profile.keys(); i.hasNext(); ) {
            String key = i.next();
            ok &= put(context, PREFIX + key, String.valueOf(profile.opt(key)));
        }
        Log.i(TAG, "profile " + name + " in use");
        return ok;
    }

    /** Saves the current settings as the profile [name]. */
    public static boolean saveCurrent(Context context, String name) {
        JSONObject profile = new JSONObject();
        try {
            for (String key : KEYS) {
                String value = Settings.Secure.getStringForUser(context.getContentResolver(),
                        PREFIX + key, context.getUserId());
                if (value != null) profile.put(key, value);
            }
            JSONObject saved = saved(context);
            saved.put(name, profile);
            return writeSaved(context, saved);
        } catch (JSONException e) {
            Log.e(TAG, "profile " + name + " not saved", e);
            return false;
        }
    }

    public static boolean rename(Context context, String name, String newName) {
        JSONObject saved = saved(context);
        Object profile = saved.opt(name);
        if (profile == null) return false;
        saved.remove(name);
        try {
            saved.put(newName, profile);
        } catch (JSONException e) {
            Log.e(TAG, "profile " + name + " not renamed", e);
            return false;
        }
        if (!writeSaved(context, saved)) return false;
        if (name.equals(RecorderConfig.chosenProfile(context))) {
            RecorderConfig.set(context, "profile_chosen", newName);
        }
        return true;
    }

    public static boolean delete(Context context, String name) {
        JSONObject saved = saved(context);
        saved.remove(name);
        return writeSaved(context, saved);
    }
}
