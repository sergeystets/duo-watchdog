package duowatchdog.core;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Duo CLI session in progress, from the log of the CLI's run, read line by line as the log grows: the agent's
 * steps (model calls), the prompts sent (round trips), the model, and ≈ credits, each step at its model's rate per
 * call ({@link ModelCredits}). Three kinds of line are looked at, and of them only the time, the session and one field:
 * <ul>
 *   <li>{@code [DuoWorkflowNodeExecutor][<session>] [requestID: <id>] Action received}: a step, counted once per
 *       session and request, as {@link DuoCliLogs} counts them;</li>
 *   <li>{@code [RunController] Executing workflow: {"type": "SEND_PROMPT", …}}: a round trip, counted; the prompt
 *       itself is not read;</li>
 *   <li>{@code [ModelResolverService] Using user-selected model: "<identifier>"}: the model of the steps after it.</li>
 * </ul>
 * Each is known by its tag coming before any quote or brace, so a prompt or code that quotes one is not taken for it.
 * No more than the start of a line is looked at, and only counts, session numbers and the model are kept. A prompt
 * names no session: it goes to the session of the next step. Each run of the CLI writes a log of its own, so a session
 * resumed from an earlier run counts what this run did.
 */
public final class LiveSession {

    /** No more of a line than this is looked at: a step's session and request, a prompt's type, a model. */
    public static final int HEAD = 400;

    /** The line's time, to the second and its milliseconds, then anything but a quote or a brace up to the tag. */
    private static final String TIME = "^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:[:.](\\d{3}))?\\S*\\s[^{\"]*?";
    private static final Pattern STEP = Pattern.compile(
            TIME + "\\[DuoWorkflowNodeExecutor]\\[(\\d{1,18})]\\s*\\[requestID:\\s*([^\\]\\s]+)\\s*]\\s*Action received");
    private static final Pattern PROMPT = Pattern.compile(
            TIME + "\\[RunController]\\s*Executing workflow:\\s*\\{\\s*\"type\"\\s*:\\s*\"SEND_PROMPT\"");
    private static final Pattern MODEL = Pattern.compile(
            TIME + "\\[ModelResolverService]\\s*Using user-selected model:\\s*\"([\\w.:@/-]{1,80})\"");

    /** The round trips per session recommended at most, unless the settings say otherwise. */
    public static final int RECOMMENDED_ROUND_TRIPS = 20;
    /** A log that has gone this long without a line is a run waiting for its user, or one that ended. */
    public static final Duration QUIET = Duration.ofMinutes(1);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** The zone of the machine the CLI runs on, which its log's times are in. */
    private final ZoneId zone;
    private final Set<String> seen = new HashSet<>();
    private final Map<Long, Tally> sessions = new HashMap<>();
    /** The session of the latest step. */
    private Long last;
    /** Prompts sent since the latest step: the next step's session's; and when the first of them was. */
    private int waiting;
    private Instant waitingSince;
    /** The model as last logged, and the first one, for the steps before it. */
    private String model;
    private String firstModel;

    /** One session's steps, by the model in use at each ("" before any), its round trips, and when it started. */
    private static final class Tally {
        Instant started;
        int steps;
        int roundTrips;
        final Map<String, Integer> byModel = new HashMap<>();
    }

    /**
     * What the meter shows.
     *
     * @param session the session's number, as the Duo CLI shows it; null before its first step
     * @param model   the model's GitLab identifier as last logged, such as {@code claude_opus_5}; null when none was
     * @param credits ≈ credits: each step at its model's rate per call; null when a model is not in the table
     * @param started its first prompt or step in this run; null when the log's time does not read
     */
    public record Meter(Long session, int steps, int roundTrips, String model, Double credits, Instant started) {
    }

    /** One run of the CLI that is still writing its log: the log's name, when it was last written, and its meter. */
    public record Run(String log, long modified, Meter meter) {

        /** The folder the CLI runs in, as the log's name gives it. */
        public String folder() {
            return DuoCliLogs.folder(log);
        }
    }

    /** @param zone the zone the log's times are in: the one of the machine the CLI runs on */
    public LiveSession(ZoneId zone) {
        this.zone = zone;
    }

