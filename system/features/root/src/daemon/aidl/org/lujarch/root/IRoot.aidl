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

package org.lujarch.root;

import org.lujarch.root.AppRoot;
import org.lujarch.root.RootInfo;
import org.lujarch.root.RootProfile;

/**
 * Root (lujarch_root): which apps KernelSU lets use su, and the root profile each one has. A
 * refused or failed call throws a ServiceSpecificException with one of the ERROR_ constants and
 * the reason.
 */
interface IRoot {
    const int NAMESPACE_INHERITED = 0;
    const int NAMESPACE_GLOBAL = 1;
    const int NAMESPACE_INDIVIDUAL = 2;

    /** The kernel has no KernelSU this daemon speaks to. */
    const int ERROR_UNAVAILABLE = 1;
    const int ERROR_NOT_INSTALLED = 2;
    /** Lujarch's own apps, and user IDs KernelSU never grants. */
    const int ERROR_NEVER_GRANTED = 3;
    /** A profile change for an app without root. */
    const int ERROR_NO_ROOT = 4;
    const int ERROR_INVALID = 5;
    const int ERROR_FAILED = 6;
    /** The caller may not change root. */
    const int ERROR_REFUSED = 7;

    RootInfo getInfo();

    /** Every profile KernelSU holds for an app. */
    AppRoot[] list();

    /** The app's profile; KernelSU's default root profile while it has none of its own. */
    RootProfile getProfile(String packageName);

    /** Allows or refuses root; a refusal returns the profile to the default (ROOT-1). */
    void setAllowed(String packageName, boolean allowed);

    /** Writes the app's root profile, which must have root. */
    void setProfile(String packageName, in RootProfile profile);

    /** Back to KernelSU's default root profile. */
    void resetProfile(String packageName);

    /**
     * Lets KernelSU grant su to the user ID of the Linux development environment's root sessions
     * in Android while Root sessions is on (LNX-4); lujarch_linux calls it.
     */
    void setLinuxRootSessions(boolean allowed);

    /**
     * Readies the process of a root session in Android, before it becomes that user ID and runs
     * su, for KernelSU to see it; lujarch_linux calls it while Root sessions is on.
     */
    void markLinuxRootSession(int pid);
}
