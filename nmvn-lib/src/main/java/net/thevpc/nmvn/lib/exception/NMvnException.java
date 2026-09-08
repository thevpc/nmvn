package net.thevpc.nmvn.lib.exception;

public class NMvnException extends RuntimeException {
    public NMvnException(String message) {
        super(message);
    }

    public NMvnException(String message, Throwable cause) {
        super(message, cause);
    }
}
