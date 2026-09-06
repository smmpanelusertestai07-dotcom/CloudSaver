#!/bin/bash
# Keep the installed entrypoint. One Python watcher replaces the Bash pipeline,
# per-event sleep processes, and per-property sed/tr/subshell helpers.
exec python3 - "$@" <<'POCKETDESK_GUARD_PY'
import fcntl
import os
from pathlib import Path
import re
import select
import shutil
import signal
import subprocess
import sys
import time

os.environ.setdefault("DISPLAY", ":1")
STATE_DIR = Path(os.environ.get("POCKETDESK_STATE_DIR", "/home/coder/.pocketdesk"))
SKIP_TYPES = ("DESKTOP", "DOCK", "MENU", "DROPDOWN_MENU", "POPUP_MENU", "TOOLTIP",
              "NOTIFICATION", "COMBO", "DND")
PROPERTIES = ("_NET_WM_WINDOW_TYPE", "_NET_WM_STATE", "_NET_FRAME_EXTENTS")


def query(arguments):
    try:
        result = subprocess.run(arguments, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=3, text=True,
                                encoding="utf-8", errors="replace")
        return result.stdout if result.returncode == 0 else ""
    except (OSError, subprocess.TimeoutExpired):
        return ""


def four_integers(raw):
    try:
        fields = raw.split("=", 1)[-1].replace(",", " ").split()
        return tuple(int(value) for value in fields[:4]) if len(fields) >= 4 else None
    except ValueError:
        return None


def valid_area(raw):
    area = four_integers(raw)
    return area if area is not None and area[2] > 0 and area[3] > 0 else None


def work_area():
    override = os.environ.get("POCKETDESK_WORKAREA", "")
    area = valid_area(override or query(["xprop", "-root", "_NET_WORKAREA"]))
    if area:
        return area
    # Older containers without xprop still fit against the full root dimensions.
    dimensions = re.search(r"dimensions:\s*(\d+)x(\d+)", query(["xdpyinfo"]))
    if dimensions:
        width, height = map(int, dimensions.groups())
        if width > 0 and height > 0:
            return 0, 0, width, height
    return None


def fit_geometry(area, geometry, extents):
    """wmctrl reports client origin/size, but moves the outside decorated frame."""
    work_x, work_y, work_w, work_h = area
    x, y, width, height = geometry
    left, right, top, bottom = extents
    outer_x, outer_y = x - left, y - top
    new_w = min(width, max(64, work_w - left - right))
    new_h = min(height, max(64, work_h - top - bottom))
    outer_w, outer_h = new_w + left + right, new_h + top + bottom
    new_x = max(work_x, min(max(outer_x, work_x), work_x + work_w - outer_w))
    new_y = max(work_y, min(max(outer_y, work_y), work_y + work_h - outer_h))
    proposed = new_x, new_y, new_w, new_h
    return proposed if proposed != (outer_x, outer_y, width, height) else None


def clamp_all(area=None, windows=None):
    area = area or work_area()
    if area is None:
        return
    for line in query(["wmctrl", "-lG"]).splitlines():
        fields = line.split(None, 7)
        if len(fields) < 7 or not re.fullmatch(r"0x[0-9a-fA-F]+", fields[0]):
            continue
        if windows is not None and int(fields[0], 16) not in windows:
            continue
        try:
            geometry = tuple(int(value) for value in fields[2:6])
        except ValueError:
            continue
        if geometry[2] <= 0 or geometry[3] <= 0:
            continue
        window = fields[0]
        # xprop accepts multiple property names: one short-lived child per window,
        # not four xprop children plus shell pipelines to parse each answer.
        properties = query(["xprop", "-id", window] + list(PROPERTIES))
        if any("_NET_WM_WINDOW_TYPE_" + name in properties for name in SKIP_TYPES):
            continue
        if "_NET_WM_STATE_HIDDEN" in properties or "_NET_WM_STATE_FULLSCREEN" in properties:
            continue
        if ("_NET_WM_STATE_MAXIMIZED_VERT" in properties
                and "_NET_WM_STATE_MAXIMIZED_HORZ" in properties):
            continue
        extents = None
        for prop in properties.splitlines():
            if prop.startswith("_NET_FRAME_EXTENTS"):
                extents = four_integers(prop)
                break
        if extents is None or any(value < 0 for value in extents):
            extents = (0, 0, 0, 0)
        fitted = fit_geometry(area, geometry, extents)
        if fitted is None:
            continue
        query(["wmctrl", "-i", "-r", window, "-e", "0," + ",".join(map(str, fitted))])
        try:
            (STATE_DIR / "logs").mkdir(parents=True, exist_ok=True)
            x, y, width, height = fitted
            work_x, work_y, work_w, work_h = area
            with (STATE_DIR / "logs/window-guard.log").open("a") as log:
                log.write("%s fitted %s to %sx%s+%s+%s inside %sx%s+%s+%s\n" %
                          (time.strftime("%Y-%m-%d %H:%M:%S"), window, width, height,
                           x, y, work_w, work_h, work_x, work_y))
        except OSError:
            pass


