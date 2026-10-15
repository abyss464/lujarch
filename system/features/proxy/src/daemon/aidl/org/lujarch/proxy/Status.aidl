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

package org.lujarch.proxy;

/** The proxy as its status page shows it. */
parcelable Status {
    /** One of IProxy's STATE_ constants. */
    String state;
    /** Unix seconds: when the running core started; 0 while none runs. */
    long startedAt;
    /** Unix seconds: when the configuration the core runs, and the last working one, were built. */
    long configAt;
    long lastGoodAt;
    /** Why the current configuration did not pass the check (STATE_FALLBACK, STATE_NO_CONFIG). */
    String checkError;
    /** The virtual network interface is up. */
    boolean tun;
    /** IPv6 is blocked (PRX-5). */
    boolean ipv6Blocked;
    /** The last restart the proxy made on its own: an IProxy RESTART_ constant, or "". */
    String lastRestart;
    long lastRestartAt;
    /** Exits of the core in the current five minutes. */
    int exits;
}
