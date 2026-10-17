// plasma-nm's CellularModemList for Plasma Mobile's shell: one modem, the phone's own radio as
// Android reports it (the status bar's signal indicator and the stock mobile data quick setting).
// The session puts this directory ahead of the real module in QML_IMPORT_PATH; Linux beside Android
// has no ModemManager.
import QtQuick
import org.lujarch.android as Android

QtObject {
    readonly property bool modemAvailable: Android.Bridge.mobilePresent
    readonly property CellularModem primaryModem: modemAvailable ? modem : null
    readonly property CellularModem modem: CellularModem {}
}
