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
package org.lujarch.sync;

/** Sync as its page shows it. */
parcelable Status {
    /** One of ISync's STATE_ constants. */
    String state;
    /** What sync waits for: "", "wifi", "charging" or "wifi,charging". */
    String waiting;
    /** Unix seconds: when the running engine started; 0 while none runs. */
    long startedAt;
    /** Exits of the engine in the current five minutes. */
    int exits;
    /** The device identity is there (certificate, key and configuration). */
    boolean identity;
    /** The sync storage has the owner and permissions it needs (SYN-8). */
    boolean storageOk;
}
