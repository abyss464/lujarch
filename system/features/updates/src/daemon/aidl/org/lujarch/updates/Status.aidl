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
}
