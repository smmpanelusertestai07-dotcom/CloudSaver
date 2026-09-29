#!/bin/bash
# Runs one command inside the engine test's computer the way the app runs every command on a
# phone (linux/ProotCommand.kt): the same PRoot flags, the home folder bound at /root, and a
# clean environment with the app's basics. Only a proxy the runner itself uses is passed on.
#
# Usage: guest.sh <command...>
# Environment: ENGINE (the work folder: rootfs/, home/, tmp/, shm/), PROOT and PROOT_LOADER;
# ENGINE_ANDROID_SECCOMP, a file of syscall numbers: the command runs under Android's app
# seccomp filter with those allowed (android-seccomp.c, which the engine test installs).
set -euo pipefail

: "${ENGINE:?set ENGINE to the work folder of the engine test}"
: "${PROOT:?set PROOT to the proot binary}"
: "${PROOT_LOADER:?set PROOT_LOADER to the loader of that proot}"

readonly GUEST_PATH=/root/.local/bin:/opt/pocketide/bin:/opt/code-server/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

proxy=()
for name in HTTPS_PROXY https_proxy NO_PROXY no_proxy; do
  if [ -n "${!name:-}" ]; then
    proxy+=("$name=${!name}")
  fi
done
# A proxy's CA that engine-test.sh put in (ENGINE_EXTRA_CA): Node.js reads it from here.
if [ -f "$ENGINE/rootfs/usr/local/share/ca-certificates/engine-proxy.crt" ]; then
  proxy+=("NODE_EXTRA_CA_CERTS=/usr/local/share/ca-certificates/engine-proxy.crt")
fi

android=()
if [ -n "${ENGINE_ANDROID_SECCOMP:-}" ]; then
  android=(/usr/local/bin/android-seccomp "$(cat "$ENGINE_ANDROID_SECCOMP")" --)
fi

mkdir -p "$ENGINE/tmp" "$ENGINE/shm"
exec env -i PATH=/usr/bin:/bin PROOT_TMP_DIR="$ENGINE/tmp" PROOT_LOADER="$PROOT_LOADER" \
  PROOT_NO_SECCOMP=1 PROOT_NO_MOUNTINFO=1 \
  "$PROOT" --link2symlink --kill-on-exit -0 -r "$ENGINE/rootfs" \
  -b /dev -b /proc -b /sys -b "$ENGINE/shm:/dev/shm" -b "$ENGINE/home:/root" -w /root \
  /usr/bin/env -i HOME=/root USER=root LOGNAME=root SHELL=/bin/bash PATH="$GUEST_PATH" \
  TERM=xterm-256color LANG=C.UTF-8 TZ=UTC TMPDIR=/tmp "${proxy[@]}" "${android[@]}" "$@"
