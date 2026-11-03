package duowatchdog.plugin;

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

/** The tool window's two tabs: Usage, the dashboard of the period, and Statistics, the last twelve months. */
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
        toolWindow.getContentManager().addContentManagerListener(new ContentManagerListener() {
            @Override
            public void selectionChanged(@NotNull ContentManagerEvent event) {
                if (event.getContent() == history) {
                    statistics.shown(event.getOperation() == ContentManagerEvent.ContentOperation.add);
                }
            }
        });
        toolWindow.getContentManager().setSelectedContent(usage);
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
