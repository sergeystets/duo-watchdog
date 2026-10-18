package duowatchdog.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The notice a refresh raises: the most severe one that holds now.
 *
 * <p>Within a period usage only grows, so the notices come in order — forecast, each threshold, the limit, GitLab's
 * block — and each has its own key. The plugin remembers the last key it showed, so every notice appears once a
 * period, and again only after the limit is changed.
 */
public record Alert(String key, Kind kind, String title, String message) {

    public enum Kind { FORECAST, THRESHOLD, LIMIT, BLOCKED }

    public static final String DEFAULT_PERCENTS = "80, 95";

    /**
     * @param percents ascending thresholds, as {@link #parsePercents} returns them
     * @param forecast whether to warn when the month-end projection passes the limit before any threshold is crossed
     */
    public static Optional<Alert> of(CreditsReport report, double limit, List<Integer> percents, boolean forecast,
                                     LocalDate today) {
        String period = String.valueOf(report.startDate());
        double used = report.creditsUsed();
        if (report.blocked()) {
            return Optional.of(new Alert("blocked|" + period, Kind.BLOCKED, "GitLab Duo is blocked",
                    "GitLab stopped your Duo requests: your credits cap for this period is reached ("
                            + CreditsView.number(used) + " used). Ask the group owner to raise it."));
        }
        if (limit <= 0) {
            return Optional.empty();
        }
        String scope = period + "|" + CreditsView.number(limit);
        BillingPeriod billing = BillingPeriod.of(report.startDate(), report.endDate(), today);
        LocalDate reach = billing.reachDate(used, limit, today);
        String pace = " At this pace: " + CreditsView.number(billing.project(used)) + " by " + CreditsView.day(billing.end())
                + (reach != null ? ", the limit is reached around " + CreditsView.day(reach) : "") + ".";
        if (used >= limit) {
            return Optional.of(new Alert("limit|" + scope, Kind.LIMIT, "Duo credits: limit reached",
                    "Used " + CreditsView.number(used) + " of your " + CreditsView.number(limit) + "-credit limit."));
        }
        for (int i = percents.size() - 1; i >= 0; i--) {
            int percent = percents.get(i);
            if (used >= limit * percent / 100.0) {
                return Optional.of(new Alert("threshold|" + scope + "|" + percent, Kind.THRESHOLD,
                        "Duo credits: " + percent + "% of your limit",
                        "Used " + CreditsView.number(used) + " of " + CreditsView.number(limit) + "." + pace));
            }
        }
        if (forecast && billing.project(used) > limit) {
            return Optional.of(new Alert("forecast|" + scope, Kind.FORECAST, "Duo credits: on pace to pass your limit",
                    "Used " + CreditsView.number(used) + " of " + CreditsView.number(limit) + " so far." + pace));
        }
        return Optional.empty();
    }

    /** "80, 95" to [80, 95]: whole percents from 1 to 100, sorted, repeats dropped; blank means no thresholds. */
    public static List<Integer> parsePercents(String text) {
        TreeSet<Integer> percents = new TreeSet<>();
        for (String part : (text == null ? "" : text).split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            String digits = part.endsWith("%") ? part.substring(0, part.length() - 1) : part;
            int percent;
            try {
                percent = Integer.parseInt(digits);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a whole percent: " + part);
            }
            if (percent < 1 || percent > 100) {
                throw new IllegalArgumentException("A percent must be from 1 to 100: " + part);
            }
            percents.add(percent);
        }
        return new ArrayList<>(percents);
    }
}
