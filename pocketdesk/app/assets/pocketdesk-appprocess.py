#!/usr/bin/env python3
"""Read-only Linux app ownership and private startup logging.

An argv substring also matches shells and the PRoot tracer. Identify native executables by
device/inode instead, including binaries beside a publisher's shell launcher. Never signal
processes from this helper. Reuse the process helper's ancestor/tracer and PID namespace checks.
"""
import importlib.util
from collections import deque
import tempfile
import codecs
import os
import re
import selectors
import signal
import shutil
import subprocess
import sys
import time
from pathlib import Path

spec = importlib.util.spec_from_file_location('procinfo', Path(__file__).with_name('pocketdesk-procinfo.py'))
procinfo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(procinfo)

spec = importlib.util.spec_from_file_location('childwatch', Path(__file__).with_name('pocketdesk-childwatch.py'))
childwatch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(childwatch)


def identity(path):
    try:
        st = os.stat(path)
        return st.st_dev, st.st_ino
    except OSError:
        return None


def executables(target, roots):
    paths = [Path(target)]
    for root in roots:
        try:
            paths.extend(p for p in Path(root).iterdir() if p.is_file() and os.access(p, os.X_OK))
        except OSError:
            continue
    return {key for p in paths if (key := identity(p)) is not None}


def snapshot(target, roots, proc=Path('/proc'), own_pid=None):
    keys = executables(target, roots)
    protected = procinfo.protected_pids(proc, own_pid)
    found = []
    for folder in proc.iterdir():
        if not folder.name.isdecimal() or int(folder.name) in protected:
            continue
        if identity(folder / 'exe') not in keys:
            continue
        record = procinfo.info(int(folder.name), proc)
        if record and record[2] != 'Z':
            found.append(int(folder.name))
    return found


def candidates(target, roots, pids, proc=Path('/proc'), own_pid=None):
    """Verify supplied window-owner PIDs without walking all of Android's /proc."""
    pids = set(pids)
    # Some development containers expose the outer procfs namespace while X11
    # reports inner PIDs. Android/PRoot has no extra PID namespace; it uses the
    # fast path below. Preserve fail-closed translation in such test containers.
    if own_pid is None and int(os.readlink(proc / 'self')) != os.getpid():
        return [pid for pid in snapshot(target, roots, proc)
                if procinfo.signal_pid(pid, proc) in pids]
    keys = executables(target, roots)
    protected = procinfo.protected_pids(proc, own_pid)
    found = []
    for pid in pids:
        if pid <= 1 or pid in protected:
            continue
        if identity(proc / str(pid) / 'exe') not in keys:
            continue
        record = procinfo.info(pid, proc)
        if record and record[2] != 'Z':
            found.append(pid)
    return found


def redact(line):
    # Auth callbacks can be echoed by Electron itself. Keep scheme/host/path for diagnosis,
    # but never keep an authorization code, token, or state from the query or fragment.
    if '://' in line:
        line = re.sub(r'''(?<![A-Za-z0-9+.-])([A-Za-z][A-Za-z0-9+.-]*://[^\s?#"'<>]*)([?#][^\s"'<>]*)''',
                      r'\1?[redacted]', line)
    line = re.sub(r'(?i)("(?:access_token|refresh_token|id_token|authorization)"\s*:\s*)"(?:\\.|[^"\\])*"',
                  r'\1"[redacted]"', line)
    line = re.sub(r'''(?i)\b(authorization\s*[:=]\s*)(?:Bearer|Basic)\s+[^\s,;"'<>]+''',
                  r'\1[redacted]', line)
    return re.sub(r'(?i)\b(code|state|access_token|refresh_token|id_token|authorization)=([^\s&]+)',
                  r'\1=[redacted]', line)


