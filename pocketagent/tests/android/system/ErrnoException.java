package android.system;

public class ErrnoException extends Exception {
    public final int errno;

    public ErrnoException(String function, Throwable cause) {
        super(function, cause);
        this.errno = 0;
    }

    public ErrnoException(String function, int errno) {
        super(function + " failed: errno " + errno);
        this.errno = errno;
    }
}
