package duowatchdog.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The rules every list of accounts follows, in the settings, the tool window and the status bar: who an account
 * is, its credits left, their order, the filters, and when an account needs a look.
 */
public final class Accounts {

    /** A token this close to its expiry raises a popup, once. */
    public static final int EXPIRY_POPUP_DAYS = 3;
    /** A token this close to its expiry turns amber. */
    public static final int EXPIRY_WARNING_DAYS = 7;
    /** What glab needs of a token, besides what Duo Watchdog does. */
    public static final List<String> GLAB_SCOPES = List.of("api", "write_repository");

    public enum Attention { NONE, WARNING, ERROR }

    private Accounts() {
    }

    /**
     * "gitlab.com/dana.k": an account is one GitLab user on one GitLab, so the same user on two GitLabs is two
     * accounts, and a token for an account already listed replaces its token.
     */
    public static String id(String gitlabUrl, String username) {
        String host = GitLabAddress.host(gitlabUrl);
        if (host == null) {
            // An address without a host still names the account, as the VS Code extension does.
            host = gitlabUrl == null ? "" : gitlabUrl.trim().replaceAll("/+$", "");
        }
        String user = username == null ? "" : username.trim().replaceFirst("^@+", "");
        return host.toLowerCase(Locale.ROOT) + "/" + user;
    }

    /** The credits left of the limit, or null while there are no numbers. */
    public static Double left(Snapshot snapshot, double limit) {
        CreditsReport report = snapshot == null ? null : snapshot.report();
        return report == null ? null : Math.max(limit - report.creditsUsed(), 0);
    }

    /** Most credits left first, accounts without numbers last; then by user and GitLab. */
    public static <T> Comparator<T> byCreditsLeft(Function<T, Double> left, Function<T, String> user,
                                                  Function<T, String> host) {
        Comparator<T> credits = Comparator.comparing(left, Comparator.nullsLast(Comparator.reverseOrder()));
        return credits.thenComparing(user, String.CASE_INSENSITIVE_ORDER).thenComparing(host, String.CASE_INSENSITIVE_ORDER);
    }

    /** The "Has credits" filter. */
    public static boolean hasCredits(Double left) {
        return left != null && left > 0;
    }

    /** The "Expired" filter: GitLab turned the token down, or its expiry day has come. */
    public static boolean expired(LocalDate expiresAt, boolean rejected, LocalDate today) {
        return rejected || expiresAt != null && !expiresAt.isAfter(today);
    }

    /** Whether the token expires in one to {@code days} days from today. */
    public static boolean expiresWithin(LocalDate expiresAt, LocalDate today, int days) {
        if (expiresAt == null) {
            return false;
        }
        long left = ChronoUnit.DAYS.between(today, expiresAt);
        return left > 0 && left <= days;
    }

    /**
     * Whether GitLab shows the token its owner's Duo usage: it needs ai_features, and read_api or api. Unknown
     * scopes count as yes, so the token is tried.
     */
    public static boolean readsUsage(List<String> scopes) {
        return scopes == null
                || scopes.contains("ai_features") && (scopes.contains("read_api") || scopes.contains("api"));
    }

    /** The scopes glab needs that the token lacks; none when its scopes are unknown. */
    public static List<String> missingForGlab(List<String> scopes) {
        return scopes == null ? List.of() : GLAB_SCOPES.stream().filter(scope -> !scopes.contains(scope)).toList();
    }

    /**
     * Whether an account needs a look: usage at the lowest alert percent, a block, a token without a saved copy,
     * turned down, expired or expiring within a week. An account whose usage cannot be read needs none: that is
     * how it was added.
     */
    public static Attention attention(Snapshot snapshot, double limit, List<Integer> percents, LocalDate expiresAt,
                                      LocalDate today) {
        Snapshot.Status status = snapshot == null ? Snapshot.Status.LOADING : snapshot.status();
        if (status == Snapshot.Status.REJECTED || expired(expiresAt, false, today)) {
            return Attention.ERROR;
        }
        CreditsReport report = snapshot == null ? null : snapshot.report();
        if (report != null && report.blocked()) {
            return Attention.ERROR;
        }
        if (status == Snapshot.Status.NO_TOKEN || expiresWithin(expiresAt, today, EXPIRY_WARNING_DAYS)) {
            return Attention.WARNING;
        }
        double warnAt = percents.isEmpty() ? 100 : percents.get(0);
        if (report != null && limit > 0 && report.creditsUsed() >= limit * warnAt / 100.0) {
            return Attention.WARNING;
        }
        return Attention.NONE;
    }
}
