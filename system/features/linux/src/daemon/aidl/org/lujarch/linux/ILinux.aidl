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

import org.lujarch.linux.AndroidState;
import org.lujarch.linux.ILinuxListener;
import org.lujarch.linux.LinuxLocation;
import org.lujarch.linux.LinuxNotification;
import org.lujarch.linux.LinuxService;
import org.lujarch.linux.RemoteConfig;
import org.lujarch.linux.RemoteStatus;
import org.lujarch.linux.Status;

/**
 * The Linux development environment (lujarch_linux): the installed system and its state, the
 * terminal sessions, the desktop, the system's services, Remote access, and the parts of the
 * phone Linux is lent. Lujarch Settings and the Terminal app call it; a refused or failed call throws a
 * ServiceSpecificException with one of the ERROR_ constants and the reason.
 */
interface ILinux {
    /** No system is installed. */
    const String STATE_NOT_INSTALLED = "not-installed";
    /** Use Linux development environment is off. */
    const String STATE_STOPPED = "stopped";
    const String STATE_STARTING = "starting";
    /** The initial setup runs. */
    const String STATE_SETUP = "setup";
    const String STATE_RUNNING = "running";
    /** Linux could not start; Status.reason says why. */
    const String STATE_FAILED = "failed";

    /** A shell of the owner's user. */
    const String KIND_ARCH = "arch";
    /** A shell of root in Arch Linux. */
    const String KIND_ARCH_ROOT = "arch-root";
    /** A shell of root in Android. */
    const String KIND_ANDROID_ROOT = "android-root";

    const String ACTION_INSTALL = "install";
    const String ACTION_SETUP = "setup";
    /** Installing the desktop in Linux (installDesktop). */
    const String ACTION_DESKTOP = "desktop";

    const String SERVICE_START = "start";
    const String SERVICE_STOP = "stop";
    const String SERVICE_RESTART = "restart";

    /** Remote access's mode (When it runs). */
    const String REMOTE_OFF = "off";
    const String REMOTE_CHARGING = "charging";
    const String REMOTE_ALWAYS = "always";

    /** RemoteStatus.state */
    const String RELAY_OFF = "off";
    /** No server or no secret is set. */
    const String RELAY_NOT_SET_UP = "not-set-up";
    /** Linux does not run. */
    const String RELAY_NO_LINUX = "no-linux";
    /** Linux has no OpenSSH server. */
    const String RELAY_NO_OPENSSH = "no-openssh";
    /** While charging, and no charger is connected. */
    const String RELAY_WAITING = "waiting";
    const String RELAY_RUNNING = "running";
    const String RELAY_FAILED = "failed";

    const int MAX_SESSIONS = 16;

    /**
     * The lent parts a program in Linux reads (ILinuxListener.onStream): Android's microphone
     * (16-bit little-endian PCM, 48 kHz, mono), a camera (frames: STREAM_FRAME_MAGIC, the frame's
     * size in bytes as a 32-bit little-endian number, then the frame, NV12 of
     * STREAM_CAMERA_WIDTH x STREAM_CAMERA_HEIGHT), the location (setLocation).
     */
    const String STREAM_MICROPHONE = "microphone";
    const String STREAM_CAMERA_BACK = "camera-back";
    const String STREAM_CAMERA_FRONT = "camera-front";
    const String STREAM_LOCATION = "location";
    const String STREAM_FRAME_MAGIC = "LJF1";
    const int STREAM_CAMERA_WIDTH = 1280;
    const int STREAM_CAMERA_HEIGHT = 720;

    /** The caller may not do this. */
    const int ERROR_REFUSED = 1;
    const int ERROR_INVALID = 2;
    const int ERROR_FAILED = 3;
    const int ERROR_NOT_INSTALLED = 4;
    /** Linux does not run. */
    const int ERROR_NOT_RUNNING = 5;
    /** Root sessions is off. */
    const int ERROR_ROOT_SESSIONS_OFF = 6;
    const int ERROR_TOO_MANY_SESSIONS = 7;
    const int ERROR_NO_SESSION = 8;
    /** An install, the initial setup or the desktop's install is under way. */
    const int ERROR_BUSY = 9;
    const int ERROR_NO_SERVICE = 10;
    /** Linux has no desktop: the service desktop is not installed. */
    const int ERROR_NO_DESKTOP = 11;
    /** Linux has no sound for the desktop. */
    const int ERROR_NO_SOUND = 12;
    /** No program in Linux reads the stream, or its part is not lent. */
    const int ERROR_NOT_WANTED = 13;

