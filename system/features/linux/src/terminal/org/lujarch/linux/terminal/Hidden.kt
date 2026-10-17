/*
 * Copyright (C) 2026 The Lujarch Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lujarch.linux.terminal

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

private val BOOL = Boolean::class.javaPrimitiveType!!
private val INT = Int::class.javaPrimitiveType!!
private val FLOAT = Float::class.javaPrimitiveType!!

/**
 * The framework's calls Android's own tiles make that the SDK does not show, by reflection: the
 * app is platform-signed, which exempts it from the hidden-API checks. A failure is an
 * [IllegalStateException] that carries the framework's own refusal, or that refusal itself when
 * it is a [SecurityException].
 */
internal object Hidden {
    /** `target.name(args)`: [types] are the declared parameter types, in order. */
    fun call(target: Any, name: String, types: Array<Class<*>>, vararg args: Any?): Any? {
        try {
            return target.javaClass.getMethod(name, *types).invoke(target, *args)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is SecurityException) throw cause
            throw IllegalStateException("${target.javaClass.simpleName}.$name: ${cause.message ?: cause.javaClass.simpleName}", cause)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("${target.javaClass.simpleName}.$name is not there: ${e.message}", e)
        }
    }

    /** `ConnectivityManager.setAirplaneMode(enable)`: NETWORK_SETTINGS. */
    fun setAirplaneMode(connectivity: Any, enable: Boolean) {
        call(connectivity, "setAirplaneMode", arrayOf(BOOL), enable)
    }

    /** `WifiManager.isWifiApEnabled()`: the hotspot is on. */
    fun isWifiApEnabled(wifi: Any): Boolean = call(wifi, "isWifiApEnabled", emptyArray()) as Boolean

    /** `NotificationManager.setZenMode(mode, conditionId, reason, fromUser)`: STATUS_BAR_SERVICE. */
    fun setZenMode(notifications: Any, mode: Int, reason: String) {
        call(
            notifications, "setZenMode",
            arrayOf(INT, android.net.Uri::class.java, String::class.java, BOOL),
            mode, null, reason, false,
        )
    }

    /** Android's own Do Not Disturb (the manual one, not a mode that a rule turns on) is on. */
    fun isManualDndOn(notifications: Any): Boolean {
        val config = call(notifications, "getZenModeConfig", emptyArray()) ?: return false
        return call(config, "isManualActive", emptyArray()) as Boolean
    }

    /**
     * The display's linear brightness and its range, `[brightness, minimum, maximum]`, from
     * `Display.getBrightnessInfo()` (CONTROL_DISPLAY_BRIGHTNESS); null when the display has none.
     */
    fun brightnessInfo(display: Any): FloatArray? {
        val info = call(display, "getBrightnessInfo", emptyArray()) ?: return null
        val c = info.javaClass
        return floatArrayOf(
            c.getField("brightness").getFloat(info),
            c.getField("brightnessMinimum").getFloat(info),
            c.getField("brightnessMaximum").getFloat(info),
        )
    }

    /** `DisplayManager.setBrightness(displayId, brightness)`: CONTROL_DISPLAY_BRIGHTNESS. */
    fun setBrightness(displays: Any, displayId: Int, linear: Float) {
        call(displays, "setBrightness", arrayOf(INT, FLOAT), displayId, linear)
    }

    /** `BluetoothDevice.isConnected()`. */
    fun isConnected(device: Any): Boolean = call(device, "isConnected", emptyArray()) as Boolean

    /**
     * The hotspot, started and stopped as SystemUI's tile does it: TetheringManager (a module's
     * API, not in the SDK) with a TETHERING_WIFI request and no entitlement UI. TETHER_PRIVILEGED.
     */
    fun setTethering(tethering: Any, enable: Boolean, executor: Executor, onFailed: (Int) -> Unit) {
        if (!enable) {
            call(tethering, "stopTethering", arrayOf(INT), TETHERING_WIFI)
            return
        }
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        try {
            val builder = builderClass.getConstructor(INT).newInstance(TETHERING_WIFI)
            builderClass.getMethod("setShouldShowEntitlementUi", BOOL).invoke(builder, false)
            val request = builderClass.getMethod("build").invoke(builder)
            val callback = Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass)) { _, method, args ->
                if (method.name == "onTetheringFailed") onFailed((args?.get(0) as? Int) ?: -1)
                null
            }
            tethering.javaClass
                .getMethod("startTethering", requestClass, Executor::class.java, callbackClass)
                .invoke(tethering, request, executor, callback)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException ?: e
            if (cause is SecurityException) throw cause
            throw IllegalStateException("TetheringManager.startTethering: ${cause.message ?: cause.javaClass.simpleName}", cause)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("TetheringManager.startTethering is not there: ${e.message}", e)
        }
    }

    private const val TETHERING_WIFI = 0
}
