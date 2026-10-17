// Caffeine: Plasma Mobile's quick setting of this name over Android's own switch and state (LNX-7,
// Android's quick settings; org.lujarch.android). It takes the place of Plasma's stock tile, which
// needs services Linux beside Android does not have.
import QtQuick 2.15

import org.kde.plasma.private.mobileshell.quicksettingsplugin as QS
import org.lujarch.android as Android

QS.QuickSetting {
    text: Android.Bridge.text("lnx_qs_caffeine")
    status: enabled
            ? Android.Bridge.text("lnx_qs_caffeine_on")
            : ""
    icon: "system-suspend-hibernate"
    enabled: Android.Bridge.caffeineOn

    function toggle() {
        Android.Bridge.setCaffeine(!enabled)
    }
}
