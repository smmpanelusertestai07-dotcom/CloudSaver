#!/usr/bin/env python3
"""Inspect and stop only the processes belonging to one exact folder tree.

Never use command-line substring matches for signals: an installer, a shell or the PRoot
tracer can carry the same app path in its arguments, and losing that tracer strands the whole
Linux session. Every decision here is made from /proc: the real executable, the real parent and
the real tracer.
"""
import os
import signal
import sys
import time
from pathlib import Path


def info(pid, proc=Path('/proc')):
    try:
        folder = proc / str(pid)
        # comm can contain spaces or parentheses; fields after the last ')' start at field 3.
        fields = (folder / 'stat').read_text().rsplit(')', 1)[1].split()
        tracer = 0
        for line in (folder / 'status').read_text().splitlines():
            if line.startswith('TracerPid:'):
                tracer = int(line.split()[1])
        return int(fields[1]), fields[19], fields[0], tracer
    except (OSError, ValueError, IndexError):
        return None


def protected_pids(proc=Path('/proc'), own_pid=None):
    """Protect the supervisor, all ancestors, and the tracer of each protected tracee."""
    protected = {0, 1}
    if own_pid is None:
        # /proc may expose an outer PID namespace in a development container.
        own_pid = int(os.readlink(proc / 'self'))
    pending = [own_pid]
    while pending:
        pid = pending.pop()
        if pid in protected:
            continue
        protected.add(pid)
        record = info(pid, proc)
        if record:
            pending.extend([record[0], record[3]])
    return protected


def signal_pid(pid, proc=Path('/proc')):
    """Translate a procfs PID only inside this process's own PID namespace; fail closed."""
    try:
        if int(os.readlink(proc / 'self')) == os.getpid():
            return pid
        if os.readlink(proc / str(pid) / 'ns/pid') != os.readlink(proc / 'self/ns/pid'):
            return None
        for line in (proc / str(pid) / 'status').read_text().splitlines():
            if line.startswith('NSpid:'):
                return int(line.split()[-1])
    except (OSError, ValueError):
        pass
    return None


def stop(prefix):
    pending = snapshot(prefix)
    protected = protected_pids()
    for sig in (signal.SIGTERM, signal.SIGKILL):
        for pid, birth in pending.items():
            if owned(pid, prefix, protected) == birth:
                destination = signal_pid(pid)
                if destination is None:
                    raise RuntimeError('Cannot safely identify the app process namespace; cleanup stopped.')
                try:
                    os.kill(destination, sig)
                except ProcessLookupError:
                    pass
        if not pending or sig == signal.SIGKILL:
            break
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            if not any(owned(pid, prefix, protected) == birth for pid, birth in pending.items()):
                break
            time.sleep(0.1)


def diagnose(prefix, proc=Path('/proc'), own_pid=None):
    """Read-only state after a failed boot; no argv, environment, registry contents or signals."""
    root = Path(prefix)
    print('PD_DIAG: app prefix state before cleanup', flush=True)
    for relative in ('system.reg', 'user.reg', '.update-timestamp'):
        try:
            state = f'{(root / relative).stat().st_size} bytes'
        except OSError:
            state = 'missing or unreadable'
        print(f'PD_DIAG: {relative}: {state}', flush=True)
    system32 = root / 'drive_c/windows/system32'
    for name in ('ole32.dll', 'combase.dll', 'oleaut32.dll', 'rpcrt4.dll', 'actxprxy.dll',
                 'services.exe', 'svchost.exe', 'rpcss.exe'):
        try:
            state = f'{(system32 / name).stat().st_size} bytes'
        except OSError:
            state = 'missing or unreadable'
        print(f'PD_DIAG: system32/{name}: {state}', flush=True)
    try:
        processes = snapshot(prefix, proc, own_pid)
    except (OSError, ValueError):
        print('PD_DIAG: process identity unavailable; no ownership was guessed', flush=True)
        return
    print(f'PD_DIAG: {len(processes)} identified app process(es) remain', flush=True)
    for pid in sorted(processes)[:32]:
        try:
            lines = (proc / str(pid) / 'status').read_text().splitlines()
            fields = [line for line in lines
                      if line.split(':', 1)[0] in ('Name', 'State', 'VmRSS', 'Threads', 'TracerPid')]
            fields = [' '.join(line.split())[:100] for line in fields]
            print(f'PD_DIAG: pid {pid}: ' + '; '.join(fields), flush=True)
        except OSError:
            print(f'PD_DIAG: pid {pid}: ended while reading', flush=True)


def main():
    if len(sys.argv) < 3 or not os.path.isabs(sys.argv[2]):
        return 2
    action, prefix = sys.argv[1:3]
    if action == 'list':
        for pid in snapshot(prefix):
            print(pid)
    elif action == 'stop':
        stop(prefix)
    elif action == 'diagnose':
        diagnose(prefix)
    elif action == 'owned' and len(sys.argv) == 4 and sys.argv[3].isdecimal():
        return 0 if owned(int(sys.argv[3]), prefix, protected_pids()) is not None else 1
    else:
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
