package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import duowatchdog.core.CreditsView;
import duowatchdog.core.Snapshot;
import org.jetbrains.annotations.NotNull;

import javax.swing.JEditorPane;
import java.time.LocalDate;
import java.time.ZoneId;

/** The tool window page: the period, used, left, projection, features and recent days. */
final class CreditsPanel extends SimpleToolWindowPanel implements Disposable {

    private final JEditorPane page = new JEditorPane();

    CreditsPanel() {
        super(true, true);
        page.setEditable(false);
        page.setEditorKit(HTMLEditorKitBuilder.simple());
        page.setBorder(JBUI.Borders.empty(8));
        setContent(ScrollPaneFactory.createScrollPane(page, true));

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(ActionManager.getInstance().getAction(RefreshCreditsAction.ID));
        actions.add(new OpenSettingsAction());
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("DuoWatchdog", actions, true);
        toolbar.setTargetComponent(this);
        setToolbar(toolbar.getComponent());

        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(CreditsListener.TOPIC, this::render);
        CreditsService.getInstance().start();
        render(CreditsService.getInstance().snapshot());
    }

    private void render(Snapshot snapshot) {
        page.setText(CreditsView.detailsHtml(snapshot, CreditsSettings.getInstance().getState().limit,
                LocalDate.now(), ZoneId.systemDefault()));
        page.setCaretPosition(0);
    }

    @Override
    public void dispose() {
    }

    private static final class OpenSettingsAction extends AnAction implements DumbAware {

        OpenSettingsAction() {
            super("Settings…", "Open Duo Watchdog settings", AllIcons.General.Settings);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            ShowSettingsUtil.getInstance().showSettingsDialog(e.getProject(), CreditsConfigurable.class);
        }
    }
}
