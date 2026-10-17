// Mobile data: Plasma Mobile's quick setting of this name over Android's own switch and state (LNX-7,
// Android's quick settings; org.lujarch.android). It takes the place of Plasma's stock tile, which
// needs services Linux beside Android does not have.
import QtQuick 2.15

import org.kde.plasma.private.mobileshell.quicksettingsplugin as QS
import org.lujarch.android as Android

QS.QuickSetting {
    text: Android.Bridge.text("lnx_qs_mobile_data")
    // Empty on and off: the drawer then says On or Off in Plasma's own language.
    status: !Android.Bridge.mobilePresent
            ? (Android.Bridge.stateKnown ? Android.Bridge.text("lnx_qs_no_sim") : "")
            : (Android.Bridge.mobileOn ? Android.Bridge.mobileOperator : "")
    icon: "network-modem"
    enabled: Android.Bridge.mobileOn

    function toggle() {
        if (Android.Bridge.mobilePresent)
            Android.Bridge.setMobile(!enabled)
    }
}
