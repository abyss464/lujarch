package org.lujarch.probe.ui;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Answers each connection to the abstract socket "lujarch-ui" with the screen's elements as
 * uiautomator's dump writes them: "active" for the focused window, "windows" for every window.
 * It reads the tree as it is, without waiting for the screen to be still. "drag x1 y1 x2 y2 hold
 * move" drags as a finger does: pressed for hold ms, moved over move ms, rested, lifted.
 */
public class Server extends Instrumentation {
    private static final String SOCKET = "lujarch-ui";

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = ui.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        ui.setServiceInfo(info);
        try (LocalServerSocket server = new LocalServerSocket(SOCKET)) {
            while (true) {
                try (LocalSocket client = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            client.getInputStream(), StandardCharsets.UTF_8));
                    String command = in.readLine();
                    if (command != null && command.startsWith("drag ")) {
                        String[] a = command.split(" ");
                        drag(ui, Float.parseFloat(a[1]), Float.parseFloat(a[2]), Float.parseFloat(a[3]),
                                Float.parseFloat(a[4]), Long.parseLong(a[5]), Long.parseLong(a[6]));
                        OutputStream o = client.getOutputStream();
                        o.write("ok\n".getBytes(StandardCharsets.UTF_8));
                        o.flush();
                        continue;
                    }
                    // Every read is fresh: pages such as Quick Settings' editor scroll without
                    // telling the cache, which would keep answering where things were.
                    ui.clearCache();
                    StringBuilder out = new StringBuilder("<?xml version='1.0' encoding='UTF-8'"
                            + " standalone='yes' ?><hierarchy rotation=\"0\">");
                    if ("windows".equals(command)) {
                        List<AccessibilityWindowInfo> windows = ui.getWindows();
                        for (AccessibilityWindowInfo w : windows) {
                            AccessibilityNodeInfo root = w.getRoot();
                            if (root != null) write(out, root);
                        }
                    } else {
                        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
                        if (root != null) write(out, root);
                    }
                    out.append("</hierarchy>\n");
                    OutputStream o = client.getOutputStream();
                    o.write(out.toString().getBytes(StandardCharsets.UTF_8));
                    o.flush();
                } catch (Exception e) {
                    android.util.Log.w("lujarch_test", "screen not read", e);
                }
            }
        } catch (Exception e) {
            android.util.Log.e("lujarch_test", "screen reader stopped", e);
            finish(1, new Bundle());
        }
    }

    private static void drag(UiAutomation ui, float x1, float y1, float x2, float y2, long hold,
            long move) throws InterruptedException {
        long down = SystemClock.uptimeMillis();
        touch(ui, down, MotionEvent.ACTION_DOWN, x1, y1);
        Thread.sleep(hold);
        int steps = (int) Math.max(1, move / 16);
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            touch(ui, down, MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
            Thread.sleep(16);
        }
        // At rest over the target, as a finger lingers before it lets go.
        for (int i = 0; i < 12; i++) {
            touch(ui, down, MotionEvent.ACTION_MOVE, x2, y2);
            Thread.sleep(50);
        }
        touch(ui, down, MotionEvent.ACTION_UP, x2, y2);
    }

    private static void touch(UiAutomation ui, long down, int action, float x, float y) {
        MotionEvent e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        ui.injectInputEvent(e, true);
        e.recycle();
    }

    private static void write(StringBuilder out, AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        out.append("<node text=\"").append(escape(n.getText()))
                .append("\" resource-id=\"").append(escape(n.getViewIdResourceName()))
                .append("\" class=\"").append(escape(n.getClassName()))
                .append("\" package=\"").append(escape(n.getPackageName()))
                .append("\" content-desc=\"").append(escape(n.getContentDescription()))
                .append("\" checkable=\"").append(n.isCheckable())
                .append("\" checked=\"").append(n.isChecked())
                .append("\" enabled=\"").append(n.isEnabled())
                .append("\" selected=\"").append(n.isSelected())
                .append("\" password=\"").append(n.isPassword())
                .append("\" bounds=\"[").append(r.left).append(',').append(r.top).append("][")
                .append(r.right).append(',').append(r.bottom).append("]\">");
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo child = n.getChild(i);
            if (child != null) write(out, child);
        }
        out.append("</node>");
    }

    private static String escape(CharSequence s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\n': b.append("&#10;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
}
