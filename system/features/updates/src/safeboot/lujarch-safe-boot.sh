#!/system/bin/sh
# Lujarch safe boot (UPD-5): applies the switches the recovery menu wrote to
# /metadata/lujarch/safe-boot. Run once per boot by init at post-fs-data
# (lujarch-safe-boot.rc), after /data is mounted and before zygote starts, as
# uid system (the owner of /data/app).
#
# One "<name>=off" per line:
#   proxy, sync, linux  lujarch.safeboot.<name>=off; each service treats its
#                       feature as switched off for this boot, whatever its
#                       own switch says
#   root                applied by the kernel (KernelSU); only recorded here,
#                       as lujarch.safeboot.root=off
#   test_updates        the /data copies of the system apps below are handed
#                       to the package manager to delete, which then runs the
#                       image's versions
# Anything else is ignored. The file stays until the recovery menu or System
# updates (returning to normal) removes it.

FLAG=/metadata/lujarch/safe-boot
TAG=lujarch_safe_boot
# What every reader of the file reads at most (the kernel too), so that all
# of them see the same switches.
MAX_BYTES=4096

log_i() { log -t "$TAG" -p i "$*"; }
log_w() { log -t "$TAG" -p w "$*"; }
log_e() { log -t "$TAG" -p e "$*"; }

# The system apps whose test updates are removed, each with the APK
# the image ships for it: a package whose APK is missing here is no system
# app of this image, and deleting its /data copy would uninstall it.
APPS='org.lujarch.settings /system_ext/priv-app/LujarchSettings/LujarchSettings.apk
org.lujarch.vault /system_ext/app/LujarchVault/LujarchVault.apk
org.lujarch.linux.terminal /system_ext/app/LujarchLinuxTerminal/LujarchLinuxTerminal.apk
org.lujarch.files /system_ext/priv-app/LujarchFiles/LujarchFiles.apk
com.android.settings /system_ext/priv-app/Settings/Settings.apk
com.android.launcher3 /system_ext/priv-app/Launcher3QuickStep/Launcher3QuickStep.apk
com.android.systemui /system_ext/priv-app/SystemUI/SystemUI.apk
org.lineageos.updater /system_ext/priv-app/Updater/Updater.apk'

# A name PackageInstallerService takes for an abandoned install session
# (vmdl<id>.tmp, isStageName): the package scan skips it and systemReady()
# deletes it through installd. Only installd may delete the compiled code
# in an update's oat/ (dalvikcache_data_file, domain.te), so the update is
# moved, not deleted, here. Ids stay within an int (tryParseSessionId).
stage_seq=0
stage_base=
next_stage() {
  [ -n "$stage_base" ] || stage_base=$(( ($(date +%s) % 10000000) * 100 + 1000000000 ))
  while [ "$stage_seq" -lt 99 ]; do
    stage_seq=$((stage_seq + 1))
    stage=/data/app/vmdl$((stage_base + stage_seq)).tmp
    [ -e "$stage" ] || return 0
  done
  return 1
}

removed=0
remove_update() { # package, its APK in the image
  if [ ! -f "$2" ]; then
    log_w "$1: $2 is not in the image; its installation is kept"
    return
  fi
  # /data/app/~~<random>/<package>-<random> (Android 11+), or the older
  # /data/app/<package>-<n>. Package names have no "-", so the prefix
  # matches no other package.
  for dir in /data/app/~~*/"$1"-* /data/app/"$1"-*; do
    [ -d "$dir" ] || continue
    if ! next_stage; then
      log_e "$1: no free session name under /data/app; $dir is kept"
      return
    fi
    if mv -n -T "$dir" "$stage" && [ ! -e "$dir" ] && [ -d "$stage" ]; then
      removed=$((removed + 1))
      log_i "$1: test update $dir moved to $stage; the package manager deletes it and runs $2"
      # The random parent of the Android 11+ layout, now empty.
      case $dir in /data/app/~~*/*) rmdir "${dir%/*}" 2>/dev/null ;; esac
    else
      log_e "$1: cannot move $dir; the test update stays"
    fi
  done
}

[ -e "$FLAG" ] || exit 0
if ! content=$(head -c "$MAX_BYTES" "$FLAG"); then
  log_e "$FLAG cannot be read; this boot is normal"
  exit 1
fi

applied=
updates=0
CR=$(printf '\r')
# One word per line: no here-document, which mksh writes to a temporary
# file this domain may not create; no globbing of the lines.
set -f
saved_ifs=$IFS
IFS='
'
for line in $content; do
  IFS=$saved_ifs
  line=${line%"$CR"}
  case $line in
    ''|'#'*) ;;
    proxy=off|sync=off|linux=off|root=off)
      name=${line%=off}
      if setprop "lujarch.safeboot.$name" off; then
        applied="$applied $name"
      else
        log_e "cannot set lujarch.safeboot.$name"
      fi
      ;;
    test_updates=off)
      updates=1
      ;;
    *)
      log_w "ignored in $FLAG: $(printf '%.64s' "$line")"
      ;;
  esac
done
IFS=$saved_ifs
set +f

if [ "$updates" = 1 ]; then
  printf '%s\n' "$APPS" | {
    while read -r pkg apk; do remove_update "$pkg" "$apk"; done
    log_i "test updates removed: $removed"
  }
  setprop lujarch.safeboot.test_updates off
  applied="$applied test_updates"
fi

if [ -n "$applied" ]; then
  log_w "safe boot, off at this boot:$applied"
else
  log_i "$FLAG sets nothing; this boot is normal"
fi
exit 0
