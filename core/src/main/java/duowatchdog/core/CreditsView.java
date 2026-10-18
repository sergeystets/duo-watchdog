package duowatchdog.core;

import duowatchdog.core.CreditsReport.DailyUsage;
import duowatchdog.core.CreditsReport.FlowUsage;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Every word the plugin shows: the status bar text, its tooltip and the tool window page. */
public final class CreditsView {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int DAYS_SHOWN = 14;

    private CreditsView() {
    }

    public static String statusText(Snapshot s, double limit) {
        CreditsReport r = s.report();
        if (r == null) {
            return switch (s.status()) {
                case NOT_CONFIGURED -> "Duo: set up";
                case LOADING, OK -> "Duo: …";
                case ERROR -> "Duo: error";
            };
        }
        return (r.blocked() ? "Duo blocked " : "Duo ") + number(r.creditsUsed()) + "/" + number(limit)
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
                    .append("<br>Projected by ").append(period.end()).append(": ")
                    .append(number(period.project(r.creditsUsed())));
            if (r.blocked()) {
                html.append("<br><b>Blocked</b> by ").append(capName(r.blockedBy()));
            }
            html.append("<br><i>Updated ").append(TIME.format(s.fetchedAt().atZone(zone))).append("</i>");
        }
        if (s.error() != null) {
            html.append("<br>Last refresh failed: ").append(escape(s.error()));
        }
        return html.append("<br>Click for details.</html>").toString();
    }

    public static String detailsHtml(Snapshot s, double limit, LocalDate today, ZoneId zone) {
        StringBuilder html = new StringBuilder("<html><body>");
        CreditsReport r = s.report();
        if (s.status() == Snapshot.Status.NOT_CONFIGURED) {
            html.append("<p>Not set up yet. Open <b>Settings | Tools | Duo Watchdog</b> and enter the GitLab URL, ")
                    .append("the top-level group that owns the Duo subscription, and a personal access token with the ")
                    .append("<code>read_api</code> and <code>ai_features</code> scopes.</p>");
        } else if (r == null && s.status() != Snapshot.Status.ERROR) {
            html.append("<p>Loading…</p>");
        }
        if (r != null) {
            appendSummary(html, r, limit, today);
            appendFlows(html, r.flows());
            appendDaily(html, r.daily());
            html.append("<p><i>Updated ").append(TIME.format(s.fetchedAt().atZone(zone)))
                    .append(". GitLab's usage data can lag actual use. The limit is the one set in Duo Watchdog's ")
                    .append("settings: GitLab shows a user's cap only to the group owner.</i></p>");
        }
        if (s.error() != null) {
            html.append("<p><b>Last refresh failed:</b> ").append(escape(s.error())).append("</p>");
        }
        return html.append("</body></html>").toString();
    }

    private static void appendSummary(StringBuilder html, CreditsReport r, double limit, LocalDate today) {
        BillingPeriod period = BillingPeriod.of(r.startDate(), r.endDate(), today);
        double used = r.creditsUsed();
        html.append("<table>");
        row(html, "User", "@" + escape(r.username())
                + (r.source() == CreditsReport.Source.OWNER ? " (read through the owner's view)" : ""));
        row(html, "Period", period.start() + " – " + period.end() + ", day " + period.elapsedDays()
                + " of " + period.periodDays());
        row(html, "Used", "<b>" + number(used) + "</b> of " + number(limit) + " (" + percent(used, limit) + ")");
        row(html, "Left", number(Math.max(limit - used, 0)));
        row(html, "Projected", number(period.project(used)) + " by " + period.end());
        row(html, "Pace", number(period.pace(used)) + " a day" + limitForecast(used, limit, period, today));
        row(html, "Status", r.blocked() ? "<b>Blocked</b> by " + capName(r.blockedBy()) : "Active");
        html.append("</table>");
    }

    private static String limitForecast(double used, double limit, BillingPeriod period, LocalDate today) {
        if (limit > 0 && used >= limit) {
            return "; the limit is reached";
        }
        LocalDate day = period.reachDate(used, limit, today);
        return day != null ? "; at this pace the limit is reached around " + day : "";
    }

    private static void appendFlows(StringBuilder html, List<FlowUsage> flows) {
        if (flows.isEmpty()) {
            return;
        }
        html.append("<h4>By feature</h4><table>");
        flows.stream()
                .sorted(Comparator.comparing((FlowUsage f) -> f.credits() == null ? -1 : f.credits()).reversed())
                .forEach(f -> row(html, escape(f.title()), f.credits() == null ? "—" : number(f.credits())));
        html.append("</table>");
    }

    private static void appendDaily(StringBuilder html, List<DailyUsage> daily) {
        if (daily.isEmpty()) {
            return;
        }
        html.append("<h4>Last days</h4><table>");
        daily.stream()
                .sorted(Comparator.comparing(DailyUsage::date).reversed())
                .limit(DAYS_SHOWN)
                .forEach(d -> row(html, d.date().toString(), number(d.credits())));
        html.append("</table>");
    }

    private static void row(StringBuilder html, String label, String value) {
        html.append("<tr><td>").append(label).append("&nbsp;&nbsp;</td><td>").append(value).append("</td></tr>");
    }

    private static String capName(String capType) {
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
    static String number(double value) {
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
