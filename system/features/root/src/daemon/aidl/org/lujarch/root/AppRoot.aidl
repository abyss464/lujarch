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

/** A profile KernelSU holds: one per user ID, under the package that was granted. */
parcelable AppRoot {
    String packageName;
    int uid;
    boolean allowed;
    /** Values of its own instead of KernelSU's default root profile. */
    boolean custom;
}
