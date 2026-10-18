package duowatchdog.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.Consumer;
import duowatchdog.core.CreditsView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.time.ZoneId;

/** "Duo 42.4/100" (used/limit) in the status bar; the tooltip summarises, a click opens the tool window. */
final class CreditsWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    static final String ID = "DuoWatchdog";

    private final Project project;

    CreditsWidget(Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String ID() {
        return ID;
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(CreditsListener.TOPIC, snapshot -> statusBar.updateWidget(ID));
        CreditsService.getInstance().start();
    }

    @Override
    public @NotNull String getText() {
        return CreditsView.statusText(CreditsService.getInstance().snapshot(), CreditsSettings.getInstance().getState().limit);
    }

    @Override
    public float getAlignment() {
        return Component.CENTER_ALIGNMENT;
    }

    @Override
    public @Nullable String getTooltipText() {
        return CreditsView.tooltipHtml(CreditsService.getInstance().snapshot(), CreditsSettings.getInstance().getState().limit,
                LocalDate.now(), ZoneId.systemDefault());
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return event -> {
            ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(CreditsToolWindowFactory.ID);
            if (window != null) {
                window.show();
            }
        };
    }

    @Override
    public void dispose() {
    }
}