def stop_monitor(monitor):
    if monitor is None:
        return
    try:
        if monitor.poll() is None:
            monitor.terminate()
            try:
                monitor.wait(timeout=2)
            except subprocess.TimeoutExpired:
                monitor.kill()
                monitor.wait(timeout=2)
    except (OSError, subprocess.TimeoutExpired):
        pass
    if monitor.stdout is not None:
        monitor.stdout.close()


class WindowChanges:
    """Fit new windows or a changed work area; leave deliberate layouts alone."""
    def __init__(self):
        self.override = valid_area(os.environ.get("POCKETDESK_WORKAREA", ""))
        self.area = self.override
        self.clients = None
        self.pending = set()
        self.all_windows = False

    def receive(self, line):
        if line.startswith("_NET_WORKAREA"):
            area = self.override or valid_area(line)
            if area is not None and area != self.area:
                self.area = area
                self.all_windows = True
                return True
        elif line.startswith("_NET_CLIENT_LIST("):
            # Unlike _NET_CLIENT_LIST_STACKING, this property does not change merely
            # because a user focuses/raises a window to drag or resize its border.
            # Keep IDs as integers: xprop and wmctrl use different leading zeroes.
            clients = {int(value, 16) for value in re.findall(r"0x[0-9a-fA-F]+", line)}
            # The initial list also covers a window mapped between the startup fit
            # and attaching xprop's event stream.
            added = clients - self.clients if self.clients is not None else clients
            self.clients = clients
            self.pending.intersection_update(clients)
            self.pending.update(added)
            return bool(added)
        return False

    def apply(self):
        if self.all_windows:
            clamp_all(self.area)
        elif self.pending:
            clamp_all(self.area, frozenset(self.pending))
        self.all_windows = False
        self.pending.clear()


def watch_stream(monitor):
    """Coalesce bursts from app creation/rotation without spawning a sleep child."""
    pending = b""
    deadline = None
    first_event = None
    changes = WindowChanges()
    fd = monitor.stdout.fileno()
    while True:
        now = time.monotonic()
        if deadline is not None and now >= deadline:
            changes.apply()
            deadline = first_event = None
        wait = None if deadline is None else max(0, deadline - time.monotonic())
        readable, _, _ = select.select([fd], [], [], wait)
        if not readable:
            continue
        data = os.read(fd, 65536)
        if not data:
            return
        pending += data
        while b"\n" in pending:
            line, pending = pending.split(b"\n", 1)
            if changes.receive(line.decode("utf-8", "replace")):
                now = time.monotonic()
                if first_event is None:
                    first_event = now
                deadline = min(now + 0.25, first_event + 1)
        if len(pending) > 1048576:
            pending = b""  # Never retain unbounded malformed xprop output.


def watch_changes():
    try:
        (STATE_DIR / "logs").mkdir(parents=True, exist_ok=True)
        lock = (STATE_DIR / "window-guard.lock").open("a")
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except (OSError, BlockingIOError):
        return
    pid_file = STATE_DIR / "window-guard.pid"
    monitor = None
    owns_pid = False
    try:
        # Respect a still-running guard from an older build, which has no lock file.
        try:
            previous = int(pid_file.read_text().strip())
            if previous > 0 and previous != os.getpid():
                os.kill(previous, 0)
                return
        except (OSError, ValueError):
            pass
        pid_file.write_text(str(os.getpid()) + "\n")
        owns_pid = True
        clamp_all()
        while True:
            if shutil.which("xprop"):
                command = ["xprop", "-spy", "-root", "_NET_CLIENT_LIST", "_NET_WORKAREA"]
                # stdbuf execs xprop; it adds no persistent process. xprop's pipe
                # output must be line buffered for events to arrive immediately.
                if shutil.which("stdbuf"):
                    command = ["stdbuf", "-oL"] + command
                try:
                    monitor = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                               stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                    watch_stream(monitor)
                except OSError:
                    pass
                finally:
                    stop_monitor(monitor)
                    monitor = None
                time.sleep(2)
            else:
                # Compatibility with older containers, without busy polling.
                time.sleep(4)
                clamp_all()
    finally:
        stop_monitor(monitor)
        if owns_pid:
            try:
                if pid_file.read_text().strip() == str(os.getpid()):
                    pid_file.unlink()
            except OSError:
                pass
        lock.close()


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "watch":
        def interrupted(signum, frame):
            raise KeyboardInterrupt
        signal.signal(signal.SIGTERM, interrupted)
        try:
            watch_changes()
        except KeyboardInterrupt:
            pass
    else:
        clamp_all()


if __name__ == "__main__":
    main()
POCKETDESK_GUARD_PY
