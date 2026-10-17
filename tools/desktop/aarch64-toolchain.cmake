# The phone's Linux (aarch64) for clang on the x86_64 host: compilers and programs from the host,
# headers, libraries and packages from the sysroot streamed from the phone (tools/desktop/xbuild.sh).
# XSYSROOT and XLDLLD come from the environment.
set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

set(XSYSROOT "$ENV{XSYSROOT}" CACHE PATH "aarch64 sysroot")
set(XLDLLD "$ENV{XLDLLD}" CACHE FILEPATH "ld.lld to link with")
set(XGCCVER "16.1.1" CACHE STRING "GCC version inside the sysroot")

set(CMAKE_SYSROOT "${XSYSROOT}")
set(CMAKE_C_COMPILER clang)
set(CMAKE_CXX_COMPILER clang++)
set(CMAKE_C_COMPILER_TARGET aarch64-unknown-linux-gnu)
set(CMAKE_CXX_COMPILER_TARGET aarch64-unknown-linux-gnu)

set(_xflags "--gcc-install-dir=${XSYSROOT}/usr/lib/gcc/aarch64-unknown-linux-gnu/${XGCCVER}")
set(CMAKE_C_FLAGS_INIT "${_xflags}")
set(CMAKE_CXX_FLAGS_INIT "${_xflags}")
set(_xld "-fuse-ld=lld --ld-path=${XLDLLD} -Wl,--allow-shlib-undefined")
set(CMAKE_EXE_LINKER_FLAGS_INIT "${_xld}")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "${_xld}")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "${_xld}")

set(CMAKE_FIND_ROOT_PATH "${XSYSROOT}")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)

set(ENV{PKG_CONFIG_SYSROOT_DIR} "${XSYSROOT}")
set(ENV{PKG_CONFIG_LIBDIR} "${XSYSROOT}/usr/lib/pkgconfig:${XSYSROOT}/usr/share/pkgconfig")
set(ENV{PKG_CONFIG_PATH} "")
