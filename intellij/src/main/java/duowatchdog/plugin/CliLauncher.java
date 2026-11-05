package duowatchdog.plugin;

import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import duowatchdog.core.CliLaunch;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * New Session, Doctor and the quick commands of your own on Usage: the Duo CLI in a new tab of the IDE's terminal, in
 * the project's folder, started as the drop-downs beside them say ({@code duo} or {@code glab duo cli}, asking before
 * tools or not). A command is typed once the CLI has started a log of its own, which {@link LiveMeter} sees within its
 * two-second poll. When none comes within a minute nothing is typed, so a CLI that did not start never
 * gets the command typed into the shell. A port of cliLaunch.ts in the VS Code extension.
 */
final class CliLauncher {

    /** What types at the prompt of the terminal the CLI runs in. */
    interface Typist {
        /** Types the text, then Enter; false when the terminal is gone. */
        boolean type(String text) throws IOException, InterruptedException;
    }

    /** How long the CLI gets to start its log; after that what an action types is given up. */
    private static final Duration START = Duration.ofMinutes(1);
    private static final long CHECK_MS = 500;
    /** From the CLI's log appearing to its prompt taking what is typed. */
    private static final long SETTLE_MS = 2500;

    private CliLauncher() {
    }

    /**
     * On the EDT, as an action runs.
     *
     * @param typed what to type at the CLI's prompt; null to only start it
     * @param name  the button's name, for the terminal tab's; null for New Session
     */
    static void launch(Project project, String typed, String name) {
        CreditsSettings.Settings s = CreditsSettings.getInstance().getState();
        String command = CliLaunch.command(CliLaunch.Cli.of(s.cli), CliLaunch.Mode.of(s.cliMode));
        LiveMeter meter = LiveMeter.getInstance();
        meter.start();
        Set<String> before = meter.logs();
        Typist typist;
        try {
            typist = CliTerminal.open(project, name != null ? "Duo CLI · " + name : "Duo CLI", command);
        } catch (LinkageError e) {
            CreditsService.notification(false, "The Duo CLI needs the Terminal plugin",
                    "Turn on Terminal in Settings | Plugins, then try again.", NotificationType.WARNING).notify(project);
            return;
        }
        DuoLog.info("Duo CLI: ran " + command + " in a new terminal" + (typed != null ? ", to type " + typed : ""));
        if (typed == null) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                long deadline = System.currentTimeMillis() + START.toMillis();
                while (CliLaunch.newLog(before, meter.logs()) == null) {
                    if (project.isDisposed()) {
                        return;
                    }
                    if (System.currentTimeMillis() > deadline) {
                        DuoLog.warn("Duo CLI: no new log within a minute, so " + typed + " was not typed");
                        CreditsService.notification(false, "The Duo CLI did not start within a minute",
                                typed + " was not typed. Once the CLI shows its prompt, type it there.",
                                NotificationType.WARNING).notify(project);
                        return;
                    }
                    Thread.sleep(CHECK_MS);
                }
                Thread.sleep(SETTLE_MS);
                if (!typist.type(typed)) {
                    DuoLog.warn("Duo CLI: its terminal is gone, so " + typed + " was not typed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException | RuntimeException | LinkageError e) {
                DuoLog.warn("Duo CLI: " + typed + " not typed: " + e);
            }
        });
    }
}
