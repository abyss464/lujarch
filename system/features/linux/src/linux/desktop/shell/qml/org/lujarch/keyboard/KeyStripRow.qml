// The row over Gboard's keys: the key strip, Esc, Tab, the arrows and the symbols a command line wants.
// Every layout starts with it, so it is there on every page. No Ctrl or Alt: plasma-keyboard sends every key without
// modifiers (InputListenerItem::keyPressEvent passes 0), so they could not reach the application.
// Esc, Tab and the arrows have no text, so plasma-keyboard sends them as keys; the arrows repeat while held.
import QtQuick
import QtQuick.Layouts
import QtQuick.VirtualKeyboard
import QtQuick.VirtualKeyboard.Components

KeyboardRow {
    id: row

    // The strip's share of the layout's height: its cell against the four rows of keys.
    Layout.fillHeight: false
    Layout.preferredHeight: parent ? parent.height * KeyboardTheme.stripCell / (KeyboardTheme.keyboardHeight - KeyboardTheme.bottomMargin) : 0

    // The strip's labels start 16 px in from the screen edge, the layout 5 px.
    readonly property real edge: KeyboardTheme.stripPadding - KeyboardTheme.sideMargin
    readonly property real slot: (KeyboardTheme.designWidth - 2 * KeyboardTheme.stripPadding) / 11
    readonly property var panel: keyboard.style ? keyboard.style.stripKeyPanel : undefined

    // Its instances are the row's children: they read the row through parent.
    component StripKey: Key {
        weight: parent ? parent.slot : 0
        showPreview: false
        keyPanelDelegate: parent ? parent.panel : undefined
    }

    FillerKey {
        weight: row.edge
    }
    StripKey {
        key: Qt.Key_Escape
        displayText: "Esc"
        functionKey: true
    }
    StripKey {
        key: Qt.Key_Tab
        displayText: "Tab"
        functionKey: true
    }
    StripKey {
        key: Qt.Key_Left
        displayText: "←"
        functionKey: true
        repeat: true
    }
    StripKey {
        key: Qt.Key_Down
        displayText: "↓"
        functionKey: true
        repeat: true
    }
    StripKey {
        key: Qt.Key_Up
        displayText: "↑"
        functionKey: true
        repeat: true
    }
    StripKey {
        key: Qt.Key_Right
        displayText: "→"
        functionKey: true
        repeat: true
    }
    StripKey {
        text: "~"
    }
    StripKey {
        text: "/"
    }
    StripKey {
        text: "|"
    }
    StripKey {
        text: "-"
    }
    StripKey {
        text: "_"
    }
    FillerKey {
        weight: row.edge
    }
}
