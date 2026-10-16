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

/** One of Linux's services (LNX-8). */
parcelable LinuxService {
    String name;
    /** Its program runs now. */
    boolean running;
    /** It is to run: started, and not stopped since. */
    boolean wanted;
    /** It starts with Linux. */
    boolean startsWithLinux;
    /** Unix seconds: when it last started or stopped; 0 when it never ran. */
    long since;
    /** How often it ended on its own and was started again since Linux started. */
    int restarts;
    /** How its program ended last: its exit status, or 128 plus the signal; -1 when it never ended. */
    int lastExit;
}
