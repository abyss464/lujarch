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

/** Remote access's connection through the owner's relay server (LNX-9). */
parcelable RemoteConfig {
    /** Host name or address; empty removes the connection. */
    String server;
    int port;
    /** The token of the relay server; null keeps the one set. */
    @nullable String token;
    /** The entry's name. */
    String name;
    /** The entry's secret; null keeps the one set. */
    @nullable String secret;
    /** The public keys allowed to log in, one OpenSSH key each. */
    String[] keys;
}
