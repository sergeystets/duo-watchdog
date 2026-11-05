package duowatchdog.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.terminal.ui.TerminalWidget;
import com.jediterm.terminal.TtyConnector;
import org.jetbrains.plugins.terminal.TerminalToolWindowManager;

/**
 * The one class that uses the Terminal plugin, an optional dependency: without it, loading this class fails, and
 * {@link CliLauncher} says the plugin is needed.
 */
final class CliTerminal {

    /** Between the typed command and its Enter, so the prompt takes them as typing rather than one paste. */
    private static final long ENTER_MS = 300;

    private CliTerminal() {
    }

    /** Opens a terminal tab in the project's folder and runs the command in it; on the EDT. */
    static CliLauncher.Typist open(Project project, String tabName, String command) {
        TerminalWidget widget = TerminalToolWindowManager.getInstance(project)
                .createShellWidget(project.getBasePath(), tabName, true, true);
        widget.sendCommandToExecute(command);
        return text -> {
            TtyConnector tty = widget.getTtyConnector();
            if (tty == null) {
                // The reworked terminal keeps its process out of reach: it types what it is sent, then Enter.
                ApplicationManager.getApplication().invokeLater(() -> widget.sendCommandToExecute(text));
                return true;
            }
            if (!tty.isConnected()) {
                return false;
            }
            tty.write(text);
            Thread.sleep(ENTER_MS);
            tty.write("\r");
            return true;
        };
    }
}