# Keep disk traffic bounded during long, verbose Electron sessions. Diagnostics
# must never close the app's stdout pipe just because the log storage failed.
class AppLog:
    LIMIT = 2 * 1024 * 1024
    TAIL = 24 * 1024

    def __init__(self, path):
        self.path = Path(path)
        self.stream = None
        self.buffer = []
        self.buffer_size = 0
        self.tail = deque()
        self.tail_size = 0
        self.last_flush = time.monotonic()
        self.header = time.strftime('--- %Y-%m-%d %I:%M:%S %p %Z ---')
        self.disabled = False

    def append(self, line):
        # No entire publisher object is retained in RAM just for diagnostics.
        if len(line) > 65536:
            line = '[Oversized app log line omitted]\n'
        else:
            line = redact(line).rstrip('\n') + '\n'
        size = len(line.encode('utf-8'))
        self.tail.append((line, size))
        self.tail_size += size
        while self.tail_size > self.TAIL and self.tail:
            self.tail_size -= self.tail.popleft()[1]
        self.buffer.append(line)
        self.buffer_size += len(line)
        if self.buffer_size >= 16384:
            self.flush()

    def flush(self):
        self.last_flush = time.monotonic()
        data = ''.join(self.buffer)
        self.buffer.clear()
        self.buffer_size = 0
        if not data or self.disabled:
            return
        try:
            if self.stream is None:
                self.stream = self.path.open('a', encoding='utf-8')
                os.chmod(self.path, 0o600)
            if self.stream.tell() + len(data.encode('utf-8')) > self.LIMIT:
                self.stream.close()
                self.stream = None
                self.path.replace(self.path.with_name(self.path.name + '.older'))
                self.stream = self.path.open('w', encoding='utf-8')
                os.chmod(self.path, 0o600)
                self.stream.write(self.header + '\n[Earlier output rotated to .older]\n')
            self.stream.write(data)
            self.stream.flush()
        except OSError:
            # Keep draining stdout, even when storage is full/read-only. A broken
            # logger must not kill Electron with SIGPIPE or block its event loop.
            self.disabled = True
            self.close_stream()

    def close_stream(self):
        if self.stream is not None:
            try:
                self.stream.close()
            except OSError:
                pass
            self.stream = None

    def failure(self, reason, resources):
        self.flush()
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8',
                                             prefix='.pocketdesk-failure-',
                                             dir=self.path.parent, delete=False) as file:
                temporary = Path(file.name)
                file.write(self.header + '\nRetained failure from this app run\n')
                file.write(''.join(line for line, _ in self.tail))
                # The report reader has a 32 KiB budget, including UTF-8 text.
                # Keep the exit/failure and latest sample at the end of that budget.
                bounded_reason = redact(reason).encode('utf-8')[:2048].decode('utf-8', 'ignore')
                bounded_resources = redact(resources).encode('utf-8')[:2048].decode('utf-8', 'ignore')
                file.write('\nPD_APP_FAILURE: ' + bounded_reason + '\n')
                file.write(bounded_resources + '\n')
            temporary.replace(self.path.with_name(self.path.name + '.failure'))
        except OSError:
            pass
        finally:
            if temporary is not None:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass

    def close(self):
        self.flush()
        self.close_stream()


def resource_snapshot(pid):
    """Two bounded procfs reads; main-process RSS is not total application memory."""
    values = {}
    prefix = 'PD_APP_RESOURCES: at=' + time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()) + ' | '
    try:
        # Development containers can expose a different procfs PID namespace.
        # Never sample an unrelated PID there; Android/PRoot shares one namespace.
        if int(os.readlink('/proc/self')) != os.getpid():
            return prefix + 'process namespace differs; sample unavailable'
        with open('/proc/%d/status' % pid) as file:
            for line in file:
                key, _, value = line.partition(':')
                if key in ('VmRSS', 'VmHWM', 'VmSwap', 'Threads'):
                    values[key] = value.strip()
        with open('/proc/meminfo') as file:
            for line in file:
                key, _, value = line.partition(':')
                if key in ('MemAvailable', 'SwapFree', 'SwapTotal'):
                    values[key] = value.strip()
    except (OSError, ValueError):
        return prefix + 'process exited or sample unavailable'
    return prefix + 'mainPid=%d | ' % pid + ' | '.join(
        key + '=' + values.get(key, 'unavailable') for key in
        ('VmRSS', 'VmHWM', 'VmSwap', 'Threads', 'MemAvailable', 'SwapFree', 'SwapTotal'))


FATAL_OUTPUT = re.compile(
    r'FATAL(?: ERROR|:)|Received signal \d+|Segmentation fault|heap out of memory|'
    r'Allocation failed|render-process-gone|GPU process exited unexpectedly|'
    r'renderer.*(?:crashed|killed|oom)|child-process-gone', re.I)


def exit_description(returncode):
    if returncode >= 0:
        return f'PD_APP_EXIT: returnCode={returncode}; no signal reported for the direct child'
    number = -returncode
    try:
        name = signal.Signals(number).name
    except ValueError:
        name = 'signal ' + str(number)
    detail = f'PD_APP_EXIT: signal={name} ({number})'
    if number == signal.SIGSEGV:
        return detail + '; native process fault; the failing component is not identified'
    return detail + '; this does not identify who sent the signal'


