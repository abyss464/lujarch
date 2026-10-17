// BluezQt's Manager for Plasma Mobile's shell, over Android's Bluetooth (LNX-7, Android's quick
// settings). The status bar's Bluetooth indicator (MobileShell.BluetoothInfo) shows itself when this
// is operational and lights up when a device is connected; Linux beside Android has no BlueZ, the
// radio is Android's. Only what the shell reads is here: the session puts this directory ahead of
// the real module in QML_IMPORT_PATH.
pragma Singleton

import QtQuick
import org.lujarch.android as Android

QtObject {
    id: root

    readonly property bool initialized: true
    readonly property bool operational: true
    readonly property bool bluetoothOperational: Android.Bridge.bluetoothOn
    readonly property bool bluetoothBlocked: !Android.Bridge.bluetoothOn
    readonly property var adapters: []
    readonly property var usableAdapter: null
    readonly property var rfkill: null
    // The paired devices that are connected now, each {name, connected}
    readonly property var devices: Android.Bridge.bluetoothDevices

    signal adapterAdded(var adapter)
    signal adapterRemoved(var adapter)
    signal adapterChanged(var adapter)
    signal deviceAdded(var device)
    signal deviceRemoved(var device)
    signal deviceChanged(var device)
    signal allAdaptersRemoved()

    onDevicesChanged: deviceChanged(null)
}
