package duowatchdog.core;

import java.time.LocalDate;
import java.util.List;

/**
 * One user's GitLab Credits for the billing period GitLab reports.
 *
 * @param blockedBy  GitLab's cap type when blocked ({@code FLAT_USER_CAP}, {@code USER_OVERRIDE_CAP}), else null
 * @param daily      per-day usage
 * @param flowsError why the per-feature credits are missing when GitLab did not give them, else null
 */
public record CreditsReport(
        String username,
        LocalDate startDate,
        LocalDate endDate,
        double creditsUsed,
        boolean blocked,
        String blockedBy,
        List<DailyUsage> daily,
        List<FlowUsage> flows,
        String flowsError) {

    public record DailyUsage(LocalDate date, double credits) {
    }

    /** @param credits null when the per-feature figure was not fetched */
    public record FlowUsage(String id, String title, Double credits) {
    }

    public CreditsReport withFlows(List<FlowUsage> flows, String flowsError) {
        return new CreditsReport(username, startDate, endDate, creditsUsed, blocked, blockedBy, daily, flows, flowsError);
    }
}
