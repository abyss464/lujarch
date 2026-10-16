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

import org.lujarch.linux.LinuxSession;

/** The Linux development environment as its page shows it. */
parcelable Status {
    /** One of ILinux's STATE_ constants. */
    String state;
    /** Why Linux could not start (STATE_FAILED). */
    @nullable String reason;
    boolean installed;
    /** Unix seconds: when the installed system was installed. */
    long installedAt;
    /** The owner's user in Linux. */
    @nullable String user;
    /** The initial setup ran to its end for the installed system. */
    boolean setupDone;
    LinuxSession[] sessions;
    /** The last action, ILinux's ACTION_ constants; null before the first one. */
    @nullable String action;
    boolean actionRunning;
    /** It ended well. */
    boolean actionOk;
    /** What failed. */
    @nullable String actionMessage;
    /** Unix seconds: its start while it runs, its end after. */
    long actionTime;
    /** An install: bytes of the archive read, and its size (0 when unknown). */
    long actionDone;
    long actionTotal;
    /** Bytes of memory Linux uses now; 0 while it does not run. */
    long memoryUsed;
    /** Bytes Linux may use before Android's apps are favored; 0 for unlimited. */
    long memoryLimit;
    /** The phone's memory in bytes. */
    long memoryTotal;
    boolean swapOn;
    /** Bytes of the swap file. */
    long swapSize;
    /** Unix seconds: when each system kept beside the installed one was installed. */
    long[] kept;
}
