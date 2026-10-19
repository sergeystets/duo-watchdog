package duowatchdog.core;

/** A failure worth showing to the user as it is: the message never carries the token. */
public final class GitLabException extends Exception {

    public GitLabException(String message) {
        super(message);
    }

    public GitLabException(String message, Throwable cause) {
        super(message, cause);
    }
}
