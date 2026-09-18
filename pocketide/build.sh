#!/usr/bin/env bash
# Builds PocketIDE: aapt2, javac, d8, apksigner. No Gradle, no dependency resolution, no
# network. The whole build is four tools the Android SDK already ships, which is why it takes
# seconds and why it will still work in five years.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_DIR/../.tooling/android-sdk}"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
BUILD_DIR="$PROJECT_DIR/build"
APP_BASENAME="PocketIDE"

# Duplicated in app/src/com/pocketide/BuildFacts.java on purpose -- the manifest can only take
# them from here, and the screens can only take them from there. tests/version_agreement.py
# fails the build if the two ever disagree, because an app that reports one version to the
# package manager and shows another in Settings produces bug reports nobody can act on.
VERSION_NAME="2.6.0"
VERSION_CODE="260"

if [[ ! -f "$ANDROID_JAR" || ! -x "$BUILD_TOOLS/aapt2" ]]; then
  echo "Android SDK platform 35 and build-tools 35.0.0 are required." >&2
  exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/gen" "$BUILD_DIR/assets"

# Package only source assets, through a staging copy, so a build never modifies the source tree.
cp -a "$PROJECT_DIR/app/assets/." "$BUILD_DIR/assets/"

# The companion extension, packaged from its two source files under app/assets/companion into
# the .vsix the editor installs (a zip with the manifest a VS Code build expects). The stamp
# beside it is what pocketide-editor.sh compares, so a new build re-installs it exactly once.
package_companion() {
  local src="$PROJECT_DIR/app/assets/companion" out="$BUILD_DIR/assets/pocketide-companion.vsix"
  python3 - "$src" "$out" "$VERSION_NAME" <<'PY'
import json, sys, zipfile, os
src, out, version = sys.argv[1:4]
manifest = json.load(open(os.path.join(src, "package.json")))
manifest["version"] = version
vsixmanifest = f"""<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011" xmlns:d="http://schemas.microsoft.com/developer/vsx-schema-design/2011">
  <Metadata>
    <Identity Language="en-US" Id="{manifest['name']}" Version="{version}" Publisher="{manifest['publisher']}"/>
    <DisplayName>{manifest['displayName']}</DisplayName>
    <Description xml:space="preserve">{manifest['description']}</Description>
    <Categories>Other</Categories>
    <Properties>
      <Property Id="Microsoft.VisualStudio.Code.Engine" Value="{manifest['engines']['vscode']}"/>
      <Property Id="Microsoft.VisualStudio.Code.ExtensionKind" Value="workspace"/>
    </Properties>
  </Metadata>
  <Installation><InstallationTarget Id="Microsoft.VisualStudio.Code"/></Installation>
  <Dependencies/>
  <Assets><Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true"/></Assets>
</PackageManifest>
"""
types = """<?xml version="1.0" encoding="utf-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="json" ContentType="application/json"/>
  <Default Extension="js" ContentType="application/javascript"/>
  <Default Extension="vsixmanifest" ContentType="text/xml"/>
</Types>
"""
stamp = (2020, 1, 1, 0, 0, 0)  # a fixed time, so the same sources give the same bytes
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for name, data in (("[Content_Types].xml", types), ("extension.vsixmanifest", vsixmanifest),
                       ("extension/package.json", json.dumps(manifest, indent=2) + "\n"),
                       ("extension/extension.js", open(os.path.join(src, "extension.js")).read())):
        info = zipfile.ZipInfo(name, date_time=stamp)
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, data)
open(out[:-len(".vsix")] + ".stamp", "w").write(version + "\n")
PY
  rm -rf "$BUILD_DIR/assets/companion"
}
package_companion
find "$BUILD_DIR/assets" -type d -name '__pycache__' -prune -exec rm -rf -- {} + 2>/dev/null || true
find "$BUILD_DIR/assets" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete 2>/dev/null || true

