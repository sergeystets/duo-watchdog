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
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.CreditsReport;
import duowatchdog.core.Snapshot;
import duowatchdog.core.ui.Dashboard;
import org.jetbrains.annotations.NotNull;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** The tool window: Refresh, Settings and "@user · group · Updated HH:mm" over the dashboard. */
final class CreditsPanel extends SimpleToolWindowPanel implements Disposable {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final Dashboard dashboard;
    /** Whose credits, in which group, and when: the IDE shows header actions only on hover, so this is no header action. */
    private final JBLabel byline = new JBLabel();

    CreditsPanel(Project project) {
        super(true, true);
        Color background = JBUI.CurrentTheme.ToolWindow.background();
        dashboard = new Dashboard(DuoIcons.theme(), () -> openSettings(project));
        dashboard.setBackground(background);
        JScrollPane scroll = ScrollPaneFactory.createScrollPane(dashboard, true);
        scroll.getViewport().setBackground(background);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        setContent(scroll);

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(ActionManager.getInstance().getAction(RefreshCreditsAction.ID));
        actions.add(new OpenSettingsAction());
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("DuoWatchdog", actions, true);
        toolbar.setTargetComponent(this);
        byline.setFont(JBFont.small());
        byline.setForeground(UIUtil.getContextHelpForeground());
        byline.setBorder(JBUI.Borders.emptyRight(12));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        bar.add(toolbar.getComponent(), BorderLayout.WEST);
        bar.add(byline, BorderLayout.EAST);
        setToolbar(bar);

        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(CreditsListener.TOPIC, this::render);
        CreditsService.getInstance().start();
        render(CreditsService.getInstance().snapshot());
    }

    private void render(Snapshot snapshot) {
        CreditsSettings settings = CreditsSettings.getInstance();
        dashboard.show(snapshot, settings.getState().limit, settings.percents(), LocalDate.now());
        List<String> parts = new ArrayList<>();
        CreditsReport report = snapshot.report();
        if (report != null && report.username() != null) {
            parts.add("@" + report.username());
        }
        String group = settings.getState().namespace;
        if (report != null && group != null && !group.isBlank()) {
            parts.add(group.trim());
        }
        if (snapshot.fetchedAt() != null) {
            parts.add("Updated " + TIME.format(snapshot.fetchedAt().atZone(ZoneId.systemDefault())));
        }
        byline.setText(String.join(" · ", parts));
    }

    private static void openSettings(Project project) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, CreditsConfigurable.class);
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
            openSettings(e.getProject());
        }
    }
}
