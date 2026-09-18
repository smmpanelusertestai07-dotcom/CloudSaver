package com.pocketide;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * One fact about this phone's kernel that decides whether a program can run at all.
 *
 * An arm64 Linux kernel gives user programs either 39 or 48 bits of address space (most phones
 * and Chromebooks are built with 39; servers and desktops with 48). A program whose memory
 * allocator assumes 48 bits aborts at start-up on a 39-bit kernel before it has done anything:
 * Google's agy, the backend the Antigravity extension downloads, is one (its repository's
 * issue 64, and the same failure reported from Chromebooks and Android). Read once, from this
 * process's own memory map: the highest address the kernel has handed out is below 2^39 on a
 * 39-bit kernel and far above it on a 48-bit one. Never guessed from a model name.
 */
final class Kernel {

    private static final long TWO_POW_39 = 1L << 39;
    private static int bits;

    private Kernel() {}

    /** 39 or 48 for this kernel; 48 when the map cannot be read, so nothing is refused blindly. */
    static synchronized int vaBits() {
        if (bits != 0) return bits;
        long highest = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int dash = line.indexOf('-');
                int space = line.indexOf(' ');
                if (dash <= 0 || space <= dash) continue;
                long end;
                try {
                    end = Long.parseUnsignedLong(line.substring(dash + 1, space), 16);
                } catch (NumberFormatException odd) {
                    continue;
                }
                // The kernel's own pages sit at the top of the 64-bit space; they say nothing
                // about what a user program may map.
                if (end < 0 || end > (1L << 52)) continue;
                if (end > highest) highest = end;
            }
        } catch (IOException unreadable) {
            highest = 0;
        }
        bits = highest == 0 ? 48 : highest <= TWO_POW_39 ? 39 : 48;
        return bits;
    }

    /** True when a program built for a 48-bit address space cannot start here. */
    static boolean narrow() {
        return vaBits() < 48;
    }
}
