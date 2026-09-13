#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-$PROJECT_DIR/../.tooling/android-sdk}"
ANDROID_JAR="$SDK_ROOT/platforms/android-35/android.jar"
OUT="$(mktemp -d "${TMPDIR:-/tmp}/pocketagent-remote-media.XXXXXX")"
trap 'rm -rf "$OUT"' EXIT
if command -v javac >/dev/null 2>&1; then JAVAC=(javac); else JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main); fi
"${JAVAC[@]}" -encoding UTF-8 -source 8 -target 8 -cp "$PROJECT_DIR/build/classes:$ANDROID_JAR" -d "$OUT" \
 "$PROJECT_DIR/app/src/com/pocketagent/mobile/WorkspaceTools.java" \
 "$PROJECT_DIR/app/src/com/pocketagent/mobile/WorkspaceMediaPaths.java" \
 "$PROJECT_DIR/app/src/com/pocketagent/mobile/RemoteMediaPolicy.java" \
 "$PROJECT_DIR/app/src/com/pocketagent/mobile/RemoteMediaFetch.java" \
 "$PROJECT_DIR/tests/RemoteMediaTest.java"
java -cp "$OUT:$PROJECT_DIR/build/classes:$ANDROID_JAR" com.pocketagent.mobile.RemoteMediaTest
