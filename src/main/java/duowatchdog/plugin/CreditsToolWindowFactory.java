package duowatchdog.plugin;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class CreditsToolWindowFactory implements ToolWindowFactory, DumbAware {

    /** Must match the {@code toolWindow id} in plugin.xml. */
    static final String ID = "Duo Watchdog";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        CreditsPanel panel = new CreditsPanel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
        toolWindow.setTitleActions(List.of(new IdentityAction(toolWindow.getDisposable())));
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
