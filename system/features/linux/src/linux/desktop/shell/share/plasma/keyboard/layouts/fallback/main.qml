// Gboard's English keyboard (LNX-6), under the key strip; en_US uses it, as does any language without a
// layout of its own. The weights and the corner symbols are Gboard's; a long press offers the symbol
// first, then the accented letters Gboard's English (US) keyboard offers. No dictionary:
// LatinInputMethod types the keys and offers no candidates, so the candidate band stays hidden.
import QtQuick
import QtQuick.Layouts
import QtQuick.VirtualKeyboard
import QtQuick.VirtualKeyboard.Components
import org.lujarch.keyboard

KeyboardLayout {
    function createInputMethod() {
        return Qt.createQmlObject('import org.lujarch.keyboard; LatinInputMethod {}', parent, "inputMethod.qml")
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
        Key { key: Qt.Key_E; text: "e"; alternativeKeys: ["e", "3", "è", "é", "ê", "ë", "ē", "ę", "ė"] }
        Key { key: Qt.Key_R; text: "r"; alternativeKeys: ["r", "4"] }
        Key { key: Qt.Key_T; text: "t"; alternativeKeys: ["t", "5"] }
        Key { key: Qt.Key_Y; text: "y"; alternativeKeys: ["y", "6", "ý", "ÿ"] }
        Key { key: Qt.Key_U; text: "u"; alternativeKeys: ["u", "7", "ù", "ú", "û", "ü", "ū"] }
        Key { key: Qt.Key_I; text: "i"; alternativeKeys: ["i", "8", "ì", "í", "î", "ï", "ī", "į"] }
        Key { key: Qt.Key_O; text: "o"; alternativeKeys: ["o", "9", "ò", "ó", "ô", "ö", "õ", "ø", "ō", "œ"] }
        Key { key: Qt.Key_P; text: "p"; alternativeKeys: ["p", "0"] }
    }
    KeyboardRow {
        FillerKey { weight: 54 }
        Key { key: Qt.Key_A; text: "a"; alternativeKeys: ["a", "@", "à", "á", "â", "ä", "æ", "ã", "å", "ā"] }
        Key { key: Qt.Key_S; text: "s"; alternativeKeys: ["s", "#", "ß", "ś", "š"] }
        Key { key: Qt.Key_D; text: "d"; alternativeKeys: ["d", "$"] }
        Key { key: Qt.Key_F; text: "f"; alternativeKeys: ["f", "_"] }
        Key { key: Qt.Key_G; text: "g"; alternativeKeys: ["g", "&"] }
        Key { key: Qt.Key_H; text: "h"; alternativeKeys: ["h", "-"] }
        Key { key: Qt.Key_J; text: "j"; alternativeKeys: ["j", "+"] }
        Key { key: Qt.Key_K; text: "k"; alternativeKeys: ["k", "("] }
        Key { key: Qt.Key_L; text: "l"; alternativeKeys: ["l", ")"] }
        FillerKey { weight: 54 }
    }
    KeyboardRow {
        ShiftKey {
            weight: 160
        }
        Key { key: Qt.Key_Z; text: "z"; alternativeKeys: ["z", "*"] }
        Key { key: Qt.Key_X; text: "x"; alternativeKeys: ["x", "\""] }
        Key { key: Qt.Key_C; text: "c"; alternativeKeys: ["c", "'", "ç", "ć", "č"] }
        Key { key: Qt.Key_V; text: "v"; alternativeKeys: ["v", ":"] }
        Key { key: Qt.Key_B; text: "b"; alternativeKeys: ["b", ";"] }
        Key { key: Qt.Key_N; text: "n"; alternativeKeys: ["n", "!", "ñ", "ń"] }
        Key { key: Qt.Key_M; text: "m"; alternativeKeys: ["m", "?"] }
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
            text: ","
            highlighted: true
            property string topIcon: "emoji"
            alternativeKeys: [",", "😂", "❤️", "👍", "🙏", "😭", "😊", "🤔"]
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
            text: "."
            highlighted: true
            alternativeKeys: [".", "?", "!", ",", "'", "\"", ":", ";", "-", "/"]
            smallTextVisible: false
        }
        EnterKey {
            weight: 160
        }
    }
}
