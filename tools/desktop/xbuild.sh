#!/usr/bin/env bash
# Builds the desktop of Linux (LNX-6) on the x86_64 host for the phone's Linux (Arch Linux ARM,
# aarch64), with clang against a sysroot streamed from the phone over adb, and installs the
# packages there. tools/lujarch desktop runs it:
#
#   xbuild.sh sysroot                      stream the sysroot from the phone's Linux
#   xbuild.sh kwin URL VERSION SHA256      kwin-lujarch from the recipe and the pin
#   xbuild.sh plasma-mobile URL VERSION SHA256
#                                          plasma-mobile-lujarch from the recipe and the pin
#   xbuild.sh plasma NAME VERSION          a Plasma component the distribution has not built yet
#   xbuild.sh install-kwin                 the last kwin-lujarch into the phone's Linux
#   xbuild.sh install-plasma-mobile        the last plasma-mobile-lujarch into it
#   xbuild.sh install-plasma NAME...       the last builds of these components into it
#
# X is the work directory, RECIPE the recipe (src/linux/desktop of the linux feature), JOBS the
# compile jobs. A component the distribution has not built yet is packaged with pkgrel 0, so that
# the distribution's own package replaces it at the next upgrade.
set -euo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
: "${X:?the work directory}" "${RECIPE:?the desktop recipe}"
JOBS=${JOBS:-$(nproc)}
ROOT=/data/lujarch/linux/os/root
sysroot=$X/sysroot

log() { printf 'desktop: %s\n' "$*"; }
die() { printf 'desktop: %s\n' "$*" >&2; exit 1; }
fetch() {
    aria2c -c -x16 -s16 -k1M --max-tries=5 --retry-wait=3 --auto-file-renaming=false \
        -d "$X" -o "$1" "$2" >/dev/null
}

mkdir -p "$X"

# ld.lld of the host clang's version, extracted from the Arch Linux Archive (not installed).
linker() {
    if command -v ld.lld >/dev/null; then
        command -v ld.lld
        return
    fi
    local ver pkg
    ver=$(pacman -Q clang | awk '{print $2}')
    pkg=lld-$ver-x86_64.pkg.tar.zst
    if [ ! -x "$X/lld/usr/bin/ld.lld" ]; then
        fetch "$pkg" "https://archive.archlinux.org/packages/l/lld/$pkg"
        mkdir -p "$X/lld"
        tar -C "$X/lld" -xf "$X/$pkg"
    fi
    mkdir -p "$X/bin"
    printf '#!/bin/sh\nLD_LIBRARY_PATH=%s/lld/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH} exec %s/lld/usr/bin/ld.lld "$@"\n' \
        "$X" "$X" >"$X/bin/ld.lld"
    chmod +x "$X/bin/ld.lld"
    echo "$X/bin/ld.lld"
}

