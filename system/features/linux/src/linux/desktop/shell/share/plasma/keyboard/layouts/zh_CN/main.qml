// Gboard's pinyin keyboard (LNX-6), under the key strip. Weights are Gboard's cells in design pixels: a
// letter 107, shift and backspace 160, the second row's half-key indent 54. The corner symbols are
// Gboard's: what a long press types, the fullwidth forms where Gboard's pinyin keyboard has them.
import QtQuick
import QtQuick.Layouts
import QtQuick.VirtualKeyboard
import QtQuick.VirtualKeyboard.Components
import org.lujarch.keyboard

KeyboardLayout {
    function createInputMethod() {
        return Qt.createQmlObject('import QtQuick; import QtQuick.VirtualKeyboard.Plugins; PinyinInputMethod {}', parent, "inputMethod.qml")
    }
    sharedLayouts: ['symbols']
    keyWeight: 107
    smallTextVisible: true
    Component.onCompleted: KeyboardTheme.setup(keyboard)

    KeyStripRow {
    }
    KeyboardRow {
        Key { key: Qt.Key_Q; text: "q"; alternativeKeys: ["q", "1"] }
        Key { key: Qt.Key_W; text: "w"; alternativeKeys: ["w", "2"] }
        Key { key: Qt.Key_E; text: "e"; alternativeKeys: ["e", "3"] }
        Key { key: Qt.Key_R; text: "r"; alternativeKeys: ["r", "4"] }
        Key { key: Qt.Key_T; text: "t"; alternativeKeys: ["t", "5"] }
        Key { key: Qt.Key_Y; text: "y"; alternativeKeys: ["y", "6"] }
        Key { key: Qt.Key_U; text: "u"; alternativeKeys: ["u", "7"] }
        Key { key: Qt.Key_I; text: "i"; alternativeKeys: ["i", "8"] }
        Key { key: Qt.Key_O; text: "o"; alternativeKeys: ["o", "9"] }
        Key { key: Qt.Key_P; text: "p"; alternativeKeys: ["p", "0"] }
    }
    KeyboardRow {
        FillerKey { weight: 54 }
        Key { key: Qt.Key_A; text: "a"; alternativeKeys: ["a", "@"] }
        Key { key: Qt.Key_S; text: "s"; alternativeKeys: ["s", "#"] }
        Key { key: Qt.Key_D; text: "d"; alternativeKeys: ["d", "$"] }
        Key { key: Qt.Key_F; text: "f"; alternativeKeys: ["f", "_"] }
        Key { key: Qt.Key_G; text: "g"; alternativeKeys: ["g", "&"] }
        Key { key: Qt.Key_H; text: "h"; alternativeKeys: ["h", "-"] }
        Key { key: Qt.Key_J; text: "j"; alternativeKeys: ["j", "+"] }
        Key { key: Qt.Key_K; text: "k"; alternativeKeys: ["k", "\uff08"] }
        Key { key: Qt.Key_L; text: "l"; alternativeKeys: ["l", "\uff09"] }
        FillerKey { weight: 54 }
    }
    KeyboardRow {
        // Shift, or Gboard's syllable mark (an apostrophe and the character for word) while
        // pinyin is being typed.
        ShiftKey {
            weight: 160
            visible: InputContext.preeditText.length === 0
        }
        Key {
            weight: 160
            visible: InputContext.preeditText.length > 0
            key: Qt.Key_Apostrophe
            text: "'"
            displayText: "'\u8bcd"
            functionKey: true
            highlighted: true
            showPreview: false
        }
        Key { key: Qt.Key_Z; text: "z"; alternativeKeys: ["z", "\uff40"] }
        Key { key: Qt.Key_X; text: "x"; alternativeKeys: ["x", "\""] }
        Key { key: Qt.Key_C; text: "c"; alternativeKeys: ["c", "·"] }
        Key { key: Qt.Key_V; text: "v"; alternativeKeys: ["v", "\uff1a"] }
        Key { key: Qt.Key_B; text: "b"; alternativeKeys: ["b", "\uff1b"] }
        Key { key: Qt.Key_N; text: "n"; alternativeKeys: ["n", "\uff01"] }
        Key { key: Qt.Key_M; text: "m"; alternativeKeys: ["m", "\uff1f"] }
        BackspaceKey {
            weight: 160
        }
    }
    KeyboardRow {
        SymbolModeKey {
            weight: 160
            displayText: "?123"
        }
        // The comma carries Gboard's emoji face; a long press offers the faces Gboard's emoji key would.
        Key {
            key: Qt.Key_Comma
            text: "\uff0c"
            displayText: ","
            highlighted: true
            property string topIcon: "emoji"
            alternativeKeys: ["\uff0c", "😂", "❤️", "👍", "🙏", "😭", "😊", "🤔"]
            smallTextVisible: false
        }
        ChangeLanguageKey {
            weight: 107
            highlighted: false
        }
        SpaceKey {
            weight: 429
            highlighted: false
        }
        Key {
            key: Qt.Key_Period
            text: "\u3002"
            highlighted: true
            alternativeKeys: ["\u3002", "\uff1f", "\uff01", "\uff0c", "\u3001", "\uff1a", "\uff1b", "……", "\uff5e"]
            smallTextVisible: false
        }
        EnterKey {
            weight: 160
        }
    }
}
