// plasma-nm's CellularModem over Android's radio: signal 0-100 (Android's five levels), operator name,
// SIM state and mobile data.
import QtQuick
import org.lujarch.android as Android

QtObject {
    readonly property int signalStrength: Android.Bridge.mobileLevel * 25
    readonly property string operatorName: Android.Bridge.mobileOperator
    readonly property bool simLocked: false
    readonly property bool simEmpty: !Android.Bridge.mobilePresent
    readonly property bool needsAPNAdded: false
    readonly property bool mobileDataSupported: Android.Bridge.mobilePresent
    readonly property bool mobileDataEnabled: Android.Bridge.mobileOn
}
