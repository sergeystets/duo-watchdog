package duowatchdog.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiveLogTest {

    private static final String MODEL = "2026-11-04T10:00:00:000 [info]: [ModelResolverService] Using user-selected model: \"claude_sonnet_5_5\"\n";
    private static final String PROMPT = "2026-11-04T10:00:01:000 [debug]: [RunController] Executing workflow: {\"type\": \"SEND_PROMPT\", \"goal\": \"";

    private static String step(long session, String request) {
        return "2026-11-04T10:00:02:000 [debug]: [DuoWorkflowNodeExecutor][" + session + "] [requestID: " + request
                + "] Action received: {}\n";
    }

    private static void append(Path log, String text) throws Exception {
        Files.writeString(log, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void readsWhatTheLogGainsAsItGrows(@TempDir Path dir) throws Exception {
        Path log = dir.resolve("duo-cli-log-2026-11-04T09-59-58-dana-projects-notes-service.log");
        LiveLog live = new LiveLog();
        String second = step(7, "b");
        append(log, MODEL + PROMPT + "x\"}\n" + step(7, "a") + second.substring(0, 50));

        live.read(log);
        assertEquals(new LiveSession.Meter(7L, 1, 1, "claude_sonnet_5_5", 0.3125), live.meter(),
                "a line not yet ended waits");

        append(log, second.substring(50));
        live.read(log);
        assertEquals(2, live.meter().steps());

        append(log, PROMPT + "y".repeat(1_000_000) + "\"}\n" + step(7, "c"));
        live.read(log);
        assertEquals(new LiveSession.Meter(7L, 3, 2, "claude_sonnet_5_5", 3 * 0.3125), live.meter(),
                "a long prompt is one round trip");

        Files.writeString(log, step(9, "a"), StandardCharsets.UTF_8);
        live.read(log);
        assertEquals(new LiveSession.Meter(9L, 1, 0, null, null), live.meter(), "a log made again is read from its start");
    }
}
