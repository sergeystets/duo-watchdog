package duowatchdog.core;

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

    /** The line's time, then anything but a quote or a brace up to the tag. */
    private static final String TIME = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\S*\\s[^{\"]*?";
    private static final Pattern STEP = Pattern.compile(
            TIME + "\\[DuoWorkflowNodeExecutor]\\[(\\d{1,18})]\\s*\\[requestID:\\s*([^\\]\\s]+)\\s*]\\s*Action received");
    private static final Pattern PROMPT = Pattern.compile(
            TIME + "\\[RunController]\\s*Executing workflow:\\s*\\{\\s*\"type\"\\s*:\\s*\"SEND_PROMPT\"");
    private static final Pattern MODEL = Pattern.compile(
            TIME + "\\[ModelResolverService]\\s*Using user-selected model:\\s*\"([\\w.:@/-]{1,80})\"");

    private final Set<String> seen = new HashSet<>();
    private final Map<Long, Tally> sessions = new HashMap<>();
    /** The session of the latest step. */
    private Long last;
    /** Prompts sent since the latest step: the next step's session's. */
    private int waiting;
    /** The model as last logged, and the first one, for the steps before it. */
    private String model;
    private String firstModel;

    /** One session's steps, by the model in use at each ("" before any), and its round trips. */
    private static final class Tally {
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
     */
    public record Meter(Long session, int steps, int roundTrips, String model, Double credits) {
    }

    /** One run of the CLI that is still writing its log: the log's name, when it was last written, and its meter. */
    public record Run(String log, long modified, Meter meter) {

        /** The folder the CLI runs in, as the log's name gives it. */
        public String folder() {
            return DuoCliLogs.folder(log);
        }
    }

    /** Takes in the log's next line. */
    public void line(String text) {
        String head = text.length() > HEAD ? text.substring(0, HEAD) : text;
        Matcher m;
        if (head.contains("Action received") && (m = STEP.matcher(head)).find()) {
            if (seen.add(m.group(1) + "/" + m.group(2))) {
                step(Long.parseLong(m.group(1)));
            }
        } else if (head.contains("SEND_PROMPT") && PROMPT.matcher(head).find()) {
            waiting++;
        } else if (head.contains("user-selected model") && (m = MODEL.matcher(head)).find()) {
            model = m.group(1);
            if (firstModel == null) {
                firstModel = model;
            }
        }
    }

    private void step(long session) {
        Tally t = sessions.computeIfAbsent(session, id -> new Tally());
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
        return new Meter(last, t.steps, t.roundTrips + waiting, model, credits);
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

    /** The status bar's "CLI 42 steps ≈ $38.2": a credit costs $1 at GitLab's list price. */
    public static String statusText(Meter m) {
        return "CLI " + steps(m.steps()) + (m.credits() != null && m.steps() > 0 ? " ≈ $" + CreditsView.number(m.credits()) : "");
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

    /** The status bar tooltip's lines on the session, in HTML. */
    public static String tooltipHtml(Meter m) {
        return "<b>Duo CLI, now</b>" + (m.session() != null ? " — session " + m.session() : " — a new session")
                + "<br>" + CreditsView.escape(what(m))
                + "<br>" + CreditsView.escape(credits(m)) + (m.credits() != null ? ", an estimate: steps × the model's rate per call" : "");
    }

    /** A tooltip with the session's lines added before its last line, "Click for details.". */
    public static String addTo(String tooltipHtml, Meter m) {
        String end = "<br>Click for details.</html>";
        String block = "<br><br>" + tooltipHtml(m);
        return tooltipHtml.endsWith(end) ? tooltipHtml.substring(0, tooltipHtml.length() - end.length()) + block + end
                : tooltipHtml.replace("</html>", block + "</html>");
    }
}