# The anchor of the phone's Linux: commands run in its mount namespace.
anchor() {
    adb shell 'for d in /proc/[0-9]*; do [ "$(readlink $d/exe)" = /system_ext/bin/lujarch_linux ] &&
        [ "$(readlink $d/ns/mnt)" != "$(readlink /proc/1/ns/mnt)" ] && { echo ${d#/proc/}; break; }; done' |
        tr -d '\r'
}

in_linux() {
    local a
    a=$(anchor)
    [ -n "$a" ] || die "Linux does not run on the phone"
    adb shell "nsenter -t $a -m -- chroot $ROOT /usr/bin/env -i PATH=/usr/bin:/usr/sbin HOME=/root LANG=C /usr/bin/bash -c $(printf '%q' "$1")"
}

cmd_sysroot() {
    adb root >/dev/null
    log "streaming the sysroot from $ROOT"
    mkdir -p "$sysroot"
    local ex="" e
    for e in 'python3*' 'libLLVM*' guile perl5 samba systemd udev glycin-loaders gconv firmware \
             modules locale dri 'libgallium*' libteflon.so 'libPyImath*' 'libavcodec*' 'libassimp*' \
             'wine*' 'ghc*' 'jvm*' 'libclang*' 'libQt6WebEngine*'; do
        ex+=" --exclude=usr/lib/$e"
    done
    adb exec-out "cd $ROOT && tar -cf - $ex usr/include usr/lib usr/share/ECM usr/share/cmake \
        usr/share/config.kcfg usr/share/dbus-1 usr/share/hwdata usr/share/kf6 usr/share/knotifications6 \
        usr/share/pkgconfig usr/share/plasma-wayland-protocols usr/share/qt6 usr/share/wayland \
        usr/share/wayland-protocols usr/share/xcb 2>/dev/null" | tar -C "$sysroot" -xf -
    # Absolute symlinks point into the host's /: relative ones stay inside the sysroot.
    (cd "$sysroot" && find . -type l -lname '/*' | while read -r l; do
        ln -sfn "$(realpath -m --relative-to="$(dirname "$l")" ".$(readlink "$l")")" "$l"
    done)
    # Arch Linux's merged /usr: configs that resolve their prefix through /lib find /include.
    local l
    for l in lib:usr/lib lib64:usr/lib bin:usr/bin sbin:usr/bin include:usr/include; do
        [ -e "$sysroot/${l%%:*}" ] || ln -s "${l#*:}" "$sysroot/${l%%:*}"
    done
    # Packages built with an absolute /usr/include in their targets: inside the sysroot instead.
    sed -i 's|INTERFACE_INCLUDE_DIRECTORIES "/usr/include;|INTERFACE_INCLUDE_DIRECTORIES "${_IMPORT_PREFIX}/include;|' \
        "$sysroot"/usr/lib/cmake/KF6WindowSystem/KF6WindowSystemTargets.cmake 2>/dev/null || true
    sed -i 's|"/usr/include"|"${CMAKE_SYSROOT}/usr/include"|g' \
        "$sysroot"/usr/lib/cmake/PulseAudio/PulseAudioConfig.cmake 2>/dev/null || true
    # KAuth's policy generator runs at build time: the host's.
    local gen=$sysroot/usr/lib/kf6/kauth/kauth-policy-gen
    if [ -f "$gen" ] && [ ! -f "$gen.aarch64" ]; then
        mv "$gen" "$gen.aarch64"
        printf '#!/bin/sh\nexec /usr/lib/kf6/kauth/kauth-policy-gen "$@"\n' >"$gen"
        chmod +x "$gen"
    fi
    touch "$sysroot/.complete"
}

# cmake configure, build and a DESTDIR install into a new stage; prints the stage.
build_tree() { # source build
    [ -f "$sysroot/.complete" ] || die "no sysroot: run tools/lujarch desktop sysroot"
    local src=$1 build=$2 gccver ld
    ld=$(linker)
    gccver=$(ls "$sysroot/usr/lib/gcc/aarch64-unknown-linux-gnu/" | head -1)
    printf 'function(kdoctools_create_handbook)\nendfunction()\nfunction(kdoctools_create_manpage)\nendfunction()\nfunction(kdoctools_install)\nendfunction()\n' \
        >"$X/nodoc.cmake"
    export XSYSROOT=$sysroot XLDLLD=$ld
    export PKG_CONFIG_SYSROOT_DIR=$sysroot PKG_CONFIG_PATH=
    export PKG_CONFIG_LIBDIR=$sysroot/usr/lib/pkgconfig:$sysroot/usr/share/pkgconfig
    cmake -B "$build" -S "$src" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$here/aarch64-toolchain.cmake" -DXGCCVER="$gccver" \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_INSTALL_LIBEXECDIR=lib \
        -DBUILD_TESTING=OFF -DQT_HOST_PATH=/usr -DQT_HOST_PATH_CMAKE_DIR=/usr/lib/cmake \
        -DKF6_HOST_TOOLING=/usr/lib/cmake -DECM_DIR="$sysroot/usr/share/ECM/cmake" \
        -DCMAKE_DISABLE_FIND_PACKAGE_KF6DocTools=ON -DCMAKE_PROJECT_INCLUDE="$X/nodoc.cmake" \
        -DOpenMP_C_FLAGS=-fopenmp=libgomp -DOpenMP_CXX_FLAGS=-fopenmp=libgomp \
        -DOpenMP_C_LIB_NAMES=gomp -DOpenMP_CXX_LIB_NAMES=gomp \
        -DOpenMP_gomp_LIBRARY="$sysroot/usr/lib/libgomp.so" >"$build.cmake.log" 2>&1 ||
        { tail -n 30 "$build.cmake.log" >&2; die "cmake failed ($build.cmake.log)"; }
    nice -n 10 ninja -C "$build" -j "$JOBS" >"$build.ninja.log" 2>&1 ||
        { grep -E "error:|FAILED" "$build.ninja.log" | head -20 >&2; die "the build failed ($build.ninja.log)"; }
    local stage
    stage=$X/stage-$(basename "$build")-$(date +%s)
    DESTDIR="$stage" ninja -C "$build" install >/dev/null
    echo "$stage"
}

# A pacman package of a stage: name, version-release, depends.
pack() { # stage name version description depends...
    local stage=$1 name=$2 version=$3 description=$4
    shift 4
    {
        echo "pkgname = $name"
        echo "pkgbase = $name"
        echo "pkgver = $version"
        echo "pkgdesc = $description"
        echo "builddate = $(date +%s)"
        echo "packager = Lujarch (cross build)"
        echo "size = $(du -sb "$stage" | cut -f1)"
        echo "arch = aarch64"
        local d
        for d in "$@"; do echo "$d"; done
    } >"$stage/.PKGINFO"
    local pkg=$X/$name-$version-aarch64.pkg.tar.zst
    (cd "$stage" && bsdtar --uid 0 --gid 0 --uname root --gname root -cf - .PKGINFO \
        $(ls -A | grep -v '^.PKGINFO$') | zstd -q -T0 -f -o "$pkg")
    echo "$pkg"
}

# The depends of a recipe's PKGBUILD, as .PKGINFO lines.
recipe_depends() { # recipe
    local d
    for d in $(sed -n '/^depends=(/,/)/p' "$RECIPE/$1/PKGBUILD" | tr -d '()' | sed 's/depends=//'); do
        echo "depend = $d"
    done
}

cmd_kwin() { # url version sha256
    local url=$1 version=$2 sha=$3
    local tar=kwin-$version.tar.xz tree=$X/src/kwin-$version
    [ -f "$X/$tar" ] || fetch "$tar" "$url"
    echo "$sha  $X/$tar" | sha256sum -c --quiet
    if [ ! -d "$tree" ]; then
        mkdir -p "$X/src"
        tar -C "$X/src" -xf "$X/$tar"
    fi
    mkdir -p "$tree/src/backends/lujarch"
    local f
    for f in "$RECIPE"/kwin/lujarch/* "$RECIPE/protocol/lujarch_display.h"; do
        cmp -s "$f" "$tree/src/backends/lujarch/$(basename "$f")" || cp "$f" "$tree/src/backends/lujarch/"
    done
    patch -d "$tree" -p1 -R --dry-run -s -f <"$RECIPE/kwin/kwin.patch" >/dev/null 2>&1 ||
        patch -d "$tree" -p1 -N <"$RECIPE/kwin/kwin.patch"
    local stage
    stage=$(build_tree "$tree" "$X/build-kwin-$version")
    local deps=()
    mapfile -t deps < <(recipe_depends kwin)
    pack "$stage" kwin-lujarch "$version-1" \
        "KWin with the Lujarch output backend (the desktop in the Terminal app)" \
        "provides = kwin=$version" "conflict = kwin" "${deps[@]}" >"$X/last-kwin"
    log "packed $(cat "$X/last-kwin")"
}

cmd_plasma_mobile() { # url version sha256
    local url=$1 version=$2 sha=$3
    local tar=plasma-mobile-$version.tar.xz tree=$X/src/plasma-mobile-lujarch-$version
    [ -f "$X/$tar" ] || fetch "$tar" "$url"
    echo "$sha  $X/$tar" | sha256sum -c --quiet
    if [ ! -d "$tree" ]; then
        mkdir -p "$tree"
        tar -C "$tree" --strip-components=1 -xf "$X/$tar"
    fi
    patch -d "$tree" -p1 -R --dry-run -s -f <"$RECIPE/plasma-mobile/plasma-mobile.patch" >/dev/null 2>&1 ||
        patch -d "$tree" -p1 -N <"$RECIPE/plasma-mobile/plasma-mobile.patch"
    local stage
    stage=$(build_tree "$tree" "$X/build-plasma-mobile-lujarch-$version")
    local deps=()
    mapfile -t deps < <(recipe_depends plasma-mobile)
    pack "$stage" plasma-mobile-lujarch "$version-1" \
        "Plasma shell for mobile devices, with the landscape layout of the Lujarch desktop" \
        "provides = plasma-mobile=$version" "conflict = plasma-mobile" "${deps[@]}" >"$X/last-plasma-mobile"
    log "packed $(cat "$X/last-plasma-mobile")"
}

cmd_plasma() { # name version
    local name=$1 version=$2
    local tar=$name-$version.tar.xz url=https://download.kde.org/stable/plasma/$version/$name-$version.tar.xz
    [ -f "$X/$tar" ] || fetch "$tar" "$url"
    echo "$(curl -fsSL "$url.sha256" | awk '{print $1}')  $X/$tar" | sha256sum -c --quiet
    [ -d "$X/src/$name-$version" ] || { mkdir -p "$X/src"; tar -C "$X/src" -xf "$X/$tar"; }
    local stage
    stage=$(build_tree "$X/src/$name-$version" "$X/build-$name-$version")
    # The next component builds against this one.
    cp -a "$stage/usr/." "$sysroot/usr/"
    local info deps=() d
    info=$(in_linux "pacman -Qi $name 2>/dev/null || pacman -Si $name")
    for d in $(printf '%s\n' "$info" | sed -n '/^Depends On *:/,/^[A-Z][a-zA-Z ]* *:/p' | sed '$d' |
            sed 's/^Depends On *: *//' | tr -s ' ' '\n' | sed 's/[<>=].*//' | grep -v '^None$' | grep -v '^$'); do
        deps+=("depend = $d")
    done
    pack "$stage" "$name" "$version-0" "$name (cross build until the distribution's own)" "${deps[@]}" \
        >"$X/last-$name"
    log "packed $(cat "$X/last-$name")"
}

# Installs packages into the phone's Linux; files no package owned (an earlier copy) are replaced.
install_packages() {
    adb root >/dev/null
    local pkgs=() p
    for p in "$@"; do
        adb push "$p" "$ROOT/var/tmp/$(basename "$p")" >/dev/null
        pkgs+=("/var/tmp/$(basename "$p")")
    done
    in_linux "pacman -U --noconfirm --ask 4 --overwrite '*' ${pkgs[*]} && rm -f ${pkgs[*]}"
}

# Records in the desktop's install state that these parts are the current recipe's, as the install
# script does after it builds them (src/linux/desktop/install, fingerprint).
record() { # commands
    in_linux '
        here=/usr/lib/lujarch/desktop
        pin() { sed -n "s|^$1: ||p" $here/downloads; }
        fingerprint() { local name=$1; shift; { (cd $here && find "$@" -type f -print0 | sort -z | xargs -0 sha256sum); pin $name.url; pin $name.sha256; } | sha256sum | cut -d" " -f1; }
        umask 022
        mkdir -p /var/lib/lujarch/desktop
        '"$1"
}

cmd_install_kwin() {
    install_packages "$(cat "$X/last-kwin")"
    record 'fingerprint kwin kwin protocol >/var/lib/lujarch/desktop/kwin
        [ -f /opt/mesa-kgsl/share/vulkan/icd.d/freedreno_icd.aarch64.json ] &&
            fingerprint mesa mesa >/var/lib/lujarch/desktop/mesa
        pacman -Q kwin-lujarch'
}

cmd_install_plasma_mobile() {
    install_packages "$(cat "$X/last-plasma-mobile")"
    record 'fingerprint plasma-mobile plasma-mobile >/var/lib/lujarch/desktop/plasma-mobile
        pacman -Q plasma-mobile-lujarch'
}

cmd_install_plasma() {
    local pkgs=() n
    for n in "$@"; do pkgs+=("$(cat "$X/last-$n")"); done
    install_packages "${pkgs[@]}"
}

action=${1:?an action}
shift
case $action in
    sysroot) cmd_sysroot ;;
    kwin) cmd_kwin "$@" ;;
    plasma-mobile) cmd_plasma_mobile "$@" ;;
    plasma) cmd_plasma "$@" ;;
    install-kwin) cmd_install_kwin ;;
    install-plasma-mobile) cmd_install_plasma_mobile ;;
    install-plasma) cmd_install_plasma "$@" ;;
    *) die "unknown action $action" ;;
esac
