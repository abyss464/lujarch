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

package org.lujarch.linux;

/**
 * Android's switches and readings as Linux's desktop shows them (LNX-7, Android's quick settings).
 * A part whose Known is false is one Android does not tell the Terminal app; Linux shows it as
 * unknown.
 */
parcelable AndroidState {
    boolean wifiKnown;
    boolean wifiOn;
    boolean wifiConnected;
    String wifiSsid;
    /** 0 to 4. */
    int wifiLevel;

    /** The phone has a radio for mobile data. */
    boolean mobileKnown;
    /** A SIM card is ready. */
    boolean mobilePresent;
    boolean mobileOn;
    String mobileOperator;
    /** 0 to 4. */
    int mobileLevel;
    /** The radio's technology as Plasma's icons name it: 5g, lte, hspa, umts, edge, gprs or empty. */
    String mobileTech;
    boolean mobileConnected;

    boolean bluetoothKnown;
    boolean bluetoothOn;
    /** The names of the paired devices connected now. */
    String[] bluetoothConnected;

    boolean airplaneKnown;
    boolean airplaneOn;

    boolean hotspotKnown;
    boolean hotspotOn;

    /** The phone has a flashlight. */
    boolean flashlightAvailable;
    boolean flashlightOn;

    boolean batteryKnown;
    /** 0 to 100. */
    int batteryPercent;
    boolean batteryCharging;
    boolean batteryPlugged;

    boolean rotationKnown;
    boolean rotationAuto;

    boolean dndKnown;
    boolean dndOn;

    /** The screen stays on while the desktop is shown (Caffeine). */
    boolean caffeineOn;

    boolean brightnessKnown;
    /** The brightness slider's position, 0 to brightnessMax. */
    int brightness;
    int brightnessMax;

    /** The default network's transport: wifi, mobile, other or none. */
    String transport;
}
