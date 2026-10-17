// plasma-nm's Configuration singleton: the airplane mode flag is Android's.
pragma Singleton

import QtQuick
import org.lujarch.android as Android

QtObject {
    readonly property bool airplaneModeEnabled: Android.Bridge.airplaneOn
    property bool unlockModemOnDetection: false
    property bool manageVirtualConnections: false
    property bool systemConnectionsByDefault: true
    property string hotspotName: ""
    property string hotspotPassword: ""
    property string hotspotConnectionPath: ""
    readonly property bool showPasswordDialog: false
}
