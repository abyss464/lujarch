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

/** Android's position for Linux (LNX-7, Location), or why there is none. */
parcelable LinuxLocation {
    /** permission: the Terminal app may not use the location; off: Android's location is off. */
    @nullable String error;
    double latitude;
    double longitude;
    /** Unix milliseconds of the fix. */
    long time;
    boolean hasAltitude;
    /** Metres above the WGS 84 ellipsoid. */
    double altitude;
    boolean hasAccuracy;
    /** Metres. */
    float accuracy;
    boolean hasSpeed;
    /** Metres a second. */
    float speed;
    boolean hasBearing;
    /** Degrees clockwise from north. */
    float bearing;
}
