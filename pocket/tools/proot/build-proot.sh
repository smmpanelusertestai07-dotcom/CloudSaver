#!/bin/bash
# Builds PRoot for arm64 Android from pinned sources, with 16 KB page alignment (plan §13).
#
# 2.6.0 shipped Termux's prebuilt PRoot with its file names and prefix byte-patched, and that
# package is gone. This builds the release marked "build": true in sources.json the way Termux
# packages it (make -C src PROOT_WITH_LIBANDROID_SHMEM=true, ARG_MAX=131072), with two changes:
# talloc and libandroid-shmem are linked in statically, so the app ships just two files and
# nothing needs renaming or patching; and every download is checked against its pinned SHA-256
# before it is unpacked.
#
# Output, in OUT_DIR:
#   jniLibs/arm64-v8a/libproot.so         the proot executable (Android extracts only lib*.so)
#   jniLibs/arm64-v8a/libproot-loader.so  the loader, which the app names in PROOT_LOADER
#   sources/                              the three source archives, exactly as verified
#   PROVENANCE.txt                        versions, checksums, NDK and flags
#
# Usage: build-proot.sh OUT_DIR
# Needs: ANDROID_NDK_HOME (the NDK version named in sources.json), python3, curl, make, unzip,
#        readelf (PRoot's loader-info step runs it on the host).
set -euo pipefail
shopt -s inherit_errexit

readonly API=29
readonly TARGET="aarch64-linux-android$API"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly HERE
readonly SOURCES_JSON="$HERE/sources.json"

die() { printf 'build-proot: %s\n' "$*" >&2; exit 1; }

# Prints "<field> <value>" lines for the release to build, from sources.json.
release_field() {
  python3 - "$SOURCES_JSON" "$1" <<'PY'
import json, sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
builds = [r for r in data["releases"] if r.get("build")]
if len(builds) != 1:
    sys.exit("sources.json must mark exactly one release with build: true")
value = data if sys.argv[2] == "ndk" else builds[0]
for key in sys.argv[2].split("."):
    value = value[key]
print(value)
PY
}

fetch_verified() {
  local url="$1" sha256="$2" dest="$3"
  if [ ! -f "$dest" ] || ! echo "$sha256  $dest" | sha256sum --check --status; then
    curl --fail --location --silent --show-error --retry 3 --output "$dest.part" "$url"
    mv "$dest.part" "$dest"
  fi
  echo "$sha256  $dest" | sha256sum --check --status || die "${url##*/} does not match its pinned SHA-256"
}

check_ndk() {
  local wanted have
  wanted=$(release_field ndk)
  [ -n "${ANDROID_NDK_HOME:-}" ] || die "set ANDROID_NDK_HOME to NDK $wanted"
  have=$(sed -n 's/^Pkg.Revision *= *//p' "$ANDROID_NDK_HOME/source.properties")
  [ "$have" = "$wanted" ] || die "NDK $have found; sources.json pins $wanted"
  NDK_VERSION="$have"
}

setup_toolchain() {
  local bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
  [ -x "$bin/$TARGET-clang" ] || die "no $TARGET-clang in the NDK (the NDK runs on x86-64 Linux hosts)"
  export CC="$bin/$TARGET-clang"
  export AR="$bin/llvm-ar"
  export STRIP="$bin/llvm-strip"
  export OBJCOPY="$bin/llvm-objcopy"
  export OBJDUMP="$bin/llvm-objdump"
}

build_shmem() {
  local archive="$1" dir
  tar -xzf "$archive" -C "$WORK"
  dir=$(find "$WORK" -maxdepth 1 -type d -name 'libandroid-shmem-*' | head -n 1)
  make -C "$dir" CC="$CC" AR="$AR" libandroid-shmem.a
  install -D -m 644 "$dir/libandroid-shmem.a" "$WORK/lib/libandroid-shmem.a"
  install -D -m 644 "$dir/shm.h" "$WORK/include/sys/shm.h"
}

