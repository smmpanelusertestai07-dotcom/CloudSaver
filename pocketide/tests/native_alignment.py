#!/usr/bin/env python3
"""The bundled native libraries, against what a 16 KB-page Android device actually does to them.

Android 15 introduced devices whose memory pages are 16 KB rather than 4 KB, and Android 16
made them the default on new hardware. Two separate things have to hold for a shared library
there, and only the first is widely known:

  1. Every LOAD segment must have p_align >= 16384. All four libraries here do (0x4000).

  2. The GNU_RELRO segment's end must also land on a 16 KB boundary. This one is not obvious
     and Google's own check_elf_alignment.sh reports it separately. libproot.so FAILS it: its
     RELRO runs 0x3bec0..0x3e000, and 0x3e000 is 0x2000 past a 16 KB boundary.

Whether that second failure is a crash depends on the layout, and this gate works out which
rather than guessing either way -- because both blunt answers are wrong. Refusing every
misaligned RELRO would block this release over something that provably cannot fire; ignoring it
would ship a segfault the first time PRoot is rebuilt with a different layout.

What bionic does, from linker_phdr.cpp, is:

    mprotect(page_start(relro.vaddr), page_end(relro.vaddr + relro.memsz) - page_start(...),
             PROT_READ)

With 16 KB pages that rounds libproot.so's protection out to 0x38000..0x40000. The only writable
segment inside that range is LOAD 0x3bec0..0x3e000 -- which is exactly the RELRO region, so
making it read-only is what RELRO is for. The next writable segment starts at 0x41550, whose
page start is 0x40000, one byte past where the protection ends. Nothing that must stay writable
is caught.

So the check is the real condition: does the rounded-up protection cover any writable memory
that RELRO itself does not already cover? If a future PRoot lands its segments differently, this
fails and says so.
"""
import re
import subprocess
import sys

PAGE = 0x4000
app = sys.argv[1]
lib_dir = app + "/app/lib/arm64-v8a"

problems = []
notes = []


def page_start(a): return a & ~(PAGE - 1)
def page_end(a): return (a + PAGE - 1) & ~(PAGE - 1)


def headers(path):
    out = subprocess.run(["readelf", "-lW", path], capture_output=True, text=True).stdout
    loads, relro = [], None
    for line in out.splitlines():
        parts = line.split()
        if len(parts) < 8:
            continue
        if parts[0] == "LOAD":
            vaddr, memsz = int(parts[2], 16), int(parts[5], 16)
            flags = "".join(parts[6:-1])
            align = int(parts[-1], 16)
            loads.append((vaddr, memsz, flags, align))
        elif parts[0] == "GNU_RELRO":
            relro = (int(parts[2], 16), int(parts[5], 16))
    return loads, relro


def judge(name, loads, relro):
    """Returns (problem, note): at most one of them is non-empty.

    Separated from the file reading so it can be exercised against layouts none of the shipped
    libraries happen to have -- which is the only way to know this gate would catch the case it
    exists for. See --self-test at the bottom.
    """
    for vaddr, memsz, flags, align in loads:
        if align < PAGE:
            return ("%s has a LOAD segment aligned to 0x%x; a 16 KB device cannot map it and "
                    "the app dies at startup" % (name, align), None)
    if relro is None:
        return (None, None)
    rvaddr, rmemsz = relro
    end = rvaddr + rmemsz
    if end % PAGE == 0:
        return (None, None)

    lo, hi = page_start(rvaddr), page_end(end)
    caught = []
    for vaddr, memsz, flags, align in loads:
        if "W" not in flags:
            continue
        seg_lo, seg_hi = page_start(vaddr), page_end(vaddr + memsz)
        if seg_hi <= lo or seg_lo >= hi:
            continue
        if not (rvaddr <= vaddr and vaddr + memsz <= end):
            caught.append("0x%x..0x%x" % (vaddr, vaddr + memsz))

    if caught:
        return ("%s: GNU_RELRO ends at 0x%x, which a 16 KB device rounds up to 0x%x, making "
                "0x%x..0x%x read-only -- and that catches writable memory RELRO does not cover "
                "(%s). This is a segmentation fault on a 16 KB phone. Rebuild it with "
                "-Wl,-z,max-page-size=16384." % (name, end, hi, lo, hi, ", ".join(caught)), None)
    return (None,
            "%s: GNU_RELRO ends at 0x%x, 0x%x short of a 16 KB boundary. Harmless on this "
            "layout -- the rounding protects 0x%x..0x%x, which holds only the RELRO region "
            "itself and padding. Rebuilding with -Wl,-z,max-page-size=16384 would remove the "
            "discrepancy." % (name, end, PAGE - (end % PAGE), lo, hi))


if len(sys.argv) > 2 and sys.argv[2] == "--self-test":
    # Three layouts, chosen because the shipped libraries only exercise one of them.
    safe_loads = [(0x00000, 0x37ec0, "RE", PAGE), (0x3bec0, 0x02140, "RW", PAGE),
                  (0x41550, 0x03440, "RW", PAGE)]
    cases = [
        ("shipped layout: rounding covers only RELRO",
         safe_loads, (0x3bec0, 0x02140), False),
        ("a rebuild that leaves .data inside the rounded-up range",
         [(0x00000, 0x37ec0, "RE", PAGE), (0x3bec0, 0x02140, "RW", PAGE),
          (0x3e000, 0x01000, "RW", PAGE)], (0x3bec0, 0x02140), True),
        ("a LOAD aligned to 4 KB, which cannot be mapped at all",
         [(0x00000, 0x37ec0, "RE", 0x1000)], None, True),
    ]
    failed = 0
    for label, loads, relro, should_fail in cases:
        problem, note = judge("test.so", loads, relro)
        got = problem is not None
        mark = "correct" if got == should_fail else "WRONG"
        if got != should_fail:
            failed += 1
        print("  %-58s %-8s %s" % (label, "flagged" if got else "passed", mark))
        if problem:
            print("      " + problem[:110])
    sys.exit(1 if failed else 0)


import os
names = sorted(n for n in os.listdir(lib_dir) if n.endswith(".so"))
if not names:
    print("  no native libraries found to check", file=sys.stderr)
    sys.exit(1)

for name in names:
    path = lib_dir + "/" + name
    loads, relro = headers(path)
    if not loads:
        problems.append("%s has no LOAD segments readable by readelf" % name)
        continue
    problem, note = judge(name, loads, relro)
    if problem:
        problems.append(problem)
    if note:
        notes.append(note)

for note in notes:
    print("  note: " + note)
for problem in problems:
    print("  " + problem, file=sys.stderr)
sys.exit(1 if problems else 0)
