package duowatchdog.core;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuoCliLogsTest {

    private static final String NAME = "duo-cli-log-2026-10-28T14-35-25-dana-projects-mobile-gateway-service.log";

    /** As the CLI writes it: steps, a repeated one, other lines, and code the agent read, which says nothing. */
    private static final String LOG = """
            2026-10-28T15:01:16:072 [debug]: fetch: request for https://gitlab.com/api/v4/usage_data/track_event made with https agent.
            2026-10-28T15:02:14:753 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: 28fd775f-03ee-4707-b87b-7900113239dd] Action received: {"requestID":"28fd7
                for sheet,name in (('xl/worksheets/sheet1.xml','Action received'),):
            2026-10-28T15:02:14:900 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: 28fd775f-03ee-4707-b87b-7900113239dd] Action received: {"requestID":"28fd7
            2026-10-28T15:08:33:391 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: c52c0b38-f540-4ec3-b59f-304472f3a0ad] Action received: {"requestID":"c52c0
            2026-10-28T23:30:00:000 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: d1] Action received: {}
            2026-10-28T15:09:00:000 [debug]: [DuoWorkflowNodeExecutor][10208240] [requestID: e1] Action received: {}
            2026-10-28T15:09:01:000 [debug]: [DuoWorkflowNodeExecutor][10208240] [requestID: e1] Action completed
            """;

    @Test
    void countsEachSessionsStepsByUtcDay() throws Exception {
        Map<Long, Sessions.Steps> steps = DuoCliLogs.read(new BufferedReader(new StringReader(LOG)), NAME,
                ZoneId.of("America/Chicago"));

        assertEquals(Map.of(LocalDate.of(2026, 10, 28), 2, LocalDate.of(2026, 10, 29), 1), steps.get(10208239L).byDay(),
                "a repeated request once; 23:30 in Chicago is the next day in UTC");
        assertEquals(1, steps.get(10208240L).total());
        assertEquals("dana-projects-mobile-gateway-service", steps.get(10208239L).folder());
    }

    @Test
    void knowsTheCliLogsByName() {
        assertTrue(DuoCliLogs.isLog(NAME));
        assertFalse(DuoCliLogs.isLog("other.log"));
        assertEquals("", DuoCliLogs.folder("duo-cli-log-x.log"));
    }

    @Test
    void addsUpTheStepsOfASessionResumedInAnotherRun() {
        LocalDate day = LocalDate.of(2026, 10, 28);
        Map<Long, Sessions.Steps> a = Map.of(1L, new Sessions.Steps(1, Map.of(day, 3), "x"));
        Map<Long, Sessions.Steps> b = Map.of(1L, new Sessions.Steps(1, Map.of(day, 2), "y"),
                2L, new Sessions.Steps(2, Map.of(day, 1), "y"));

        Map<Long, Sessions.Steps> sum = DuoCliLogs.add(a, b);

        assertEquals(5, sum.get(1L).total());
        assertEquals("x", sum.get(1L).folder());
        assertEquals(1, sum.get(2L).total());
    }

    @Test
    void keepsWhatWasReadAcrossRuns() throws Exception {
        Map<Long, Sessions.Steps> steps = DuoCliLogs.read(new BufferedReader(new StringReader(LOG)), NAME, ZoneId.of("UTC"));
        String json = DuoCliLogs.write(Map.of(NAME, new DuoCliLogs.Read(13684, 1761662125000L, steps)));

        DuoCliLogs.Read back = DuoCliLogs.parse(json).get(NAME);

        assertEquals(13684, back.size());
        assertEquals(1761662125000L, back.modified());
        assertEquals(steps, back.steps());
        assertFalse(json.contains("sheet1"), "nothing but counts is kept");
        assertTrue(DuoCliLogs.parse("not json").isEmpty());
    }
}
