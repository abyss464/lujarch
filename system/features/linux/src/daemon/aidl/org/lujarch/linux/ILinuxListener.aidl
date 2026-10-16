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

/** What lujarch_linux tells Lujarch Settings and the Terminal app. */
oneway interface ILinuxListener {
    /** The status, the sessions, the services or Remote access changed. */
    void onChanged();

    /** A session ended: its shell's exit status, or 128 plus the signal that ended it. */
    void onSessionExited(String id, int status);

    /** A program in Linux asks the owner to confirm an authorization (ILinux.answerAuth). */
    void onAuthRequest(int id, String text);

    /** A program in Linux sends a command to the NFC card (ILinux.answerNfc). */
    void onNfcRequest(int id, in byte[] command);

    /** Linux's desktop copied text, for Android's clipboard while it is lent. */
    void onClipboard(String text);
}
