// Auto-rotate: Plasma Mobile's quick setting of this name over Android's own switch and state (LNX-7,
// Android's quick settings; org.lujarch.android). It takes the place of Plasma's stock tile, which
// needs services Linux beside Android does not have.
import QtQuick 2.15

import org.kde.plasma.private.mobileshell.quicksettingsplugin as QS
import org.lujarch.android as Android

QS.QuickSetting {
    text: Android.Bridge.text("lnx_qs_auto_rotate")
    icon: "rotation-allowed"
    enabled: Android.Bridge.rotationAuto

    function toggle() {
        Android.Bridge.setRotation(!enabled)
    }
}
