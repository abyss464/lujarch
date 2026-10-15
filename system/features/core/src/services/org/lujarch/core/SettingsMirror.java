package org.lujarch.core;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors Lujarch's Global settings into read-only properties for init and native services.
 * The list holds one switch per line: settings key, property, default value, tab-separated.
 */
final class SettingsMirror {
    private static final String TAG = "lujarch_core";

    private final Context mContext;
    private final String mListPath;

    SettingsMirror(Context context, String listPath) {
        mContext = context;
        mListPath = listPath;
    }

    void start() {
        List<String[]> entries = readList();
        HandlerThread thread = new HandlerThread(TAG + "_mirror");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        ContentResolver resolver = mContext.getContentResolver();
        for (String[] entry : entries) {
            String key = entry[0];
            String property = entry[1];
            String fallback = entry[2];
            ContentObserver observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    mirror(resolver, key, property, fallback);
                }
            };
            resolver.registerContentObserver(Settings.Global.getUriFor(key), false, observer);
            mirror(resolver, key, property, fallback);
        }
        Log.i(TAG, "mirroring " + entries.size() + " settings");
    }

    private static void mirror(ContentResolver resolver, String key, String property,
            String fallback) {
        String value = Settings.Global.getString(resolver, key);
        try {
            SystemProperties.set(property, value != null ? value : fallback);
        } catch (RuntimeException e) {
            Log.e(TAG, "cannot mirror " + key + " into " + property, e);
        }
    }

    private List<String[]> readList() {
        List<String[]> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(Paths.get(mListPath), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                if (fields.length != 3) {
                    Log.e(TAG, mListPath + ": malformed line: " + line);
                    continue;
                }
                entries.add(fields);
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot read " + mListPath, e);
        }
        return entries;
    }
}
