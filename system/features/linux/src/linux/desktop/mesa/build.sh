#!/usr/bin/bash
# Mesa with Turnip on KGSL and Zink, built and installed in Linux at /opt/mesa-kgsl, beside the
# system's own mesa package, which has no KGSL backend. The desktop's install script runs it as
# root:
#
#   build.sh VERSION URL SHA256
#
# The source is the release lock.toml pins (VERSION, URL, SHA256); patches/NNNN-*.patch apply in
# name order with `patch -p1` from the source root. A run reuses the source and build directories
# of an earlier one, so a patch change compiles only what it touches.
#
# Environment: MESA_WORK (default /var/tmp/lujarch/mesa-kgsl), JOBS (default 3), NICE (default 19).
set -euo pipefail

version=$1
url=$2
sha256=$3
prefix=/opt/mesa-kgsl

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
work=${MESA_WORK:-/var/tmp/lujarch/mesa-kgsl}
jobs=${JOBS:-3}
nice_level=${NICE:-19}
tarball=$work/mesa-$version.tar.xz
src=$work/mesa-$version
build=$work/build

# Turnip (msm and kgsl kernel drivers) and Zink; no GLX and no LLVM, so no llvmpipe and no
# freedreno or radeonsi Gallium drivers; Wayland as the window system platform.
options=(
    -Dbuildtype=release
    -Dprefix="$prefix"
    -Dvulkan-drivers=freedreno
    -Dfreedreno-kmds=msm,kgsl
    -Dgallium-drivers=zink
    -Dplatforms=wayland
    -Dglx=disabled
    -Dllvm=disabled
    -Dvideo-codecs=
    -Dlibdir=lib
    -Dvalgrind=disabled
    -Dlmsensors=disabled
)

log() { printf 'mesa: %s\n' "$*"; }
die() { printf 'mesa: %s\n' "$*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "runs as root (it installs to $prefix)"
for tool in curl sha256sum tar patch meson ninja nice; do
    command -v "$tool" >/dev/null || die "the tool $tool is missing"
done
mkdir -p "$work"

# The tarball, checked against its pin. A partial download resumes from $tarball.part.
tarball_ok() { [ -f "$tarball" ] && [ "$(sha256sum "$tarball" | cut -d' ' -f1)" = "$sha256" ]; }
if [ -f "$tarball" ]; then
    tarball_ok || { rm -f "$tarball"; die "$tarball did not match its sha256; it is removed"; }
else
    log "downloading $url"
    curl -fL --retry 5 --retry-delay 3 -C - -o "$tarball.part" "$url"
    mv "$tarball.part" "$tarball"
    tarball_ok || { rm -f "$tarball"; die "$url does not match its sha256"; }
fi

# The source tree, extracted aside so an interrupted run leaves no half tree.
if [ ! -d "$src" ]; then
    rm -rf "$work/.extract"
    mkdir "$work/.extract"
    tar -xf "$tarball" -C "$work/.extract" --no-same-owner
    mv "$work/.extract/mesa-$version" "$src"
    rmdir "$work/.extract"
fi

# Patches in name order. One that reverse-applies is in the tree already. A later patch of the
# series may have changed lines an earlier one reverse-needs, so the series from this patch on is
# also reversed, last patch first, on a scratch copy of the files it touches.
patches=()
for p in "$here"/patches/*.patch; do
    if [ -e "$p" ]; then patches+=("$p"); fi
done

series_reverses() {
    local from=$1 scratch j f ok=0
    scratch=$(mktemp -d "$work/.series.XXXXXX")
    for ((j = from; j < ${#patches[@]}; j++)); do
        sed -n 's/^\(---\|+++\) [ab]\/\([^[:space:]]*\).*/\2/p' "${patches[$j]}"
    done | sort -u | while IFS= read -r f; do
        if [ -f "$src/$f" ]; then
            mkdir -p "$scratch/$(dirname "$f")"
            cp -p "$src/$f" "$scratch/$f"
        fi
    done
    for ((j = ${#patches[@]} - 1; j >= from; j--)); do
        patch -d "$scratch" -p1 -R -s -f <"${patches[$j]}" >/dev/null 2>&1 || ok=1
    done
    rm -rf "$scratch"
    return "$ok"
}

for i in "${!patches[@]}"; do
    p=${patches[$i]}
    name=${p#"$here/"}
    if patch -d "$src" -p1 -R --dry-run -s -f <"$p" >/dev/null 2>&1 || series_reverses "$i"; then
        log "already applied: $name"
    elif patch -d "$src" -p1 --dry-run -s -f <"$p" >/dev/null 2>&1; then
        patch -d "$src" -p1 -s -f <"$p"
        log "applied: $name"
    else
        die "$name does not apply to $src"
    fi
done

# One log set per run.
stamp=$(date +%Y%m%d-%H%M%S)
logdir=/var/log/lujarch/desktop-install
mkdir -p "$logdir"
setup_log=$logdir/$stamp-mesa-setup.log
build_log=$logdir/$stamp-mesa-build.log
install_log=$logdir/$stamp-mesa-install.log

# Configure: a new build directory, or the existing one with the options set again. A build
# directory of another source tree is discarded.
if [ -f "$build/build.ninja" ] && ! grep -q "\"source\": \"$src\"" "$build/meson-info/meson-info.json" 2>/dev/null; then
    rm -rf "$build"
fi
if [ -f "$build/build.ninja" ]; then
    nice -n "$nice_level" meson configure "$build" "${options[@]}" >"$setup_log" 2>&1 ||
        { tail -n 30 "$setup_log"; die "meson configure failed ($setup_log)"; }
else
    nice -n "$nice_level" meson setup "$build" "$src" "${options[@]}" >"$setup_log" 2>&1 ||
        { tail -n 30 "$setup_log"; die "meson setup failed ($setup_log)"; }
fi

log "building with $jobs jobs ($build_log)"
nice -n "$nice_level" ninja -C "$build" -j "$jobs" >"$build_log" 2>&1 ||
    { tail -n 40 "$build_log"; die "the build failed ($build_log)"; }
nice -n "$nice_level" meson install -C "$build" --no-rebuild --only-changed >"$install_log" 2>&1 ||
    { tail -n 30 "$install_log"; die "the install failed ($install_log)"; }

icd=$prefix/share/vulkan/icd.d/freedreno_icd.aarch64.json
[ -f "$icd" ] || die "installed without $icd"
log "installed to $prefix"
