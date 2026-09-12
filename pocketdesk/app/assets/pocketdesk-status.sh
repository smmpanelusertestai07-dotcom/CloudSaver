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


def gigabytes(byte_count):
    """Decimal GB, the way Android's own storage screen counts.

    The tenth is dropped above 99 GB: on a 256 GB phone it is noise, and the bar is laid out
    around the widest line this can print, so every character costs the window buttons room.
    """
    value = byte_count / 1000000000
    return ("%.1f" if value < 100 else "%.0f") % value


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
                values["mem"] = gigabytes(available * 1024)
            break
    try:
        fs = os.statvfs(storage)
        # One statfs on / answers for PocketLinux's private data partition. Do
        # not enumerate mounts or traverse files to compute available storage.
        if fs.f_frsize > 0 and fs.f_bavail >= 0:
            values["free_gb"] = gigabytes(fs.f_frsize * fs.f_bavail)
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


def sentences(values):
    """The same numbers as words, for the tooltip and for the dialog behind a tap."""
    said = []
    if values["cap"]:
        charging = ", charging" if values["status"] in ("Charging", "Full") else ""
        said.append("Battery: " + values["cap"] + "%" + charging)
    if values["temp"]:
        said.append("Battery temperature: " + values["temp"] + " C")
    if values["mem"]:
        said.append("Memory free: " + values["mem"] + " GB")
    if values["free_gb"]:
        said.append("Storage free: " + values["free_gb"] + " GB - the computer may use all of it")
    if values["net"]:
        said.append("Network: " + values["net"])
    return said


def render(values, output=sys.stdout, tooltip=sys.stderr):
    # The battery used to be the first thing on this line. It came off because the phone keeps
    # its own status bar on show above the desktop, so the percentage was on the screen twice.
    # What Android does not show is how much memory and storage the computer has left, and that
    # is the pair that decides whether the next app opens.
    lines = [label + " " + values[key] + "G"
             for label, key in (("Memory", "mem"), ("Storage", "free_gb")) if values[key]]
    # Temperature stays. It is not on Android's status bar either, and it is the one number that
    # predicts the thing this app actually does to a phone: the heat guard pausing the work.
    if values["temp"]:
        lines.append(values["temp"] + " C")
    # Never leave the block empty: it is the tap target for everything below, and an empty
    # execp item is nothing to tap, so the product name stands in until a number arrives.
    for line in lines or ["PocketLinux"]:
        print(line, file=output)
    # tint2 reads this command's standard error and uses it as the tooltip. Plain text only: it
    # goes through Pango, which drew a terminal's screen-clearing escape as two boxes of
    # gibberish at the head of the tooltip.
    print("This phone, right now", file=tooltip)
    for said in sentences(values):
        print(said, file=tooltip)
    print("Tap for storage, memory and battery.", file=tooltip)


def detail(values):
    """What the two numbers on the bar stand for, on a tap.

    The tooltip says the same, but a tooltip needs a pointer that rests on the item and the
    default Finger mode taps and lifts, so on a phone this dialog is the only way to these
    sentences at all.
    """
    import subprocess

    body = sentences(values) or ["This phone did not report its numbers just now."]
    body.append("")
    body.append("The computer shares the phone's memory and storage. Closing a window you have "
                "finished with gives its memory back.")
    text = "\n".join(body)
    storage_button = "More about storage"
    storage_app = "/usr/local/bin/pocketdesk-storage"
    try:
        answer = subprocess.run(["zenity", "--info", "--no-markup", "--width=420",
                                 "--title=This phone", "--text=" + text,
                                 "--extra-button=" + storage_button],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, text=True)
    except OSError:
        try:
            subprocess.run(["notify-send", "-a", "PocketLinux", "This phone", text],
                           stdin=subprocess.DEVNULL)
        except OSError:
            print(text)
        return
    if answer.stdout.strip() == storage_button:
        try:
            subprocess.run([storage_app], stdin=subprocess.DEVNULL)
        except OSError:
            pass


if __name__ == "__main__":
    if "detail" in sys.argv[1:]:
        detail(collect())
    else:
        render(collect())
POCKETDESK_STATUS_PY
