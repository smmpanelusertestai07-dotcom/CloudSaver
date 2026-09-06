#!/bin/bash
# Keep the entrypoint used by existing tint2 configurations. exec replaces Bash:
# reading the panel's numbers needs one process and no cat/awk/stat helpers.
exec python3 - "$@" <<'POCKETDESK_STATUS_PY'
import os
from pathlib import Path
import sys


def read_text(path):
    try:
        return Path(path).read_text().strip()
    except (OSError, UnicodeError):
        return ""


def integer(value):
    try:
        return int(value)
    except (ValueError, TypeError):
        return None


def collect(sys_root="/sys", meminfo="/proc/meminfo", storage="/"):
    """Read the same kernel counters as Android, without launching child processes."""
    values = dict(cap="", status="", temp="", mem="", free_gb="", net="")
    supplies = Path(sys_root) / "class/power_supply"
    battery = None
    for candidate in sorted(supplies.glob("*")):
        if read_text(candidate / "type") == "Battery":
            battery = candidate
            break
    if battery is None and (supplies / "battery").is_dir():
        battery = supplies / "battery"
    if battery is not None:
        cap = integer(read_text(battery / "capacity"))
        if cap is not None and 0 <= cap <= 100:
            values["cap"] = str(cap)
        values["status"] = read_text(battery / "status")
        temp = integer(read_text(battery / "temp"))
        # Kernel power_supply normally reports tenths; retain the compatibility
        # conversion for kernels that expose whole degrees or thousandths.
        if temp is not None:
            if temp > 1000:
                temp //= 1000
            elif temp > 100:
                temp //= 10
            if 0 <= temp <= 80:
                values["temp"] = str(temp)
    for line in read_text(meminfo).splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] == "MemAvailable:":
            available = integer(fields[1])
            if available is not None and available >= 0:
                values["mem"] = "%.1f" % (available * 1024 / 1000000000)
            break
    try:
        fs = os.statvfs(storage)
        # One statfs on / answers for PocketLinux's private data partition. Do
        # not enumerate mounts or traverse files to compute available storage.
        if fs.f_frsize > 0 and fs.f_bavail >= 0:
            values["free_gb"] = "%.1f" % (fs.f_frsize * fs.f_bavail / 1000000000)
    except OSError:
        pass
    interfaces = Path(sys_root) / "class/net"
    for label, patterns in (("Wi-Fi", ("wlan*", "wifi*")),
                            ("Mobile data", ("rmnet*", "ccmni*"))):
        if any(read_text(iface / "operstate") == "up"
               for pattern in patterns for iface in interfaces.glob(pattern)):
            values["net"] = label
            break
    return values


def render(values, output=sys.stdout, tooltip=sys.stderr):
    cap, temp = values["cap"], values["temp"]
    storage, memory = values["free_gb"], values["mem"]
    line1 = " · ".join(part for part in ((cap + "%") if cap else "",
                                        (storage + "G") if storage else "") if part)
    line2 = " · ".join(part for part in ((temp + "°C") if temp else "",
                                        (memory + "G") if memory else "") if part)
    print(line1 or "PocketLinux", file=output)
    if line2:
        print(line2, file=output)
    # tint2 uses standard error as the tooltip; clear the previous invocation.
    print("\033[2JThis phone, right now", file=tooltip)
    if cap:
        charging = ", charging" if values["status"] in ("Charging", "Full") else ""
        print("Battery: " + cap + "%" + charging, file=tooltip)
    if temp:
        print("Battery temperature: " + temp + " C", file=tooltip)
    if memory:
        print("Memory free: " + memory + " GB", file=tooltip)
    if storage:
        print("Storage free: " + storage + " GB - the computer may use all of it", file=tooltip)
    if values["net"]:
        print("Network: " + values["net"], file=tooltip)
    print("Tap for storage.", file=tooltip)


if __name__ == "__main__":
    render(collect())
POCKETDESK_STATUS_PY
