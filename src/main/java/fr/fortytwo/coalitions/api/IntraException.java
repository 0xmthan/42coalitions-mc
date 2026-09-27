package fr.fortytwo.coalitions.api;

/** A call to the intra failed. {@link #status()} is 0 for transport errors. */
public class IntraException extends Exception {

    private final int status;

    public IntraException(String message, int status) {
        super(message);
        this.status = status;
    }

    public IntraException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    public int status() {
        return status;
    }

    /** True when the intra has no such resource -- an unknown login, usually. */
    public boolean notFound() {
        return status == 404;
    }
}
