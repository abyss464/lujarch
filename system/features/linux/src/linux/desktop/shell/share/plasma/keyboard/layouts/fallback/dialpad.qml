// Gboard's phone pad (LNX-6): the digits in a column 20 px left of centre with their letters beside them,
// minus, space, backspace and next (Tab) on the right. *# types * (# on a long press), 0 types 0 (+ on a
// long press). Under the key strip; no candidate band, as Gboard has none here.
import QtQuick
import QtQuick.Layouts
import QtQuick.VirtualKeyboard
import QtQuick.VirtualKeyboard.Components
import org.lujarch.keyboard

KeyboardLayout {
    readonly property var pad: keyboard.style ? keyboard.style.padKeyPanel : undefined
    keyWeight: 267
    Component.onCompleted: KeyboardTheme.setup(keyboard)

    KeyStripRow {
    }
    KeyboardRow {
        Key { text: "1"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        Key { text: "2"; property string padLetters: "ABC"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        Key { text: "3"; property string padLetters: "DEF"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        Key { text: "-"; displayText: "−"; highlighted: true; showPreview: false; keyPanelDelegate: pad }
    }
    KeyboardRow {
        Key { text: "4"; property string padLetters: "GHI"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        Key { text: "5"; property string padLetters: "JKL"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        Key { text: "6"; property string padLetters: "MNO"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        SpaceKey {
            property string padIcon: "space"
            keyPanelDelegate: pad
        }
    }
    KeyboardRow {
        Key { text: "7"; property string padLetters: "PQRS"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        Key { text: "8"; property string padLetters: "TUV"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        Key { text: "9"; property string padLetters: "WXYZ"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        BackspaceKey {
            property string padIcon: "backspace"
            keyPanelDelegate: pad
        }
    }
    KeyboardRow {
        Key { text: "*"; displayText: "*#"; alternativeKeys: ["*", "#"]; showPreview: false; keyPanelDelegate: pad }
        Key { text: "0"; alternativeKeys: ["0", "+"]; property string padLetters: "+"; property bool padColumn: true; showPreview: false; keyPanelDelegate: pad }
        Key { text: "."; showPreview: false; keyPanelDelegate: pad }
        Key {
            key: Qt.Key_Tab
            functionKey: true
            highlighted: true
            showPreview: false
            property string padIcon: "tab"
            keyPanelDelegate: pad
        }
    }
}
