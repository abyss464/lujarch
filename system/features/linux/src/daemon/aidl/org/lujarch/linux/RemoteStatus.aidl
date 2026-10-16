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

/** Remote access (LNX-9) as its page shows it; the token and the secret never leave the service. */
parcelable RemoteStatus {
    /** ILinux's REMOTE_ constants: When it runs. */
    String mode;
    /** ILinux's RELAY_ constants. */
    String state;
    /** What failed (RELAY_FAILED). */
    @nullable String reason;
    /** A charger is connected; false when it cannot be read. */
    boolean charging;
    String server;
    int port;
    /** The entry's name. */
    String name;
    boolean tokenSet;
    boolean secretSet;
    /** The public keys allowed to log in, one OpenSSH key each. */
    String[] keys;
}
