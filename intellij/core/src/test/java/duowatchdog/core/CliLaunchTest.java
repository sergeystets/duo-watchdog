package duowatchdog.core;

import duowatchdog.core.CliLaunch.Cli;
import duowatchdog.core.CliLaunch.Mode;
import duowatchdog.core.CliLaunch.QuickCommand;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CliLaunchTest {

    private static final QuickCommand AB = new QuickCommand("AB Testing", "/ab-testing");

    @Test
    void theCliStartsOnItsOwnOrThroughGlabAskingUnlessAutoApproveIsPicked() {
        assertEquals("duo", CliLaunch.command(Cli.DUO, Mode.ASK));
        assertEquals("duo --dangerously-skip-permissions", CliLaunch.command(Cli.DUO, Mode.AUTO_APPROVE));
        assertEquals("glab duo cli", CliLaunch.command(Cli.GLAB, Mode.ASK));
        assertEquals("glab duo cli --dangerously-skip-permissions", CliLaunch.command(Cli.GLAB, Mode.AUTO_APPROVE));
    }

    @Test
    void aSettingThatIsNoneOfTheChoicesStartsDuoAsking() {
        assertEquals(Cli.GLAB, Cli.of("glab duo cli"));
        assertEquals(Cli.DUO, Cli.of("duo; rm -rf ~"));
        assertEquals(Cli.DUO, Cli.of(null));
        assertEquals(Mode.AUTO_APPROVE, Mode.of("autoApprove"));
        assertEquals(Mode.ASK, Mode.of("yolo"));
        assertEquals(Mode.ASK, Mode.of(null));
    }

    @Test
    void aQuickCommandsCommandIsOneLineASlashCommandOrAPrompt() {
        assertEquals("/ab-testing", CliLaunch.commandText("/ab-testing"));
        assertEquals("/ab-testing", CliLaunch.commandText("  /ab-testing \r\n"));
        assertEquals("review the open merge request", CliLaunch.commandText("review the open merge request"));
        assertNull(CliLaunch.commandText(""));
        assertNull(CliLaunch.commandText("/"));
        assertNull(CliLaunch.commandText("/ab\nrm -rf ~"));
        assertNull(CliLaunch.commandText("/a\rb"));
        assertNull(CliLaunch.commandText("/ab\u001b[A"));
        assertNull(CliLaunch.commandText("/ab\u001b"));
        assertNull(CliLaunch.commandText("/" + "a".repeat(200)));
        assertNull(CliLaunch.commandText(null));
        assertEquals("Type what it types at the Duo CLI's prompt, such as /ab-testing", CliLaunch.commandProblem("/"));
        assertEquals("One line, with no control characters", CliLaunch.commandProblem("a\nb"));
    }

    @Test
    void aNameIsOneLineNotTakenThoughTheCommandEditedMayKeepItsOwn() {
        assertNull(CliLaunch.nameProblem("AB Testing", List.of(), -1));
        assertEquals("Give its button a name, such as AB Testing", CliLaunch.nameProblem(" ", List.of(), -1));
        assertEquals("At most 40 characters", CliLaunch.nameProblem("a".repeat(41), List.of(), -1));
        assertEquals("There is a button named ab testing already", CliLaunch.nameProblem("ab testing", List.of(AB), -1));
        assertEquals("There is a button named Doctor already", CliLaunch.nameProblem("Doctor", List.of(), -1));
        assertNull(CliLaunch.nameProblem("ab testing", List.of(AB), 0));
    }

    @Test
    void theNameStartsFromTheCommand() {
        assertEquals("Ab Testing", CliLaunch.suggestedName("/ab-testing"));
        assertEquals("Review Mr", CliLaunch.suggestedName("/review_mr now"));
        assertEquals("Review my changes", CliLaunch.suggestedName("review my changes"));
        assertEquals(40, CliLaunch.suggestedName("x".repeat(60)).length());
    }

    @Test
    void aSettingKeepsTheQuickCommandsThatCanBeEachNameOnceAtMostTwelve() {
        assertEquals(List.of(), CliLaunch.quickCommands(List.of()));
        List<QuickCommand> saved = new ArrayList<>(Arrays.asList(
                new QuickCommand(" AB Testing ", " /ab-testing "), new QuickCommand("ab testing", "/other"),
                new QuickCommand("Two lines", "a\nb"), new QuickCommand("No command", null), null));
        assertEquals(List.of(AB), CliLaunch.quickCommands(saved));
        List<QuickCommand> many = IntStream.range(0, 20).mapToObj(i -> new QuickCommand("C" + i, "/c" + i)).toList();
        assertEquals(CliLaunch.MAX_QUICK_COMMANDS, CliLaunch.quickCommands(many).size());
    }

    @Test
    void theRunsLogIsTheFirstOneNotThereBefore() {
        Set<String> before = Set.of("duo-cli-log-2026-11-05T09-00-00-a.log", "duo-cli-log-2026-11-05T08-00-00-a.log");
        assertNull(CliLaunch.newLog(before, List.copyOf(before)));
        assertNull(CliLaunch.newLog(before, List.of()));
        assertEquals("duo-cli-log-2026-11-05T10-00-00-a.log", CliLaunch.newLog(before,
                List.of("duo-cli-log-2026-11-05T10-00-00-a.log", "duo-cli-log-2026-11-05T09-00-00-a.log")));
    }

    @Test
    void aButtonSaysWhatItRuns() {
        assertEquals("Runs glab duo cli --dangerously-skip-permissions in a new terminal",
                CliLaunch.title(Cli.GLAB, Mode.AUTO_APPROVE, null));
        assertEquals("Runs duo in a new terminal, then types /ab-testing once it has started",
                CliLaunch.title(Cli.DUO, Mode.ASK, "/ab-testing"));
        assertEquals("Ask before tools", Mode.ASK.label());
        assertEquals("Auto-approve tools", Mode.AUTO_APPROVE.label());
    }
}
