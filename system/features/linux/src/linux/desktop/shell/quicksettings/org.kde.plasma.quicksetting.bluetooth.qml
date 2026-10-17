// Bluetooth: Plasma Mobile's quick setting of this name over Android's own switch and state (LNX-7,
// Android's quick settings; org.lujarch.android). It takes the place of Plasma's stock tile, which
// needs services Linux beside Android does not have.
import QtQuick 2.15

import org.kde.plasma.private.mobileshell.quicksettingsplugin as QS
import org.lujarch.android as Android

QS.QuickSetting {
    text: Android.Bridge.text("lnx_qs_bluetooth")
    status: {
        var devices = Android.Bridge.bluetoothDevices
        var names = []
        for (var i = 0; i < devices.length; ++i)
            names.push(devices[i].name)
        return names.join(Android.Bridge.text("lnx_qs_list_separator"))
    }
    icon: Android.Bridge.bluetoothDevices.length > 0 ? "network-bluetooth-activated" : "network-bluetooth"
    enabled: Android.Bridge.bluetoothOn

    function toggle() {
        Android.Bridge.setBluetooth(!enabled)
    }
}
