#!/bin/bash
# The computer PocketIDE builds on a phone, built and used here, on a real Linux machine of the
# phone's own kind (arm64 in CI), with Termux's PRoot and the app's own flags:
#
#  1. Every shell script passes bash -n and ShellCheck, the forwarder and the companion parse, and
#     the Python tools' tests pass.
#  2. Termux's PRoot, from the source sources.json pins, is built for this machine.
#  3. The Ubuntu base the app pins (checked by SHA-256 and size) becomes the computer through the
#     app's bootstrap.sh; a second run installs nothing; update.sh ends with "pocketide-fixed <n>".
#  4. The pinned code-server, the three official agents (the releases the app would pick, checked
#     against Open VSX's SHA-256) and PocketIDE's companion are installed with code-server's own
#     installer, and the agents' command-line tools answer.
#  5. screens.mjs opens each agent's screen and the sign-in terminal as the app does, and saves
#     a picture of each.
#
# Inputs: the files the app writes, from its unit tests (app/build/ide-files/ and
# app/build/engine/pins.json; set ENGINE_INPUTS to another app/build).
# Environment: ENGINE (work folder), ENGINE_OUT (pictures and logs), CHROMIUM (a Chromium to use
# instead of downloading Playwright's), ENGINE_EXTRA_CA (a CA file Linux must trust, behind a
# proxy that re-signs HTTPS), ENGINE_PROOT_SRC (a checkout of the pinned PRoot tag, where
# GitHub's archive cannot be downloaded).
# Usage: engine-test.sh    (needs python3, node 22, curl, make, gcc, shellcheck and libtalloc-dev)
set -euo pipefail
shopt -s inherit_errexit

TOOLS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POCKET="$(dirname "$TOOLS")"
readonly TOOLS POCKET
readonly ASSETS="$POCKET/app/src/main/assets"
readonly INPUTS="${ENGINE_INPUTS:-$POCKET/app/build}"
readonly PLAYWRIGHT=1.56.1
ENGINE="${ENGINE:-${RUNNER_TEMP:-/tmp}/pocketide-engine}"
OUT="${ENGINE_OUT:-$ENGINE/out}"
export ENGINE

