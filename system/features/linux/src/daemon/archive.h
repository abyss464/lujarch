// Copyright (C) 2026 The Lujarch Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// A root file system archive (LNX-2): a tar archive, plain or gzip-compressed, as Arch Linux ARM
// releases it. It is checked as it is unpacked: every header and its checksum, names that stay
// inside the system, links made without following any, and at the end a shell for this phone's
// processor. Device nodes are left out; the system gets Android's. Directories get their owner
// and mode last, deepest first, since no domain bypasses permissions.
#pragma once

#include <android-base/result.h>

#include <atomic>
#include <cstdint>

namespace lujarch::linux::archive {

// Unpacks the archive read from fd into the empty directory dir; done counts the bytes read.
android::base::Result<void> Unpack(int fd, int dir, std::atomic<int64_t>* done);

// The unpacked system runs on this phone: it has /usr/bin/bash for the phone's processor.
android::base::Result<void> Check(int dir);

}  // namespace lujarch::linux::archive