    /** Takes in the log's next line. */
    public void line(String text) {
        String head = text.length() > HEAD ? text.substring(0, HEAD) : text;
        Matcher m;
        if (head.contains("Action received") && (m = STEP.matcher(head)).find()) {
            if (seen.add(m.group(3) + "/" + m.group(4))) {
                step(Long.parseLong(m.group(3)), time(m));
            }
        } else if (head.contains("SEND_PROMPT") && (m = PROMPT.matcher(head)).find()) {
            if (waiting++ == 0) {
                waitingSince = time(m);
            }
        } else if (head.contains("user-selected model") && (m = MODEL.matcher(head)).find()) {
            model = m.group(3);
            if (firstModel == null) {
                firstModel = model;
            }
        }
    }

    private Instant time(Matcher m) {
        try {
            return LocalDateTime.parse(m.group(1)).atZone(zone).toInstant()
                    .plusMillis(m.group(2) != null ? Integer.parseInt(m.group(2)) : 0);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private void step(long session, Instant at) {
        Tally t = sessions.computeIfAbsent(session, id -> new Tally());
        if (t.started == null) {
            t.started = waiting > 0 && waitingSince != null ? waitingSince : at;
        }
        t.steps++;
        t.roundTrips += waiting;
        t.byModel.merge(model == null ? "" : model, 1, Integer::sum);
        waiting = 0;
        last = session;
    }

    /** The session of the latest step, with the prompts sent since; null while the log has neither. */
    public Meter meter() {
        if (last == null && waiting == 0) {
            return null;
        }
        Tally t = last == null ? new Tally() : sessions.get(last);
        Double credits = 0.0;
        for (Map.Entry<String, Integer> e : t.byModel.entrySet()) {
            Double rate = ModelCredits.perCall(e.getKey().isEmpty() ? firstModel : e.getKey());
            if (rate == null) {
                credits = null;
                break;
            }
            credits += rate * e.getValue();
        }
        if (t.steps == 0 && ModelCredits.perCall(model) == null) {
            credits = null;
        }
        return new Meter(last, t.steps, t.roundTrips + waiting, model, credits, last == null ? waitingSince : t.started);
    }

    /**
     * The folder part of the name of the log the CLI writes when run in a folder: its last three names joined with
     * "-", as the CLI makes it.
     */
    public static String folderKey(String path) {
        if (path == null) {
            return "";
        }
        String[] names = Arrays.stream(path.split("[/\\\\]")).filter(n -> !n.isEmpty()).toArray(String[]::new);
        return String.join("-", Arrays.copyOfRange(names, Math.max(names.length - 3, 0), names.length))
                .replaceAll("[<>:\"/\\\\|?*]", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
    }

    /**
     * The run to show for a project: the CLI's in the project's folder, else the one written to last; null for none.
     *
     * @param runs most recently written first
     */
    public static Run pick(List<Run> runs, String projectPath) {
        String key = folderKey(projectPath);
        return runs.stream().filter(r -> !key.isEmpty() && r.folder().equalsIgnoreCase(key)).findFirst()
                .orElse(runs.isEmpty() ? null : runs.get(0));
    }

    /** "42 steps". */
    public static String steps(int steps) {
        return steps + (steps == 1 ? " step" : " steps");
    }

    /** "5 round trips". */
    public static String roundTrips(int trips) {
        return trips + (trips == 1 ? " round trip" : " round trips");
    }

    /** "Opus 5 · 42 steps · 5 round trips". */
    public static String what(Meter m) {
        return (m.model() != null ? ModelCredits.name(m.model()) + " · " : "") + steps(m.steps()) + " · " + roundTrips(m.roundTrips());
    }

    /** "≈ 38.2 credits", or why there is no estimate. */
    public static String credits(Meter m) {
        if (m.credits() != null) {
            return "≈ " + CreditsView.number(m.credits()) + " credits";
        }
        return m.model() == null ? "No ≈ credits: the log names no model"
                : "No ≈ credits: no rate known for " + m.model();
    }

    /** How the estimate is made, and what it is not. */
    public static String basis(Meter m) {
        Double rate = ModelCredits.perCall(m.model());
        return "An estimate, updated as the session runs: GitLab bills each model call at its model's rate"
                + (rate != null ? " (" + ModelCredits.name(m.model()) + ": " + rate + " credits)" : "")
                + ", and a step is a model call the Duo CLI logged. GitLab's own credits come per day and feature, hours"
                + " later. Tokens are not shown: GitLab bills per call, not per token.";
    }

    /** How far the session's round trips are from the recommended most: amber from 80 % of it, red at it. */
    public enum Level { NORMAL, NEAR, OVER }

    /** @param limit the recommended round trips per session; 0 for no colour */
    public static Level level(Meter m, int limit) {
        if (limit <= 0) {
            return Level.NORMAL;
        }
        return m.roundTrips() >= limit ? Level.OVER : m.roundTrips() >= limit * 0.8 ? Level.NEAR : Level.NORMAL;
    }

    /** How long the session has run: to now while its log is being written, else to the log's last write. */
    public static Duration runTime(Run run, Instant now) {
        Instant started = run.meter() != null ? run.meter().started() : null;
        if (started == null) {
            return null;
        }
        Instant written = Instant.ofEpochMilli(run.modified());
        Instant end = Duration.between(written, now).compareTo(QUIET) <= 0 ? now : written;
        return end.isAfter(started) ? Duration.between(started, end) : Duration.ZERO;
    }

    /** "45s", "12m 4s", "1h 2m". */
    public static String duration(Duration d) {
        long s = d.getSeconds();
        return s < 60 ? s + "s" : s < 3600 ? s / 60 + "m " + s % 60 + "s" : s / 3600 + "h " + s % 3600 / 60 + "m";
    }

    /** The status bar's "12m 4s · 5 round trips · 42 steps · ≈ $38.2": a credit costs $1 at GitLab's list price. */
    public static String barText(Run run, Instant now) {
        Meter m = run.meter();
        Duration ran = runTime(run, now);
        return (ran != null ? duration(ran) + " · " : "") + roundTrips(m.roundTrips()) + " · " + steps(m.steps())
                + (m.credits() != null && m.steps() > 0 ? " · ≈ $" + CreditsView.number(m.credits()) : "");
    }

    /** "Started 10:00, running 12m 4s", or "…, 12m 4s, quiet since 10:12"; null when its start is not known. */
    public static String started(Run run, Instant now, ZoneId zone) {
        Duration ran = runTime(run, now);
        if (ran == null) {
            return null;
        }
        Instant written = Instant.ofEpochMilli(run.modified());
        boolean running = Duration.between(written, now).compareTo(QUIET) <= 0;
        return startedAt(run.meter(), zone) + (running ? ", running " + duration(ran)
                : ", " + duration(ran) + ", quiet since " + CLOCK.format(written.atZone(zone)));
    }

    /** "Started 10:00"; null when its start is not known. */
    public static String startedAt(Meter m, ZoneId zone) {
        return m.started() != null ? "Started " + CLOCK.format(m.started().atZone(zone)) : null;
    }

    /** "5 of 20 recommended round trips"; null when there is no limit. */
    public static String limitText(Meter m, int limit) {
        return limit <= 0 ? null : m.roundTrips() + " of " + limit + " recommended round trips";
    }

    /** The status bar tooltip on the session, in HTML. */
    public static String tooltipHtml(Run run, Instant now, ZoneId zone, int limit) {
        Meter m = run.meter();
        String started = started(run, now, zone);
        String limited = limitText(m, limit);
        return "<html><b>Duo CLI, now</b>" + (m.session() != null ? " — session " + m.session() : " — a new session")
                + "<br>" + CreditsView.escape(what(m))
                + "<br>" + CreditsView.escape(credits(m)) + (m.credits() != null ? ", an estimate: steps × the model's rate per call" : "")
                + (started != null ? "<br>" + started : "")
                + (limited != null ? "<br>" + (level(m, limit) == Level.NORMAL ? limited : "<b>" + limited + "</b>") : "")
                + "<br>Click for the Sessions tab.</html>";
    }
}
