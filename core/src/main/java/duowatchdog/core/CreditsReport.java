package duowatchdog.core;

import java.time.LocalDate;
import java.util.List;

/**
 * One user's GitLab Credits for the billing period GitLab reports.
 *
 * @param source    where the numbers came from: the user's own view, or the namespace owner's per-user view
 * @param blockedBy GitLab's cap type when blocked ({@code FLAT_USER_CAP}, {@code USER_OVERRIDE_CAP}), else null
 * @param daily     per-day usage; only the user's own view has it
 */
public record CreditsReport(
        String username,
        Source source,
        LocalDate startDate,
        LocalDate endDate,
        double creditsUsed,
        boolean blocked,
        String blockedBy,
        List<DailyUsage> daily,
        List<FlowUsage> flows) {

    public enum Source { SELF, OWNER }

    public record DailyUsage(LocalDate date, double credits) {
    }

    /** @param credits null when the per-feature figure was not fetched */
    public record FlowUsage(String id, String title, Double credits) {
    }

    public CreditsReport withFlows(List<FlowUsage> flows) {
        return new CreditsReport(username, source, startDate, endDate, creditsUsed, blocked, blockedBy, daily, flows);
    }
}
