package duowatchdog.core;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** The words and numbers the plugin shows: the status bar text, its tooltip, and the dashboard's dates. */
public final class CreditsView {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMMM d", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.ENGLISH);

    private CreditsView() {
    }

    /** "$21.5 / 300": a credit costs $1 at GitLab's list price. */
    public static String statusText(Snapshot s, double limit) {
        CreditsReport r = s.report();
        if (r == null) {
            return switch (s.status()) {
                case NOT_CONFIGURED -> "Duo: set up";
                case LOADING, OK -> "Duo: …";
                case ERROR -> "Duo: error";
            };
        }
        return (r.blocked() ? "Blocked · " : "") + "$" + number(r.creditsUsed()) + " / " + number(limit)
                + (s.status() == Snapshot.Status.ERROR ? " ⚠" : "");
    }

    public static String tooltipHtml(Snapshot s, double limit, LocalDate today, ZoneId zone) {
        StringBuilder html = new StringBuilder("<html><b>Duo Watchdog</b>");
        CreditsReport r = s.report();
        if (s.status() == Snapshot.Status.NOT_CONFIGURED) {
            html.append("<br>Set the GitLab URL, group and token in Settings | Tools | Duo Watchdog.");
        } else if (r == null && s.status() != Snapshot.Status.ERROR) {
            html.append("<br>Loading…");
        }
        if (r != null) {
            BillingPeriod period = BillingPeriod.of(r.startDate(), r.endDate(), today);
            html.append(" — @").append(escape(r.username()))
                    .append("<br>Used ").append(number(r.creditsUsed())).append(" of ").append(number(limit))
                    .append(" (").append(percent(r.creditsUsed(), limit)).append("), left ")
                    .append(number(Math.max(limit - r.creditsUsed(), 0)))
                    .append("<br>Forecast by ").append(day(period.end())).append(": ")
                    .append(number(period.project(r.creditsUsed())));
            if (r.blocked()) {
                html.append("<br><b>Blocked</b> by ").append(capName(r.blockedBy()));
            }
            html.append("<br><i>Updated ").append(TIME.format(s.fetchedAt().atZone(zone))).append("</i>")
                    .append("<br>$ = credits at GitLab's $1 list price");
        }
        if (s.error() != null) {
            html.append("<br>Last refresh failed: ").append(escape(s.error()));
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
