#!/bin/bash
# The Linux side of PocketIDE, tested on arm64 the way the phone runs it.
#
#  1. Every shell script in the app's assets and in pocket/tools passes bash -n and the
#     ShellCheck linter, and every Python file in the assets compiles.
#  2. The Python unit tests under pocket/tools/tests pass (the room tools and the gates).
#  3. The exact Ubuntu base image the app pins (read from LinuxPins.kt, checked by SHA-256 and
#     size) becomes a container, and assets/linux/bootstrap.sh turns it into the computer:
#     it must finish, report progress to 100, leave the tools in place, and run a second time
#     without redoing anything. update.sh, when present, must end with "pocketide-fixed <n>".
#
# Docker stands in for proot here: the app's proot is an Android binary that needs Android's
# own linker, so this checks the scripts and the image, not proot itself.
#
# Usage: engine-test.sh        (needs docker, shellcheck, python3 and curl; run on arm64,
#                               or on another host with arm64 emulation registered)
set -euo pipefail

TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POCKET="$(dirname "$TOOLS")"
readonly TOOLS POCKET
readonly ASSETS="$POCKET/app/src/main/assets"
readonly LINUX="$ASSETS/linux"
readonly CACHE="${POCKETIDE_ENGINE_CACHE:-${RUNNER_TEMP:-/tmp}/pocketide-engine}"

failures=0

say() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; failures=$((failures + 1)); }
summary() { [ -n "${GITHUB_STEP_SUMMARY:-}" ] && printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"; return 0; }

need() {
  command -v "$1" > /dev/null || { printf 'FAIL: %s is not installed; the engine test needs it.\n' "$1" >&2; exit 1; }
}

lint_one() {
  local script="$1"
  if ! bash -n "$script"; then
    fail "${script#"$POCKET"/} has a syntax error"
  elif ! shellcheck --severity=style "$script"; then
    fail "${script#"$POCKET"/} has shellcheck findings (above)"
  else
    say "ok   ${script#"$POCKET"/}"
  fi
}

lint_scripts() {
  local script found=0
  say "== Shell scripts under assets/ and tools/"
  while IFS= read -r -d '' script; do
    found=1
    lint_one "$script"
  done < <(find "$ASSETS" -type f -name '*.sh' -print0 | sort -z)
  [ "$found" = 1 ] || fail "no shell scripts under assets/; the Linux set-up scripts are missing"
  while IFS= read -r -d '' script; do
    lint_one "$script"
  done < <(find "$TOOLS" -type f -name '*.sh' -print0 | sort -z)
}

compile_python() {
  local file
  say "== Python under assets/"
  while IFS= read -r -d '' file; do
    if python3 -c 'import ast, sys; ast.parse(open(sys.argv[1], encoding="utf-8").read(), sys.argv[1])' "$file"; then
      say "ok   ${file#"$POCKET"/}"
    else
      fail "${file#"$POCKET"/} does not compile"
    fi
  done < <(find "$ASSETS" -type f -name '*.py' -print0 | sort -z)
}

python_tests() {
  say "== Python unit tests (pocket/tools/tests)"
  if ! python3 -m unittest discover -s "$TOOLS/tests" -t "$TOOLS" -v; then
    fail "the Python unit tests failed (above)"
  fi
}

# The base image, fetched once and kept only if it matches the app's own pin.
ubuntu_image() {
  local url sha256 bytes file tag
  read -r url sha256 bytes < <(python3 "$TOOLS/pins.py" --get ubuntuBase)
  file="$CACHE/${url##*/}"
  mkdir -p "$CACHE"
  if [ ! -f "$file" ] || ! echo "$sha256  $file" | sha256sum --check --status; then
    say "Downloading ${url##*/}…" >&2
    curl --fail --location --silent --show-error --retry 3 --output "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  if ! echo "$sha256  $file" | sha256sum --check --status; then
    rm -f "$file"
    printf 'FAIL: %s does not match the SHA-256 pinned in LinuxPins.kt\n' "${url##*/}" >&2
    exit 1
  fi
  if [ "$bytes" != "-" ] && [ "$(stat -c %s "$file")" != "$bytes" ]; then
    printf 'FAIL: %s is not the %s bytes pinned in LinuxPins.kt\n' "${url##*/}" "$bytes" >&2
    exit 1
  fi
  tag="pocketide-ubuntu-base:${sha256:0:12}"
  if ! docker image inspect "$tag" > /dev/null 2>&1; then
    docker import --platform linux/arm64 "$file" "$tag" > /dev/null
  fi
  printf '%s\n' "$tag"
}

bootstrap_in_container() {
  local image
  say "== bootstrap.sh in the pinned Ubuntu base (arm64)"
  if [ ! -f "$LINUX/bootstrap.sh" ]; then
    fail "app/src/main/assets/linux/bootstrap.sh is missing: the linux module writes it, and without it no computer can be set up"
    return
  fi
  image=$(ubuntu_image)
  if docker run --rm --platform linux/arm64 --network bridge \
      --volume "$LINUX:/pocketide:ro" --volume "$TOOLS/engine:/checks:ro" --env HOME=/root \
      "$image" /bin/bash /checks/check-computer.sh; then
    say "ok   bootstrap.sh set up the computer, and a second run changed nothing"
  else
    fail "bootstrap.sh did not set up the computer (exit $?, output above)"
  fi
}

main() {
  need shellcheck
  need python3
  need docker
  need curl
  lint_scripts
  compile_python
  python_tests
  bootstrap_in_container
  if [ "$failures" -gt 0 ]; then
    summary "### Engine test: $failures failure(s)"
    say "$failures failure(s)."
    exit 1
  fi
  summary "### Engine test passed on $(uname -m)"
  say "Engine test passed."
}

main "$@"
