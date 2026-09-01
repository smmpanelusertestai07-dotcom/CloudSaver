package com.pocketdesk;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.IOException;

/**
 * File-tree helpers that never follow a symbolic link.
 *
 * Ubuntu's rootfs ships /bin, /lib, /sbin and /lib64 as links into /usr. Resolving them while
 * walking the tree deletes or measures the wrong directory, so every operation here inspects the
 * link itself with lstat.
 */
final class Trees {
    private Trees() {}

    /** True for a symbolic link itself, without following it. */
    static boolean isSymlink(File file) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (ErrnoException error) {
            return false;
        }
    }

    /** exists() that also sees a dangling symlink, which File#exists reports as missing. */
    static boolean exists(File file) {
        try {
            Os.lstat(file.getAbsolutePath());
            return true;
        } catch (ErrnoException error) {
            return false;
        }
    }

    /** Recursive on-disk size. Links count as nothing, so no bytes are counted twice. */
    static long size(File root) {
        if (root == null || !exists(root) || isSymlink(root)) return 0L;
        if (root.isFile()) return root.length();
        File[] children = root.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            try {
                total += size(child);
            } catch (RuntimeException ignored) {
                // A single unreadable entry must not break the total.
            }
        }
        return total;
    }

    /** Deletes a tree, removing links as links and never descending through them. */
    static void delete(File root) throws IOException {
        if (root == null || !exists(root)) return;
        if (!isSymlink(root) && root.isDirectory()) {
            File[] children = root.listFiles();
            if (children == null) {
                // An unreadable directory can still be emptied once it is writable again.
                relax(root);
                children = root.listFiles();
            }
            if (children != null) {
                for (File child : children) delete(child);
            }
        }
        if (root.delete() || !exists(root)) return;
        relax(root.getParentFile());
        relax(root);
        if (!root.delete() && exists(root)) {
            throw new IOException("Could not remove " + root.getName());
        }
    }

    private static void relax(File file) {
        if (file == null) return;
        try {
            Os.chmod(file.getAbsolutePath(), 0700);
        } catch (ErrnoException ignored) {
            // Best effort; the delete below reports the real problem.
        }
    }
}
