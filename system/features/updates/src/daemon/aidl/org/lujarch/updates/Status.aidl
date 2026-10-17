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

/** The running build's update, as System updates shows it. */
parcelable Status {
    /** One of IUpdates' STATE_ constants. */
    int state;
    /** The merge's progress in percent while the update is merged, else -1. */
    int mergeProgress = -1;
    /** Minutes a new build runs before it is confirmed by itself; 0: never. */
    int autoConfirmMinutes;
    /** Unix seconds at which the waiting build is confirmed by itself; 0: it is not. */
    long autoConfirmAt;
    /** The previous build's build number, empty when it is not known. */
    @utf8InCpp String previousBuild;
    /** Why the previous build cannot be restored (a RESTORE_ constant); empty when it can. */
    @utf8InCpp String restoreBlocker;
    /** Safe boot (UPD-5): what its switches turned off at this boot ("proxy", "sync", "linux",
        "root", "test_updates"); empty when it is not on. */
    @utf8InCpp String[] safeBootOff;
    /** Safe boot's switches are set, for this boot or from the next one on. */
    boolean safeBootSet;

    /** System slots (UPD-6): the running slot and its build. */
    @utf8InCpp String runningSlot;
    @utf8InCpp String runningBuild;
    @utf8InCpp String runningAndroid;
    @utf8InCpp String runningSecurityPatch;
    /** Unix seconds: the running build's first start in its slot; 0 when not known. */
    long runningFirstBoot;
    /** The other slot and its build as last recorded (previousBuild); empty when not known. */
    @utf8InCpp String otherSlot;
    @utf8InCpp String otherAndroid;
    @utf8InCpp String otherSecurityPatch;
    /** Whether the other slot can start: 1 yes, 0 no, -1 not known. */
    int otherBootable = -1;
}
