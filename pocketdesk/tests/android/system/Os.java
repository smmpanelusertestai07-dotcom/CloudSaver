package android.system;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Os {
    public static void chmod(String path, int mode) throws ErrnoException {
        // Permissions are not material to this parser test.
    }

    public static void symlink(String target, String link) throws ErrnoException {
        try { Files.createSymbolicLink(Paths.get(link), Paths.get(target)); }
        catch (Exception error) { throw new ErrnoException("symlink", error); }
    }

    public static void link(String source, String target) throws ErrnoException {
        try { Files.createLink(Paths.get(target), Paths.get(source)); }
        catch (Exception error) { throw new ErrnoException("link", error); }
    }
}
