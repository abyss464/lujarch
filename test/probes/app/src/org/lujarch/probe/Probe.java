package org.lujarch.probe;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaMetadata;
import android.media.MediaRecorder;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.CalendarContract;
import android.provider.DocumentsContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Does what the test orders in the extra "do" (comma-separated actions) and records each result
 * as a JSON line {"key", "value", "time"} in files/results.jsonl; the activity also shows them.
 */
public class Probe extends Activity {
    private TextView view;
    private String pending;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new TextView(this);
        view.setTextSize(13);
        view.setPadding(24, 120, 24, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        setContentView(scroll);
        pending = getIntent().getStringExtra("do");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        pending = intent.getStringExtra("do");
        setIntent(intent);
        if (hasWindowFocus()) onWindowFocusChanged(true);
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        // The clipboard is readable only by the app in focus.
        if (focus && pending != null) {
            final String actions = pending;
            final Intent intent = getIntent();
            pending = null;
            new Thread(() -> {
                perform(this, actions, intent);
                runOnUiThread(() -> view.setText(read(this)));
                if (intent.getBooleanExtra("crash", false)) {
                    runOnUiThread(() -> {
                        throw new IllegalStateException("probe stops on the test's order");
                    });
                }
            }).start();
        }
    }

    private static final int PICK = 2;
    private static final int SAVE = 3;
    private static final int TREE = 4;