# talloc's own build (waf) with Termux's cross-compile answers, then a static archive of its
# objects and of the libreplace objects it relies on.
build_talloc() {
  local archive="$1" dir objects
  tar -xzf "$archive" -C "$WORK"
  dir=$(find "$WORK" -maxdepth 1 -type d -name 'talloc-*' | head -n 1)
  cat > "$dir/cross-answers.txt" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF
  (
    cd "$dir"
    ./configure --prefix="$WORK/talloc-prefix" --disable-rpath --disable-python \
      --cross-compile --cross-answers=cross-answers.txt
    make
  )
  mapfile -t objects < <(find "$dir/bin/default" -maxdepth 1 -name 'talloc*.o'; \
    find "$dir/bin/default/lib/replace" -maxdepth 1 -name '*.o' 2> /dev/null)
  [ "${#objects[@]}" -gt 0 ] || die "talloc's build left no object files"
  mkdir -p "$WORK/lib"
  "$AR" rcs "$WORK/lib/libtalloc.a" "${objects[@]}"
  install -D -m 644 "$dir/talloc.h" "$WORK/include/talloc.h"
}

build_proot() {
  local archive="$1" version="$2" dir
  unzip -q "$archive" -d "$WORK"
  dir=$(find "$WORK" -maxdepth 1 -type d -name 'proot-*' | head -n 1)
  # PRoot's makefile appends to these (+=), so they go through the environment, not the
  # command line, which would replace its own flags.
  # The backslashes survive into make's shell, which turns \" into the quotes of a C string.
  export CPPFLAGS="-I$WORK/include -DARG_MAX=131072 -DVERSION=\\\"$version\\\""
  export CFLAGS="-O2"
  export LDFLAGS="-L$WORK/lib -Wl,-z,max-page-size=16384 -llog -landroid"
  make -C "$dir/src" PROOT_WITH_LIBANDROID_SHMEM=true V=1 proot loader/loader
  mkdir -p "$OUT/jniLibs/arm64-v8a"
  "$STRIP" -o "$OUT/jniLibs/arm64-v8a/libproot.so" "$dir/src/proot"
  "$STRIP" -o "$OUT/jniLibs/arm64-v8a/libproot-loader.so" "$dir/src/loader/loader"
}

check_output() {
  local lib="$OUT/jniLibs/arm64-v8a/libproot.so" needed
  needed=$("$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -d "$lib" \
    | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | tr '\n' ' ')
  case "$needed" in
    *talloc* | *shmem*) die "libproot.so still needs a shared talloc or libandroid-shmem: $needed" ;;
  esac
  grep -aq "$VERSION" "$lib" || die "libproot.so does not carry its version string $VERSION"
  python3 "$HERE/../gates/native_alignment.py" --lib-dir "$OUT/jniLibs"
  printf 'libproot.so needs: %s\n' "$needed"
}

write_provenance() {
  {
    printf 'PRoot %s for arm64-v8a Android (API %s), built by PocketIDE CI.\n\n' "$VERSION" "$API"
    printf 'NDK %s, clang target %s\n' "$NDK_VERSION" "$TARGET"
    printf 'make -C src PROOT_WITH_LIBANDROID_SHMEM=true\n'
    printf 'CPPFLAGS=%s\nCFLAGS=%s\nLDFLAGS=%s\n\n' "$CPPFLAGS" "$CFLAGS" "$LDFLAGS"
    printf 'Sources (verified by SHA-256 before use; copies in sources/):\n'
    (cd "$OUT/sources" && sha256sum -- *)
    printf '\nOutputs:\n'
    (cd "$OUT/jniLibs/arm64-v8a" && sha256sum -- *.so)
  } > "$OUT/PROVENANCE.txt"
}

main() {
  [ "$#" -eq 1 ] || die "usage: build-proot.sh OUT_DIR"
  OUT="$(mkdir -p "$1" && cd "$1" && pwd)"
  WORK="$(mktemp -d)"
  trap 'rm -rf "$WORK"' EXIT
  VERSION=$(release_field version)
  check_ndk
  setup_toolchain
  mkdir -p "$OUT/sources"
  local proot talloc shmem
  proot="$OUT/sources/proot-$VERSION.zip"
  talloc="$OUT/sources/talloc-$(release_field talloc.version).tar.gz"
  shmem="$OUT/sources/libandroid-shmem-$(release_field libandroid_shmem.version).tar.gz"
  fetch_verified "$(release_field proot.url)" "$(release_field proot.sha256)" "$proot"
  fetch_verified "$(release_field talloc.url)" "$(release_field talloc.sha256)" "$talloc"
  fetch_verified "$(release_field libandroid_shmem.url)" "$(release_field libandroid_shmem.sha256)" "$shmem"
  build_shmem "$shmem"
  build_talloc "$talloc"
  build_proot "$proot" "$VERSION"
  check_output
  write_provenance
  printf 'Built PRoot %s into %s\n' "$VERSION" "$OUT"
}

main "$@"
