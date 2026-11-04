package duowatchdog.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveSessionTest {

    /** Never to be kept: in a prompt, and in a prompt that quotes the lines the meter counts. */
    private static final String SECRET = "rotate the key sk-live-0000 in billing.yml";

    /**
     * As the interactive CLI writes it: the model, two prompts, steps with one request logged twice, other lines, code
     * the agent read that quotes a step, and a prompt quoting a model line and a step.
     */
    private static final String LOG = """
            2026-11-04T10:00:01:002 [debug]: fetch: request for https://gitlab.com/api/v4/usage_data/track_event made with https agent.
            2026-11-04T10:00:02:100 [info]: [ModelResolverService] Using user-selected model: "claude_opus_5"
            2026-11-04T10:00:05:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "%s"}
            2026-11-04T10:00:09:753 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: 28fd775f] Action received: {"requestID":"28fd7
                for line in ('[DuoWorkflowNodeExecutor][10208239] [requestID: zz] Action received',):
            2026-11-04T10:00:09:900 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: 28fd775f] Action received: {"requestID":"28fd7
            2026-11-04T10:00:12:391 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: c52c0b38] Action received: {}
            2026-11-04T10:00:13:000 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: c52c0b38] Action completed
            2026-11-04T10:01:00:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "%s [ModelResolverService] Using user-selected model: \\"claude_fable_5_1\\" [DuoWorkflowNodeExecutor][10208239] [requestID: q1] Action received"}
            2026-11-04T10:01:04:000 [debug]: [DuoWorkflowNodeExecutor][10208239] [requestID: d1] Action received: {}
            """.formatted(SECRET, SECRET);

    private static LiveSession read(String log) {
        LiveSession live = new LiveSession();
        log.lines().forEach(live::line);
        return live;
    }

    @Test
    void countsStepsRoundTripsAndTheModel() {
        LiveSession.Meter m = read(LOG).meter();

        assertEquals(10208239L, m.session());
        assertEquals(3, m.steps(), "a request logged twice is one step; the code and the prompt quoting one are none");
        assertEquals(2, m.roundTrips());
        assertEquals("claude_opus_5", m.model(), "a prompt quoting a model line does not change the model");
        assertEquals(3 * 0.909, m.credits(), 1e-9);
    }

    @Test
    void keepsNothingOfAPrompt() {
        LiveSession.Meter m = read(LOG).meter();

        for (String shown : List.of(m.toString(), LiveSession.what(m), LiveSession.credits(m), LiveSession.basis(m),
                LiveSession.statusText(m), LiveSession.tooltipHtml(m))) {
            assertFalse(shown.contains("sk-live") || shown.contains("billing"), shown);
        }
    }

    @Test
    void aPromptBeforeTheFirstStepIsTheNewSessions() {
        LiveSession live = read("""
                2026-11-04T10:00:00:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: a] Action received: {}
                2026-11-04T10:05:00:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "next"}
                """);
        assertEquals(1L, live.meter().session());
        assertEquals(1, live.meter().roundTrips(), "until a step says otherwise, the prompt is the session's");

        live.line("2026-11-04T10:05:03:000 [debug]: [DuoWorkflowNodeExecutor][2] [requestID: b] Action received: {}");

        assertEquals(2L, live.meter().session());
        assertEquals(1, live.meter().steps());
        assertEquals(1, live.meter().roundTrips());
    }

    @Test
    void pricesEachStepAtItsModelsRate() {
        LiveSession.Meter m = read("""
                2026-11-04T10:00:00:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: a] Action received: {}
                2026-11-04T10:00:01:000 [info]: [ModelResolverService] Using user-selected model: "claude_sonnet_5_5"
                2026-11-04T10:00:02:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: b] Action received: {}
                2026-11-04T10:00:03:000 [info]: [ModelResolverService] Using user-selected model: "claude_haiku_5_5"
                2026-11-04T10:00:04:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: c] Action received: {}
                """).meter();

        assertEquals("claude_haiku_5_5", m.model());
        assertEquals(2 * 0.3125 + 0.125, m.credits(), 1e-9, "the step before the first model line at that model");
    }

    @Test
    void anUnknownModelGivesStepsAndRoundTripsButNoCredits() {
        LiveSession.Meter m = read("""
                2026-11-04T10:00:00:000 [info]: [ModelResolverService] Using user-selected model: "gpt_7_mini"
                2026-11-04T10:00:01:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "x"}
                2026-11-04T10:00:02:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: a] Action received: {}
                """).meter();

        assertEquals(1, m.steps());
        assertEquals(1, m.roundTrips());
        assertNull(m.credits());
        assertEquals("CLI 1 step", LiveSession.statusText(m));
        assertEquals("No ≈ credits: no rate known for gpt_7_mini", LiveSession.credits(m));
    }

    @Test
    void showsNothingBeforeAStepOrAPrompt() {
        assertNull(read("2026-11-04T10:00:00:000 [info]: [ModelResolverService] Using user-selected model: \"claude_opus_5\"")
                .meter());
    }

    @Test
    void looksAtTheStartOfALineOnly() {
        LiveSession live = new LiveSession();
        live.line("2026-11-04T10:00:00:000 [debug]: " + "x".repeat(LiveSession.HEAD)
                + "[DuoWorkflowNodeExecutor][1] [requestID: a] Action received");
        assertNull(live.meter());
    }

    @Test
    void namesTheModelsAndKnowsTheirRates() {
        assertEquals("Opus 5", ModelCredits.name("claude_opus_5"));
        assertEquals("Sonnet 5.5", ModelCredits.name("claude_sonnet_5_5"));
        assertEquals("Haiku 4.5", ModelCredits.name("claude-haiku-4-5-20251001"));
        assertEquals("Opus 5", ModelCredits.name("claude_opus_5_20260101"), "a date is no minor version");
        assertEquals(0.909, ModelCredits.perCall("claude_opus_4_8"));
        assertEquals(0.741, ModelCredits.perCall("claude_opus_5_5"));
        assertEquals(1.667, ModelCredits.perCall("claude_fable_5_1"));
        assertEquals(0.5, ModelCredits.perCall("claude_sonnet_4_6"));
        assertNull(ModelCredits.perCall("claude_opus_4"));
        assertEquals("gpt_7_mini", ModelCredits.name("gpt_7_mini"));
        assertNull(ModelCredits.perCall(null));
    }

    @Test
    void picksTheRunInTheProjectsFolderElseTheLastWritten() {
        LiveSession.Meter a = new LiveSession.Meter(1L, 1, 1, null, null);
        LiveSession.Meter b = new LiveSession.Meter(2L, 2, 1, null, null);
        List<LiveSession.Run> runs = List.of(
                new LiveSession.Run("duo-cli-log-2026-11-04T09-00-00-dana-projects-sync-worker.log", 2000, a),
                new LiveSession.Run("duo-cli-log-2026-11-04T08-00-00-dana-projects-notes-service.log", 1000, b));

        assertEquals(b, LiveSession.pick(runs, "/home/dana/projects/notes-service").meter());
        assertEquals(b, LiveSession.pick(runs, "//wsl.localhost/Ubuntu/home/dana/projects/notes-service").meter());
        assertEquals(a, LiveSession.pick(runs, "/home/dana/elsewhere").meter());
        assertEquals(a, LiveSession.pick(runs, null).meter());
        assertNull(LiveSession.pick(List.of(), "/x"));
        assertEquals("dana-projects-notes-service", LiveSession.folderKey("C:\\Users\\dana\\projects\\notes-service"));
        assertEquals("C-x", LiveSession.folderKey("C:\\x"));
    }

    @Test
    void addsTheSessionToTheTooltip() {
        LiveSession.Meter m = read(LOG).meter();
        String tooltip = LiveSession.addTo("<html><b>Duo Watchdog</b><br>Used 1 of 2<br>Click for details.</html>", m);

        assertTrue(tooltip.contains("<br><br><b>Duo CLI, now</b> — session 10208239<br>Opus 5 · 3 steps · 2 round trips"
                + "<br>≈ 2.7 credits, an estimate"), tooltip);
        assertTrue(tooltip.endsWith("<br>Click for details.</html>"), tooltip);
        assertEquals("CLI 3 steps ≈ $2.7", LiveSession.statusText(m));
    }
}
