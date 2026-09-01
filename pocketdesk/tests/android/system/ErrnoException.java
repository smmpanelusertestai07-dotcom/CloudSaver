package android.system;

public class ErrnoException extends Exception {
    public ErrnoException(String function, Throwable cause) {
        super(function, cause);
    }
}
