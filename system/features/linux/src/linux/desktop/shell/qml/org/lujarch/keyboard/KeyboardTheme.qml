// The Linux desktop's on-screen keyboard (LNX-6) is Gboard as the phone draws it. Every size here is in
// design pixels: the phone's physical pixels, 1080 px wide. The style scales them by the keyboard's width,
// so at the desktop's scale of 1.5 they come out 1:1.
pragma Singleton

import QtQuick
import QtQuick.VirtualKeyboard.Settings

QtObject {
    id: theme

    readonly property string styleName: "Lujarch"

    // Geometry. The candidate band sits where Gboard's toolbar is; under it the key strip (the terminal's
    // keys row's look), then Gboard's four rows.
    readonly property real designWidth: 1080
    readonly property real bandHeight: 122
    readonly property real bandCenter: 57
    readonly property real stripCell: 112
    readonly property real stripHeight: 105
    readonly property real stripRadius: 52
    readonly property real stripPadding: 16
    readonly property real rowPitch: 155
    readonly property real sideMargin: 5
    readonly property real bottomMargin: 10
    readonly property real keyboardHeight: stripCell + 4 * rowPitch + bottomMargin
    // A letter key is 93 x 124 in a 107 x 155 cell, corners of 18; ?123 and enter are pills.
    readonly property real keyInsetX: 7
    readonly property real keyInsetY: 15.5
    readonly property real keyRadius: 18
    // The number pads: 255 x 140 pills in 267 x 155 cells.
    readonly property real padInsetX: 6
    readonly property real padInsetY: 7.5

    // Colours: Gboard's dark theme; the accent pair is Android's system_accent1_200 and _800.
    readonly property color background: "#1a191f"
    readonly property color key: "#2d2b34"
    readonly property color functionKey: "#3c394d"
    readonly property color text: "#e8e3f0"
    readonly property color hint: "#ada9b5"
    readonly property color pressedText: "#58565e"
    readonly property color strip: "#313035"
    readonly property color stripText: "#e5e1e7"
    readonly property color separator: "#494750"
    readonly property color accent: "#c8c0f8"
    readonly property color accentText: "#302a58"

    // Type: Gboard draws with Android's own fonts, so the keyboard does too: Linux sees them at
    // /usr/share/fonts/android and android-product. Without them the keyboard uses the desktop's.
    readonly property string fontDir: "file:///usr/share/fonts/android/"
    readonly property string productFontDir: "file:///usr/share/fonts/android-product/"
    readonly property string fontFamily: "Google Sans Flex"
    readonly property string cjkFamily: "Noto Sans CJK SC"
    property FontLoader sansFont: FontLoader {
        source: theme.productFontDir + "GoogleSansFlex-Regular.ttf"
    }
    property FontLoader cjkFont: FontLoader {
        source: theme.fontDir + "NotoSansCJK-Regular.ttc"
    }
    property FontLoader emojiFont: FontLoader {
        source: theme.fontDir + "NotoColorEmoji.ttf"
    }

    // The family for a label: the CJK one when it has Chinese characters or fullwidth punctuation.
    function family(text) {
        return /[\u3000-\u9fff\uff00-\uffef]/.test(text) ? cjkFamily : fontFamily
    }
    readonly property real letterSize: 68
    readonly property real letterBaseline: 84
    readonly property real hintSize: 33
    readonly property real hintBaseline: 35
    readonly property real hintRight: 4
    readonly property real labelSize: 47
    readonly property real labelBaseline: 80
    readonly property real spaceLabelSize: 36
    readonly property real stripTextSize: 42
    readonly property real candidateSize: 58
    readonly property real padDigitSize: 78
    readonly property real padDigitBaseline: 100
    readonly property real padLettersSize: 35

    // Called by every layout once it is loaded. plasma-keyboard selects its own Breeze style when its panel
    // is complete (main.qml, compiled into the program), so the layouts, which are ours, select this one and
    // keep it selected. The candidate band shows only while it has candidates (pinyin being typed), and a
    // long press opens the symbols after Gboard's 300 ms instead of Qt's 500.
    function setup(keyboard) {
        if (VirtualKeyboardSettings.styleName !== styleName)
            VirtualKeyboardSettings.styleName = styleName
        VirtualKeyboardSettings.wordCandidateList.alwaysVisible = false
        if (keyboard)
            keyboard.pressAndHoldDelay = 300
    }

    property Connections styleGuard: Connections {
        target: VirtualKeyboardSettings
        function onStyleNameChanged() {
            if (VirtualKeyboardSettings.styleName !== theme.styleName)
                Qt.callLater(theme.setup, null)
        }
    }
}
