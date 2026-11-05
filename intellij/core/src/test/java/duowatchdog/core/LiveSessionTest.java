package duowatchdog.core;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveSessionTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final String NAME = "duo-cli-log-2026-11-04T09-59-58-dana-projects-notes-service.log";

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
        LiveSession live = new LiveSession(UTC);
        log.lines().forEach(live::line);
        return live;
    }

    /**
     * As the interactive CLI 9.30 writes it: the group's default model, then two prompts with a tool call each and a
     * third answered without one. No SEND_PROMPT line; checkpoints, which are no model calls; a prompt in the line that
     * runs it.
     */
    private static final String INTERACTIVE = """
            2026-11-05T09:00:00:000 [info]: [ModelResolverService] Resolved user-selected model ref "__default__duo_agent_platform_agentic_chat" is a "use the feature default" sentinel; the feature's default model is used
            2026-11-05T09:00:05:000 [debug]: [DuoWorkflowNodeExecutor][10301001] Running workflow {"goal": "%s"}
            2026-11-05T09:00:05:100 [debug]: Creating websocket connection...
            2026-11-05T09:00:05:300 [debug]: [DuoWorkflowNodeExecutor][10301001] startRequest written to stream
            2026-11-05T09:00:07:000 [debug]: [DuoWorkflowNodeExecutor][10301001] Received new checkpoint
            2026-11-05T09:00:09:500 [debug]: [DuoWorkflowNodeExecutor][10301001] [requestID: r1] Action received: {"requestID":"r1"
            2026-11-05T09:00:10:000 [debug]: [DuoWorkflowNodeExecutor][10301001] Received new checkpoint
            2026-11-05T09:00:12:000 [debug]: [GitLabBackend] Workflow turn ended
            2026-11-05T09:01:00:000 [debug]: [DuoWorkflowNodeExecutor][10301001] Running workflow {"goal": "and %s"}
            2026-11-05T09:01:00:100 [debug]: Creating websocket connection...
            2026-11-05T09:01:03:000 [debug]: [DuoWorkflowNodeExecutor][10301001] [requestID: r2] Action received: {"requestID":"r2"
            2026-11-05T09:01:04:000 [debug]: [DuoWorkflowNodeExecutor][10301001] Received new checkpoint
            2026-11-05T09:01:06:000 [debug]: [GitLabBackend] Workflow turn ended
            2026-11-05T09:02:00:000 [debug]: [DuoWorkflowNodeExecutor][10301001] Running workflow {"goal": "thanks"}
            2026-11-05T09:02:02:000 [debug]: [DuoWorkflowNodeExecutor][10301001] Received new checkpoint
            2026-11-05T09:02:03:000 [debug]: [GitLabBackend] Workflow turn ended
            """.formatted(SECRET, SECRET);

    @Test
    void countsTheRoundTripsOfAnInteractiveSession() {
        LiveSession live = new LiveSession(UTC);
        INTERACTIVE.lines().limit(8).forEach(live::line);
        assertEquals(new LiveSession.Meter(10301001L, 1, 1, LiveSession.GROUP_DEFAULT, 0.5, "Sonnet 4.5",
                Instant.parse("2026-11-05T09:00:05Z")), live.meter(), "one Running workflow a prompt; checkpoints no steps");

        INTERACTIVE.lines().skip(8).forEach(live::line);
        LiveSession.Meter m = live.meter();
        assertEquals(2, m.steps());
        assertEquals(3, m.roundTrips(), "a reply without a tool call is a round trip too");
        assertEquals("Group default · 2 steps · 3 round trips", LiveSession.what(m));
        assertEquals("≈ 1 credits", LiveSession.credits(m), "each step at the group's default model taken as Sonnet 4.5");
        assertEquals("Group default taken as Sonnet 4.5 (0.5 credits a step), as set in Settings", LiveSession.assumption(m));
        LiveSession.Run run = new LiveSession.Run(NAME, Instant.parse("2026-11-05T09:02:03Z").toEpochMilli(), m);
        Instant now = Instant.parse("2026-11-05T09:02:10Z");
        assertEquals("2m 5s · 3 round trips · 2 steps · ≈ $1", LiveSession.barText(run, now));
        assertTrue(LiveSession.tooltipHtml(run, now, UTC, 20).contains("<br>≈ 1 credits, an estimate: steps × the model's "
                + "rate per call<br>Group default taken as Sonnet 4.5 (0.5 credits a step), as set in Settings<br>"));
        assertTrue(LiveSession.basis(m).startsWith(
                "Group default taken as Sonnet 4.5 (0.5 credits a step), as set in Settings. An estimate"));
        assertEquals(2 * 0.909, live.meter("Opus 5").credits(), 1e-9, "the group default model set in the settings");
        assertEquals("Opus 5", live.meter("Opus 5").assumed());
        for (String shown : List.of(m.toString(), LiveSession.barText(run, Instant.parse("2026-11-05T09:02:10Z")),
                LiveSession.tooltipHtml(run, Instant.parse("2026-11-05T09:02:10Z"), UTC, 20))) {
            assertFalse(shown.contains("sk-live") || shown.contains("billing") || shown.contains("__default__"), shown);
        }
    }

    @Test
    void aReplyWithoutAToolCallShowsTheSession() {
        LiveSession live = new LiveSession(UTC);
        live.line("2026-11-05T09:00:05:000 [debug]: [DuoWorkflowNodeExecutor][10301001] Running workflow");
        assertEquals(new LiveSession.Meter(10301001L, 0, 1, null, 0.0, "Sonnet 4.5", Instant.parse("2026-11-05T09:00:05Z")),
                live.meter());
    }

    @Test
    void aHeadlessRunWritingBothCountsItsPromptOnce() {
        LiveSession.Meter m = read("""
                2026-11-04T10:00:02:100 [info]: [ModelResolverService] Using user-selected model: "claude_sonnet_5_5"
                2026-11-04T10:00:05:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "x"}
                2026-11-04T10:00:05:200 [debug]: [DuoWorkflowNodeExecutor][7] Running workflow
                2026-11-04T10:00:09:000 [debug]: [DuoWorkflowNodeExecutor][7] [requestID: a] Action received: {}
                """).meter();

        assertEquals(1, m.roundTrips());
        assertEquals("claude_sonnet_5_5", m.model(), "the model line of the headless CLI is still read");
        assertEquals(Instant.parse("2026-11-04T10:00:05Z"), m.started(), "from its prompt");
    }

    @Test
    void countsStepsRoundTripsAndTheModel() {
        LiveSession.Meter m = read(LOG).meter();

        assertEquals(10208239L, m.session());
        assertEquals(3, m.steps(), "a request logged twice is one step; the code and the prompt quoting one are none");
        assertEquals(2, m.roundTrips());
        assertEquals("claude_opus_5", m.model(), "a prompt quoting a model line does not change the model");
        assertEquals(3 * 0.909, m.credits(), 1e-9);
        assertNull(m.assumed(), "every step at its own model's rate");
        assertNull(LiveSession.assumption(m));
        assertEquals(Instant.parse("2026-11-04T10:00:05Z"), m.started(), "from its first prompt");
    }

    @Test
    void keepsNothingOfAPrompt() {
        LiveSession.Meter m = read(LOG).meter();
        LiveSession.Run run = new LiveSession.Run(NAME, Instant.parse("2026-11-04T10:01:04Z").toEpochMilli(), m);
        Instant now = Instant.parse("2026-11-04T10:01:10Z");

        for (String shown : List.of(m.toString(), LiveSession.what(m), LiveSession.credits(m), LiveSession.basis(m),
                LiveSession.barText(run, now), LiveSession.tooltipHtml(run, now, UTC, 20))) {
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
    void anUnknownModelIsPricedAsTheGroupDefaultModel() {
        LiveSession live = read("""
                2026-11-04T10:00:00:000 [info]: [ModelResolverService] Using user-selected model: "gpt_7_mini"
                2026-11-04T10:00:01:000 [debug]: [RunController] Executing workflow: {"type": "SEND_PROMPT", "goal": "x"}
                2026-11-04T10:00:02:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: a] Action received: {}
                """);
        LiveSession.Meter m = live.meter();

        assertEquals(1, m.steps());
        assertEquals(1, m.roundTrips());
        assertEquals(0.5, m.credits());
        assertEquals("Sonnet 4.5", m.assumed());
        LiveSession.Run run = new LiveSession.Run(NAME, Instant.parse("2026-11-04T10:00:02Z").toEpochMilli(), m);
        Instant now = Instant.parse("2026-11-04T10:00:31Z");
        assertEquals("30s · 1 round trip · 1 step · ≈ $0.5", LiveSession.barText(run, now));
        assertEquals("No rate known for gpt_7_mini: taken as Sonnet 4.5 (0.5 credits a step), as set in Settings",
                LiveSession.assumption(m));

        LiveSession.Meter none = live.meter("Sonnet 9");
        assertNull(none.credits(), "a model with no rate to take");
        assertNull(none.assumed());
        assertEquals("30s · 1 round trip · 1 step", LiveSession.barText(new LiveSession.Run(NAME, run.modified(), none), now));
        assertEquals("No ≈ credits: no rate known for gpt_7_mini", LiveSession.credits(none));
    }

    @Test
    void aLogThatNamesNoModelIsPricedAsTheGroupDefaultModel() {
        LiveSession.Meter m = read("""
                2026-11-04T10:00:00:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: a] Action received: {}
                2026-11-04T10:00:01:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: b] Action received: {}
                """).meter("Fable 5.1");

        assertNull(m.model());
        assertEquals(2 * 1.667, m.credits(), 1e-9);
        assertEquals("No model in the log: taken as Fable 5.1 (1.667 credits a step), as set in Settings",
                LiveSession.assumption(m));
    }

    @Test
    void onlyTheStepsOfAModelWithNoRateArePricedAsTheGroupDefaultModel() {
        LiveSession.Meter m = read("""
                2026-11-04T10:00:00:000 [info]: [ModelResolverService] Resolved user-selected model ref "__default__x" is a "use the feature default" sentinel
                2026-11-04T10:00:01:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: a] Action received: {}
                2026-11-04T10:00:02:000 [info]: [ModelResolverService] Using user-selected model: "claude_opus_5"
                2026-11-04T10:00:03:000 [debug]: [DuoWorkflowNodeExecutor][1] [requestID: b] Action received: {}
                """).meter();

        assertEquals(0.5 + 0.909, m.credits(), 1e-9);
        assertEquals("Steps of a model without a known rate taken as Sonnet 4.5 (0.5 credits a step), as set in Settings",
                LiveSession.assumption(m));
    }

    @Test
    void theGroupDefaultModelIsOneInTheTable() {
        assertEquals(List.of("Haiku 5.5", "Haiku 4.5", "Sonnet 5", "Sonnet 5.5", "Sonnet 4.5", "Sonnet 4.6", "Opus 5.5",
                "Opus 4.5", "Opus 4.6", "Opus 4.7", "Opus 4.8", "Opus 5", "Fable 5", "Fable 5.1"), ModelCredits.MODELS,
                "the cheapest first, as in the VS Code setting");
        assertEquals(0.5, ModelCredits.perCall(ModelCredits.ASSUMED));
        assertEquals("Opus 5", ModelCredits.assumed("Opus 5"));
        assertEquals(ModelCredits.ASSUMED, ModelCredits.assumed("claude_opus_5"), "as the table names it");
        assertEquals(ModelCredits.ASSUMED, ModelCredits.assumed(null));
    }

    @Test
    void showsNothingBeforeAStepOrAPrompt() {
        assertNull(read("2026-11-04T10:00:00:000 [info]: [ModelResolverService] Using user-selected model: \"claude_opus_5\"")
                .meter());
    }

    @Test
    void looksAtTheStartOfALineOnly() {
        LiveSession live = new LiveSession(UTC);
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
        LiveSession.Meter a = new LiveSession.Meter(1L, 1, 1, null, null, null, null);
        LiveSession.Meter b = new LiveSession.Meter(2L, 2, 1, null, null, null, null);
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
    void aRunWithNothingCountedYetDoesNotHideASessionStillGoing() {
        LiveSession.Meter a = new LiveSession.Meter(1L, 4, 2, null, null, null, null);
        List<LiveSession.Run> sameFolder = List.of(
                new LiveSession.Run("duo-cli-log-2026-11-04T10-30-00-dana-projects-notes-service.log", 3000, null),
                new LiveSession.Run("duo-cli-log-2026-11-04T10-00-00-dana-projects-notes-service.log", 2000, a));
        List<LiveSession.Run> elsewhere = List.of(
                new LiveSession.Run("duo-cli-log-2026-11-04T10-30-00-dana-projects-sync-worker.log", 3000, null),
                new LiveSession.Run("duo-cli-log-2026-11-04T10-00-00-dana-projects-notes-service.log", 2000, a));

        assertEquals(a, LiveSession.pick(sameFolder, "/home/dana/projects/notes-service").meter(),
                "duo started again in the folder, with no prompt yet");
        assertEquals(a, LiveSession.pick(elsewhere, "/home/dana/elsewhere").meter(), "the last written has nothing yet");
        assertNull(LiveSession.pick(List.of(sameFolder.get(0)), "/home/dana/projects/notes-service"));
    }

    @Test
    void saysTheSessionInTheStatusBarWithItsRunTime() {
        LiveSession.Meter m = read(LOG).meter();
        LiveSession.Run run = new LiveSession.Run(NAME, Instant.parse("2026-11-04T10:01:04Z").toEpochMilli(), m);
        Instant now = Instant.parse("2026-11-04T10:01:10Z");

        assertEquals("1m 5s · 2 round trips · 3 steps · ≈ $2.7", LiveSession.barText(run, now),
                "running from its first prompt to now, while its log is written to");
        String tooltip = LiveSession.tooltipHtml(run, now, UTC, 20);
        assertTrue(tooltip.startsWith("<html><b>Duo CLI, now</b> — session 10208239<br>Opus 5 · 3 steps · 2 round trips"
                + "<br>≈ 2.7 credits, an estimate"), tooltip);
        assertTrue(tooltip.contains("<br>Started today 10:00 UTC, running 1m 5s<br>2 of 20 recommended round trips<br>"), tooltip);

        Instant later = Instant.parse("2026-11-04T10:30:00Z");
        assertEquals(Duration.ofSeconds(59), LiveSession.runTime(run, later), "a quiet log stops the clock at its last line");
        assertEquals("Started today 10:00 UTC, 59s, quiet since today 10:01 UTC", LiveSession.started(run, later, UTC));
        assertEquals("Started yesterday 10:00 UTC, 59s, quiet since yesterday 10:01 UTC",
                LiveSession.started(run, Instant.parse("2026-11-05T00:10:00Z"), UTC), "left open past midnight");
        ZoneId kyiv = ZoneId.of("Europe/Kyiv");
        assertEquals("Started yesterday 12:00 UTC+2, 59s, quiet since yesterday 12:01 UTC+2",
                LiveSession.started(run, Instant.parse("2026-11-04T23:10:00Z"), kyiv), "past midnight on the zone's clock");
        assertEquals("Started Nov 4, 10:00 UTC, 59s, quiet since Nov 4, 10:01 UTC",
                LiveSession.started(run, Instant.parse("2026-11-07T09:00:00Z"), UTC));
        assertEquals("Started today 12:00 UTC+2", LiveSession.startedAt(m, LocalDate.of(2026, 11, 4), kyiv));
    }

    @Test
    void coloursTheRoundTripsAgainstTheRecommendedMost() {
        assertEquals(LiveSession.Level.NORMAL, LiveSession.level(trips(15), 20));
        assertEquals(LiveSession.Level.NEAR, LiveSession.level(trips(16), 20), "from 80 %");
        assertEquals(LiveSession.Level.OVER, LiveSession.level(trips(20), 20));
        assertEquals(LiveSession.Level.NORMAL, LiveSession.level(trips(500), 0), "no limit, no colour");
        assertNull(LiveSession.limitText(trips(5), 0));
        assertEquals("45s", LiveSession.duration(Duration.ofSeconds(45)));
        assertEquals("12m 4s", LiveSession.duration(Duration.ofSeconds(12 * 60 + 4)));
        assertEquals("1h 2m", LiveSession.duration(Duration.ofSeconds(3725)));
    }

    private static LiveSession.Meter trips(int roundTrips) {
        return new LiveSession.Meter(1L, roundTrips, roundTrips, null, null, null, null);
    }
}
