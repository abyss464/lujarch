package org.lujarch.probe.ime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;

/**
 * org.lujarch.probe.ime.TYPE with extras: compose (text set as composing, then committed with
 * commit=1), commit (text committed at once), key (a key code sent down and up), delete (characters
 * deleted before the cursor).
 */
public class Keyboard extends InputMethodService {
    static final String ACTION = "org.lujarch.probe.ime.TYPE";

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            String compose = intent.getStringExtra("compose");
            if (compose != null) {
                ic.setComposingText(compose, 1);
                if ("1".equals(intent.getStringExtra("commit"))) ic.finishComposingText();
            }
            String commit = intent.getStringExtra("commit_text");
            if (commit != null) ic.commitText(commit, 1);
            String delete = intent.getStringExtra("delete");
            if (delete != null) ic.deleteSurroundingText(Integer.parseInt(delete), 0);
            String key = intent.getStringExtra("key");
            if (key != null) {
                int code = Integer.parseInt(key);
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(receiver, new IntentFilter(ACTION), Context.RECEIVER_EXPORTED);
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        View v = new View(this);
        v.setMinimumHeight(1);
        return v;
    }
}
