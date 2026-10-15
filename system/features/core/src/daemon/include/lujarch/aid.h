#pragma once

#include <sys/types.h>

// Lujarch's own user and group ids, the only ones outside AOSP's table.
namespace lujarch::aid {

inline constexpr uid_t kSync = 5100;   // the sync engine
inline constexpr uid_t kLinux = 5200;  // the Linux system's user

}  // namespace lujarch::aid
