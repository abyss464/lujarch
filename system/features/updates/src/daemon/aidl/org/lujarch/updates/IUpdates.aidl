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

package org.lujarch.updates;

import org.lujarch.updates.Status;

/**
 * The confirmation of the running build and the return to the previous one (lujarch_updates).
 * A refused call throws a ServiceSpecificException with ERROR_REFUSED and the reason, a failed one
 * ERROR_FAILED.
 */
interface IUpdates {
    /** No update waits: none was installed, or the running build is confirmed and merged. */
    const int STATE_NONE = 0;
    /** An update is written to the other slot and starts at the next restart. */
    const int STATE_INSTALLED = 1;
    /** The running build waits for confirmation; the previous build is whole. */
    const int STATE_WAITING = 2;
    /** The running build is confirmed and Android merges its update. */
    const int STATE_MERGING = 3;
    /** The previous build starts at the next restart. */
    const int STATE_RESTORING = 4;

    /** The previous build has another Android version. */
    const String RESTORE_ANDROID_VERSION = "android_version";
    /** The previous build has an older security patch. */
    const String RESTORE_PATCH_LEVEL = "patch_level";
    /** The previous slot cannot start. */
    const String RESTORE_NOT_BOOTABLE = "not_bootable";
    /** Which build the previous slot holds is not known. */
    const String RESTORE_UNKNOWN_BUILD = "unknown_build";
    /** The boot state cannot be read. */
    const String RESTORE_UNKNOWN = "unknown";

    const int ERROR_REFUSED = 1;
    const int ERROR_FAILED = 2;

    Status getStatus();

    /** Confirms the running build; Android then merges its update. */
    void confirm();

    /** Makes the previous build start at the next restart, which the caller then makes. */
    void restorePrevious();

    /** Ends safe boot: its switches are removed, and the next start is a normal one (UPD-5). */
    void endSafeBoot();
}
