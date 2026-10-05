#!/usr/bin/env python3
"""Fail if any shared library in an APK has an ELF LOAD segment aligned below 16 KiB.

zipalign -c -P 16 only checks where each .so starts inside the zip; it does not
look inside the ELF. A library linked for 4 KiB pages passes zipalign and still
triggers Android 16's page-size compatibility dialog on 16 KiB devices.
Usage: check_elf_16k.py app.apk   (exit 0 = all aligned, 1 = misaligned, 2 = bad input)
"""
import struct, sys, zipfile

def load_aligns(data):
    if data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    is64, e = data[4] == 2, ("<" if data[5] == 1 else ">")
    if is64:
        phoff = struct.unpack_from(e + "Q", data, 0x20)[0]
        phentsize, phnum = struct.unpack_from(e + "HH", data, 0x36)
    else:
        phoff = struct.unpack_from(e + "I", data, 0x1C)[0]
        phentsize, phnum = struct.unpack_from(e + "HH", data, 0x2A)
    out = []
    for i in range(phnum):
        o = phoff + i * phentsize
        if struct.unpack_from(e + "I", data, o)[0] != 1:  # PT_LOAD
            continue
        out.append(struct.unpack_from(e + ("Q" if is64 else "I"), data, o + (48 if is64 else 28))[0])
    return out

def main(apk):
    bad = 0
    with zipfile.ZipFile(apk) as z:
        libs = [n for n in z.namelist() if n.startswith("lib/") and n.endswith(".so")]
        for name in sorted(libs):
            aligns = load_aligns(z.read(name))
            ok = all(a >= 16384 for a in aligns)
            bad += not ok
            print(f"{'ALIGNED  ' if ok else 'UNALIGNED'} {name} p_align={[hex(a) for a in aligns]}")
    print(f"{len(libs)} libraries, {bad} misaligned")
    return 1 if bad else 0

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__); sys.exit(2)
    sys.exit(main(sys.argv[1]))
