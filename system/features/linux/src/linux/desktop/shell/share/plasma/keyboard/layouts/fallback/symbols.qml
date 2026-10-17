// Gboard's English symbol pages (LNX-6): ?123, then =\< for the second page, 12/34 for a number pad; ABC
// goes back to the letters. en_US uses them, as does any language without symbols of its own.
import QtQuick
import QtQuick.Layouts
import QtQuick.VirtualKeyboard
import QtQuick.VirtualKeyboard.Components
import org.lujarch.keyboard

KeyboardLayoutLoader {
    function createInputMethod() {
        return Qt.createQmlObject('import org.lujarch.keyboard; LatinInputMethod {}', parent, "inputMethodSymbols.qml")
    }
    sharedLayouts: ['main']
    property int page
    readonly property var pad: keyboard.style ? keyboard.style.padKeyPanel : undefined
    Component.onCompleted: KeyboardTheme.setup(keyboard)
    sourceComponent: page === 2 ? numberPad : page === 1 ? secondPage : firstPage

    Component {
        id: firstPage
        KeyboardLayout {
            keyWeight: 107
            KeyStripRow {
            }
            KeyboardRow {
                Key { text: "1" }
                Key { text: "2" }
                Key { text: "3" }
                Key { text: "4" }
                Key { text: "5" }
                Key { text: "6" }
                Key { text: "7" }
                Key { text: "8" }
                Key { text: "9" }
                Key { text: "0" }
            }
            KeyboardRow {
                Key { text: "@" }
                Key { text: "#" }
                Key { text: "$" }
                Key { text: "_" }
                Key { text: "&" }
                Key { text: "-" }
                Key { text: "+" }
                Key { text: "(" }
                Key { text: ")" }
                Key { text: "/" }
            }
            KeyboardRow {
                Key {
                    weight: 160
                    displayText: "=\\<"
                    functionKey: true
                    highlighted: true
                    showPreview: false
                    onClicked: page = 1
                }
                Key { text: "*" }
                Key { text: "\"" }
                Key { text: "'" }
                Key { text: ":" }
                Key { text: ";" }
                Key { text: "!" }
                Key { text: "?" }
                BackspaceKey {
                    weight: 160
                }
            }
            KeyboardRow {
                SymbolModeKey {
                    weight: 160
                    displayText: "ABC"
                }
                Key { text: ","; highlighted: true }
                Key {
                    displayText: "12\n34"
                    functionKey: true
                    showPreview: false
                    onClicked: page = 2
                }
                SpaceKey {
                    weight: 429
                    highlighted: false
                }
                Key { text: "."; highlighted: true }
                EnterKey {
                    weight: 160
                }
            }
        }
    }

    Component {
        id: secondPage
        KeyboardLayout {
            keyWeight: 107
            KeyStripRow {
            }
            KeyboardRow {
                Key { text: "~" }
                Key { text: "`" }
                Key { text: "|" }
                Key { text: "•" }
                Key { text: "√" }
                Key { text: "π" }
                Key { text: "÷" }
                Key { text: "×" }
                Key { text: "¶" }
                Key { text: "∆" }
            }
            KeyboardRow {
                Key { text: "£" }
                Key { text: "¢" }
                Key { text: "€" }
                Key { text: "¥" }
                Key { text: "^" }
                Key { text: "°" }
                Key { text: "=" }
                Key { text: "{" }
                Key { text: "}" }
                Key { text: "\\" }
            }
            KeyboardRow {
                Key {
                    weight: 160
                    displayText: "?123"
                    functionKey: true
                    highlighted: true
                    showPreview: false
                    onClicked: page = 0
                }
                Key { text: "%" }
                Key { text: "©" }
                Key { text: "®" }
                Key { text: "™" }
                Key { text: "✓" }
                Key { text: "[" }
                Key { text: "]" }
                BackspaceKey {
                    weight: 160
                }
            }
            KeyboardRow {
                SymbolModeKey {
                    weight: 160
                    displayText: "ABC"
                }
                Key { text: "<"; highlighted: true }
                Key {
                    displayText: "12\n34"
                    functionKey: true
                    showPreview: false
                    onClicked: page = 2
                }
                SpaceKey {
                    weight: 429
                    highlighted: false
                }
                Key { text: ">"; highlighted: true }
                EnterKey {
                    weight: 160
                }
            }
        }
    }

    // 12/34: Gboard's number pad, operators on the left; a long press on + gives =, on * gives /.
    Component {
        id: numberPad
        KeyboardLayout {
            keyWeight: 214
            KeyStripRow {
            }
            KeyboardRow {
                Key { text: "+"; alternativeKeys: ["+", "="]; showPreview: false; keyPanelDelegate: pad }
                Key { text: "1"; showPreview: false; keyPanelDelegate: pad }
                Key { text: "2"; showPreview: false; keyPanelDelegate: pad }
                Key { text: "3"; showPreview: false; keyPanelDelegate: pad }
                Key { text: "%"; highlighted: true; showPreview: false; keyPanelDelegate: pad }
            }
            KeyboardRow {
                Key { text: "-"; displayText: "−"; showPreview: false; keyPanelDelegate: pad }
                Key { text: "4"; showPreview: false; keyPanelDelegate: pad }
                Key { text: "5"; showPreview: false; keyPanelDelegate: pad }
                Key { text: "6"; showPreview: false; keyPanelDelegate: pad }
                SpaceKey {
                    property string padIcon: "space"
                    keyPanelDelegate: pad
                }
            }
            KeyboardRow {
                Key { text: "*"; alternativeKeys: ["*", "/"]; showPreview: false; keyPanelDelegate: pad }
                Key { text: "7"; showPreview: false; keyPanelDelegate: pad }
                Key { text: "8"; showPreview: false; keyPanelDelegate: pad }
                Key { text: "9"; showPreview: false; keyPanelDelegate: pad }
                BackspaceKey {
                    property string padIcon: "backspace"
                    keyPanelDelegate: pad
                }
            }
            KeyboardRow {
                Key {
                    displayText: "?123"
                    functionKey: true
                    highlighted: true
                    showPreview: false
                    keyPanelDelegate: pad
                    onClicked: page = 0
                }
                Key { text: ","; showPreview: false; keyPanelDelegate: pad }
                Key { text: "0"; showPreview: false; keyPanelDelegate: pad }
                Key { text: "."; showPreview: false; keyPanelDelegate: pad }
                EnterKey {
                    property string padIcon: "enter"
                    keyPanelDelegate: pad
                }
            }
        }
    }
}
