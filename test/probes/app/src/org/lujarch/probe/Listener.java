package org.lujarch.probe;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Reads other apps' notifications once the user grants notification access. */
public class Listener extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Probe.record(this, "notification_seen", sbn.getPackageName());
    }
}