    Status getStatus();

    /** The listener hears of every change while its process lives. */
    void registerListener(ILinuxListener listener);
    void unregisterListener(ILinuxListener listener);

    /** Opens a session of a KIND_ and returns its ID (LNX-3, LNX-4). */
    String createSession(String kind, String name, int columns, int rows);

    /**
     * The session's terminal as a stream: what the session wrote lately, then its output; what
     * is written to it is the session's input. A newer attach ends the older one's stream.
     */
    ParcelFileDescriptor attachSession(String id, int columns, int rows);

    void resizeSession(String id, int columns, int rows);
    void renameSession(String id, String name);

    /** Keeps the phone awake while a command runs in the session. */
    void setSessionAwake(String id, boolean awake);

    void endSession(String id);

    /** Checks the archive and installs it as the system for user (LNX-2). */
    void install(in ParcelFileDescriptor archive, String user);

    /** Runs the initial setup again. */
    void runSetup();

    /**
     * Installs the desktop in Linux (LNX-6): Plasma Mobile, Mesa with Turnip and Zink, KWin with
     * the Lujarch backend, built in Linux, and the service desktop; then (re)starts the service.
     */
    void installDesktop();

    LinuxService[] listServices();

    /** One of SERVICE_START, SERVICE_STOP, SERVICE_RESTART. */
    void controlService(String name, String action);

    void setServiceStartsWithLinux(String name, boolean starts);

    /** The end of the service's log. */
    String readServiceLog(String name);

    /** The files of the service's directory, as paths relative to it. */
    String[] listServiceFiles(String name);

    /** The beginning of one of them. */
    String readServiceFile(String name, String file);

    RemoteStatus getRemote();

    /** A null token or secret keeps the one set before. */
    void setRemote(in RemoteConfig config);

    /** The owner's own terminal colors (Termux's colors.properties); empty when none are set. */
    String getColors();
    void setColors(String colors);

    /**
     * The Terminal app's connection to the desktop: the display protocol
     * (src/linux/desktop/protocol/lujarch_display.h) to KWin in Linux. A newer one replaces the
     * older one; the desktop is frozen while none is attached or the last configure says it is
     * not on screen.
     */
    ParcelFileDescriptor attachDisplay();

    /**
     * The desktop's sound (16-bit little-endian PCM, 48 kHz, stereo) as a non-blocking FIFO; it
     * reads end of file while nothing in Linux plays into it.
     */
    ParcelFileDescriptor openSound();

    /** Android's clipboard as the Terminal app sees it, for the desktop while it is lent. */
    void setClipboard(String text);

    /** Android's notifications, while Linux is lent them. */
    void setNotifications(in LinuxNotification[] notifications);

    /** The owner's answer to an authorization request (ILinuxListener.onAuthRequest). */
    void answerAuth(int id, boolean allowed);

    /** The card NFC reads now, null when none. */
    void setNfcCard(@nullable String card);

    /** The card's answer to a command (ILinuxListener.onNfcRequest), or why there is none. */
    void answerNfc(int id, in byte[] response, @nullable String error);

    /** Android's switches and readings, while Linux is lent Android's quick settings. */
    void setAndroidState(in AndroidState state);

    /**
     * What the Terminal app writes a stream into while a program in Linux reads it
     * (STREAM_MICROPHONE, STREAM_CAMERA_BACK, STREAM_CAMERA_FRONT): a non-blocking FIFO.
     */
    ParcelFileDescriptor openStream(String stream);

    /** Android's position, while a program in Linux reads STREAM_LOCATION. */
    void setLocation(in LinuxLocation location);
}
