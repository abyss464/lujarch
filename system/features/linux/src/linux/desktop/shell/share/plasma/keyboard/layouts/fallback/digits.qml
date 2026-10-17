// Gboard's number pad for number fields (LNX-6): pills in four columns, the right one minus, space,
// backspace and done. Under the key strip; no candidate band, as Gboard has none here.
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
        Key { text: "1"; showPreview: false; keyPanelDelegate: pad }
        Key { text: "2"; showPreview: false; keyPanelDelegate: pad }
        Key { text: "3"; showPreview: false; keyPanelDelegate: pad }
        Key { text: "-"; displayText: "−"; highlighted: true; showPreview: false; keyPanelDelegate: pad }
    }
    KeyboardRow {
        Key { text: "4"; showPreview: false; keyPanelDelegate: pad }
        Key { text: "5"; showPreview: false; keyPanelDelegate: pad }
        Key { text: "6"; showPreview: false; keyPanelDelegate: pad }
        SpaceKey {
            property string padIcon: "space"
            keyPanelDelegate: pad
        }
    }
    KeyboardRow {
        Key { text: "7"; showPreview: false; keyPanelDelegate: pad }
        Key { text: "8"; showPreview: false; keyPanelDelegate: pad }
        Key { text: "9"; showPreview: false; keyPanelDelegate: pad }
        BackspaceKey {
            property string padIcon: "backspace"
            keyPanelDelegate: pad
        }
    }
    KeyboardRow {
        Key { text: ","; showPreview: false; keyPanelDelegate: pad }
        Key { text: "0"; showPreview: false; keyPanelDelegate: pad }
        Key { text: "."; showPreview: false; keyPanelDelegate: pad }
        EnterKey {
            property string padIcon: "done"
            keyPanelDelegate: pad
        }
    }
}