failures=0
say() { printf '%s\n' "$*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; failures=$((failures + 1)); }
die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
summary() { [ -n "${GITHUB_STEP_SUMMARY:-}" ] && printf '%s\n' "$*" >> "$GITHUB_STEP_SUMMARY"; return 0; }
need() { command -v "$1" > /dev/null || die "$1 is not installed; the engine test needs it"; }
guest() { "$TOOLS/engine/guest.sh" "$@"; }

# One pinned download from pins.json: prints "url sha256 bytes".
pin() {
  python3 - "$INPUTS/engine/pins.json" "$1" <<'PY'
import json, sys
pin = json.load(open(sys.argv[1], encoding="utf-8"))["pins"][sys.argv[2]]
print(pin["url"], pin["sha256"], pin["bytes"])
PY
}

# Saves url to file once, and keeps it only when it has the pinned SHA-256 (and size, when given).
fetch() {
  local url="$1" sha256="$2" bytes="$3" file="$4"
  if [ ! -f "$file" ] || ! echo "$sha256  $file" | sha256sum --check --status; then
    say "Downloading ${url##*/}…"
    curl --fail --location --silent --show-error --retry 5 --retry-all-errors --output "$file.part" "$url"
    mv "$file.part" "$file"
  fi
  echo "$sha256  $file" | sha256sum --check --status || { rm -f "$file"; die "${url##*/} does not match its pinned SHA-256"; }
  [ "$bytes" = "-" ] || [ "$(stat -c %s "$file")" = "$bytes" ] || die "${url##*/} is not the pinned $bytes bytes"
}

lint() {
  local file
  say "== Scripts"
  while IFS= read -r -d '' file; do
    if ! bash -n "$file"; then
      fail "${file#"$POCKET"/} has a syntax error"
    elif ! shellcheck --severity=style "$file"; then
      fail "${file#"$POCKET"/} has ShellCheck findings (above)"
    else
      say "ok   ${file#"$POCKET"/}"
    fi
  done < <(find "$ASSETS/linux" "$TOOLS" -type f \( -name '*.sh' -o -path '*/linux/bin/*' \) -print0 | sort -z)
  for file in "$ASSETS/linux/forwarder.js" "$ASSETS/companion/extension.js"; do
    node --check "$file" || fail "${file#"$POCKET"/} does not parse"
  done
  say "== Python tools' tests"
  python3 -m unittest discover -s "$TOOLS/tests" -t "$TOOLS" || fail "the Python tools' tests failed (above)"
}

# Termux's PRoot, from the release sources.json marks for building, for this machine.
build_proot() {
  local fields url sha256 version dir
  say "== PRoot for $(uname -m)"
  fields=$(python3 - "$TOOLS/proot/sources.json" <<'PY'
import json, sys
release = [r for r in json.load(open(sys.argv[1], encoding="utf-8"))["releases"] if r.get("build")][0]
print(release["proot"]["url"], release["proot"]["sha256"], release["version"])
PY
)
  read -r url sha256 version <<< "$fields"
  rm -rf "${ENGINE:?}/proot-src"
  mkdir -p "$ENGINE/dl" "$ENGINE/proot-src"
  if [ -n "${ENGINE_PROOT_SRC:-}" ]; then
    # A checkout of that same tag, where GitHub's archive cannot be downloaded.
    cp -r "$ENGINE_PROOT_SRC" "$ENGINE/proot-src/proot-$version"
  else
    fetch "$url" "$sha256" - "$ENGINE/dl/proot-$version.zip"
    unzip -q "$ENGINE/dl/proot-$version.zip" -d "$ENGINE/proot-src"
  fi
  dir=$(find "$ENGINE/proot-src" -mindepth 1 -maxdepth 1 -type d -name 'proot-*' | head -n 1)
  make -s -C "$dir/src" proot loader/loader > /dev/null
  PROOT="$dir/src/proot"
  PROOT_LOADER="$dir/src/loader/loader"
  export PROOT PROOT_LOADER
  "$PROOT" --version > /dev/null || die "PRoot $version did not build"
  say "ok   PRoot $version"
}

# The pinned Ubuntu base, unpacked the way the app unpacks it (no owners), with the files the
# app writes into it (linux/GuestConfig.kt) and its scripts (linux/GuestScripts.kt).
make_base() {
  local url sha256 bytes file
  say "== Ubuntu base ($ARCH)"
  read -r url sha256 bytes <<< "$(pin "ubuntuBase-$ARCH")"
  file="$ENGINE/dl/${url##*/}"
  fetch "$url" "$sha256" "$bytes" "$file"
  rm -rf "${ENGINE:?}/rootfs" "${ENGINE:?}/home"
  mkdir -p "$ENGINE/rootfs" "$ENGINE/home/projects/demo"
  tar -xzf "$file" -C "$ENGINE/rootfs" --no-same-owner
  printf 'pocketide\n' > "$ENGINE/rootfs/etc/hostname"
  printf '127.0.0.1\tlocalhost pocketide\n::1\tlocalhost ip6-localhost ip6-loopback\n' > "$ENGINE/rootfs/etc/hosts"
  printf 'precedence ::ffff:0:0/96  100\n' > "$ENGINE/rootfs/etc/gai.conf"
  rm -f "$ENGINE/rootfs/etc/resolv.conf"
  grep '^nameserver' /etc/resolv.conf > "$ENGINE/rootfs/etc/resolv.conf"
  if [ -n "${ENGINE_EXTRA_CA:-}" ]; then
    mkdir -p "$ENGINE/rootfs/usr/local/share/ca-certificates"
    cp "$ENGINE_EXTRA_CA" "$ENGINE/rootfs/usr/local/share/ca-certificates/engine-proxy.crt"
  fi
  (cd "$ASSETS/linux" && find . -type f) | while read -r name; do
    name="${name#./}"
    install -D -m 644 "$ASSETS/linux/$name" "$ENGINE/rootfs/opt/pocketide/$name"
    case "$name" in bin/* | *.sh | *.pl) chmod 755 "$ENGINE/rootfs/opt/pocketide/$name" ;; esac
  done
  echo 'hello from PocketIDE' > "$ENGINE/home/projects/demo/README.txt"
}

set_up() {
  local first second last tool
  say "== bootstrap.sh"
  first=$(guest /bin/bash /opt/pocketide/bootstrap.sh < /dev/null) || { printf '%s\n' "$first"; die "bootstrap.sh failed"; }
  printf '%s\n' "$first" | grep -E '^(pocketide-|Ubuntu|That|Could)' || true
  grep -qx 'pocketide-progress 100' <<< "$first" || fail "bootstrap.sh never reported pocketide-progress 100"
  if grep -q 'Trying again' <<< "$first"; then fail "bootstrap.sh needed a second try (output above)"; fi
  for tool in git python3 node npm curl rg jq gh sqlite3 less nano unzip; do
    guest /bin/bash -c "command -v $tool" > /dev/null || fail "$tool is missing after the set-up"
  done
  guest /bin/bash -c 'npm --version 2>&1' | grep -qi 'does not support' && fail "npm does not support the installed Node.js"
  second=$(guest /bin/bash /opt/pocketide/bootstrap.sh < /dev/null)
  grep -q 'The tools are already installed.' <<< "$second" || fail "a second run of bootstrap.sh installed the tools again"
  say "== update.sh"
  last=$(guest /bin/bash /opt/pocketide/update.sh < /dev/null | tail -n 1)
  say "$last"
  grep -Eqx 'pocketide-fixed [0-9]+' <<< "$last" || fail "update.sh did not end with 'pocketide-fixed <count>'"
}

install_ide() {
  local url sha256 bytes file vscode vsix
  say "== code-server"
  read -r url sha256 bytes <<< "$(pin "codeServer-$ARCH")"
  file="$ENGINE/dl/${url##*/}"
  fetch "$url" "$sha256" "$bytes" "$file"
  mkdir -p "$ENGINE/rootfs/opt/code-server"
  tar -xzf "$file" -C "$ENGINE/rootfs/opt/code-server" --strip-components=1 --no-same-owner
  guest /opt/code-server/bin/code-server --version < /dev/null
  vscode=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' \
    "$ENGINE/rootfs/opt/code-server/lib/vscode/product.json")
  # What the app does before every start (ide/Ide.kt prepare): sign-in sites trusted, its settings, the agents list.
  python3 - "$ENGINE/rootfs/opt/code-server/lib/vscode/product.json" "$INPUTS/ide-files/trusted-sites.txt" <<'PY'
import json, sys
product = json.load(open(sys.argv[1], encoding="utf-8"))
sites = [line.strip() for line in open(sys.argv[2], encoding="utf-8") if line.strip()]
current = product.get("linkProtectionTrustedDomains", [])
product["linkProtectionTrustedDomains"] = current + [site for site in sites if site not in current]
json.dump(product, open(sys.argv[1], "w", encoding="utf-8"), indent=2)
PY
  mkdir -p "$ENGINE/home/.local/share/code-server/Machine" "$ENGINE/home/.pocketide/requests" "$ENGINE/home/.cache/agents"
  cp "$INPUTS/ide-files/machine-settings.json" "$ENGINE/home/.local/share/code-server/Machine/settings.json"
  cp "$INPUTS/ide-files/agents.json" "$ENGINE/home/.pocketide/agents.json"
  cp "$INPUTS/ide-files/companion.vsix" "$ENGINE/home/.cache/agents/companion.vsix"
  say "== The official agents (Open VSX, $OPENVSX, VS Code $vscode)"
  python3 "$TOOLS/engine/agents.py" "$INPUTS/ide-files/agents.json" --target "$OPENVSX" --vscode "$vscode" \
    --out "$ENGINE/home/.cache/agents" > /dev/null || die "the agents could not be downloaded"
  for vsix in "$ENGINE"/home/.cache/agents/*.vsix; do
    guest /opt/code-server/bin/code-server --user-data-dir /root/.local/share/code-server \
      --extensions-dir /root/.local/share/code-server/extensions \
      --install-extension "/root/.cache/agents/${vsix##*/}" --force < /dev/null | tail -n 1 \
      || fail "code-server could not install ${vsix##*/}"
  done
  say "== The agents' command-line tools"
  guest /opt/pocketide/bin/claude --version < /dev/null || fail "claude (Claude Code's own) does not answer"
  guest /opt/pocketide/bin/codex --version < /dev/null || fail "codex (Codex's own) does not answer"
}

