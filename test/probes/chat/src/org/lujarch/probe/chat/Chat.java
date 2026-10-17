package org.lujarch.probe.chat;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A conversation's screen: its name and the last message, large enough to read on a card. */
public class Chat extends Activity {
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String who = getIntent().getStringExtra("who");
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setBackgroundColor(Color.rgb(0xE8, 0xF0, 0xE4));
        TextView title = new TextView(this);
        title.setText(who == null ? "Probe chat" : who);
        title.setTextSize(34);
        title.setTextColor(Color.rgb(0x20, 0x40, 0x28));
        title.setGravity(Gravity.CENTER);
        TextView body = new TextView(this);
        body.setText("conversation " + (who == null ? "list" : who));
        body.setTextSize(20);
        body.setGravity(Gravity.CENTER);
        column.addView(title);
        column.addView(body);
        setContentView(column);
    }
}