    /** What came back from a document request: the document, then what the app can do with it. */
    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        final String key = code == PICK ? "picked" : code == SAVE ? "saved" : "tree";
        final Uri uri = data == null ? null : data.getData();
        if (result != RESULT_OK || uri == null) {
            record(this, key, "no answer");
            return;
        }
        record(this, key, uri.toString());
        final int flags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        new Thread(() -> {
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
                record(this, "persisted", "yes");
            } catch (Exception e) {
                record(this, "persisted", "error: " + e);
            }
            try {
                if (code == PICK) {
                    try (InputStream in = getContentResolver().openInputStream(uri)) {
                        byte[] buf = new byte[64];
                        int n = in.read(buf);
                        record(this, "read", n > 0 ? new String(buf, 0, n).trim() : "");
                    }
                } else if (code == SAVE) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        out.write("probe wrote this".getBytes());
                    }
                    record(this, "wrote", "yes");
                } else {
                    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri,
                            DocumentsContract.getTreeDocumentId(uri));
                    try (Cursor cur = getContentResolver().query(children, null, null, null)) {
                        record(this, "listed", String.valueOf(cur == null ? -1 : cur.getCount()));
                    }
                }
            } catch (Exception e) {
                record(this, code == PICK ? "read" : code == SAVE ? "wrote" : "listed",
                        "error: " + e);
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        for (int i = 0; i < permissions.length; i++) {
            record(this, "requested:" + permissions[i],
                    results[i] == PackageManager.PERMISSION_GRANTED ? "granted" : "denied");
        }
        view.setText(read(this));
    }

    /** Receives the orders a test sends while the probe is not on screen. */
    public static class Background extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                record(context, "started_by", "boot");
                return;
            }
            final PendingResult result = goAsync();
            final Context app = context.getApplicationContext();
            final String actions = intent.getStringExtra("do");
            new Thread(() -> {
                perform(app, actions, intent);
                result.finish();
                if (intent.getBooleanExtra("crash", false)) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        throw new IllegalStateException("probe stops on the test's order");
                    });
                }
            }).start();
        }
    }

    public static class Alarm extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            record(context, "started_by", "alarm");
        }
    }

    public static class Installed extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                record(context, "install", "pending_user_action");
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
                return;
            }
            record(context, "install", status + ":" + message);
        }
    }

    // ---------------------------------------------------------------------------------------

    static void perform(Context c, String actions, Intent intent) {
        if (actions == null) return;
        for (String a : actions.split(",")) {
            a = a.trim();
            try {
                record(c, a, act(c, a, intent));
            } catch (SecurityException e) {
                record(c, a, "refused: " + e.getMessage());
            } catch (Throwable e) {
                record(c, a, "error: " + e);
            }
        }
    }

    static String act(Context c, String a, Intent intent) throws Exception {
        switch (a) {
            case "sensors": return sensors(c);
            case "clipboard": {
                ClipboardManager cm = c.getSystemService(ClipboardManager.class);
                ClipData clip = cm.getPrimaryClip();
                if (clip == null || clip.getItemCount() == 0) return "";
                CharSequence t = clip.getItemAt(0).getText();
                return t == null ? "" : t.toString();
            }
            case "copy": {
                ClipboardManager cm = c.getSystemService(ClipboardManager.class);
                cm.setPrimaryClip(ClipData.newPlainText("probe", intent.getStringExtra("text")));
                return "copied";
            }
            case "apps": {
                List<PackageInfo> all = c.getPackageManager().getInstalledPackages(0);
                List<String> user = new ArrayList<>();
                for (PackageInfo p : all) {
                    if ((p.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) user.add(p.packageName);
                }
                return "total=" + all.size() + " user=" + String.join("|", user);
            }
            case "ids": {
                String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
                return "android_id=" + id;
            }
            case "device": {
                Intent battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                int level = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                String name = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
                return "model=" + Build.MODEL + " maker=" + Build.MANUFACTURER + " build=" + Build.DISPLAY
                        + " tz=" + TimeZone.getDefault().getID() + " battery=" + level + " name=" + name;
            }
            case "network": {
                ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
                Network n = cm.getActiveNetwork();
                LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
                NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
                WifiInfo wifi = c.getSystemService(WifiManager.class).getConnectionInfo();
                BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
                return "addresses=" + (lp == null ? "" : lp.getLinkAddresses())
                        + " dns=" + (lp == null ? "" : lp.getDnsServers())
                        + " vpn=" + (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                        + " rssi=" + (wifi == null ? "" : wifi.getRssi())
                        + " bluetooth=" + (bt != null && bt.isEnabled());
            }
            case "phone": {
                TelephonyManager tm = c.getSystemService(TelephonyManager.class);
                return "operator=" + tm.getNetworkOperatorName() + " country=" + tm.getNetworkCountryIso()
                        + " sim=" + tm.getSimState() + " code=" + tm.getNetworkOperator();
            }
            case "contacts": return count(c, ContactsContract.Contacts.CONTENT_URI);
            case "calendar": return count(c, CalendarContract.Events.CONTENT_URI);
            case "location": {
                LocationManager lm = c.getSystemService(LocationManager.class);
                Location l = null;
                for (String p : lm.getAllProviders()) {
                    Location x = lm.getLastKnownLocation(p);
                    if (x != null) l = x;
                }
                return l == null ? "none" : l.getLatitude() + "," + l.getLongitude();
            }
            case "mic": {
                int seconds = intent.getIntExtra("seconds", 8);
                int rate = 16000;
                int size = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size * 4);
                if (r.getState() != AudioRecord.STATE_INITIALIZED) return "no microphone";
                r.startRecording();
                byte[] buf = new byte[size];
                long total = 0;
                long end = SystemClock.elapsedRealtime() + seconds * 1000L;
                while (SystemClock.elapsedRealtime() < end) {
                    int n = r.read(buf, 0, buf.length);
                    if (n > 0) total += n;
                }
                r.stop();
                r.release();
                return "recorded " + total + " bytes";
            }
            case "play": {
                int seconds = intent.getIntExtra("seconds", 10);
                int rate = 44100;
                short[] tone = new short[rate];
                for (int i = 0; i < tone.length; i++) {
                    tone[i] = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 8000);
                }
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).build();
                t.play();
                for (int s = 0; s < seconds; s++) t.write(tone, 0, tone.length);
                t.stop();
                t.release();
                return "played " + seconds + " s";
            }
            case "media": {
                // A song that plays for seconds with its timed lyrics (FeelUOwn's lyricInfo), its
                // session, and the media notification that opens the probe.
                int seconds = intent.getIntExtra("seconds", 20);
                String lyrics = intent.getStringExtra("lyrics");
                MediaSession session = new MediaSession(c, "probe");
                PendingIntent open = PendingIntent.getActivity(c, 5,
                        new Intent(c, Probe.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE);
                session.setSessionActivity(open);
                MediaMetadata.Builder meta = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, "Probe song")
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Probe")
                        .putLong(MediaMetadata.METADATA_KEY_DURATION, seconds * 1000L);
                if (lyrics != null) {
                    meta.putString("lyricInfo", "{\"lyric\":" + json(lyrics.replace("|", "\n")) + "}");
                }
                session.setMetadata(meta.build());
                session.setPlaybackState(new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f, SystemClock.elapsedRealtime())
                        .setActions(PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY).build());
                session.setActive(true);
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel("media", "Media",
                        NotificationManager.IMPORTANCE_LOW));
                nm.notify(2, new Notification.Builder(c, "media")
                        .setSmallIcon(R.mipmap.ic_probe).setContentTitle("Probe song")
                        .setContentIntent(open)
                        .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()))
                        .build());
                String played = act(c, "play", intent.putExtra("seconds", seconds));
                session.setPlaybackState(new PlaybackState.Builder()
                        .setState(PlaybackState.STATE_STOPPED, 0, 0f).build());
                session.release();
                nm.cancel(2);
                return "media " + played;
            }
            case "track": {
                int seconds = intent.getIntExtra("seconds", 8);
                LocationManager lm = c.getSystemService(LocationManager.class);
                LocationListener listener = l -> { };
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0, listener,
                        Looper.getMainLooper());
                SystemClock.sleep(seconds * 1000L);
                lm.removeUpdates(listener);
                return "tracked";
            }
            case "notify": {
                NotificationManager nm = c.getSystemService(NotificationManager.class);
                nm.createNotificationChannel(new NotificationChannel("probe", "Probe", NotificationManager.IMPORTANCE_DEFAULT));
                String title = intent.getStringExtra("title");
                Notification n = new Notification.Builder(c, "probe")
                        .setSmallIcon(R.mipmap.ic_probe)
                        .setContentTitle(title == null ? "Probe" : title)
                        .setContentText("from the probe").build();
                nm.notify(1, n);
                return "posted enabled=" + nm.areNotificationsEnabled();
            }
            case "install": return install(c, intent.getStringExtra("apk"));
            case "connect": {
                String[] hp = intent.getStringExtra("to").split(":");
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 5000);
                    OutputStream o = s.getOutputStream();
                    o.write(("GET / HTTP/1.0\r\nHost: " + hp[0] + "\r\n\r\n").getBytes());
                    o.flush();
                    InputStream in = s.getInputStream();
                    byte[] buf = new byte[64];
                    int n = in.read(buf);
                    return "connected " + (n > 0 ? new String(buf, 0, n).split("\r\n")[0] : "");
                }
            }
            case "vibrate": {
                c.getSystemService(Vibrator.class).vibrate(VibrationEffect.createOneShot(200, 128));
                return "vibrated";
            }
            case "wakelock": {
                String tag = intent.getStringExtra("tag");
                PowerManager.WakeLock w = c.getSystemService(PowerManager.class).newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, tag == null ? "probe:test" : tag);
                w.acquire(1000);
                return "held";
            }
            case "alarm": {
                int seconds = intent.getIntExtra("seconds", 20);
                AlarmManager am = c.getSystemService(AlarmManager.class);
                PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Alarm.class),
                        PendingIntent.FLAG_IMMUTABLE);
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + seconds * 1000L, pi);
                return "in " + seconds + " s";
            }
            case "pick":
            case "save":
            case "tree": {
                if (!(c instanceof Activity)) return "not on screen";
                Intent i;
                int code;
                if (a.equals("pick")) {
                    String type = intent.getStringExtra("type");
                    i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(type == null ? "*/*" : type);
                    code = PICK;
                } else if (a.equals("save")) {
                    i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                            .putExtra(Intent.EXTRA_TITLE, "probe-saved.txt");
                    code = SAVE;
                } else {
                    i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    code = TREE;
                }
                Activity activity = (Activity) c;
                Intent request = i;
                int requestCode = code;
                activity.runOnUiThread(() -> activity.startActivityForResult(request, requestCode));
                return "asked";
            }
            case "request": {
                if (c instanceof Activity) {
                    ((Activity) c).requestPermissions(intent.getStringExtra("permissions").split(";"), 1);
                    return "asked";
                }
                return "not on screen";
            }
            case "login": {
                Intent i = new Intent(c, Login.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return "opened";
            }
            case "clear": {
                new File(c.getFilesDir(), "results.jsonl").delete();
                return "cleared";
            }
            default: return "unknown action";
        }
    }

    static String count(Context c, android.net.Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            return cur == null ? "null" : "rows=" + cur.getCount();
        }
    }

    static String sensors(Context c) throws InterruptedException {
        SensorManager sm = c.getSystemService(SensorManager.class);
        Sensor s = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (s == null) return "no sensor";
        final CountDownLatch got = new CountDownLatch(1);
        final float[][] values = new float[1][];
        SensorEventListener l = new SensorEventListener() {
            public void onSensorChanged(SensorEvent e) {
                if (values[0] == null) values[0] = e.values.clone();
                got.countDown();
            }
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        boolean ok = sm.registerListener(l, s, SensorManager.SENSOR_DELAY_NORMAL, new Handler(Looper.getMainLooper()));
        if (!ok) return "refused";
        got.await(3, TimeUnit.SECONDS);
        sm.unregisterListener(l);
        if (values[0] == null) return "no readings";
        return "readings " + values[0][0] + "," + values[0][1] + "," + values[0][2];
    }

    static String install(Context c, String apk) throws Exception {
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            File f = new File(apk);
            try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            PendingIntent done = PendingIntent.getBroadcast(c, id, new Intent(c, Installed.class),
                    PendingIntent.FLAG_MUTABLE);
            s.commit(done.getIntentSender());
        }
        return "committed session " + id;
    }

    // ---------------------------------------------------------------------------------------

    static synchronized void record(Context c, String key, String value) {
        try (FileWriter w = new FileWriter(new File(c.getFilesDir(), "results.jsonl"), true)) {
            w.write("{\"key\":" + json(key) + ",\"value\":" + json(value) + ",\"time\":"
                    + System.currentTimeMillis() + "}\n");
        } catch (Exception ignored) {
        }
    }

    static String read(Context c) {
        StringBuilder b = new StringBuilder();
        try (FileInputStream in = new FileInputStream(new File(c.getFilesDir(), "results.jsonl"))) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) b.append(new String(buf, 0, n));
        } catch (Exception ignored) {
        }
        return b.toString();
    }

    static String json(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
