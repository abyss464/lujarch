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

import org.lujarch.proxy.IProxyListener;
import org.lujarch.proxy.Reply;
import org.lujarch.proxy.Status;

/**
 * The proxy (lujarch_proxy): its state, its core's controller, its manager and its restart. A
 * refused call throws a ServiceSpecificException with ERROR_REFUSED and the reason, a failed one
 * ERROR_FAILED, and a manager run while another runs ERROR_BUSY.
 */
interface IProxy {
    /** Use proxy is off. */
    const String STATE_OFF = "off";
    /** The proxy waits for the phone's start or its network to finish, or a core starts. */
    const String STATE_STARTING = "starting";
    /** The core runs the current configuration. */
    const String STATE_RUNNING = "running";
    /** The core runs the last working configuration: the current one did not pass the check. */
    const String STATE_FALLBACK = "fallback";
    /** No configuration passes the check; the phone connects directly. */
    const String STATE_NO_CONFIG = "no-config";
    /** The core stopped after repeated failures; the phone connects directly. */
    const String STATE_CRASH_LOOP = "crash-loop";

    /** Why the proxy restarted its core on its own (Status.lastRestart). */
    const String RESTART_REPAIR = "repair";
    const String RESTART_UNHEALTHY = "unhealthy";
    const String RESTART_EXITED = "exited";
    const String RESTART_NETWORK_STACK = "network-stack";

    const int ERROR_REFUSED = 1;
    const int ERROR_FAILED = 2;
    const int ERROR_BUSY = 3;

    Status getStatus();

    /** Calls listener with the state now and at every change, until its process ends. */
    void watch(IProxyListener listener);

    /**
     * One request to the core's controller, which the service signs with the control key. Only
     * the requests Lujarch's pages make are allowed. Reply.status is the HTTP status.
     */
    Reply controller(String method, String path, @nullable String body);

    /**
     * A live stream of the controller (/traffic, /logs?level=...): one JSON line per event on the
     * returned socket until the caller closes it or the core ends the stream.
     */
    ParcelFileDescriptor stream(String path);

    /**
     * A run of the proxy's manager with args; input, when the command reads it, is the read end of
     * a pipe the caller fills. Reply.status is its exit code and Reply.body its output, with every
     * address in it masked.
     */
    Reply manager(in String[] args, in @nullable ParcelFileDescriptor input);

    /**
     * The manager's export written to out: every file of the proxy, with the subscription
     * addresses and node passwords when secrets is set. Returns the manager's exit code; its
     * messages go to the log.
     */
    int exportTo(boolean secrets, in ParcelFileDescriptor out);

    /** Stops and starts the core; also ends a stop after repeated failures. */
    void restart();
}