# ---------------------------------------------------------------------------- the phone's adb
#
# "Test on this phone" needs an adb client that holds the phone's paired key, and the one place
# that client must never run is inside the Linux the agent works in: a program that runs from a
# rootfs the agent can write to can be replaced by the agent, and a replaced adb is the whole key
# handed over. So adb gets a root of its own -- assembled here, at build time, from Ubuntu's own
# arm64 packages (Noble, release pocket, which never changes), each pinned by name and SHA-256 --
# and ships inside the APK as one zip. The app unpacks it into its private storage and runs adb
# there under a PRoot of its own (Workspace.startPrivate), with no shell, no profile and nothing
# the Linux rootfs can reach. Ubuntu's release pocket is what makes the pins permanent: a file
# in it is never replaced, so the same nineteen packages assemble byte for byte in five years.
#
# The list is adb's runtime closure from the packages' own Depends, read with readelf below
# rather than assumed: only what adb actually loads is copied. gconv, docs and manual pages are
# not, which is what keeps the zip near 5 MB.
ADB_ROOT_MIRROR="${POCKETIDE_UBUNTU_PORTS:-https://ports.ubuntu.com/ubuntu-ports}"
ADB_ROOT_DEBS=(
  "adb|pool/universe/a/android-platform-tools/adb_34.0.4-1build3_arm64.deb|17433097fd151c47a95de2f7b45c3b683cde0a14d2b1e21251744075e71a1bcd|271840"
  "android-libbase|pool/universe/a/android-platform-tools/android-libbase_34.0.4-1build3_arm64.deb|0d764f726449e35b4a861b056d661da6526ef8b7d3403fc8d11836e63a9ac6d6|96774"
  "android-libcutils|pool/universe/a/android-platform-tools/android-libcutils_34.0.4-1build3_arm64.deb|6888e9ed1722b63e1c8dd4656927da299b291ad83d141451f6e6394f9d7b0071|35818"
  "android-liblog|pool/universe/a/android-platform-tools/android-liblog_34.0.4-1build3_arm64.deb|81e9bcec0986b8111f63617fc226d3690ed90cca61af27b57750fd1ec65e994b|35688"
  "android-libziparchive|pool/universe/a/android-platform-tools/android-libziparchive_34.0.4-1build3_arm64.deb|c924e3e4fd363cb0889ce977d2f1716c63532b17bc5f55f8a78d20496b4014a6|40496"
  "android-libboringssl|pool/universe/a/android-platform-external-boringssl/android-libboringssl_14.0.0+r11-4build1_arm64.deb|ebf7531fe6347a2c802b8f861872d7a2d0322d3102ea7fadf06977f66ae2653a|657662"
  "libbrotli1|pool/main/b/brotli/libbrotli1_1.1.0-2build2_arm64.deb|cabf3462d908e72f2e594f19ae87c581c4614f099947e38fbd865bf8eae27014|339424"
  "liblz4-1|pool/main/l/lz4/liblz4-1_1.9.4-1build1_arm64.deb|ca49b1a29c5fc04533248d9bbe1b7fca75e38f6b9e808683a62b569541ef9d98|64062"
  "libprotobuf32t64|pool/main/p/protobuf/libprotobuf32t64_3.21.12-8.2build1_arm64.deb|c35f528c08499f75c2e721114657d12cdf08455ff6e1b49bc3c630865f2175e8|858706"
  "libusb-1.0-0|pool/main/libu/libusb-1.0/libusb-1.0-0_1.0.27-1_arm64.deb|c8c34ef4385b6be34fe94aabd4de06c7bbd5ae617e80e9306f412f4c979aa5e4|54082"
  "libudev1|pool/main/s/systemd/libudev1_255.4-1ubuntu8_arm64.deb|62567062c6f08702d6876bd5ad8993ff618478df9bd8613270032e9477b82e93|172824"
  "libcap2|pool/main/libc/libcap2/libcap2_2.66-5ubuntu2_arm64.deb|88c75467ee09a14981661782bf62f4a0029458922245d7f22225c2ca4441eeee|30200"
  "libzstd1|pool/main/libz/libzstd/libzstd1_1.5.5+dfsg2-2build1_arm64.deb|98568129024af1a71702d5a13f2e01c53afd5e2c3f71b2ee97fdd9a19cd97eda|271224"
  "zlib1g|pool/main/z/zlib/zlib1g_1.3.dfsg-3.1ubuntu2_arm64.deb|66aaebb68401a88b4f70a5f48e1a516b611fdcaae15208d8aee677c556d2cf01|61682"
  "libbsd0|pool/main/libb/libbsd/libbsd0_0.12.1-1build1_arm64.deb|6200ae28cdd976f9bf98571e4f7d49f9c632cfbc7eba241a3fed0f21ec8fe3ca|40926"
  "libmd0|pool/main/libm/libmd/libmd0_1.1.0-2build1_arm64.deb|9d957330c83693dfd870a56794317395b9519b385a69933828ff8e8e14829aa0|24590"
  "libc6|pool/main/g/glibc/libc6_2.39-0ubuntu8_arm64.deb|04d7cb73e608b41713b63ef915577f7f5d75e95b1b33174e82e05687fdb6cfaf|2774086"
  "libgcc-s1|pool/main/g/gcc-14/libgcc-s1_14-20240412-0ubuntu1_arm64.deb|bb1c262f2ac9aeb357d3344a06f22f1057306694f6200896775a03ecced08b13|61720"
  "libstdc++6|pool/main/g/gcc-14/libstdc++6_14-20240412-0ubuntu1_arm64.deb|cd9ad360c8eee25e1fd9869303f4782eeaa47723594e85631c3def7ec42c3b08|747982"
)
ADB_ROOT_VERSION="adb 34.0.4-1build3 · Ubuntu 24.04 arm64"