screens() {
  local node_dir="$ENGINE/node"
  say "== The agent screens"
  mkdir -p "$node_dir" "$OUT"
  if [ ! -d "$node_dir/node_modules/playwright-core" ]; then
    npm install --silent --no-save --prefix "$node_dir" "playwright-core@$PLAYWRIGHT" > /dev/null
  fi
  if [ -z "${CHROMIUM:-}" ]; then
    "$node_dir/node_modules/.bin/playwright-core" install --with-deps chromium > /dev/null
  fi
  cp "$TOOLS/engine/screens.mjs" "$node_dir/screens.mjs"
  GUEST="$TOOLS/engine/guest.sh" node "$node_dir/screens.mjs" "$OUT" || fail "the agent screens did not show as the app shows them (above)"
  # Antigravity's extension fetched Google's agy while its screen was open.
  guest /opt/pocketide/bin/agy --version < /dev/null || fail "agy (Antigravity's own) does not answer"
}

main() {
  need python3
  need node
  need curl
  need make
  need shellcheck
  case "$(uname -m)" in
    aarch64 | arm64) ARCH=arm64 OPENVSX=linux-arm64 ;;
    x86_64) ARCH=amd64 OPENVSX=linux-x64 ;;
    *) die "no pins for $(uname -m)" ;;
  esac
  if [ ! -f "$INPUTS/engine/pins.json" ] || [ ! -f "$INPUTS/ide-files/companion.vsix" ]; then
    die "run the app's unit tests first: they write app/build/engine/pins.json and app/build/ide-files/"
  fi
  mkdir -p "$ENGINE" "$OUT"
  lint
  build_proot
  make_base
  set_up
  install_ide
  screens
  if [ "$failures" -gt 0 ]; then
    summary "### Engine test: $failures failure(s) on $(uname -m)"
    say "$failures failure(s)."
    exit 1
  fi
  summary "### Engine test passed on $(uname -m): Ubuntu set up, updated, and all three agents shown"
  say "Engine test passed."
}

main "$@"
