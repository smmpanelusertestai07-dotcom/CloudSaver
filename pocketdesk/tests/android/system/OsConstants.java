package android.system;

public final class OsConstants {
    public static final int EPERM = 1;
    public static final int EACCES = 13;
    public static final int EXDEV = 18;
    public static final int ENOSYS = 38;
    public static final int EOPNOTSUPP = 95;
    public static final int ENOENT = 2;

    private static final int S_IFMT = 0xF000;
    private static final int S_IFLNK = 0xA000;

    private OsConstants() {}

    public static boolean S_ISLNK(int mode) {
        return (mode & S_IFMT) == S_IFLNK;
    }
}
