package duowatchdog.core;

import duowatchdog.core.CreditsReport.DailyUsage;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCacheTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 3);

    @Test
    void aRangeIsThisMonthAndTheOnesBefore() {
        assertEquals(List.of(YearMonth.of(2026, 11), YearMonth.of(2026, 10), YearMonth.of(2026, 9)),
                History.monthsFor(3, TODAY));
        assertEquals(3, History.RANGES.get(0), "three months by default");
    }

    @Test
    void aMonthSettlesTwoDaysAfterItEnds() {
        assertFalse(History.settled(YearMonth.of(2026, 11), TODAY), "this month");
        assertFalse(History.settled(YearMonth.of(2026, 10), LocalDate.of(2026, 11, 1)), "a day after: still lagging");
        assertTrue(History.settled(YearMonth.of(2026, 10), LocalDate.of(2026, 11, 2)));
        assertTrue(History.settled(YearMonth.of(2026, 9), TODAY));
    }

    @Test
    void aMonthReadsBackAsItWasWritten() {
        History month = new History(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                List.of(new DailyUsage(LocalDate.of(2026, 9, 10), 7.25)),
                List.of(new History.FlowHistory("chat", "Agentic Chat", List.of(new DailyUsage(LocalDate.of(2026, 9, 10), 7.25)))),
                List.of(new DailyUsage(LocalDate.of(2026, 9, 11), 1.5)), null);

        History back = HistoryJson.read(HistoryJson.write(month));

        assertEquals(month, back);
        assertEquals(History.none(YearMonth.of(2026, 3)), HistoryJson.read(HistoryJson.write(History.none(YearMonth.of(2026, 3)))));
        assertNull(HistoryJson.read("not json"));
    }
}
