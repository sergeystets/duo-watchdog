package duowatchdog.core;

import java.time.Instant;

/**
 * What the IDE currently knows of one account: the last good report, and why the latest refresh failed if it did.
 * Without numbers, {@code error} says why.
 */
public record Snapshot(Status status, CreditsReport report, String error, Instant fetchedAt) {

    public enum Status {
        /** No account, or the account has no group path yet. */
        NOT_CONFIGURED,
        /** The account's token is not in the password safe. */
        NO_TOKEN,
        /** GitLab takes the token, but it cannot read the account's Duo usage: it lacks a scope. */
        NOT_TRACKED,
        /** GitLab turned the token down: it expired or was revoked. */
        REJECTED,
        LOADING,
        OK,
        ERROR
    }

    public static Snapshot notConfigured() {
        return new Snapshot(Status.NOT_CONFIGURED, null, null, null);
    }

    public static Snapshot notConfigured(String why) {
        return new Snapshot(Status.NOT_CONFIGURED, null, why, null);
    }

    public static Snapshot noToken(String why) {
        return new Snapshot(Status.NO_TOKEN, null, why, null);
    }

    public static Snapshot notTracked(String why) {
        return new Snapshot(Status.NOT_TRACKED, null, why, null);
    }

    public static Snapshot rejected(String why) {
        return new Snapshot(Status.REJECTED, null, why, null);
    }

    public static Snapshot loading() {
        return new Snapshot(Status.LOADING, null, null, null);
    }

    public static Snapshot ok(CreditsReport report, Instant fetchedAt) {
        return new Snapshot(Status.OK, report, null, fetchedAt);
    }

    /** Keeps the last good report, so a failed refresh shows stale numbers marked as such rather than nothing. */
    public Snapshot failed(String error) {
        return new Snapshot(Status.ERROR, report, error, fetchedAt);
    }
}
