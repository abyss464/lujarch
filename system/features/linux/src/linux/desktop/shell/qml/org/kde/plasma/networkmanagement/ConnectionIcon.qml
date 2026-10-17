// plasma-nm's ConnectionIcon for Plasma Mobile's shell, over Android's connection (LNX-7, Android's
// quick settings): the status bar's internet indicator shows the icon of the network the phone uses
// now, with its strength, as Android reports it. Linux beside Android has no NetworkManager; the
// session puts this directory ahead of the real module in QML_IMPORT_PATH.
import QtQuick
import org.lujarch.android as Android

QtObject {
    readonly property bool connecting: false
    readonly property string connectionIcon: Android.Bridge.networkIcon
    readonly property string connectionTooltipIcon: connectionIcon
    property int connectivity: 0
}
