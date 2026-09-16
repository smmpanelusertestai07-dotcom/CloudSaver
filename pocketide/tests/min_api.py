#!/usr/bin/env python3
"""Every use of an Android API newer than minSdk is known, and guarded.

An app that closes the moment it opens is what a call to a method the phone does not have
looks like: NoSuchMethodError, thrown before anything is drawn, on every phone older than the
API that added it. The build compiles against the newest SDK, so the compiler cannot notice.
This gate compiles every source against the minSdk platform instead, reads off what is
missing, and allows exactly the symbols listed in min_api_allowed.txt -- each with the guard
that protects it, checked in the source:

    File.java|symbol|SDK_INT >= 30     the guard text appears in the 40 lines before the use
    File.java|symbol|anywhere:TEXT     the guard text appears somewhere in the file: for a
                                       class whose every entry point checks the version and
                                       whose other uses are in methods only those reach
    File.java|symbol|inlined           a static final constant the compiler folds into the caller
    File.java|symbol|caught            the use is inside a try that catches Throwable

An import of a missing class is not a use: it compiles to nothing and the phone never sees
it. Anything else missing at minSdk and not in that list fails the gate; so does a listed
guard that is no longer in the source. Usage: min_api.py <app dir> <sdk root>."""
import os
import re
import subprocess
import sys
import tempfile

app = sys.argv[1]
sdk = sys.argv[2]
src = os.path.join(app, "app", "src")
gen = os.path.join(app, "build", "gen")
build_sh = open(os.path.join(app, "build.sh")).read()
min_sdk = re.search(r"--min-sdk-version (\d+)", build_sh).group(1)
jar = os.path.join(sdk, "platforms", "android-" + min_sdk, "android.jar")
stubs = os.path.join(sdk, "build-tools", "35.0.0", "core-lambda-stubs.jar")
problems = []

if not os.path.isfile(jar):
    print("  the Android %s platform is not installed at %s; the gate cannot run" % (min_sdk, jar),
          file=sys.stderr)
    sys.exit(1)
if not os.path.isdir(gen):
    print("  build/gen is missing; run build.sh first so R.java exists", file=sys.stderr)
    sys.exit(1)

sources = []
for base in (src, gen):
    for folder, _, names in os.walk(base):
        sources += [os.path.join(folder, n) for n in names if n.endswith(".java")]
with tempfile.TemporaryDirectory() as out:
    javac = subprocess.run(
        ["javac", "-encoding", "UTF-8", "-source", "8", "-target", "8", "-nowarn",
         "-bootclasspath", jar + os.pathsep + stubs, "-d", out] + sorted(sources),
        capture_output=True, text=True)
    output = javac.stdout + javac.stderr

# One record per error: file, line, symbol.
found = set()
lines = output.split("\n")
for i, line in enumerate(lines):
    hit = re.match(r"(.*?/([A-Za-z]+\.java)):(\d+): error: (.*)", line)
    if not hit:
        continue
    name, at, message = hit.group(2), int(hit.group(3)), hit.group(4)
    symbol = ""
    for follow in lines[i + 1:i + 4]:
        sym = re.match(r"\s+symbol:\s+(?:class|method|variable|package)\s+([A-Za-z_][A-Za-z0-9_]*)", follow)
        if sym:
            symbol = sym.group(1)
            break
    if not symbol:
        pkg = re.match(r"package ([A-Za-z0-9_.]+) does not exist", message)
        symbol = pkg.group(1) if pkg else message[:40]
    source_line = open(os.path.join(app, "app", "src", "com", "pocketide", name)).read().split("\n")
    if at <= len(source_line) and source_line[at - 1].strip().startswith("import "):
        continue
    found.add((name, symbol, at))

allowed = {}
listing = os.path.join(app, "tests", "min_api_allowed.txt")
for raw in open(listing):
    raw = raw.strip()
    if not raw or raw.startswith("#"):
        continue
    name, symbol, guard = [part.strip() for part in raw.split("|", 2)]
    allowed[(name, symbol)] = guard

by_file = {}
for name, symbol, at in found:
    by_file.setdefault(name, {}).setdefault(symbol, []).append(at)

for name, symbols in sorted(by_file.items()):
    text = open(os.path.join(app, "app", "src", "com", "pocketide", name)).read().split("\n")
    for symbol, ats in sorted(symbols.items()):
        guard = allowed.get((name, symbol))
        if guard is None:
            problems.append("%s uses %s, which Android %s does not have, and no guard is "
                            "listed for it in tests/min_api_allowed.txt (lines %s)"
                            % (name, symbol, min_sdk, ", ".join(str(a) for a in sorted(ats))))
            continue
        if guard == "inlined":
            continue
        for at in ats:
            before = "\n".join(text[max(0, at - 40):at])
            if guard == "caught":
                ok = "catch (Throwable" in "\n".join(text[at - 1:at + 12]) and "try {" in before
            elif guard.startswith("anywhere:"):
                ok = guard[len("anywhere:"):] in "\n".join(text)
            else:
                ok = guard in before
            if not ok:
                problems.append("%s:%d uses %s without the listed guard (%s) close enough to "
                                "protect it" % (name, at, symbol, guard))

for (name, symbol), guard in sorted(allowed.items()):
    if symbol not in by_file.get(name, {}):
        problems.append("tests/min_api_allowed.txt lists %s in %s, which the minSdk compile "
                        "no longer reports; remove the stale line" % (symbol, name))

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
