// Wi-Fi: Plasma Mobile's quick setting of this name over Android's own switch and state (LNX-7,
// Android's quick settings; org.lujarch.android). It takes the place of Plasma's stock tile, which
// needs services Linux beside Android does not have.
import QtQuick 2.15

import org.kde.plasma.private.mobileshell.quicksettingsplugin as QS
import org.lujarch.android as Android

QS.QuickSetting {
    text: Android.Bridge.text("lnx_qs_wifi")
    status: Android.Bridge.wifiOn && Android.Bridge.wifiConnected
            ? (Android.Bridge.wifiSsid !== "" ? Android.Bridge.wifiSsid : Android.Bridge.text("lnx_qs_connected"))
            : ""
    icon: Android.Bridge.wifiOn ? "network-wireless" : "network-wireless-disconnected"
    enabled: Android.Bridge.wifiOn

    function toggle() {
        Android.Bridge.setWifi(!enabled)
    }
}
