package duowatchdog.core;

import java.time.Instant;

/** What the IDE currently knows: the last good report, and why the latest refresh failed if it did. */
public record Snapshot(Status status, CreditsReport report, String error, Instant fetchedAt) {

    public enum Status { NOT_CONFIGURED, LOADING, OK, ERROR }

    public static Snapshot notConfigured() {
        return new Snapshot(Status.NOT_CONFIGURED, null, null, null);
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
