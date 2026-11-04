package duowatchdog.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.StatusBarWidgetFactory;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

public final class LiveWidgetFactory implements StatusBarWidgetFactory {

    @Override
    public @NotNull String getId() {
        return LiveWidget.ID;
    }

    @Override
    public @Nls @NotNull String getDisplayName() {
        return "Duo Watchdog: Duo CLI Session";
    }

    @Override
    public @NotNull StatusBarWidget createWidget(@NotNull Project project) {
        return new LiveWidget(project);
    }

    @Override
    public void disposeWidget(@NotNull StatusBarWidget widget) {
        Disposer.dispose(widget);
    }
}
