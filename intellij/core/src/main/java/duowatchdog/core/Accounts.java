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
    /** A token this close to its expiry rotates, when rotating is on: before the popup about it is due. */
    public static final int ROTATE_DAYS = EXPIRY_WARNING_DAYS;
    /**
     * A rotated token lives as long as the one it replaces, within these bounds: long enough not to be due again at
     * once, and no longer than GitLab allows by default.
     */
    public static final int MIN_ROTATED_DAYS = 30;
    public static final int MAX_ROTATED_DAYS = 365;
    /** What glab needs of a token, besides what Duo Watchdog does. */
    public static final List<String> GLAB_SCOPES = List.of("api", "write_repository");
    /** What the GitLab Duo CLI needs of the token it gets through glab: read_api is not enough for it. */
    public static final List<String> DUO_CLI_SCOPES = List.of("api", "ai_features");

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

    /** Whether GitLab lets the token rotate itself: api or self_rotate. Unknown scopes count as yes; a 403 says no. */
    public static boolean canRotate(List<String> scopes) {
        return scopes == null || scopes.contains("api") || scopes.contains("self_rotate");
    }

    /**
     * Whether a token is due to rotate: it expires within {@link #ROTATE_DAYS} and may rotate itself. The same rule
     * as rotationDue in the VS Code extension, which also checks there that GitLab did not refuse it before.
     */
    public static boolean rotationDue(LocalDate expiresAt, List<String> scopes, LocalDate today) {
        return expiresWithin(expiresAt, today, ROTATE_DAYS) && canRotate(scopes);
    }

    /**
     * The expiry day of a rotated token: as many days from today as the old token lived, from {@code createdOn} to
     * {@code expiresAt}, within {@link #MIN_ROTATED_DAYS} and {@link #MAX_ROTATED_DAYS}; the least when its age is
     * unknown.
     */
    public static LocalDate rotatedExpiry(LocalDate createdOn, LocalDate expiresAt, LocalDate today) {
        long lived = createdOn == null ? MIN_ROTATED_DAYS : ChronoUnit.DAYS.between(createdOn, expiresAt);
        return today.plusDays(Math.min(Math.max(lived, MIN_ROTATED_DAYS), MAX_ROTATED_DAYS));
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
     * What the Duo CLI cannot do with a token, or null when it can (or the scopes are unknown): without api and
     * ai_features its GitLab Duo access shows as unavailable, even where the group has Duo.
     */
    public static String duoScopeNote(List<String> scopes) {
        if (scopes == null) {
            return null;
        }
        List<String> missing = DUO_CLI_SCOPES.stream().filter(scope -> !scopes.contains(scope)).toList();
        return missing.isEmpty() ? null : "The Duo CLI needs the api and ai_features scopes; this token lacks "
                + String.join(" and ", missing) + ", so its GitLab Duo access shows as unavailable.";
    }

    /**
     * Which account glab should have for one GitLab, among those allowed to hand their token to it: the one picked in
     * the tool window, else the one glab has now, else the first. Null when none is allowed.
     *
     * @param allowed the accounts on that GitLab with glab or the Duo CLI on, in the list's order
     * @param picked  the account picked in the tool window, or null
     * @param hasGlab whether glab has an account's current token
     */
    public static <T> T glabOwner(List<T> allowed, T picked, java.util.function.Predicate<T> hasGlab) {
        if (allowed.isEmpty()) {
            return null;
        }
        if (picked != null && allowed.contains(picked)) {
            return picked;
        }
        return allowed.stream().filter(hasGlab).findFirst().orElse(allowed.get(0));
    }

    /**
     * What a token's scopes leave glab unable to do, or null when nothing (or the scopes are unknown). Without api
     * glab can only read; without write_repository it does all it does through the API, but cannot push over HTTPS,
     * which matters only when Git runs over HTTPS rather than SSH.
     */
    public static String glabScopeNote(List<String> scopes) {
        List<String> missing = missingForGlab(scopes);
        if (missing.isEmpty()) {
            return null;
        }
        if (missing.contains("api")) {
            return "The token lacks the api scope, so glab can only read with it"
                    + (missing.contains("write_repository") ? ", and can't git push over HTTPS." : ".");
        }
        return "The token lacks write_repository: glab works with the API, but can't git push over HTTPS. Over SSH it can.";
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
