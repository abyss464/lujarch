package org.lujarch.probe.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.net.Uri;

/**
 * Posts what a messaging app posts, on the extra "do":
 * "message": a message from the person "who" in the conversation of that name, a MessagingStyle
 * notification of a long-lived sharing shortcut with bubble metadata, as apps that offer their
 * conversations as bubbles post them;
 * "notice": an ordinary notification whose tap opens the app, with the title "who".
 */
public class Post extends BroadcastReceiver {
    static final String CHANNEL = "messages";

    @Override
    public void onReceive(Context c, Intent intent) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel channel =
                new NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(channel);
        String who = intent.getStringExtra("who");
        if (who == null) who = "Lin";
        String text = intent.getStringExtra("text");
        if (text == null) text = "Is the place still open on the weekend?";
        Intent open = new Intent(c, Chat.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("probechat://" + who))
                .putExtra("who", who);
        PendingIntent content = PendingIntent.getActivity(c, who.hashCode(), open,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int id = Math.abs(who.hashCode());
        if ("notice".equals(intent.getStringExtra("do"))) {
            nm.notify(id, new Notification.Builder(c, CHANNEL)
                    .setSmallIcon(R.mipmap.ic_chat)
                    .setContentTitle(who)
                    .setContentText(text)
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .build());
            return;
        }
        Icon icon = Icon.createWithResource(c, R.mipmap.ic_chat);
        Person person = new Person.Builder().setName(who).setKey(who).setIcon(icon).build();
        ShortcutInfo shortcut = new ShortcutInfo.Builder(c, who)
                .setShortLabel(who)
                .setLongLived(true)
                .setPerson(person)
                .setIcon(icon)
                .setLocusId(new LocusId(who))
                .setCategories(java.util.Set.of("org.lujarch.probe.chat.category.CHAT"))
                .setIntent(open)
                .build();
        c.getSystemService(ShortcutManager.class).pushDynamicShortcut(shortcut);
        Notification.BubbleMetadata bubble =
                new Notification.BubbleMetadata.Builder(content, icon)
                        .setDesiredHeight(600)
                        .build();
        Person me = new Person.Builder().setName("Me").build();
        Notification.MessagingStyle style = new Notification.MessagingStyle(me)
                .addMessage(text, System.currentTimeMillis(), person);
        nm.notify(id, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.mipmap.ic_chat)
                .setContentTitle(who)
                .setContentText(text)
                .setContentIntent(content)
                .setShortcutId(who)
                .setLocusId(new LocusId(who))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setStyle(style)
                .setBubbleMetadata(bubble)
                .build());
    }
}
