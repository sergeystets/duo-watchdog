package duowatchdog.core;

import duowatchdog.core.CreditsReport.DailyUsage;
import duowatchdog.core.CreditsReport.FlowUsage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditsViewTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 18);
    private static final Instant AT = Instant.parse("2026-10-18T14:05:00Z");

    static CreditsReport report(double used, boolean blocked) {
        return new CreditsReport("alice", CreditsReport.Source.SELF,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), used, blocked,
                blocked ? "FLAT_USER_CAP" : null,
                List.of(new DailyUsage(LocalDate.of(2026, 10, 17), 3.5), new DailyUsage(LocalDate.of(2026, 10, 18), 1.25)),
                List.of(new FlowUsage("chat", "Duo Chat", 12.0), new FlowUsage("review", "Code <Review>", 30.0)));
    }

    @Test
    void billingPeriodProjectsTheRunRate() {
        BillingPeriod p = BillingPeriod.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), TODAY);
        assertEquals(18, p.elapsedDays());
        assertEquals(31, p.periodDays());
        assertEquals(62.0, p.project(36), 1e-9);

        BillingPeriod unknown = BillingPeriod.of(null, null, TODAY);
        assertEquals(LocalDate.of(2026, 10, 1), unknown.start());
        assertEquals(LocalDate.of(2026, 10, 31), unknown.end());

        BillingPeriod stale = BillingPeriod.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), TODAY);
        assertEquals(stale.elapsedDays(), stale.periodDays(), "a period already over projects to what was used");
        assertEquals(90.0, stale.project(90), 1e-9);
    }

    @Test
    void reachDateIsWithinThePeriodOrAbsent() {
        BillingPeriod p = BillingPeriod.of(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), TODAY);
        // 4 a day, 28 left: seven days from the 18th.
        assertEquals(LocalDate.of(2026, 10, 25), p.reachDate(72, 100, TODAY));
        assertNull(p.reachDate(42, 100, TODAY), "the pace does not reach the limit by the 31st");
        assertNull(p.reachDate(100, 100, TODAY), "already reached");
        assertNull(p.reachDate(0, 100, TODAY), "no pace");
    }

    @Test
    void statusTextShowsUsedOverLimit() {
        assertEquals("Duo 42.4/100", CreditsView.statusText(Snapshot.ok(report(42.37, false), AT), 100));
        assertEquals("Duo blocked 100/100", CreditsView.statusText(Snapshot.ok(report(100, true), AT), 100));
        assertEquals("Duo 42.4/300 ⚠", CreditsView.statusText(Snapshot.ok(report(42.37, false), AT).failed("x"), 300));
        assertEquals("Duo: set up", CreditsView.statusText(Snapshot.notConfigured(), 100));
        assertEquals("Duo: error", CreditsView.statusText(Snapshot.loading().failed("x"), 100));
    }

    @Test
    void detailsShowProjectionPaceAndForecast() {
        String html = CreditsView.detailsHtml(Snapshot.ok(report(72, false), AT), 100, TODAY, ZoneOffset.UTC);

        assertTrue(html.contains("<b>72</b> of 100 (72%)"), html);
        assertTrue(html.contains("124 by 2026-10-31"), html);
        assertTrue(html.contains("day 18 of 31"), html);
        assertTrue(html.contains("4 a day; at this pace the limit is reached around 2026-10-25"), html);
        assertTrue(html.contains("Updated 2026-10-18 14:05"), html);
    }

    @Test
    void detailsListFeaturesByCreditsAndEscapeGitLabText() {
        String html = CreditsView.detailsHtml(Snapshot.ok(report(42, false), AT), 100, TODAY, ZoneOffset.UTC);

        assertTrue(html.contains("Code &lt;Review&gt;"), html);
        assertFalse(html.contains("<Review>"), html);
        assertTrue(html.indexOf("Code &lt;Review&gt;") < html.indexOf("Duo Chat"), "the costlier feature first");
        assertTrue(html.indexOf("2026-10-18") < html.indexOf("2026-10-17"), "newest day first");
        assertFalse(html.contains("at this pace"), "no forecast when the projection stays under the limit");
    }

    @Test
    void aFailedRefreshKeepsTheNumbersAndSaysWhy() {
        Snapshot s = Snapshot.ok(report(42, false), AT).failed("Cannot reach gitlab.com: <timeout>");

        String html = CreditsView.detailsHtml(s, 100, TODAY, ZoneOffset.UTC);
        String tip = CreditsView.tooltipHtml(s, 100, TODAY, ZoneOffset.UTC);

        assertTrue(html.contains("<b>42</b> of 100"), html);
        assertTrue(html.contains("Last refresh failed:</b> Cannot reach gitlab.com: &lt;timeout&gt;"), html);
        assertTrue(tip.contains("Last refresh failed: Cannot reach gitlab.com"), tip);
    }

    @Test
    void numbersAreWholeOrOneDecimal() {
        assertEquals("100", CreditsView.number(100));
        assertEquals("42.4", CreditsView.number(42.37));
        assertEquals("0", CreditsView.number(0.04));
        assertEquals("1.3", CreditsView.number(1.25));
    }
}
