#pragma once

#include <sys/types.h>

// Lujarch's own user and group ids, the only ones outside AOSP's table.
namespace lujarch::aid {

inline constexpr uid_t kSync = 5100;        // the sync engine
inline constexpr uid_t kLinux = 5200;       // the Linux system's user
inline constexpr uid_t kLinuxRoot = 5201;   // Android's root sessions of Linux, through KernelSU
inline constexpr uid_t kLinuxRelay = 5202;  // Remote access's relay client

}  // namespace lujarch::aid
