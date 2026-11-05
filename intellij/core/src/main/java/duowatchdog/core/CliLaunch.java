package duowatchdog.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The quick actions on Usage: the Duo CLI started in a new terminal, as {@code duo} or as {@code glab duo cli} (which
 * hands its arguments to {@code duo}), asking before each tool or not. New Session only starts it; Doctor then types
 * {@code /doctor} at its prompt, and each quick command of your own what you gave it, such as {@code /ab-testing}.
 * Nothing is typed until the CLI has started a log of its own, so a CLI that does not start never gets the command
 * typed into the shell. The VS Code extension has a port of it.
 */
public final class CliLaunch {

    /** The CLI's flag that runs every tool without asking. */
    public static final String SKIP_PERMISSIONS = "--dangerously-skip-permissions";
    /** The CLI's diagnostics, as typed at its prompt. */
    public static final String DOCTOR = "/doctor";
    /** Quick commands of your own, at most. */
    public static final int MAX_QUICK_COMMANDS = 12;
    private static final int MAX_NAME = 40;
    private static final int MAX_COMMAND = 200;
    /** The buttons Usage has anyway: a quick command may not take their names. */
    private static final Set<String> BUILT_IN = Set.of("new session", "doctor");

    /** How the CLI is started: on its own, or through glab. */
    public enum Cli {
        DUO("duo"), GLAB("glab duo cli");

        private final String command;

        Cli(String command) {
            this.command = command;
        }

        /** "duo", "glab duo cli": also the setting's value. */
        public String command() {
            return command;
        }

        /** The one a setting names; duo for anything else. */
        public static Cli of(String value) {
            for (Cli cli : values()) {
                if (cli.command.equals(value)) {
                    return cli;
                }
            }
            return DUO;
        }
    }

    /** Whether the CLI asks before it runs a tool. */
    public enum Mode {
        ASK("ask", "Ask before tools", "The CLI asks before it runs each tool"),
        AUTO_APPROVE("autoApprove", "Auto-approve tools", "With " + SKIP_PERMISSIONS + ": the CLI runs every tool without asking");

        private final String id;
        private final String label;
        private final String title;

        Mode(String id, String label, String title) {
            this.id = id;
            this.label = label;
            this.title = title;
        }

        /** "ask", "autoApprove": the setting's value. */
        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        /** What the mode does, for a tooltip. */
        public String title() {
            return title;
        }

        /** The one a setting names; asking for anything else. */
        public static Mode of(String value) {
            for (Mode mode : values()) {
                if (mode.id.equals(value)) {
                    return mode;
                }
            }
            return ASK;
        }
    }

    /** A quick command of your own: its button's name, and what it types at the CLI's prompt once the CLI has started. */
    public record QuickCommand(String name, String command) {
    }

    private CliLaunch() {
    }

    /** "duo", or "glab duo cli --dangerously-skip-permissions". */
    public static String command(Cli cli, Mode mode) {
        return cli.command() + (mode == Mode.AUTO_APPROVE ? " " + SKIP_PERMISSIONS : "");
    }

    /** The text without the spaces and line ends around it. */
    private static String trimmed(String value) {
        return value == null ? "" : value.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$", "");
    }

    private static boolean hasControl(String text) {
        return text.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    /** Why the text cannot be a quick command's command; null when it can. */
    public static String commandProblem(String value) {
        String text = trimmed(value);
        return text.isEmpty() || text.equals("/") ? "Type what it types at the Duo CLI's prompt, such as /ab-testing"
                : text.length() > MAX_COMMAND ? "At most " + MAX_COMMAND + " characters"
                : hasControl(text) ? "One line, with no control characters"
                : null;
    }

    /** "/ab-testing" from " /ab-testing "; null when it cannot be a command. */
    public static String commandText(String value) {
        return commandProblem(value) == null ? trimmed(value) : null;
    }

    /**
     * Why the text cannot name a quick command; null when it can.
     *
     * @param editing the index of the command being edited, which may keep its name; -1 for a new one
     */
    public static String nameProblem(String value, List<QuickCommand> commands, int editing) {
        String text = trimmed(value);
        if (text.isEmpty()) {
            return "Give its button a name, such as AB Testing";
        }
        if (text.length() > MAX_NAME) {
            return "At most " + MAX_NAME + " characters";
        }
        if (hasControl(text)) {
            return "One line, with no control characters";
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int taken = -1;
        for (int i = 0; i < commands.size() && taken < 0; i++) {
            if (commands.get(i).name().toLowerCase(Locale.ROOT).equals(lower)) {
                taken = i;
            }
        }
        return taken >= 0 && taken != editing || BUILT_IN.contains(lower)
                ? "There is a button named " + text + " already" : null;
    }

    /** "Ab Testing" from "/ab-testing", "Review my changes" from "review my changes": a name to start from. */
    public static String suggestedName(String command) {
        String text = trimmed(command);
        Stream<String> words = text.startsWith("/")
                ? Stream.of(text.substring(1).split("\\s", 2)[0].split("[-_.:]+"))
                : Stream.of(text);
        String name = words.filter(w -> !w.isEmpty()).map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).collect(Collectors.joining(" "));
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME - 1).stripTrailing() + "…" : name;
    }

    /** The quick commands of a setting that can be: each name once, at most twelve, trimmed. */
    public static List<QuickCommand> quickCommands(List<QuickCommand> saved) {
        List<QuickCommand> commands = new ArrayList<>();
        for (QuickCommand c : saved) {
            String command = c != null ? commandText(c.command()) : null;
            if (command != null && commands.size() < MAX_QUICK_COMMANDS && nameProblem(c.name(), commands, -1) == null) {
                commands.add(new QuickCommand(trimmed(c.name()), command));
            }
        }
        return commands;
    }

    /** The log of a run started since {@code before} was taken: the first log not in it; null while there is none. */
    public static String newLog(Set<String> before, Collection<String> now) {
        return now.stream().filter(name -> !before.contains(name)).findFirst().orElse(null);
    }

    /** "Runs duo in a new terminal, then types /doctor once it has started": what a button does, for its tooltip. */
    public static String title(Cli cli, Mode mode, String typed) {
        return "Runs " + command(cli, mode) + " in a new terminal"
                + (typed != null ? ", then types " + typed + " once it has started" : "");
    }
}
