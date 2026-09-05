#!/usr/bin/env python3
"""Wake select on child exit without a polling child, thread, or timer."""
import os
import signal


class ChildWakeup:
    def __init__(self):
        self.reader = self.writer = None
        self.previous_handler = None
        self.previous_fd = None

    def __enter__(self):
        try:
            self.reader, self.writer = os.pipe2(os.O_NONBLOCK | os.O_CLOEXEC)
            previous_handler = signal.getsignal(signal.SIGCHLD)
            # Reaping remains with Popen.poll or the exact inherited display PID.
            # The signal handler only interrupts the descriptor wait.
            signal.signal(signal.SIGCHLD, lambda signum, frame: None)
            self.previous_handler = previous_handler
            self.previous_fd = signal.set_wakeup_fd(self.writer, warn_on_full_buffer=False)
        except (OSError, ValueError, AttributeError):
            self.close()
        return self

    def drain(self):
        if self.reader is None:
            return
        while True:
            try:
                if not os.read(self.reader, 4096):
                    return
            except BlockingIOError:
                return

    def close(self):
        if self.previous_fd is not None:
            signal.set_wakeup_fd(self.previous_fd)
            self.previous_fd = None
        if self.previous_handler is not None:
            signal.signal(signal.SIGCHLD, self.previous_handler)
            self.previous_handler = None
        for name in ('reader', 'writer'):
            descriptor = getattr(self, name)
            if descriptor is not None:
                os.close(descriptor)
                setattr(self, name, None)

    def __exit__(self, *error):
        self.close()

    def bounded_wait(self, deadline=None):
        # Normal operation can sleep until a real output/child/download event.
        # A restricted Python runtime still reaps within one second.
        if self.reader is not None:
            return deadline
        return min(deadline, 1.0) if deadline is not None else 1.0


def inherited_child_status(pid):
    """Return shell-compatible exit status, or None while this exact child lives."""
    try:
        finished, status = os.waitpid(pid, os.WNOHANG)
    except ChildProcessError:
        # Lost ownership is an error; never report a dead/untracked display as running.
        return 1
    if not finished:
        return None
    result = os.waitstatus_to_exitcode(status)
    return 128 - result if result < 0 else result
