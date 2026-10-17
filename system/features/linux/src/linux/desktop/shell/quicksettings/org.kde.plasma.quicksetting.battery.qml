// Battery: Plasma Mobile's quick setting of this name over Android's own switch and state (LNX-7,
// Android's quick settings; org.lujarch.android). It takes the place of Plasma's stock tile, which
// needs services Linux beside Android does not have.
import QtQuick 2.15

import org.kde.plasma.private.mobileshell.quicksettingsplugin as QS
import org.lujarch.android as Android

QS.QuickSetting {
    text: Android.Bridge.text("lnx_qs_battery")
    status: Android.Bridge.batteryPercent < 0 ? "" : Android.Bridge.batteryPercent + "%"
            + (Android.Bridge.batteryCharging ? " · " + Android.Bridge.text("lnx_qs_charging") : "")
    icon: "battery-full" + (Android.Bridge.batteryCharging ? "-charging" : "")
    enabled: false
}
