package android.system;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

public final class Os {
    /** Set by tests to reproduce Android builds that refuse hard links with EACCES. */
    public static boolean denyHardLinks;

    public static void chmod(String path, int mode) throws ErrnoException {
        Path target = Paths.get(path);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new ErrnoException("chmod", OsConstants.ENOENT);
        }
        File file = target.toFile();
        file.setReadable((mode & 0400) != 0, true);
        file.setWritable((mode & 0200) != 0, true);
        file.setExecutable((mode & 0100) != 0, true);
    }

    public static void symlink(String target, String link) throws ErrnoException {
        try { Files.createSymbolicLink(Paths.get(link), Paths.get(target)); }
        catch (Exception error) { throw new ErrnoException("symlink", error); }
    }

    public static void link(String source, String target) throws ErrnoException {
        if (denyHardLinks) throw new ErrnoException("link", OsConstants.EACCES);
        try { Files.createLink(Paths.get(target), Paths.get(source)); }
        catch (Exception error) { throw new ErrnoException("link", error); }
    }

    public static StructStat lstat(String path) throws ErrnoException {
        return statAt(path, LinkOption.NOFOLLOW_LINKS);
    }

    public static StructStat stat(String path) throws ErrnoException {
        return statAt(path);
    }

    private static StructStat statAt(String path, LinkOption... options) throws ErrnoException {
        Path target = Paths.get(path);
        if (!Files.exists(target, options)) throw new ErrnoException("stat", OsConstants.ENOENT);
        int mode = 0;
        try {
            PosixFileAttributes attributes =
                    Files.readAttributes(target, PosixFileAttributes.class, options);
            if (attributes.isSymbolicLink()) mode |= 0xA000;
            Set<PosixFilePermission> permissions = attributes.permissions();
            if (permissions.contains(PosixFilePermission.OWNER_READ)) mode |= 0400;
            if (permissions.contains(PosixFilePermission.OWNER_WRITE)) mode |= 0200;
            if (permissions.contains(PosixFilePermission.OWNER_EXECUTE)) mode |= 0100;
        } catch (Exception error) {
            throw new ErrnoException("stat", error);
        }
        return new StructStat(mode);
    }
}
