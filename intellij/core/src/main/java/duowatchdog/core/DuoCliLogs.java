package duowatchdog.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The agent's steps in each session, from the GitLab Duo CLI's logs ({@code duo log list}): one line
 * {@code [DuoWorkflowNodeExecutor][<session>] [requestID: <id>] Action received} for each action the model asked the
 * CLI to run. Only those lines are looked at, and of them only the time, the session and the request: the logs also
 * hold prompts and code, and none of it is read on or kept.
 */
public final class DuoCliLogs {

    /** The folder, under the system's temporary folder, that the CLI writes its logs to. */
    public static final String FOLDER = "gitlab-duo-cli";

    private static final Pattern ACTION = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})\\S*\\s.*?\\[DuoWorkflowNodeExecutor]\\[(\\d+)]\\s*"
                    + "\\[requestID:\\s*([^\\]\\s]+)\\s*]\\s*Action received");
    private static final Pattern NAME = Pattern.compile("^duo-cli-log-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}-(.+)\\.log$");

    private DuoCliLogs() {
    }

    /** Whether a file name is one of the CLI's logs. */
    public static boolean isLog(String fileName) {
        return fileName.startsWith("duo-cli-log-") && fileName.endsWith(".log");
    }

    /** The folder the CLI ran in, as the log's name gives it: its path with "-" for "/", or "" when it gives none. */
    public static String folder(String fileName) {
        Matcher m = NAME.matcher(fileName);
        return m.matches() ? m.group(1) : "";
    }

    /**
     * Each session's steps in one log, by UTC day.
     *
     * @param zone the zone the log's times are in: the one of the machine the CLI ran on
     */
    public static Map<Long, Sessions.Steps> read(BufferedReader log, String fileName, ZoneId zone) throws IOException {
        Map<Long, Map<LocalDate, Integer>> byDay = new HashMap<>();
        Set<String> seen = new HashSet<>();
        String line;
        while ((line = log.readLine()) != null) {
            if (!line.contains("Action received")) {
                continue;
            }
            Matcher m = ACTION.matcher(line);
            if (!m.find() || !seen.add(m.group(2) + "/" + m.group(3))) {
                continue;
            }
            try {
                LocalDate day = LocalDateTime.parse(m.group(1)).atZone(zone)
                        .withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
                byDay.computeIfAbsent(Long.parseLong(m.group(2)), id -> new TreeMap<>()).merge(day, 1, Integer::sum);
            } catch (DateTimeParseException | NumberFormatException e) {
                // Not a step after all.
            }
        }
        String folder = folder(fileName);
        Map<Long, Sessions.Steps> steps = new HashMap<>();
        byDay.forEach((id, days) -> steps.put(id, new Sessions.Steps(id, days, folder)));
        return steps;
    }

    /**
     * Two logs' steps added up: a session resumed in another run of the CLI has steps in both. Each log is counted
     * once, however often it is read.
     */
    public static Map<Long, Sessions.Steps> add(Map<Long, Sessions.Steps> a, Map<Long, Sessions.Steps> b) {
        Map<Long, Sessions.Steps> sum = new HashMap<>(a);
        b.forEach((id, steps) -> sum.merge(id, steps, (x, y) -> {
            Map<LocalDate, Integer> days = new TreeMap<>(x.byDay());
            y.byDay().forEach((day, count) -> days.merge(day, count, Integer::sum));
            return new Sessions.Steps(id, days, x.folder().isBlank() ? y.folder() : x.folder());
        }));
        return sum;
    }

    /**
     * One log as last read: its size and time, so it is read again only when it has changed, and its sessions' steps,
     * kept after the log is gone, since the CLI's logs live in the temporary folder.
     */
    public record Read(long size, long modified, Map<Long, Sessions.Steps> steps) {
    }

    /** The logs read so far as JSON: per log, its size, time and sessions' steps by day. No prompt, no code. */
    public static String write(Map<String, Read> logs) {
        JsonObject root = new JsonObject();
        logs.forEach((name, read) -> {
            JsonObject log = new JsonObject();
            log.addProperty("size", read.size());
            log.addProperty("modified", read.modified());
            JsonObject sessions = new JsonObject();
            read.steps().forEach((id, steps) -> {
                JsonObject days = new JsonObject();
                steps.byDay().forEach((day, count) -> days.addProperty(day.toString(), count));
                sessions.add(Long.toString(id), days);
            });
            log.add("sessions", sessions);
            root.add(name, log);
        });
        return root.toString();
    }

    /** What {@link #write} wrote; empty when the text is not that. */
    public static Map<String, Read> parse(String json) {
        Map<String, Read> logs = new HashMap<>();
        try {
            for (Map.Entry<String, JsonElement> log : JsonParser.parseString(json).getAsJsonObject().entrySet()) {
                JsonObject o = log.getValue().getAsJsonObject();
                Map<Long, Sessions.Steps> steps = new HashMap<>();
                for (Map.Entry<String, JsonElement> session : o.getAsJsonObject("sessions").entrySet()) {
                    long id = Long.parseLong(session.getKey());
                    Map<LocalDate, Integer> days = new TreeMap<>();
                    session.getValue().getAsJsonObject().entrySet()
                            .forEach(d -> days.put(LocalDate.parse(d.getKey()), d.getValue().getAsInt()));
                    steps.put(id, new Sessions.Steps(id, days, folder(log.getKey())));
                }
                logs.put(log.getKey(), new Read(o.get("size").getAsLong(), o.get("modified").getAsLong(), steps));
            }
            return logs;
        } catch (RuntimeException e) {
            return new HashMap<>();
        }
    }
}
