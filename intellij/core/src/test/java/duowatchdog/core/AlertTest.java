package duowatchdog.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static duowatchdog.core.CreditsViewTest.TODAY;
import static duowatchdog.core.CreditsViewTest.report;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertTest {

    private static final List<Integer> DEFAULT = Alert.parsePercents(Alert.DEFAULT_PERCENTS);

    private static Optional<Alert> alert(double used, double limit, boolean forecast) {
        return Alert.of(report(used, false), limit, DEFAULT, forecast, TODAY);
    }

    @Test
    void quietWhileUnderEveryThresholdAndOnPace() {
        // 42 by the 18th projects to 72: under the limit.
        assertTrue(alert(42, 100, true).isEmpty());
    }

    @Test
    void theForecastWarnsBeforeAnyThreshold() {
        // 72 by the 18th projects to 124.
        Alert a = alert(72, 100, true).orElseThrow();
        assertEquals(Alert.Kind.FORECAST, a.kind());
        assertEquals("forecast|2026-10-01|100", a.key());
        assertTrue(a.message().contains("At this pace: 124 by Oct 31, the limit is reached around Oct 25."), a.message());

        assertTrue(alert(72, 100, false).isEmpty(), "the forecast notice can be turned off");
    }

    @Test
    void theHighestCrossedThresholdWins() {
        Alert at80 = alert(80, 100, true).orElseThrow();
        assertEquals(Alert.Kind.THRESHOLD, at80.kind());
        assertEquals("threshold|2026-10-01|100|80", at80.key());
        assertEquals("Duo credits: 80% of your limit", at80.title());
        assertEquals("Used 80 of 100. At this pace: 137.8 by Oct 31, the limit is reached around Oct 23.", at80.message());

        Alert at95 = alert(96, 100, true).orElseThrow();
        assertEquals("threshold|2026-10-01|100|95", at95.key());
    }

    @Test
    void reachingTheLimitAndBeingBlockedAreTheirOwnNotices() {
        Alert reached = alert(100, 100, true).orElseThrow();
        assertEquals(Alert.Kind.LIMIT, reached.kind());
        assertEquals("limit|2026-10-01|100", reached.key());

        Alert blocked = Alert.of(report(100, true), 100, DEFAULT, true, TODAY).orElseThrow();
        assertEquals(Alert.Kind.BLOCKED, blocked.kind());
        assertEquals("blocked|2026-10-01", blocked.key());
    }

    @Test
    void anotherLimitIsAnotherKey() {
        assertNotEquals(alert(80, 100, true).orElseThrow().key(), alert(240, 300, true).orElseThrow().key());
    }

    @Test
    void parsesThresholds() {
        assertEquals(List.of(50, 80, 95), Alert.parsePercents("95, 50 80%, 80"));
        assertEquals(List.of(), Alert.parsePercents("  "));
        assertThrows(IllegalArgumentException.class, () -> Alert.parsePercents("80, lots"));
        assertThrows(IllegalArgumentException.class, () -> Alert.parsePercents("0"));
        assertThrows(IllegalArgumentException.class, () -> Alert.parsePercents("120"));
    }
}
