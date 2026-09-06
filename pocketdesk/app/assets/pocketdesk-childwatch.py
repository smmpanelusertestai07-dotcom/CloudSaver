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

# --------------------------------------------------------------- Android's real ceiling
#
# Under PRoot every Linux process is a real Android process of this app, so Android's own limit
# on an app's forked children is the limit on the whole computer. Android 12 and later kill those
# children once there are more than 32 of them -- the whole session, at once, with SIGKILL, which
# arrives as "the desktop display ended unexpectedly (exit 137)".
#
# The count that matters includes ZOMBIES. A finished process that nobody has waited for still
# holds its slot. When an app exits and its children are reparented, nothing in a PRoot container
# waits for them: there is no init here. They pile up until the session is killed for a crowd that
# is mostly already dead. On a real report, five of the seven surviving processes were zombies.
#
# So the session's last process becomes a subreaper and clears them continuously.
PROCESS_CEILING = 32


def become_subreaper():
    """Make orphaned grandchildren reparent here, so they can be reaped rather than pile up."""
    try:
        import ctypes
        libc = ctypes.CDLL(None, use_errno=True)
        # PR_SET_CHILD_SUBREAPER = 36. Without it an orphan reparents to a pid 1 that a PRoot
        # container does not really have, and can never be waited for by anyone.
        return libc.prctl(36, 1, 0, 0, 0) == 0
    except (OSError, AttributeError, ValueError):
        return False


def reap_unowned(owned):
    """Clear finished processes nobody owns. Returns how many slots were freed.

    Owned children -- the display, the panel, an installer -- are left exactly as they are: their
    status belongs to whoever started them, and stealing it would make a live child look dead.
    waitid with WNOWAIT looks without consuming, so the choice is made before anything is taken.
    """
    freed = 0
    while True:
        try:
            info = os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT)
        except (ChildProcessError, OSError, AttributeError):
            return freed
        if info is None:
            return freed
        if info.si_pid in owned:
            return freed          # the owner will take this one; looking further would spin
        try:
            os.waitpid(info.si_pid, os.WNOHANG)
            freed += 1
        except ChildProcessError:
            return freed


def process_count(proc='/proc'):
    """How many processes this container has, which is what Android is counting."""
    try:
        return sum(1 for name in os.listdir(proc) if name.isdecimal())
    except OSError:
        return 0
