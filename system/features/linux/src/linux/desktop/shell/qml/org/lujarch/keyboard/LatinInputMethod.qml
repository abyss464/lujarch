// English typing without a dictionary: it stands in for Qt's default
// input method, which would bring Hunspell's word candidates. Its word candidate list stays empty, so the
// candidate band stays hidden in English. Every key goes on to Qt's fallback input method (keyEvent
// returns false), which types it.
// QtQuick has an InputMethod of its own (Qt.inputMethod's type, not creatable), hence the qualifier.
import QtQuick
import QtQuick.VirtualKeyboard as VKB

VKB.InputMethod {
    function inputModes(locale) {
        return [VKB.InputEngine.InputMode.Latin]
    }

    function setInputMode(locale, inputMode) {
        return true
    }

    function setTextCase(textCase) {
        return true
    }

    function reset() {
    }

    function update() {
    }

    function keyEvent(key, text, modifiers) {
        return false
    }

    function selectionLists() {
        return [VKB.SelectionListModel.Type.WordCandidateList]
    }

    function selectionListItemCount(type) {
        return 0
    }

    function selectionListData(type, role, index) {
        return null
    }

    function selectionListItemSelected(type, index) {
    }
}
