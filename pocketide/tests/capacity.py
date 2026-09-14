#!/usr/bin/env python3
"""The build-memory ladder, in the two places it has to be identical.

pocketide-tools.sh WRITES the numbers into Gradle's own settings on the phone. Capacity.java
TELLS THE OWNER what was written. They are two copies of one table, in two languages, and the
failure mode when they drift is silent and confidence-destroying: a screen that says "2048 MB
build heap" beside a build that actually got 1024 and died.

There is no way to share one table between a bash script and a Java class without inventing a
build step to generate one from the other, which for five rows is worse than this test.

The check is the table, not the wording: every threshold, every heap and every worker count is
parsed out of both files and compared row by row.
"""
import re
import sys

app = sys.argv[1]
script = open(app + "/app/assets/pocketide-tools.sh").read()

# Comments stripped, and that matters more than it looks: Capacity.java's own header explains at
# length why getMemoryClass() is the wrong number to quote, and a naive search for the word finds
# the explanation and fails the file for containing its own reasoning.
java = open(app + "/app/src/com/pocketide/Capacity.java").read()
java = re.sub(r'/\*.*?\*/', '', java, flags=re.S)
java = re.sub(r'//.*$', '', java, flags=re.M)

problems = []

# --- the shell ladder ------------------------------------------------------------------------
# elif [ "$total_kb" -ge 7500000  ]; then heap=2048; workers=3; kotlin_heap=1024
shell_rows = []
for match in re.finditer(
        r'\[\s*"\$total_kb"\s*-ge\s*(\d+)\s*\]\s*;\s*then\s+heap=(\d+);\s*workers=(\d+)',
        script):
    shell_rows.append((int(match.group(1)), int(match.group(2)), int(match.group(3))))

shell_default = re.search(r'else\s+heap=(\d+);\s*workers=(\d+)', script)
if not shell_rows or not shell_default:
    problems.append("the build-memory ladder cannot be read out of pocketide-tools.sh")

# --- the Java ladder -------------------------------------------------------------------------
# if (totalRam >= 7_500_000L * 1024) return 2048;
heap_body = re.search(r'static int buildHeapMb\(long totalRam\)\s*\{(.*?)\n    \}', java, re.S)
worker_body = re.search(r'static int workers\(long totalRam, int cores\)\s*\{(.*?)\n    \}',
                        java, re.S)

java_heap = []
java_heap_default = None
if not heap_body:
    problems.append("Capacity.buildHeapMb cannot be read")
else:
    for match in re.finditer(r'totalRam >= ([\d_]+)L \* 1024\) return (\d+);', heap_body.group(1)):
        java_heap.append((int(match.group(1).replace("_", "")), int(match.group(2))))
    tail = re.search(r'return (\d+);\s*$', heap_body.group(1).strip())
    if tail:
        java_heap_default = int(tail.group(1))

java_workers = []
java_workers_default = None
if not worker_body:
    problems.append("Capacity.workers cannot be read")
else:
    for match in re.finditer(r'totalRam >= ([\d_]+)L \* 1024\) byMemory = (\d+);',
                             worker_body.group(1)):
        java_workers.append((int(match.group(1).replace("_", "")), int(match.group(2))))
    tail = re.search(r'else byMemory = (\d+);', worker_body.group(1))
    if tail:
        java_workers_default = int(tail.group(1))

# --- compare ---------------------------------------------------------------------------------
if shell_rows and java_heap:
    if [(kb, heap) for kb, heap, _ in shell_rows] != java_heap:
        problems.append(
            "the build heap ladder differs. pocketide-tools.sh writes %s; Capacity.java tells "
            "the owner %s. A screen that promises more memory than the build gets is worse "
            "than saying nothing."
            % ([(kb, heap) for kb, heap, _ in shell_rows], java_heap))

if shell_default and java_heap_default is not None:
    if int(shell_default.group(1)) != java_heap_default:
        problems.append("the smallest-phone build heap is %s in the script and %s in Capacity"
                        % (shell_default.group(1), java_heap_default))

# The worker ladders are allowed to differ in SHAPE but not in outcome: Java also caps by core
# count, which the script does not need to because Gradle reads the same machine. So every
# threshold the script names must produce the same worker count in Java for a phone with
# enough cores -- which is what the comparison below does.
if shell_rows and java_workers:
    java_at = dict(java_workers)
    for kb, _, workers in shell_rows:
        if kb in java_at and java_at[kb] != workers:
            problems.append("at %d kB of RAM the script uses %d parallel worker(s) and "
                            "Capacity says %d" % (kb, workers, java_at[kb]))
        elif kb not in java_at and workers != max(w for _, w in java_workers):
            # A threshold the script has and Java does not is only safe when the script's
            # answer there is one Java also reaches.
            if workers not in [w for _, w in java_workers] + [java_workers_default]:
                problems.append("the script uses %d worker(s) at %d kB, a count Capacity never "
                                "reports for any phone" % (workers, kb))

# --- the honest claims the screen makes ------------------------------------------------------
#
# Two are load-bearing and were both nearly got wrong here. getMemoryClass() bounds the app's
# own Dalvik heap and has nothing to do with a PRoot child process; quoting it would have told
# every owner their computer had 256 MB. And the APK's own size is not what limits a build --
# saying otherwise sends someone off to buy a phone they did not need.
if "getMemoryClass" in java:
    problems.append("Capacity quotes ActivityManager.getMemoryClass, which bounds the app's own "
                    "Dalvik heap and not the PRoot processes the workspace actually runs in")
if "availableProcessors" not in java:
    problems.append("Capacity never reads the core count it reports")
if "NDK" not in java:
    problems.append("Capacity does not mention that C and C++ cannot be built, which is the "
                    "limit an owner is most likely to hit after spending an afternoon")
if "Android 17" not in java:
    problems.append("Capacity does not mention the Android 17 memory limiter, which is the one "
                    "hard ceiling on how large a build can get")

for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
