// Android's switches and state for Plasma Mobile's shell (LNX-7, Android's quick settings).
//
// The quick settings and the status bar's indicators read their values here and flip their switches
// here; one tap changes Android's own switch, never a second setting of Plasma's. The Terminal app
// keeps Android's state in /run/lujarch/android while Android's quick settings are lent and executes
// the switches while Linux is on screen; this reads the file and sets through the android daemon's
// command. A value Android has not reported (yet) is unknown: the readers below give a neutral
// default, and `stateKnown` tells the first report from a quiet one.
pragma Singleton

import QtQuick
import org.kde.plasma.private.mobileshell as MobileShell

QtObject {
    id: root

    // Android's state (QML_XHR_ALLOW_FILE_READ=1 lets XMLHttpRequest read it; the session sets it)
    readonly property string stateUrl: "file:///run/lujarch/android"
    readonly property string cli: "/usr/bin/python3 /usr/lib/lujarch/desktop/android/android"
    // The state is read about once a second while the desktop is on screen (it is frozen otherwise).
    readonly property int pollMs: 1000
    // A switch the owner just flipped shows its new position at once; Android's own report replaces it,
    // and without one it goes back after this long, so a refusal is visible as the switch not moving.
    readonly property int holdMs: 3000

    property var snapshot: ({})
    // "group.name" -> {value, until}
    property var pending: ({})
    readonly property bool stateKnown: Object.keys(snapshot).length > 0

    property string lastText: ""
    property int emptyReads: 0

    // ---- reading
    function field(group, name, fallback) {
        var held = pending[group + "." + name]
        if (held !== undefined)
            return held.value
        var g = snapshot[group]
        if (g === undefined || g === null)
            return fallback
        var v = g[name]
        return v === undefined ? fallback : v
    }

    function known(group) {
        var g = snapshot[group]
        return g !== undefined && g !== null
    }

    readonly property bool wifiOn: field("wifi", "on", false)
    readonly property bool wifiConnected: field("wifi", "connected", false)
    readonly property string wifiSsid: field("wifi", "ssid", "")
    readonly property int wifiLevel: field("wifi", "level", 0)

    readonly property bool mobilePresent: field("mobile", "present", false)
    readonly property bool mobileOn: field("mobile", "on", false)
    readonly property string mobileOperator: field("mobile", "operator", "")
    readonly property int mobileLevel: field("mobile", "level", 0)
    readonly property string mobileTech: field("mobile", "tech", "")
    readonly property bool mobileConnected: field("mobile", "connected", false)

    readonly property bool bluetoothKnown: known("bluetooth")
    readonly property bool bluetoothOn: field("bluetooth", "on", false)
    // [{name, connected}], the paired devices that are connected now
    readonly property var bluetoothDevices: {
        var names = field("bluetooth", "connected", [])
        var list = []
        for (var i = 0; i < names.length; ++i)
            list.push({"name": names[i], "connected": true})
        return list
    }

    readonly property bool airplaneOn: field("airplane", "on", false)
    readonly property bool hotspotOn: field("hotspot", "on", false)
    readonly property bool flashAvailable: !known("flashlight") || field("flashlight", "available", false)
    readonly property bool flashOn: field("flashlight", "on", false)
    readonly property int batteryPercent: field("battery", "percent", -1)
    readonly property bool batteryCharging: field("battery", "charging", false)
    readonly property bool rotationAuto: field("rotation", "auto", false)
    readonly property bool dndOn: field("dnd", "on", false)
    readonly property bool caffeineOn: field("caffeine", "on", false)
    // wifi, mobile, other or none
    readonly property string defaultTransport: snapshot["default"] === undefined ? "none" : snapshot["default"]

    // Plasma's own connection icons (plasma-nm's ConnectionIcon): the default network's, with its strength
    readonly property string networkIcon: {
        if (!stateKnown)
            return "network-unavailable"
        if (airplaneOn && !wifiConnected && !mobileConnected)
            return "network-flightmode-on"
        var wifiIcon = "network-wireless-" + Math.floor(wifiLevel * 25 / 20) * 20
        var mobileIcon = "network-mobile-" + Math.floor(mobileLevel * 25 / 20) * 20 + (mobileTech !== "" ? "-" + mobileTech : "")
        if (defaultTransport === "wifi" && wifiConnected)
            return wifiIcon
        if (defaultTransport === "mobile" && mobileConnected)
            return mobileIcon
        if (wifiConnected)
            return wifiIcon
        if (wifiOn)
            return "network-wireless-disconnected"
        if (mobilePresent && mobileOn)
            return "network-mobile-available"
        return "network-unavailable"
    }

    // ---- switching
    function request(key, value, heldField) {
        var held = Object.assign({}, pending)
        held[heldField] = {"value": value, "until": Date.now() + holdMs}
        pending = held
        MobileShell.ShellUtil.executeCommand(cli + " set " + key + " " + (value === true ? "1" : value === false ? "0" : String(value)))
    }

    function setWifi(on) { request("wifi", on, "wifi.on") }
    function setMobile(on) { request("mobile", on, "mobile.on") }
    function setBluetooth(on) { request("bluetooth", on, "bluetooth.on") }
    function setAirplane(on) { request("airplane", on, "airplane.on") }
    function setHotspot(on) { request("hotspot", on, "hotspot.on") }
    function setFlashlight(on) { request("flashlight", on, "flashlight.on") }
    function setRotation(auto) { request("rotation", auto, "rotation.auto") }
    function setDnd(on) { request("dnd", on, "dnd.on") }
    function setCaffeine(on) { request("caffeine", on, "caffeine.on") }

    // ---- text: Lujarch's strings (/usr/lib/lujarch/desktop/res) in the locale Plasma runs in,
    // English where it has none
    readonly property string resUrl: "file:///usr/lib/lujarch/desktop/res/"
    property var strings: ({})
    property var english: ({})

    function text(name) {
        var s = strings[name]
        if (s === undefined)
            s = english[name]
        return s === undefined ? "" : s
    }

    function loadStrings(dir, done) {
        var xhr = new XMLHttpRequest()
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== XMLHttpRequest.DONE)
                return
            var out = {}
            var re = /<string name="([a-z0-9_]+)">([\s\S]*?)<\/string>/g
            var m
            while ((m = re.exec(xhr.responseText)) !== null) {
                out[m[1]] = m[2].replace(/\\'/g, "'").replace(/\\"/g, '"').replace(/&lt;/g, "<")
                    .replace(/&gt;/g, ">").replace(/&amp;/g, "&")
            }
            if (Object.keys(out).length === 0)
                console.warn("android: no strings in", resUrl + dir)
            done(out)
        }
        xhr.open("GET", resUrl + dir + "/strings.xml")
        xhr.send()
    }

    Component.onCompleted: {
        loadStrings("values", function (out) { english = out })
        var name = Qt.locale().name
        var dir = name.indexOf("zh") === 0 ? "values-zh-rCN" : name.indexOf("ja") === 0 ? "values-ja" : ""
        if (dir !== "")
            loadStrings(dir, function (out) { strings = out })
    }

    // ---- following the file
    function drop(group, name) {
        var held = Object.assign({}, pending)
        delete held[group + "." + name]
        pending = held
    }

    function settle() {
        var keys = Object.keys(pending)
        if (keys.length === 0)
            return
        var now = Date.now()
        var held = Object.assign({}, pending)
        var changed = false
        for (var i = 0; i < keys.length; ++i) {
            var parts = keys[i].split(".")
            var g = snapshot[parts[0]]
            var reported = (g !== undefined && g !== null) ? g[parts[1]] : undefined
            if (held[keys[i]].until <= now || reported === held[keys[i]].value) {
                delete held[keys[i]]
                changed = true
            }
        }
        if (changed)
            pending = held
    }

    function apply(text) {
        var o
        try {
            o = JSON.parse(text)
        } catch (e) {
            console.warn("android: the state file is not JSON:", e)
            return
        }
        snapshot = o !== null && typeof o === "object" ? o : ({})
        settle()
    }

    function poll() {
        var xhr = new XMLHttpRequest()
        xhr.onreadystatechange = function () {
            if (xhr.readyState !== XMLHttpRequest.DONE)
                return
            var text = xhr.responseText
            if (!text) {
                if (++emptyReads === 10)
                    console.warn("android: no state at", stateUrl, "(Android's quick settings are not lent, or the session does not set QML_XHR_ALLOW_FILE_READ=1)")
                if (emptyReads >= 10 && Object.keys(snapshot).length > 0) {
                    lastText = ""
                    snapshot = ({})
                }
                return
            }
            emptyReads = 0
            if (text !== lastText) {
                lastText = text
                apply(text)
            }
        }
        xhr.open("GET", stateUrl)
        xhr.send()
    }

    property var timer: Timer {
        interval: root.pollMs
        running: true
        repeat: true
        triggeredOnStart: true
        onTriggered: {
            root.poll()
            root.settle()
        }
    }
}
