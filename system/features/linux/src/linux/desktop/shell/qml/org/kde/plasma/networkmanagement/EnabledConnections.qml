// plasma-nm's EnabledConnections over Android's radios.
import QtQuick
import org.lujarch.android as Android

QtObject {
    readonly property bool networkingEnabled: true
    readonly property bool wirelessEnabled: Android.Bridge.wifiOn
    readonly property bool wirelessHwEnabled: true
    readonly property bool wwanEnabled: Android.Bridge.mobileOn
    readonly property bool wwanHwEnabled: Android.Bridge.mobilePresent
}
