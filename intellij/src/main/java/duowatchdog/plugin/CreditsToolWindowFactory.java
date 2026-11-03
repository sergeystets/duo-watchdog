package duowatchdog.plugin;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.content.ContentManagerListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The tool window's tabs: Usage, the dashboard of the period; Statistics, the months before; Sessions, what each
 * session cost.
 */
public final class CreditsToolWindowFactory implements ToolWindowFactory, DumbAware {

    /** Must match the {@code toolWindow id} in plugin.xml. */
    static final String ID = "Duo Watchdog";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        CreditsPanel panel = new CreditsPanel(project);
        Content usage = ContentFactory.getInstance().createContent(panel, "Usage", false);
        usage.setDisposer(panel);
        usage.setPreferredFocusableComponent(panel.focusTarget());
        toolWindow.getContentManager().addContent(usage);

        StatisticsPanel statistics = new StatisticsPanel(project);
        Content history = ContentFactory.getInstance().createContent(statistics, "Statistics", false);
        history.setDisposer(statistics);
        history.setPreferredFocusableComponent(statistics.focusTarget());
        toolWindow.getContentManager().addContent(history);

        SessionsPanel sessions = new SessionsPanel(project);
        Content perSession = ContentFactory.getInstance().createContent(sessions, "Sessions", false);
        perSession.setDisposer(sessions);
        perSession.setPreferredFocusableComponent(sessions.focusTarget());
        toolWindow.getContentManager().addContent(perSession);
        // Statistics and Sessions ask GitLab only while they show.
        toolWindow.getContentManager().addContentManagerListener(new ContentManagerListener() {
            @Override
            public void selectionChanged(@NotNull ContentManagerEvent event) {
                boolean shown = event.getOperation() == ContentManagerEvent.ContentOperation.add;
                if (event.getContent() == history) {
                    statistics.shown(shown);
                } else if (event.getContent() == perSession) {
                    sessions.shown(shown);
                }
            }
        });
        toolWindow.getContentManager().setSelectedContent(usage);
        // The tool window's ⋮ menu: what to send when something goes wrong.
        DefaultActionGroup gear = new DefaultActionGroup();
        gear.add(ActionManager.getInstance().getAction("duowatchdog.CopyDiagnostics"));
        gear.add(ActionManager.getInstance().getAction("duowatchdog.WhatsNew"));
        toolWindow.setAdditionalGearActions(gear);
    }

    static void show(@Nullable Project project) {
        if (project == null || project.isDisposed()) {
            return;
        }
        ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(ID);
        if (window != null) {
            window.show();
        }
    }
}
