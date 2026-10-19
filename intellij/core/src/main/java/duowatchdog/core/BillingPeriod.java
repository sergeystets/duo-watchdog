package duowatchdog.core;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The billing period and its run-rate projection,
 * {@code used / elapsed_days * period_days}.
 *
 * <p>Note: GitLab's {@code endDate} is the last day it has data
 * for — today, mid-period — not the end of the period. Taken as the end, the projection would equal what was used.
 * A monthly period runs a month from its start, so a shorter end is stretched to that.
 */
public record BillingPeriod(LocalDate start, LocalDate end, int elapsedDays, int periodDays) {

    public static BillingPeriod of(LocalDate start, LocalDate end, LocalDate today) {
        LocalDate from = start != null ? start : today.withDayOfMonth(1);
        LocalDate monthEnd = from.plusMonths(1).minusDays(1);
        LocalDate to = end != null && end.isAfter(monthEnd) ? end : monthEnd;
        int elapsed = (int) Math.max(ChronoUnit.DAYS.between(from, today) + 1, 1);
        int period = (int) Math.max(ChronoUnit.DAYS.between(from, to) + 1, elapsed);
        return new BillingPeriod(from, to, elapsed, period);
    }

    public double project(double used) {
        return used / Math.max(elapsedDays, 1) * Math.max(periodDays, 1);
    }

    public double pace(double used) {
        return used / Math.max(elapsedDays, 1);
    }

    /** The day the run rate reaches {@code limit}; null when it already has, or will not before the period ends. */
    public LocalDate reachDate(double used, double limit, LocalDate today) {
        double pace = pace(used);
        if (limit <= 0 || used >= limit || pace <= 0) {
            return null;
        }
        LocalDate day = today.plusDays((long) Math.ceil((limit - used) / pace));
        return day.isAfter(end) ? null : day;
    }
}
