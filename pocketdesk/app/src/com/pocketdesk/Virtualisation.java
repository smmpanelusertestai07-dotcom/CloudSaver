package com.pocketdesk;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * Whether this phone offers apps hardware virtualisation, measured, not assumed.
 *
 * A whole other operating system, Windows or macOS, can only run inside a virtual machine, and
 * a virtual machine is only usable with hardware virtualisation: on Linux that is KVM, seen by
 * a program as /dev/kvm. Without it the only way is emulation, which runs an operating system
 * ten to fifty times slower than the phone itself. No app, script or setting can add KVM to a
 * phone whose kernel or vendor does not provide it, so the honest thing is to test for it and
 * say what was found. If it is ever found, a Windows 11 ARM virtual machine becomes possible
 * inside this app; if not, Windows and macOS stay off this phone, whatever anyone promises.
 */
final class Virtualisation {
    static final class Result {
        final boolean available;
        final String headline;
        final String detail;
        Result(boolean available, String headline, String detail) {
            this.available = available;
            this.headline = headline;
            this.detail = detail;
        }
    }

    private Virtualisation() {}

    static Result check() {
        File kvm = new File("/dev/kvm");
        String why = "Windows and macOS can only run inside a virtual machine, and a virtual machine "
                + "needs hardware virtualisation, which Linux exposes to programs as /dev/kvm. "
                + "This test looks for it on this phone and tries to open it, exactly as a "
                + "virtual-machine program would.";
        if (!kvm.exists()) {
            return new Result(false, "Hardware virtualisation: not available",
                    why + "\n\nResult: /dev/kvm does not exist on this phone. The kernel does not "
                            + "provide virtualisation to apps, so no virtual machine can be built "
                            + "here, and no app, script or trick can change that: it is set by the "
                            + "phone's maker in the kernel and the boot chain. Without it, Windows "
                            + "or macOS could only be emulated, at a tenth to a fiftieth of this "
                            + "phone's speed, with less memory than they need.\n\n"
                            + "What runs natively on the phone's own kernel is Linux, which is "
                            + "what this app does. For Windows or macOS with their AI apps, a cloud "
                            + "PC over remote desktop is the real route.");
        }
        try (RandomAccessFile device = new RandomAccessFile(kvm, "rw")) {
            return new Result(true, "Hardware virtualisation: available",
                    why + "\n\nResult: /dev/kvm exists and this app can open it. A Windows 11 "
                            + "ARM virtual machine is genuinely possible on this phone. Send this "
                            + "result to the developer: it changes the plan.");
        } catch (Exception error) {
            return new Result(false, "Hardware virtualisation: present but closed to apps",
                    why + "\n\nResult: /dev/kvm exists, but Android refuses to let this app open "
                            + "it (" + error.getClass().getSimpleName() + "). Only the system itself "
                            + "may use it. A virtual machine needs that door open, and an app cannot "
                            + "open it without the phone maker's permission, which no script grants.\n\n"
                            + "For Windows or macOS with their AI apps, a cloud PC over remote desktop "
                            + "is the real route; Linux runs here natively, which is what this app does.");
        }
    }
}
