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

/** A terminal session (LNX-3). */
parcelable LinuxSession {
    String id;
    /** One of ILinux's KIND_ constants. */
    String kind;
    /** The owner's name for it; empty when none. */
    String name;
    /** Unix seconds. */
    long created;
    /** It keeps the phone awake while a command runs. */
    boolean awake;
    /** A command runs in it. */
    boolean busy;
    /** The command's name while one runs. */
    @nullable String job;
}
