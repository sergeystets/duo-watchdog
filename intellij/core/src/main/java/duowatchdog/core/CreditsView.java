package duowatchdog.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/** The words and numbers the plugin shows: the status bar text, its tooltip, and the dashboard's dates. */
public final class CreditsView {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MOMENT = DateTimeFormatter.ofPattern("MMM d, HH:mm zzz", Locale.ENGLISH);
    private static final DateTimeFormatter MOMENT_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy, HH:mm zzz", Locale.ENGLISH);

    private CreditsView() {
    }

    /** "$21.5 / 300": a credit costs $1 at GitLab's list price. */
    public static String statusText(Snapshot s, double limit) {
        CreditsReport r = s.report();
        if (r == null) {
            return switch (s.status()) {
                case NOT_CONFIGURED -> "Duo: set up";
                case NO_TOKEN -> "Duo: no token";
                case NOT_TRACKED -> "Duo: not tracked";
                case REJECTED -> "Duo: token rejected";
                case LOADING, OK -> "Duo: …";
                case ERROR -> "Duo: error";
            };
        }
        return (r.blocked() ? "Blocked · " : "") + "$" + number(r.creditsUsed()) + " / " + number(limit)
                + (s.status() == Snapshot.Status.ERROR ? " ⚠" : "");
    }

    /** Why an account has no numbers, in a line. */
    public static String reason(Snapshot s) {
        String why = s.error();
        return switch (s.status()) {
            case NOT_CONFIGURED -> why != null ? why
                    : "Not set up yet: add a token in Settings | Tools | Duo Watchdog.";
            case NO_TOKEN -> why != null ? why : "No token saved for this account.";
            case NOT_TRACKED -> why != null ? why : "Usage not tracked: the token lacks the ai_features scope.";
            case REJECTED -> why != null ? why : "GitLab turns the token down: it expired or was revoked.";
            case LOADING, OK -> "Loading…";
            case ERROR -> "Last refresh failed: " + (why != null ? why : "no reason given");
        };
    }

    public static String tooltipHtml(Snapshot s, double limit, LocalDate today, ZoneId zone) {
        StringBuilder html = new StringBuilder("<html><b>Duo Watchdog</b>");
        CreditsReport r = s.report();
        if (r == null) {
            html.append("<br>").append(escape(reason(s)));
        } else {
            BillingPeriod period = BillingPeriod.of(r.startDate(), r.endDate(), today);
            html.append(" — @").append(escape(r.username()))
                    .append("<br>Used ").append(number(r.creditsUsed())).append(" of ").append(number(limit))
                    .append(" (").append(percent(r.creditsUsed(), limit)).append("), left ")
                    .append(number(Math.max(limit - r.creditsUsed(), 0)))
                    .append("<br>Spent today: ").append(number(r.spentOn(today)))
                    .append("<br>Forecast by ").append(day(period.end())).append(": ")
                    .append(number(period.project(r.creditsUsed())));
            if (r.blocked()) {
                html.append("<br><b>Blocked</b> by ").append(capName(r.blockedBy()));
            }
            html.append("<br><i>Updated ").append(TIME.format(s.fetchedAt().atZone(zone))).append("</i>")
                    .append("<br>$ = credits at GitLab's $1 list price");
            if (s.error() != null) {
                html.append("<br>Last refresh failed: ").append(escape(s.error()));
            }
        }
        return html.append("<br>Click for details.</html>").toString();
    }

    /**
     * One account in the status bar tooltip.
     *
     * @param label "@dana.k · gitlab.com · acme"
     * @param shown whether the status bar shows this account
     * @param note  what else to say of it, e.g. that its token expires soon; null for nothing
     */
    public record TooltipAccount(String label, Snapshot snapshot, double limit, boolean shown, String note) {
    }

