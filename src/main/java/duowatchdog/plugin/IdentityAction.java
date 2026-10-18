package duowatchdog.plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.CreditsReport;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;

/** "@jane.doe · acme" at the right of the tool window's header: whose credits these are, and in which group. */
final class IdentityAction extends AnAction implements CustomComponentAction, DumbAware {

    private final Disposable parent;

    IdentityAction(Disposable parent) {
        this.parent = parent;
    }

    static String identity() {
        CreditsReport report = CreditsService.getInstance().snapshot().report();
        String group = CreditsSettings.getInstance().getState().namespace;
        String who = report != null && report.username() != null ? "@" + report.username() : "";
        String where = group == null ? "" : group.trim();
        return who.isEmpty() || where.isEmpty() ? who + where : who + " · " + where;
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        e.getPresentation().setEnabledAndVisible(!identity().isEmpty());
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
    }

    @Override
    public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
        JBLabel label = new JBLabel(identity());
        label.setFont(JBFont.small());
        label.setForeground(UIUtil.getContextHelpForeground());
        label.setBorder(JBUI.Borders.empty(0, 4, 0, 8));
        ApplicationManager.getApplication().getMessageBus().connect(parent)
                .subscribe(CreditsListener.TOPIC, snapshot -> label.setText(identity()));
        return label;
    }

    @Override
    public void updateCustomComponent(@NotNull JComponent component, @NotNull Presentation presentation) {
        ((JBLabel) component).setText(identity());
    }
}
