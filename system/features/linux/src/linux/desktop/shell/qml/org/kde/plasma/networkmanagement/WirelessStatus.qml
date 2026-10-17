// plasma-nm's WirelessStatus over Android's Wi-Fi and hotspot. Android does not say the hotspot's
// name to this app, so while it is on `hotspotSSID` is a placeholder (the shell only tests whether it is
// empty, to show the hotspot icon).
import QtQuick
import org.lujarch.android as Android

QtObject {
    readonly property string wifiSSID: Android.Bridge.wifiConnected ? Android.Bridge.wifiSsid : ""
    readonly property string hotspotSSID: Android.Bridge.hotspotOn ? Android.Bridge.text("lnx_qs_hotspot") : ""
}