    /** Every account, in the order given: the one in the status bar in full, the others in a line or two. */
    public static String tooltipHtml(List<TooltipAccount> accounts, LocalDate today, ZoneId zone) {
        if (accounts.size() == 1 && accounts.get(0).note() == null) {
            TooltipAccount only = accounts.get(0);
            return tooltipHtml(only.snapshot(), only.limit(), today, zone);
        }
        StringBuilder html = new StringBuilder("<html><b>Duo Watchdog</b>");
        if (accounts.isEmpty()) {
            html.append("<br>").append(escape(reason(Snapshot.notConfigured())));
        }
        Instant updated = null;
        for (TooltipAccount account : accounts) {
            Snapshot s = account.snapshot();
            CreditsReport r = s.report();
            html.append("<br><br><b>").append(escape(account.label())).append("</b>")
                    .append(account.shown() && accounts.size() > 1 ? " (in the status bar)" : "");
            if (r == null) {
                html.append("<br>").append(escape(reason(s)));
            } else {
                double limit = account.limit();
                html.append("<br>Used ").append(number(r.creditsUsed())).append(" of ").append(number(limit))
                        .append(" (").append(percent(r.creditsUsed(), limit)).append("), left ")
                        .append(number(Math.max(limit - r.creditsUsed(), 0)));
                if (account.shown()) {
                    BillingPeriod period = BillingPeriod.of(r.startDate(), r.endDate(), today);
                    html.append("<br>Spent today: ").append(number(r.spentOn(today)))
                            .append(" · forecast by ").append(day(period.end())).append(": ")
                            .append(number(period.project(r.creditsUsed())));
                }
                if (r.blocked()) {
                    html.append("<br><b>Blocked</b> by ").append(capName(r.blockedBy()));
                }
                if (s.error() != null) {
                    html.append("<br>Last refresh failed: ").append(escape(s.error()));
                }
            }
            if (account.note() != null) {
                html.append("<br>").append(escape(account.note()));
            }
            if (s.fetchedAt() != null && (updated == null || s.fetchedAt().isAfter(updated))) {
                updated = s.fetchedAt();
            }
        }
        if (updated != null) {
            html.append("<br><br><i>Updated ").append(TIME.format(updated.atZone(zone))).append("</i>")
                    .append("<br>$ = credits at GitLab's $1 list price");
        }
        return html.append("<br>Click for details.</html>").toString();
    }

    /** "October 1 – 31 · day 18 of 31"; a period across two months names both. */
    public static String periodText(BillingPeriod period) {
        LocalDate start = period.start();
        LocalDate end = period.end();
        String to = start.getMonth() == end.getMonth() && start.getYear() == end.getYear()
                ? String.valueOf(end.getDayOfMonth())
                : MONTH_DAY.format(end);
        return MONTH_DAY.format(start) + " – " + to + " · day " + Math.min(period.elapsedDays(), period.periodDays())
                + " of " + period.periodDays();
    }

    /** "October", or "September – October" for a period across two months. */
    public static String monthText(BillingPeriod period) {
        String from = MONTH.format(period.start());
        String to = MONTH.format(period.end());
        return from.equals(to) ? from : from + " – " + to;
    }

    /** "Oct 1, 14:38 EEST", with the year when it is not this one: when the token was saved. */
    public static String moment(Instant at, ZoneId zone, LocalDate today) {
        ZonedDateTime local = at.atZone(zone);
        return (local.getYear() == today.getYear() ? MOMENT : MOMENT_YEAR).format(local);
    }

    /**
     * "Expires on Dec 31, 2026, in 62 days", "Expires tomorrow", "Expired on Oct 3, 2026" or "Never expires". GitLab
     * stops taking a token at the start of its expiry day.
     */
    public static String expiry(LocalDate expiresAt, LocalDate today) {
        if (expiresAt == null) {
            return "Never expires";
        }
        long days = ChronoUnit.DAYS.between(today, expiresAt);
        if (days <= 0) {
            return "Expired on " + DATE.format(expiresAt);
        }
        if (days == 1) {
            return "Expires tomorrow, " + DATE.format(expiresAt);
        }
        return "Expires on " + DATE.format(expiresAt) + ", in " + days + " days";
    }

    /**
     * What the token box says of its rotation, or null when nothing: rotating is off, or the token never expires or
     * has expired. "Rotates on Nov 27, 2026, a week before it expires", "Rotates at the next refresh…", or why it
     * won't. rotationDetail in the VS Code extension says the same, shorter, in its Accounts list.
     *
     * @param refused GitLab refused to rotate this token before
     */
    public static String rotation(boolean on, LocalDate expiresAt, List<String> scopes, boolean refused, LocalDate today) {
        if (!on || expiresAt == null || !expiresAt.isAfter(today)) {
            return null;
        }
        if (!Accounts.canRotate(scopes)) {
            return "Doesn't rotate itself: it has neither the api nor the self_rotate scope.";
        }
        if (refused) {
            return "GitLab would not rotate it: replace it before it expires.";
        }
        if (Accounts.rotationDue(expiresAt, scopes, today)) {
            return "Rotates at the next refresh while the IDE is focused.";
        }
        return "Rotates on " + DATE.format(expiresAt.minusDays(Accounts.ROTATE_DAYS)) + ", a week before it expires.";
    }

    /** "Oct 31". */
    public static String day(LocalDate date) {
        return DAY.format(date);
    }

    public static String capName(String capType) {
        if ("FLAT_USER_CAP".equals(capType)) {
            return "GitLab's default per-user cap";
        }
        if ("USER_OVERRIDE_CAP".equals(capType)) {
            return "your personal GitLab cap";
        }
        return "a GitLab cap";
    }

    private static String percent(double used, double limit) {
        return limit > 0 ? Math.round(used / limit * 100) + "%" : "no limit";
    }

    /** Whole numbers bare, the rest to one decimal: 100, 42.4. */
    public static String number(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded)
                ? String.format(Locale.ROOT, "%.0f", rounded)
                : String.format(Locale.ROOT, "%.1f", rounded);
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
