// plasma-nm's Handler for Plasma Mobile's shell: what its stock Wi-Fi, airplane mode and hotspot
// quick settings call, mapped to Android's own switches (the replacement quick settings call the
// bridge directly).
import QtQuick
import org.lujarch.android as Android

QtObject {
    readonly property bool hotspotSupported: true
    readonly property bool hotspotActive: Android.Bridge.hotspotOn
    readonly property bool scanning: false

    function enableWireless(enable) { Android.Bridge.setWifi(enable) }
    function enableAirplaneMode(enable) { Android.Bridge.setAirplane(enable) }
    function createHotspot() { Android.Bridge.setHotspot(true) }
    function stopHotspot() { Android.Bridge.setHotspot(false) }
}
