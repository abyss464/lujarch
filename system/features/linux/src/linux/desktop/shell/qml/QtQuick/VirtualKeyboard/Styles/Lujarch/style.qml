// The Linux desktop's on-screen keyboard (LNX-6) drawn as Gboard draws it on the phone. Sizes and colours
// are KeyboardTheme's design pixels; scaleHint turns them into the keyboard's pixels (the keyboard is as
// wide as the screen, 1080 design pixels). The icons in images/ are Gboard's own at that size.
import QtQuick
import QtQuick.Window
import QtQuick.Effects
import QtQuick.VirtualKeyboard
import QtQuick.VirtualKeyboard.Styles
import org.lujarch.keyboard
import org.kde.plasma.keyboard

KeyboardStyle {
    id: currentStyle

    readonly property var t: KeyboardTheme
    // plasma-keyboard's own switches keep their meaning: its key sound, and its vibration request, which
    // the desktop's android daemon plays on Android's vibrator while vibration is lent.
    readonly property url keySound: PlasmaKeyboardSettings.soundEnabled ? "qrc:///sounds/keyboard_tick2_quiet.wav" : ""

    // plasma-keyboard makes its panel aspectRatio * targetKeyboardHeight wide (main.qml), and Qt makes it
    // keyboardDesignHeight / keyboardDesignWidth as tall. In portrait that is the screen's width at Gboard's
    // proportions. In landscape the keyboard is 40% of the screen wide at landscapeAspect (keys taller than
    // in portrait, about 1.8 times as tall as wide), and KWin docks it at the work area's bottom right,
    // beside the navigation panel, the application's width giving way to it (kwin.patch).
    readonly property bool landscape: Screen.width > Screen.height
    readonly property real landscapeAspect: 1.22
    readonly property real landscapeWidth: Screen.width * 0.40
    readonly property real targetKeyboardHeight: landscape ? landscapeWidth / landscapeAspect
                                                           : Screen.width * t.keyboardHeight / t.designWidth
    readonly property real aspectRatio: keyboardDesignWidth / keyboardDesignHeight

    keyboardDesignWidth: t.designWidth
    keyboardDesignHeight: landscape ? t.designWidth / landscapeAspect : t.keyboardHeight
    keyboardRelativeLeftMargin: t.sideMargin / t.designWidth
    keyboardRelativeRightMargin: t.sideMargin / t.designWidth
    keyboardRelativeTopMargin: 0
    keyboardRelativeBottomMargin: t.bottomMargin / keyboardDesignHeight

    // Gboard cycles its languages with the globe key; Qt would open a list instead.
    languagePopupListEnabled: false

    // A key's plate: inset in its cell, corners of 18 or a pill. Letter keys and the globe and space keys
    // are the lighter grey, every other key Gboard's purple grey. A letter key that shows its preview
    // bubble while pressed loses its plate, as in Gboard; other keys lighten.
    component Plate: Rectangle {
        property var control
        property real unit: 1
        property bool pill: false
        property real insetX: KeyboardTheme.keyInsetX
        property real insetY: KeyboardTheme.keyInsetY
        readonly property bool previewing: !!(control && control.pressed && control.showPreview)

        anchors.fill: parent
        anchors.leftMargin: insetX * unit
        anchors.rightMargin: insetX * unit
        anchors.topMargin: insetY * unit
        anchors.bottomMargin: insetY * unit
        radius: pill ? height / 2 : KeyboardTheme.keyRadius * unit
        visible: !previewing
        color: {
            const base = control && control.highlighted ? KeyboardTheme.functionKey : KeyboardTheme.key
            return control && control.pressed ? Qt.lighter(base, 1.3) : base
        }
        opacity: control && !control.enabled ? 0.45 : 1

        Connections {
            target: control
            function onPressedChanged() {
                if (control.pressed && PlasmaKeyboardSettings.vibrationEnabled)
                    Vibration.vibrate(PlasmaKeyboardSettings.vibrationMs)
            }
        }
    }

    // One of Gboard's icons at its own size, centred on the plate.
    component Glyph: Image {
        property string name
        property real unit: 1
        source: name ? Qt.resolvedUrl("images/" + name + ".png") : ""
        width: sourceSize.width * unit
        height: sourceSize.height * unit
        smooth: true
        mipmap: true
    }

    keyboardBackground: Rectangle {
        color: t.background
        // The key strip's bar under the strip row (KeyStripRow), the terminal's look: rounded on top.
        Rectangle {
            width: parent.width
            height: t.stripHeight * scaleHint
            color: t.strip
            topLeftRadius: t.stripRadius * scaleHint
            topRightRadius: t.stripRadius * scaleHint
        }
    }

    keyPanel: KeyPanel {
        id: keyPanel
        soundEffect: currentStyle.keySound

        // control is set once the panel exists (BaseKey's loader), so every read allows for null.
        readonly property string label: !control ? "" : control.uppercased ? control.displayText.toUpperCase() : control.displayText
        readonly property bool single: [...label].length === 1
        readonly property bool cjk: /[\u3000-\u9fff\uff00-\uffef]/.test(label)
        readonly property color ink: plate.previewing ? t.pressedText : t.text

        Plate {
            id: plate
            control: keyPanel.control
            unit: scaleHint
        }
        // The comma key carries the emoji face over its comma, as Gboard's does.
        Glyph {
            name: control && control.topIcon ? control.topIcon : ""
            unit: scaleHint
            visible: name.length > 0
            anchors.horizontalCenter: plate.horizontalCenter
            y: plate.y + 13.5 * scaleHint
            opacity: plate.previewing ? 0.35 : 1
        }
        // Gboard's ideographic full stop (U+3002) is a ring at the period's place; the CJK font
        // would draw it off centre.
        Rectangle {
            visible: keyPanel.label === "\u3002"
            width: 18 * scaleHint
            height: width
            radius: width / 2
            color: "transparent"
            border.color: keyPanel.ink
            border.width: 2.5 * scaleHint
            anchors.horizontalCenter: plate.horizontalCenter
            y: plate.y + (85.5 - 9) * scaleHint
        }
        Text {
            id: keyText
            visible: keyPanel.label !== "\u3002"
            text: keyPanel.label
            color: keyPanel.ink
            font.family: t.family(text)
            font.pixelSize: (keyPanel.single ? t.letterSize : keyPanel.label.indexOf("\n") >= 0 ? 32 : keyPanel.cjk ? 38 : 40) * scaleHint
            anchors.horizontalCenter: plate.horizontalCenter
            // A single character stands on Gboard's baseline, a longer label is centred.
            y: keyPanel.single ? plate.y + t.letterBaseline * scaleHint - baselineOffset
                               : plate.y + (plate.height - height) / 2
            horizontalAlignment: Text.AlignHCenter
            lineHeight: 0.85
        }
        // The symbol a long press gives, small in the corner (Gboard's "long press for symbols").
        Text {
            text: control ? control.smallText : ""
            visible: !!(control && control.smallTextVisible) && text.length > 0
            color: plate.previewing ? t.pressedText : t.hint
            font.family: t.family(text)
            font.pixelSize: t.hintSize * scaleHint
            anchors.right: plate.right
            anchors.rightMargin: t.hintRight * scaleHint
            anchors.baseline: plate.top
            anchors.baselineOffset: t.hintBaseline * scaleHint
        }
    }

    backspaceKeyPanel: KeyPanel {
        soundEffect: currentStyle.keySound
        Plate {
            id: backspacePlate
            control: parent.control
            unit: scaleHint
        }
        Glyph {
            name: "backspace"
            unit: scaleHint
            anchors.centerIn: backspacePlate
        }
    }

    languageKeyPanel: KeyPanel {
        soundEffect: currentStyle.keySound
        Plate {
            id: languagePlate
            control: parent.control
            unit: scaleHint
        }
        Glyph {
            name: "globe"
            unit: scaleHint
            anchors.centerIn: languagePlate
            opacity: control && !control.enabled ? 0.45 : 1
        }
    }

    enterKeyPanel: KeyPanel {
        soundEffect: currentStyle.keySound
        Plate {
            id: enterPlate
            control: parent.control
            unit: scaleHint
            pill: true
        }
        Glyph {
            name: "enter"
            unit: scaleHint
            anchors.centerIn: enterPlate
        }
    }

    hideKeyPanel: KeyPanel {
        soundEffect: currentStyle.keySound
        Plate {
            control: parent.control
            unit: scaleHint
        }
    }

    shiftKeyPanel: KeyPanel {
        soundEffect: currentStyle.keySound
        Plate {
            id: shiftPlate
            control: parent.control
            unit: scaleHint
        }
        // Gboard: an outline arrow, filled while shifted; caps lock adds a bar under it.
        Glyph {
            id: shiftGlyph
            name: InputContext.shiftActive || InputContext.capsLockActive ? "shift-active" : "shift"
            unit: scaleHint
            anchors.centerIn: shiftPlate
            anchors.verticalCenterOffset: InputContext.capsLockActive ? -6 * scaleHint : 0
            opacity: control && !control.enabled ? 0.45 : 1
        }
        Rectangle {
            visible: InputContext.capsLockActive
            width: 30 * scaleHint
            height: 5 * scaleHint
            radius: height / 2
            color: t.text
            anchors.horizontalCenter: shiftPlate.horizontalCenter
            anchors.top: shiftGlyph.bottom
            anchors.topMargin: 3 * scaleHint
        }
    }

    // Gboard names the language on its space bar.
    spaceKeyPanel: KeyPanel {
        soundEffect: currentStyle.keySound
        Plate {
            id: spacePlate
            control: parent.control
            unit: scaleHint
        }
        Text {
            text: InputContext.locale.indexOf("zh") === 0 ? "\u62fc\u97f3" : "English"
            color: t.text
            font.family: t.family(text)
            font.pixelSize: t.spaceLabelSize * scaleHint
            anchors.centerIn: spacePlate
        }
    }

    // ?123, and the way back from the symbols (Chinese "back" or ABC): a pill.
    symbolKeyPanel: KeyPanel {
        id: symbolPanel
        soundEffect: currentStyle.keySound
        readonly property string label: control ? control.displayText : ""
        readonly property bool cjk: /[\u3000-\u9fff]/.test(label)
        Plate {
            id: symbolPlate
            control: symbolPanel.control
            unit: scaleHint
            pill: true
        }
        Text {
            text: symbolPanel.label
            color: t.text
            font.family: t.family(text)
            font.pixelSize: (symbolPanel.cjk ? 38 : t.labelSize) * scaleHint
            anchors.horizontalCenter: symbolPlate.horizontalCenter
            y: symbolPanel.cjk ? symbolPlate.y + (symbolPlate.height - height) / 2
                               : symbolPlate.y + t.labelBaseline * scaleHint - baselineOffset
        }
    }

    modeKeyPanel: KeyPanel {
        soundEffect: currentStyle.keySound
        Plate {
            id: modePlate
            control: parent.control
            unit: scaleHint
        }
        Text {
            text: control ? control.displayText : ""
            color: t.text
            font.family: t.family(text)
            font.pixelSize: 40 * scaleHint
            anchors.centerIn: modePlate
        }
    }

    handwritingKeyPanel: KeyPanel {
        soundEffect: currentStyle.keySound
        Plate {
            control: parent.control
            unit: scaleHint
        }
    }

    // The strip row's keys (KeyStripRow): a label on the bar, the terminal's borderless touch circle.
    property Component stripKeyPanel: KeyPanel {
        id: stripPanel
        soundEffect: currentStyle.keySound
        Rectangle {
            visible: !!(control && control.pressed)
            width: 84 * scaleHint
            height: width
            radius: width / 2
            color: Qt.rgba(1, 1, 1, 0.12)
            anchors.horizontalCenter: parent.horizontalCenter
            y: (t.stripHeight / 2 - 42) * scaleHint
        }
        Text {
            text: !control ? "" : control.displayText.length ? control.displayText : control.text
            color: t.stripText
            font.family: t.fontFamily
            font.weight: Font.Medium
            font.pixelSize: t.stripTextSize * scaleHint
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.baseline: parent.top
            anchors.baselineOffset: 68 * scaleHint
        }
        Connections {
            target: stripPanel.control
            function onPressedChanged() {
                if (stripPanel.control.pressed && PlasmaKeyboardSettings.vibrationEnabled)
                    Vibration.vibrate(PlasmaKeyboardSettings.vibrationMs)
            }
        }
    }

    // The number pads (digits, phone, and 12/34 in the symbols): Gboard's pills, a digit with the phone's
    // letters beside it, or one of its icons.
    property Component padKeyPanel: KeyPanel {
        id: padPanel
        soundEffect: currentStyle.keySound
        readonly property string label: control ? control.displayText : ""
        readonly property string letters: control && control.padLetters ? control.padLetters : ""
        readonly property string icon: control && control.padIcon ? control.padIcon : ""
        readonly property bool column: !!(control && control.padColumn)
        readonly property bool digit: /^[0-9]$/.test(label)
        Plate {
            id: padPlate
            control: padPanel.control
            unit: scaleHint
            pill: true
            insetX: t.padInsetX
            insetY: t.padInsetY
        }
        Glyph {
            name: padPanel.icon
            unit: scaleHint
            visible: name.length > 0
            anchors.centerIn: padPlate
        }
        // On the phone pad the digits sit 20 px left of centre, their letters centred 84 px right of the
        // digit. Digits, the comma and the point stand on Gboard's baseline; other signs and labels are centred.
        Text {
            id: padText
            readonly property bool onBaseline: padPanel.digit || text === "," || text === "."
            visible: padPanel.icon.length === 0
            text: padPanel.label
            color: t.text
            font.family: t.fontFamily
            font.pixelSize: (padPanel.digit ? t.padDigitSize : [...text].length > 1 ? t.labelSize : t.letterSize) * scaleHint
            anchors.horizontalCenter: padPlate.horizontalCenter
            anchors.horizontalCenterOffset: (padPanel.column ? -20 : 0) * scaleHint
            y: onBaseline ? padPlate.y + t.padDigitBaseline * scaleHint - baselineOffset
                          : padPlate.y + (padPlate.height - height) / 2
        }
        Text {
            visible: padPanel.letters.length > 0
            text: padPanel.letters
            color: t.hint
            font.family: t.fontFamily
            font.pixelSize: (padPanel.letters === "+" ? 50 : t.padLettersSize) * scaleHint
            anchors.horizontalCenter: padPlate.horizontalCenter
            anchors.horizontalCenterOffset: (padPanel.column ? 64 : 0) * scaleHint
            anchors.baseline: padPlate.top
            anchors.baselineOffset: 83 * scaleHint
        }
    }

    // The bubble over a pressed key: a circle centred one row up, the letter larger, three dots when a long
    // press has more.
    characterPreviewMargin: 0
    characterPreviewDelegate: Item {
        id: preview
        property string text
        readonly property var activeKey: parent && parent.parent ? parent.parent.activeKey : null
        readonly property bool more: !!(activeKey && activeKey.effectiveAlternativeKeys
                                        && activeKey.effectiveAlternativeKeys.length > 0)

        Rectangle {
            id: bubble
            width: 151 * scaleHint
            height: width
            radius: width / 2
            color: t.key
            anchors.centerIn: parent
            layer.enabled: true
            layer.effect: MultiEffect {
                shadowEnabled: true
                shadowColor: Qt.rgba(0, 0, 0, 0.45)
                shadowBlur: 0.5
                shadowVerticalOffset: 2 * scaleHint
            }
        }
        Text {
            text: preview.text
            color: t.text
            font.family: t.family(text)
            font.pixelSize: 86 * scaleHint
            anchors.horizontalCenter: bubble.horizontalCenter
            anchors.baseline: bubble.verticalCenter
            anchors.baselineOffset: 30 * scaleHint
        }
        Row {
            visible: preview.more
            spacing: 3 * scaleHint
            anchors.horizontalCenter: bubble.horizontalCenter
            y: bubble.y + bubble.height / 2 + 58 * scaleHint
            Repeater {
                model: 3
                Rectangle {
                    width: 6 * scaleHint
                    height: width
                    radius: width / 2
                    color: t.text
                }
            }
        }
    }

    // The long press: the key's symbols on a raised plate over it, the chosen one on Android's accent.
    alternateKeysListItemWidth: 107 * scaleHint
    alternateKeysListItemHeight: 124 * scaleHint
    alternateKeysListBottomMargin: -8 * scaleHint
    alternateKeysListLeftMargin: t.sideMargin * scaleHint
    alternateKeysListRightMargin: t.sideMargin * scaleHint
    alternateKeysListDelegate: Item {
        id: alternateKey
        width: alternateKeysListItemWidth
        height: alternateKeysListItemHeight
        Text {
            text: model.text
            color: alternateKey.ListView.isCurrentItem ? t.accentText : t.text
            font.family: t.family(text)
            font.pixelSize: ([...model.text].length > 1 ? 40 : t.letterSize) * scaleHint
            anchors.centerIn: parent
        }
    }
    alternateKeysListHighlight: Item {
        Rectangle {
            anchors.fill: parent
            anchors.margins: 6 * scaleHint
            radius: 18 * scaleHint
            color: t.accent
        }
    }
    alternateKeysListBackground: Item {
        Rectangle {
            anchors.fill: parent
            anchors.margins: -8 * scaleHint
            radius: 26 * scaleHint
            color: t.key
            layer.enabled: true
            layer.effect: MultiEffect {
                shadowEnabled: true
                shadowColor: Qt.rgba(0, 0, 0, 0.45)
                shadowBlur: 0.5
                shadowVerticalOffset: 2 * scaleHint
            }
        }
    }

    // The candidate band, where Gboard's toolbar is: words in cells at least 160 wide, a short rule after
    // each. It stays reserved while empty (KeyboardTheme.setup), so the keyboard keeps its height.
    selectionListHeight: t.bandHeight * scaleHint
    selectionListDelegate: SelectionListItem {
        id: candidate
        width: Math.max(160 * scaleHint, candidateText.implicitWidth + 60 * scaleHint)
        Text {
            id: candidateText
            text: display
            color: t.text
            font.family: t.family(text)
            font.pixelSize: t.candidateSize * scaleHint
            anchors.horizontalCenter: parent.horizontalCenter
            anchors.verticalCenter: parent.top
            anchors.verticalCenterOffset: t.bandCenter * scaleHint
        }
        Rectangle {
            width: 3 * scaleHint
            height: 63 * scaleHint
            color: t.separator
            anchors.right: parent.right
            y: (t.bandCenter - 31.5) * scaleHint
        }
    }
    selectionListHighlight: Item {}
    selectionListBackground: Rectangle {
        color: t.background
    }
    selectionListAdd: Transition {}
    selectionListRemove: Transition {}

    navigationHighlight: Rectangle {
        color: "transparent"
        border.color: t.accent
        border.width: 4 * scaleHint
        radius: t.keyRadius * scaleHint
    }

    // Lists Qt opens itself (the full candidate list): the band's look.
    popupListDelegate: SelectionListItem {
        width: popupListText.implicitWidth + 60 * scaleHint
        height: t.bandHeight * scaleHint
        Text {
            id: popupListText
            text: display
            color: t.text
            font.family: t.family(text)
            font.pixelSize: t.candidateSize * scaleHint
            anchors.centerIn: parent
        }
    }
    popupListHighlight: Rectangle {
        radius: 18 * scaleHint
        color: Qt.rgba(1, 1, 1, 0.08)
    }
    popupListBackground: Rectangle {
        radius: 26 * scaleHint
        color: t.key
    }
    popupListAdd: Transition {}
    popupListRemove: Transition {}

    selectionHandle: Rectangle {
        implicitWidth: 40 * scaleHint
        implicitHeight: implicitWidth
        radius: implicitWidth / 2
        color: t.accent
    }
}
