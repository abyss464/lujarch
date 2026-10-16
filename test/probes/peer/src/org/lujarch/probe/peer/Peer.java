package org.lujarch.probe.peer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * On the extra "to" (host:port, or [IPv6 address]:port), connects, asks for "/" over HTTP and
 * records the first line of the answer, or the error, as the JSON line {"key": "connect", "value",
 * "time"} in files/results.jsonl.
 */
public class Peer extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult result = goAsync();
        final Context app = context.getApplicationContext();
        final String to = intent.getStringExtra("to");
        new Thread(() -> {
            String value;
            try {
                value = connect(to);
            } catch (Throwable e) {
                value = "error: " + e;
            }
            record(app, "connect", value);
            result.finish();
        }).start();
    }

    static String connect(String to) throws Exception {
        // host:port, or [IPv6 address]:port
        int colon = to.lastIndexOf(':');
        String host = to.substring(0, colon).replace("[", "").replace("]", "");
        int port = Integer.parseInt(to.substring(colon + 1));
        try (Socket s = new Socket()) {
            s.setSoTimeout(150000);
            s.connect(new InetSocketAddress(host, port), 8000);
            OutputStream o = s.getOutputStream();
            o.write(("GET / HTTP/1.0\r\nHost: " + host + "\r\n\r\n").getBytes());
            o.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[128];
            int n = in.read(buf);
            return "connected " + (n > 0 ? new String(buf, 0, n).split("\r\n")[0] : "");
        }
    }

    static synchronized void record(Context c, String key, String value) {
        try (FileWriter w = new FileWriter(new File(c.getFilesDir(), "results.jsonl"), true)) {
            w.write("{\"key\":" + json(key) + ",\"value\":" + json(value) + ",\"time\":"
                    + System.currentTimeMillis() + "}\n");
        } catch (Exception ignored) {
        }
    }

    static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            if (ch == '"' || ch == '\\') b.append('\\').append(ch);
            else if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
            else b.append(ch);
        }
        return b.append('"').toString();
    }
}
