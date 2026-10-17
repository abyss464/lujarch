// Fields that prefer numbers get Gboard's number pad, the same as digit fields (digits.qml).
import QtQuick
import QtQuick.VirtualKeyboard.Components

KeyboardLayoutLoader {
    source: Qt.resolvedUrl("digits.qml")
}