def supervise(log_path, label, command):
    """Own one child and drain its output without per-line disk flushes or restarts."""
    started = time.monotonic()
    log = AppLog(log_path)
    child_events = childwatch.ChildWakeup().__enter__()
    try:
        try:
            child = subprocess.Popen(command, stdin=subprocess.DEVNULL,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        except OSError as error:
            reason = 'app could not be executed: ' + redact(str(error))
            log.append('PD_ERROR: ' + reason)
            log.failure(reason, 'PD_APP_RESOURCES: app was not started')
            return 127
        decoder = codecs.getincrementaldecoder('utf-8')('replace')
        pending = ''
        dropping_line = False
        drain_deadline = None
        resources = resource_snapshot(child.pid)
        next_sample = started + 15.0
        last_failure = -100.0
        log.append('PD_APP_RUN: supervisor active; resource samples describe main process only')
        log.append(resources)

        def accept_line(line):
            nonlocal last_failure
            if len(line) > 65536:
                log.append('[Oversized app log line omitted]')
                return
            log.append(line)
            now = time.monotonic()
            if FATAL_OUTPUT.search(line) and now - last_failure >= 5:
                log.failure('Publisher reported a possible child/runtime failure: ' + line,
                            resource_snapshot(child.pid))
                last_failure = now

        def consume(text, final=False):
            nonlocal pending, dropping_line
            pending += text
            while '\n' in pending:
                line, pending = pending.split('\n', 1)
                if not dropping_line:
                    accept_line(line)
                dropping_line = False
            if len(pending) > 65536:
                if not dropping_line:
                    log.append('[Oversized app log line omitted]')
                pending = ''
                dropping_line = True
            if final and pending and not dropping_line:
                accept_line(pending)
                pending = ''

        with child.stdout, selectors.DefaultSelector() as selector:
            selector.register(child.stdout, selectors.EVENT_READ, 'output')
            if child_events.reader is not None:
                selector.register(child_events.reader, selectors.EVENT_READ, 'child')
            pipe_open = True
            while True:
                now = time.monotonic()
                if now >= next_sample and child.poll() is None:
                    resources = resource_snapshot(child.pid)
                    log.append(resources)
                    next_sample = now + 15.0
                if log.buffer and now - log.last_flush >= 1.0:
                    log.flush()
                status = child.poll()
                if status is not None and not pipe_open:
                    break
                if status is not None and drain_deadline is None:
                    drain_deadline = now + 1.0
                if drain_deadline is not None and now >= drain_deadline:
                    consume(decoder.decode(b'', final=True), final=True)
                    log.append('Output drain ended after app exit; inherited pipe remained open.')
                    break
                deadlines = []
                if status is None:
                    deadlines.append(next_sample)
                if log.buffer:
                    deadlines.append(log.last_flush + 1.0)
                if drain_deadline is not None:
                    deadlines.append(drain_deadline)
                wait = max(0, min(deadlines) - now) if deadlines else None
                # Idle apps wait for output, a child exit, or the next 15-second
                # resource sample. Four wakeups per second added CPU/PRoot work
                # even when the user had switched to another Android app.
                for key, _ in selector.select(child_events.bounded_wait(wait)):
                    if key.data == 'child':
                        child_events.drain()
                        continue
                    data = os.read(child.stdout.fileno(), 16384)
                    if not data:
                        consume(decoder.decode(b'', final=True), final=True)
                        selector.unregister(child.stdout)
                        pipe_open = False
                        continue
                    consume(decoder.decode(data))
        raw_status = child.wait()
        exit_detail = exit_description(raw_status)
        status = 128 - raw_status if raw_status < 0 else raw_status
        ended = f'{label} process ended after {int(time.monotonic() - started)}s · exit {status}'
        log.append(exit_detail)
        log.append(ended)
        if status not in (0, 143):
            # Preserve across a later successful reopen. Signal numbers do not identify
            # Android/LMKD as their sender, and SIGTERM can be an ordinary desktop stop.
            log.failure(ended + '; ' + exit_detail, resources)
        log.close()
        if status not in (0, 143) and shutil.which('notify-send'):
            try:
                subprocess.run(['notify-send', '-a', 'PocketLinux', '-u', 'critical',
                                label + ' stopped with an error',
                                f'Exit {status}. Settings → Linux app reports keeps the failure.'],
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=3)
            except (OSError, subprocess.TimeoutExpired):
                pass
        return status
    finally:
        log.close()
        child_events.close()


def main():
    if len(sys.argv) >= 6 and sys.argv[1] == 'supervise' and sys.argv[4] == '--':
        return supervise(sys.argv[2], sys.argv[3], sys.argv[5:])
    if len(sys.argv) >= 4 and sys.argv[1] == 'candidates' and '--' in sys.argv[3:]:
        boundary = sys.argv.index('--', 3)
        supplied = sys.argv[boundary + 1:]
        if any(not pid.isdecimal() for pid in supplied):
            return 2
        for pid in candidates(sys.argv[2], sys.argv[3:boundary], map(int, supplied)):
            destination = procinfo.signal_pid(pid)
            if destination is not None:
                print(destination)
        return 0
    if len(sys.argv) >= 3 and sys.argv[1] == 'list':
        for pid in snapshot(sys.argv[2], sys.argv[3:]):
            destination = procinfo.signal_pid(pid)
            if destination is not None:
                print(destination)
        return 0
    if sys.argv[1:] == ['redact']:
        for line in sys.stdin:
            print(redact(line), end='', flush=True)
        return 0
    return 2


if __name__ == '__main__':
    sys.exit(main())