assemble_adb_root() {
  local cache="$PROJECT_DIR/../.tooling/adb-debs"
  local work="$BUILD_DIR/adb-root"
  local unpack="$work/unpack" root="$work/root"
  local entry name path sha size file got
  mkdir -p "$cache" "$unpack" "$root"
  for entry in "${ADB_ROOT_DEBS[@]}"; do
    IFS='|' read -r name path sha size <<<"$entry"
    file="$cache/$(basename "$path")"
    if [[ ! -f "$file" || "$(sha256sum "$file" | cut -d' ' -f1)" != "$sha" ]]; then
      echo "  fetching $name ($size bytes)"
      curl -fsSL --retry 4 --retry-delay 3 -o "$file.part" "$ADB_ROOT_MIRROR/$path"
      got="$(sha256sum "$file.part" | cut -d' ' -f1)"
      if [[ "$got" != "$sha" ]]; then
        echo "$name: SHA-256 $got does not match the pinned $sha. Refusing to build." >&2
        rm -f "$file.part"
        exit 1
      fi
      mv -f "$file.part" "$file"
    fi
    dpkg-deb -x "$file" "$unpack"
  done

  # Only what adb loads: its NEEDED entries, followed transitively through the unpacked
  # libraries. Every path is dereferenced (cp -L) so the zip holds plain files and Java's
  # ZipInputStream, which knows nothing of symlinks, can unpack it; the one link the loader
  # needs (/lib -> usr/lib, for the interpreter path baked into the binary) is listed in
  # links.txt and made on the phone.
  local libdir="$unpack/usr/lib/aarch64-linux-gnu"
  mkdir -p "$root/usr/bin" "$root/usr/lib/aarch64-linux-gnu/android" "$root/etc" \
    "$root/root" "$root/tmp" "$root/stage" "$root/dev" "$root/proc" "$root/sys"
  cp -L "$unpack/usr/bin/adb" "$root/usr/bin/adb"
  cp -L "$unpack/usr/lib/ld-linux-aarch64.so.1" "$root/usr/lib/ld-linux-aarch64.so.1"
  local -a queue=("$root/usr/bin/adb")
  local -A seen=()
  local so found
  while ((${#queue[@]})); do
    file="${queue[0]}"; queue=("${queue[@]:1}")
    for so in $(readelf -d "$file" 2>/dev/null | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p'); do
      [[ -n "${seen[$so]:-}" ]] && continue
      seen[$so]=1
      if [[ -e "$libdir/android/$so" ]]; then found="$libdir/android/$so"; \
        cp -L "$found" "$root/usr/lib/aarch64-linux-gnu/android/$so"
      elif [[ -e "$libdir/$so" ]]; then found="$libdir/$so"; \
        cp -L "$found" "$root/usr/lib/aarch64-linux-gnu/$so"
      elif [[ "$so" == ld-linux-aarch64.so.1 ]]; then continue
      else echo "adb needs $so and no pinned package provides it." >&2; exit 1
      fi
      queue+=("$found")
    done
  done
  # glibc opens these by name when it resolves a host, numeric or not on some paths.
  for so in libnss_files.so.2 libnss_dns.so.2 libresolv.so.2; do
    [[ -e "$root/usr/lib/aarch64-linux-gnu/$so" ]] || cp -L "$libdir/$so" "$root/usr/lib/aarch64-linux-gnu/$so"
  done
  printf 'lib usr/lib\n' > "$root/links.txt"
  printf '127.0.0.1 localhost\n' > "$root/etc/hosts"
  printf 'hosts: files dns\n' > "$root/etc/nsswitch.conf"
  chmod 0755 "$root/usr/bin/adb" "$root/usr/lib/ld-linux-aarch64.so.1"
  # One mtime everywhere, so the same input assembles the same zip and the stamp below only
  # changes when the packages do -- an app update then re-unpacks nothing it already has.
  find "$root" -exec touch -h -d '2024-04-25 00:00:00 UTC' {} +
  rm -f "$BUILD_DIR/assets/adb-root.zip"
  (cd "$root" && find . -type f -o -type d | sort | TZ=UTC zip -q -X -D "$BUILD_DIR/assets/adb-root.zip" -@)
  printf '%s\n%s\n' "$ADB_ROOT_VERSION" "$(sha256sum "$BUILD_DIR/assets/adb-root.zip" | cut -d' ' -f1)" \
    > "$BUILD_DIR/assets/adb-root.stamp"
  echo "  adb root: $(du -h "$BUILD_DIR/assets/adb-root.zip" | cut -f1) ($(unzip -l "$BUILD_DIR/assets/adb-root.zip" | tail -1 | awk '{print $2}') files)"
}

echo "Assembling the phone's adb…"
for tool in curl dpkg-deb readelf zip unzip; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required to assemble adb's root." >&2; exit 1; }
done
assemble_adb_root

echo "Compiling resources…"
"$BUILD_TOOLS/aapt2" compile --dir "$PROJECT_DIR/app/res" -o "$BUILD_DIR/compiled.zip"
"$BUILD_TOOLS/aapt2" link \
  -o "$BUILD_DIR/$APP_BASENAME-unsigned.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJECT_DIR/app/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 35 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  -A "$BUILD_DIR/assets" \
  "$BUILD_DIR/compiled.zip"

# PRoot and its loader ship as native APK libraries, stored uncompressed so the package manager
# can extract them, which is what keeps the container bootstrap reliable on a modern target.
(cd "$PROJECT_DIR/app" && zip -q -0 "$BUILD_DIR/$APP_BASENAME-unsigned.apk" lib/arm64-v8a/*.so)

echo "Compiling Java…"
mapfile -t JAVA_SOURCES < <(find "$PROJECT_DIR/app/src" "$BUILD_DIR/gen" -name '*.java' -type f | sort)
if command -v javac >/dev/null 2>&1; then
  JAVAC=(javac)
else
  JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
fi
# -Xlint:all with no -Werror: warnings are read, not ignored, but a deprecation in the platform
# SDK must not be able to stop a release.
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -Xlint:all -nowarn \
  -bootclasspath "$ANDROID_JAR:$BUILD_TOOLS/core-lambda-stubs.jar" \
  -d "$BUILD_DIR/classes" \
  "${JAVA_SOURCES[@]}"

echo "Dexing…"
mapfile -t CLASS_FILES < <(find "$BUILD_DIR/classes" -name '*.class' -type f | sort)
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 29 --output "$BUILD_DIR/dex" "${CLASS_FILES[@]}"
zip -q -j "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/dex/classes.dex"
# -P 16, not -p. They are different flags and the difference is a crash on a modern phone:
# lowercase -p aligns uncompressed .so files to 4 KB, which is all Android needed until 15.
# Android 15 introduced devices with 16 KB memory pages, and on those the loader maps a
# library straight out of the APK -- so a library sitting at an offset that is not a multiple
# of 16384 cannot be mapped and the app dies at startup with no useful message.
#
# The four libraries here are already built with 16 KB ELF segment alignment (readelf shows
# LOAD align 0x4000 on each), and with -p 4 their offsets happened to land on 16 KB boundaries
# anyway. Happened to. Add a fifth library, or change the order of anything before them, and
# that luck ends. -P 16 makes it a property of the build instead.
"$BUILD_TOOLS/zipalign" -f -P 16 4 \
  "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/$APP_BASENAME-aligned.apk"

# Proved, not assumed: the same tool re-reads the file and is asked whether every uncompressed
# library actually sits on a 16 KB boundary. A build that cannot answer yes does not ship.
if ! "$BUILD_TOOLS/zipalign" -c -P 16 4 "$BUILD_DIR/$APP_BASENAME-aligned.apk" >/dev/null; then
  echo "Native libraries are not 16 KB aligned; this APK would not start on an Android 15" >&2
  echo "device with 16 KB pages. Refusing to sign it." >&2
  exit 1
fi

# ---------------------------------------------------------------------------- signing
#
# Android refuses an update signed with a different key than the one already installed, and the
# only way out of that is uninstalling -- which on this app means deleting the whole workspace.
# So the key has to be stable across releases.
#
# There is exactly one stable key, and it is the one supplied through the environment. No key is
# committed to this repository, and none should be: this repository is public, and a signing key
# anyone can read is a signing key anyone can use to build an "update" that installs straight
# over the owner's app.
#
# So the key has to be held as a GitHub secret. Create it once, keep it, and set the four
# secrets named below; every build after that -- in CI or on a laptop -- signs identically and
# every APK installs over the last one.
#
#   keytool -genkeypair -v -keystore pocketide.jks -storetype JKS \
#     -alias pocketide -keyalg RSA -keysize 4096 -validity 10950 \
#     -dname "CN=PocketIDE, OU=PocketIDE, O=PocketIDE, C=IN"
#   base64 -w0 pocketide.jks        # -> POCKETIDE_KEYSTORE_B64
#
# Without those secrets this script generates a throwaway key so a first build works at all.
# That APK installs fine on a phone with nothing installed, and will not install over an APK
# signed by any other throwaway. The warning below says so at build time rather than leaving it
# to be discovered as "App not installed" on a phone.
KEYSTORE="${POCKETIDE_KEYSTORE:-$PROJECT_DIR/.signing/pocketide.jks}"
STORE_PASS="${POCKETIDE_STORE_PASS:-}"

# No POCKETIDE_STORE_PASS means no real key either, so what gets signed is the throwaway
# keystore under .signing/, which git ignores. Its password is minted here, per machine, rather
# than written into this file: a default password in a public repository is a password everyone
# has, and it would be the password on the only key a first build produces. It is kept beside
# the keystore it locks -- inside .signing/, never committed -- because the next build has to
# open the same keystore, and a keystore whose password nothing records is a keystore that can
# only be thrown away.
SIGNING_DIR="$PROJECT_DIR/.signing"
PASS_FILE="$SIGNING_DIR/pocketide.pass"
if [[ -z "$STORE_PASS" ]]; then
  if [[ -s "$PASS_FILE" ]]; then
    STORE_PASS="$(cat "$PASS_FILE")"
  else
    STORE_PASS="$(openssl rand -hex 16 2>/dev/null \
      || head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    mkdir -p "$SIGNING_DIR"
    ( umask 077; printf '%s\n' "$STORE_PASS" >"$PASS_FILE" )
    # Any keystore left here by an older build was locked with a password nothing records any
    # more, so it is replaced rather than guessed at. Only ever the throwaway one: a keystore
    # supplied through POCKETIDE_KEYSTORE is never touched.
    if [[ -z "${POCKETIDE_KEYSTORE:-}" ]]; then rm -f "$KEYSTORE"; fi
  fi
fi
KEY_PASS="${POCKETIDE_KEY_PASS:-$STORE_PASS}"
KEY_ALIAS="${POCKETIDE_KEY_ALIAS:-pocketide}"

THROWAWAY_KEY=0
if [[ ! -f "$KEYSTORE" ]]; then
  THROWAWAY_KEY=1
  echo "No signing key was supplied; generating a throwaway one."
  echo "  This APK will NOT install over an APK signed by a different key."
  echo "  Set POCKETIDE_KEYSTORE_B64, POCKETIDE_STORE_PASS, POCKETIDE_KEY_PASS and"
  echo "  POCKETIDE_KEY_ALIAS as repository secrets to sign every build the same way."
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" -storetype JKS \
    -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10950 \
    -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
    -dname "CN=PocketIDE, OU=PocketIDE, O=PocketIDE, C=IN" >/dev/null 2>&1
fi

echo "Signing…"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$STORE_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --out "$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME-release.apk" \
  "$BUILD_DIR/$APP_BASENAME-aligned.apk"

"$BUILD_TOOLS/apksigner" verify --verbose \
  "$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME-release.apk" | head -5

rm -f "$BUILD_DIR/$APP_BASENAME-unsigned.apk" "$BUILD_DIR/$APP_BASENAME-aligned.apk"

APK="$BUILD_DIR/$APP_BASENAME-v$VERSION_NAME-release.apk"
echo
echo "Built: $APK"
echo "Size:  $(du -h "$APK" | cut -f1)"

if [[ "$THROWAWAY_KEY" == "1" ]]; then
  echo
  echo "Signed with a throwaway key. Installs on a phone that has no PocketIDE yet;"
  echo "will not install over a PocketIDE signed by a different key. See the note above"
  echo "the signing block in this file for the four secrets that fix this for good."
fi
