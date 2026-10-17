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

import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Display
import java.util.concurrent.Executor
import org.lujarch.linux.client.Linux
import org.lujarch.linux.client.TAG

/**
 * Linux's desktop flips Android's own switches (LNX-7, Android's quick settings) through the
 * calls Android's own tiles make, so there is one state and two places to change it. A key is
 * one of wifi, mobile, bluetooth, airplane, hotspot, flashlight, rotation, dnd, caffeine (1 or 0)
 * and brightness (the slider's position, 0..[BrightnessCurve.GAMMA_MAX]); lujarch_linux checked
 * both. What Android refuses is logged; the desktop sees the switch stay where it is.
 */
class Switches(private val context: Context) {
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val camera = context.getSystemService(CameraManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val direct = Executor { it.run() }

    fun set(key: String, value: String) {
        try {
            val on = value == "1"
            when (key) {
                "wifi" -> setWifi(on)
                "mobile" -> setMobile(on)
                "bluetooth" -> setBluetooth(on)
                "airplane" -> Hidden.setAirplaneMode(connectivity, on)
                "hotspot" -> setHotspot(on)
                "flashlight" -> setFlashlight(on)
                "rotation" -> setRotation(on)
                "dnd" -> setDnd(on)
                "caffeine" -> Linux.setCaffeine(context, on)
                "brightness" -> setBrightness(value.toInt())
                else -> Log.w(TAG, "Linux's desktop asked for a switch Android has not: $key")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Linux's desktop could not switch $key: ${e.message ?: "Android refused"}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Linux's desktop could not switch $key: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Linux's desktop could not switch $key: ${e.message ?: "the camera"}")
        }
    }

    @Suppress("DEPRECATION") // setWifiEnabled: what a system app and Android's own tile call
    private fun setWifi(on: Boolean) {
        if (!wifi.setWifiEnabled(on)) Log.w(TAG, "Wi-Fi: Android refused (a user restriction or satellite mode)")
    }

    @Suppress("DEPRECATION") // setDataEnabled: the default data subscription, as the shade's Internet tile
    private fun setMobile(on: Boolean) {
        telephony.setDataEnabled(on)
    }

    @Suppress("DEPRECATION") // enable and disable: what a platform-signed app may still call
    private fun setBluetooth(on: Boolean) {
        val adapter = bluetooth
        if (adapter == null) {
            Log.w(TAG, "Bluetooth: the phone has no adapter")
            return
        }
        val ok = if (on) adapter.enable() else adapter.disable()
        if (!ok) Log.w(TAG, "Bluetooth: Android refused")
    }

    private fun setHotspot(on: Boolean) {
        val tethering = context.getSystemService("tethering")
        if (tethering == null) {
            Log.w(TAG, "hotspot: no tethering service")
            return
        }
        Hidden.setTethering(tethering, on, direct) { error ->
            Log.w(TAG, "hotspot: Android could not start tethering (error $error)")
        }
    }

    private fun setFlashlight(on: Boolean) {
        val id = camera.cameraIdList.firstOrNull { id ->
            val c = camera.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        if (id == null) {
            Log.w(TAG, "flashlight: the phone has no flash unit")
            return
        }
        camera.setTorchMode(id, on)
    }

    private fun setRotation(auto: Boolean) {
        if (!Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (auto) 1 else 0)) {
            Log.w(TAG, "auto-rotate: the settings provider refused")
        }
    }

    // Android's own Do Not Disturb. Turning it off snoozes the modes a rule turned on, the Mode
    // Linux among them, which is turned on again then.
    private fun setDnd(on: Boolean) {
        Hidden.setZenMode(notifications, if (on) ZEN_MODE_IMPORTANT_INTERRUPTIONS else ZEN_MODE_OFF, "Linux's desktop")
        if (!on) LinuxMode.renew(context)
    }

    private fun setBrightness(gamma: Int) {
        val info = displays.getDisplay(Display.DEFAULT_DISPLAY)?.let { Hidden.brightnessInfo(it) }
        val min = info?.get(1) ?: 0f
        val max = info?.get(2) ?: 1f
        Hidden.setBrightness(displays, Display.DEFAULT_DISPLAY, BrightnessCurve.toLinear(gamma, min, max))
    }

    private companion object {
        const val ZEN_MODE_OFF = 0
        const val ZEN_MODE_IMPORTANT_INTERRUPTIONS = 1
    }
}
