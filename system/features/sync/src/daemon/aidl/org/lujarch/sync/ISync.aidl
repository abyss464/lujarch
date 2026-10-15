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

import org.lujarch.sync.Reply;
import org.lujarch.sync.Status;

/**
 * Sync (lujarch_sync): its state, the engine's REST API, its device identity, and the KeePass
 * databases of the synced folders for Lujarch Vault. Methods that return text return JSON. A
 * refused or failed call throws a ServiceSpecificException with one of the ERROR_ constants and
 * the reason.
 */
interface ISync {
    /** Use sync is off. */
    const String STATE_OFF = "off";
    /** The phone is starting, or the engine starts. */
    const String STATE_STARTING = "starting";
    const String STATE_RUNNING = "running";
    /** A run condition is not met (Status.waiting says which). */
    const String STATE_WAITING = "waiting";
    /** There is no device identity. */
    const String STATE_NO_CONFIG = "no-config";
    /** The engine stopped after repeated failures. */
    const String STATE_CRASH_LOOP = "crash-loop";

    const int ERROR_REFUSED = 1;
    const int ERROR_FAILED = 2;
    /** Outside what the pages may ask the engine. */
    const int ERROR_NOT_ALLOWED = 3;
    /** The engine is not running. */
    const int ERROR_STOPPED = 4;
    /** The engine runs, but its API does not answer. */
    const int ERROR_UNREACHABLE = 5;
    /** Another folder uses that directory. */
    const int ERROR_FOLDER_IN_USE = 6;
    /** No folder with that ID. */
    const int ERROR_NO_FOLDER = 7;
    /** A send-only folder cannot be moved. */
    const int ERROR_NOT_MOVABLE = 8;
    /** Overriding would delete items on the other devices: ask again with deleteMissing. */
    const int ERROR_OVERRIDE_DELETES = 9;
    /** File versions would be kept inside a synced folder. */
    const int ERROR_VERSIONS_IN_FOLDER = 10;

    Status getStatus();

    /** One request to the engine's REST API, signed with its API key; secrets are left out. */
    Reply api(String method, String path, @nullable String body);

    /** The directories of the sync storage, for the folder picker. */
    String dirs();

    /** The devices the engine ignores: op list, add or remove. */
    String ignored(String op, @nullable String device, @nullable String name, @nullable String address);

    /** The run conditions and the state of the network and the charger (SYN-2). */
    String conditions();

    /** Moves a folder to another directory of the sync storage. */
    String moveFolder(String folder, String dir);

    /** The device identity and its backups (SYN-7). */
    String identity();
    String importIdentity(in byte[] cert, in byte[] key, in @nullable byte[] config, boolean keepConfig);
    String generateIdentity(boolean keepConfig);
    String restoreIdentity(String backup);

    /** The KeePass databases of the synced folders, for Lujarch Vault (VLT-2). */
    String vaultFind();
    /** One of them, open for reading. */
    ParcelFileDescriptor vaultOpen(String folder, String path);
}
