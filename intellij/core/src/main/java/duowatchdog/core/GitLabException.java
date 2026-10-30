package duowatchdog.core;

/** A failure worth showing to the user as it is: the message never carries the token. */
public final class GitLabException extends Exception {

    private final int status;

    public GitLabException(String message) {
        this(message, 0);
    }

    /** @param status GitLab's HTTP status, e.g. 401 when it turned the token down */
    public GitLabException(String message, int status) {
        super(message);
        this.status = status;
    }

    public GitLabException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    /** GitLab's HTTP status, or 0 when the request did not get that far or was answered. */
    public int status() {
        return status;
    }
}
