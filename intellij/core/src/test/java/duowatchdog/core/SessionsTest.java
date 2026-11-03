package duowatchdog.core;

import duowatchdog.core.CreditsReport.DailyUsage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionsTest {

    private static final LocalDate OCT_28 = LocalDate.of(2026, 10, 28);
    private static final LocalDate OCT_29 = LocalDate.of(2026, 10, 29);

    private static Sessions.Session session(long id, String flow, String project, String created, String updated) {
        return new Sessions.Session(id, "Session " + id, flow, project, null, null, "Finished", Instant.parse(created),
                Instant.parse(updated), null);
    }

    private static Sessions.Steps steps(long id, LocalDate day, int count) {
        return new Sessions.Steps(id, Map.of(day, count), "home-dana-projects-notes");
    }

    /** Oct 28: Agentic Chat 60 credits, Developer Flow 30, and 10 more no feature was priced for. */
    private static History history() {
        return new History(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31),
                List.of(new DailyUsage(OCT_28, 100), new DailyUsage(OCT_29, 20)),
                List.of(new History.FlowHistory("agentic_chat", "Agentic Chat", List.of(new DailyUsage(OCT_28, 60),
                                new DailyUsage(OCT_29, 20))),
                        new History.FlowHistory("developer", "Developer Flow", List.of(new DailyUsage(OCT_28, 30)))),
                List.of(), null);
    }

    private static double credits(List<Sessions.Estimate> estimates, long id) {
        return estimates.stream().filter(e -> e.session().id() == id).findFirst().orElseThrow().credits();
    }

    @Test
    void aFeaturesCreditsAreSplitByTheAgentsStepsWhenTheLogsCountEverySession() {
        List<Sessions.Session> sessions = List.of(
                session(1, "chat", "acme/a", "2026-10-28T09:00:00Z", "2026-10-28T10:00:00Z"),
                session(2, "chat", "acme/b", "2026-10-28T11:00:00Z", "2026-10-28T12:00:00Z"));
        Map<Long, Sessions.Steps> steps = Map.of(1L, steps(1, OCT_28, 300), 2L, steps(2, OCT_28, 100));

        List<Sessions.Estimate> e = Sessions.estimate(sessions, steps, history());

        assertEquals(45, credits(e, 1), 1e-9);
        assertEquals(15, credits(e, 2), 1e-9);
        Sessions.Share share = e.get(0).shares().get(0);
        assertEquals("Agentic Chat", share.feature());
        assertEquals(60, share.dayCredits(), 1e-9);
        assertEquals(2, share.sessions());
        assertTrue(share.bySteps());
    }

    @Test
    void withoutStepsForEverySessionTheyShareEqually() {
        List<Sessions.Session> sessions = List.of(
                session(1, "chat", "acme/a", "2026-10-28T09:00:00Z", "2026-10-28T10:00:00Z"),
                session(2, "chat", "acme/b", "2026-10-28T11:00:00Z", "2026-10-28T12:00:00Z"),
                session(3, "developer/v1", "acme/a", "2026-10-28T13:00:00Z", "2026-10-28T14:00:00Z"));
        Map<Long, Sessions.Steps> steps = Map.of(1L, steps(1, OCT_28, 300));

        List<Sessions.Estimate> e = Sessions.estimate(sessions, steps, history());

        assertEquals(30, credits(e, 1), 1e-9);
        assertEquals(30, credits(e, 2), 1e-9);
        assertFalse(e.get(1).shares().get(0).bySteps());
        assertEquals(30, credits(e, 3), 1e-9, "developer/v1 goes with Developer Flow");
    }

    @Test
    void sessionsNoFeatureMatchesShareWhatTheMatchedOnesLeft() {
        List<Sessions.Session> sessions = List.of(
                session(1, "chat", "acme/a", "2026-10-28T09:00:00Z", "2026-10-28T10:00:00Z"),
                session(2, "fix_pipeline/v1", "acme/b", "2026-10-28T11:00:00Z", "2026-10-28T12:00:00Z"));

        List<Sessions.Estimate> e = Sessions.estimate(sessions, Map.of(), history());

        assertEquals(60, credits(e, 1), 1e-9);
        assertEquals(40, credits(e, 2), 1e-9, "Developer Flow's 30 and the unpriced 10");
        assertEquals("Developer Flow, Other features", e.get(1).shares().get(0).feature());
    }

    @Test
    void aSessionTakesPartOnTheDaysItsLogCounts() {
        List<Sessions.Session> sessions = List.of(
                session(1, "chat", "acme/a", "2026-10-28T09:00:00Z", "2026-10-29T10:00:00Z"));
        Map<Long, Sessions.Steps> steps = Map.of(1L, new Sessions.Steps(1, Map.of(OCT_29, 5), ""));

        List<Sessions.Estimate> e = Sessions.estimate(sessions, steps, history());

        assertEquals(20, credits(e, 1), 1e-9, "only Oct 29, where the log has its steps");
        assertEquals(0, e.get(0).credits(new Sessions.Range(OCT_28, OCT_28)), 1e-9);
    }

    @Test
    void theSummaryListsTheRangesSessionsNewestFirstAndWhatTheyLeftOut() {
        List<Sessions.Session> sessions = List.of(
                session(1, "chat", "acme/a", "2026-10-28T09:00:00Z", "2026-10-28T10:00:00Z"),
                session(2, "chat", "acme/b", "2026-10-28T11:00:00Z", "2026-10-28T12:00:00Z"),
                session(3, "chat", "acme/b", "2026-10-29T11:00:00Z", "2026-10-29T12:00:00Z"));
        List<Sessions.Estimate> e = Sessions.estimate(sessions, Map.of(), history());

        Sessions.Summary s = Sessions.summarize(e, history(), new Sessions.Range(OCT_28, OCT_28), "acme");

        assertEquals(List.of(2L, 1L), s.rows().stream().map(r -> r.session().id()).toList());
        assertEquals(100, s.total(), 1e-9);
        assertEquals(60, s.inSessions(), 1e-9);
        assertEquals(40, s.outside(), 1e-9);
        assertEquals(List.of("a", "b"), s.byProject().stream().map(CreditsReport.FlowUsage::title).toList());
    }

    @Test
    void readsTheSessionNumberInWhatIsTyped() {
        assertEquals(10208239L, Sessions.id(" 10208239 "));
        assertEquals(10208239L, Sessions.id("#10208239"));
        assertEquals(10208239L, Sessions.id("gid://gitlab/Ai::DuoWorkflows::Workflow/10208239"));
        assertEquals(10208239L, Sessions.id("https://gitlab.com/acme/notes/-/automate/agent-sessions/10208239"));
        assertNull(Sessions.id("notes"));
        assertNull(Sessions.id(""));
    }

    @Test
    void placesASessionInItsProjectWithoutTheGroup() {
        Sessions.Session inProject = session(1, "chat", "acme/apps/notes", "2026-10-28T09:00:00Z", "2026-10-28T10:00:00Z");
        Sessions.Session nowhere = session(2, "chat", null, "2026-10-28T09:00:00Z", "2026-10-28T10:00:00Z");

        assertEquals("apps/notes", Sessions.place(inProject, null, "acme"));
        assertEquals("home-dana-projects-notes", Sessions.place(nowhere, steps(2, OCT_28, 1), "acme"));
        assertEquals("—", Sessions.place(nowhere, null, "acme"));
        assertTrue(Sessions.inGroup(inProject, "ACME"));
        assertTrue(Sessions.inGroup(nowhere, "acme"));
        assertFalse(Sessions.inGroup(inProject, "acm"));
    }

    @Test
    void matchesFlowsToFeaturesByTheirWords() {
        assertEquals(java.util.Set.of("developer"), Sessions.words("developer/v1"));
        assertEquals(java.util.Set.of("developer"), Sessions.words("Developer Flow"));
        assertEquals(java.util.Set.of("agentic", "chat"), Sessions.words("Agentic Chat"));
    }
}
