package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.JBPopup;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.Accounts;
import duowatchdog.core.CreditsReport;
import duowatchdog.core.CreditsView;
import duowatchdog.core.Snapshot;
import duowatchdog.core.ui.Dashboard;
import duowatchdog.plugin.CreditsSettings.Account;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The tool window: Refresh, Settings and the account it shows, with "@user · group · Updated today 14:05 UTC+2",
 * then the Duo CLI's quick actions ({@link CliActions}), over the dashboard.
 */
final class CreditsPanel extends SimpleToolWindowPanel implements Disposable {

    private final Dashboard dashboard;
    /** Whose credits, in which group, and when: the IDE shows header actions only on hover, so this is no header action. */
    private final JBLabel byline = new JBLabel();
    private final ActionToolbar toolbar;

    CreditsPanel(Project project) {
        super(true, true);
        Color background = JBUI.CurrentTheme.ToolWindow.background();
        dashboard = new Dashboard(DuoIcons.theme(), () -> openSettings(project));
        dashboard.setBackground(background);
        // Something to focus makes the tool window active when clicked, as the IDE's own are: Shift+Esc hides it,
        // Esc goes back to the editor, and the arrows scroll.
        dashboard.setFocusable(true);
        JScrollPane scroll = ScrollPaneFactory.createScrollPane(dashboard, true);
        scroll.getViewport().setBackground(background);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        setContent(scroll);

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(ActionManager.getInstance().getAction(RefreshCreditsAction.ID));
        actions.add(new OpenSettingsAction());
        actions.addSeparator();
        actions.add(new AccountSwitcher(project));
        // "Update to 0.7.0", shown only once a newer release is known; not in the build for JetBrains Marketplace.
        AnAction update = Channel.toolbarAction();
        if (update != null) {
            actions.add(update);
        }
        toolbar = ActionManager.getInstance().createActionToolbar("DuoWatchdog", actions, true);
        toolbar.setTargetComponent(this);
        byline.setFont(JBFont.small());
        byline.setForeground(UIUtil.getContextHelpForeground());
        byline.setBorder(JBUI.Borders.emptyRight(12));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        bar.add(toolbar.getComponent(), BorderLayout.WEST);
        bar.add(byline, BorderLayout.EAST);

        ActionToolbar cliToolbar = ActionManager.getInstance().createActionToolbar("DuoWatchdogCli",
                CliActions.group(project), true);
        cliToolbar.setTargetComponent(this);
        JBLabel cliLabel = new JBLabel("Duo CLI");
        cliLabel.setFont(JBFont.small());
        cliLabel.setForeground(UIUtil.getContextHelpForeground());
        cliLabel.setBorder(JBUI.Borders.emptyLeft(10));
        JPanel cliRow = new JPanel(new BorderLayout());
        cliRow.setOpaque(false);
        cliRow.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        cliRow.add(cliLabel, BorderLayout.WEST);
        cliRow.add(cliToolbar.getComponent(), BorderLayout.CENTER);

        JPanel bars = new JPanel(new BorderLayout());
        bars.setOpaque(false);
        bars.add(bar, BorderLayout.NORTH);
        bars.add(cliRow, BorderLayout.SOUTH);
        setToolbar(bars);

        ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(CreditsListener.TOPIC, this::render);
        CreditsService.getInstance().start();
        render();
    }

    private void render() {
        CreditsService service = CreditsService.getInstance();
        Account account = service.selected();
        Snapshot snapshot = service.selectedSnapshot();
        dashboard.show(snapshot, account != null ? account.limit : 0, CreditsSettings.getInstance().percents(), LocalDate.now());
        List<String> parts = new ArrayList<>();
        CreditsReport report = snapshot.report();
        if (report != null && report.username() != null && service.sorted().size() < 2) {
            // With several accounts the switcher names it.
            parts.add("@" + report.username());
            if (account != null && !account.namespace.isBlank()) {
                parts.add(account.namespace.trim());
            }
        }
        if (snapshot.fetchedAt() != null) {
            ZoneId zone = ZoneId.systemDefault();
            parts.add("Updated " + CreditsView.clockText(snapshot.fetchedAt(), LocalDate.now(zone), zone));
        }
        byline.setText(String.join(" · ", parts));
    }

    /** What the tool window focuses when it is opened or clicked. */
    JComponent focusTarget() {
        return dashboard;
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

    /**
     * "@dana.k · acme ▾": which account the dashboard and the status bar show. Its list puts the most credits left
     * first, and typing filters it.
     */
    static final class AccountSwitcher extends ComboBoxAction implements DumbAware {

        private final Project project;

        AccountSwitcher(Project project) {
            this.project = project;
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            CreditsService service = CreditsService.getInstance();
            Account account = service.selected();
            e.getPresentation().setVisible(account != null);
            if (account != null) {
                e.getPresentation().setText("@" + account.username
                        + (account.namespace.isBlank() ? "" : " · " + account.namespace.trim()), false);
                e.getPresentation().setDescription("The account shown: " + account.label());
            }
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
            CreditsService service = CreditsService.getInstance();
            Account shown = service.selected();
            List<Integer> percents = CreditsSettings.getInstance().percents();
            LocalDate today = LocalDate.now();
            DefaultActionGroup group = new DefaultActionGroup();
            for (Account account : service.sorted()) {
                Snapshot s = service.snapshot(account.id());
                String text = account.label() + "   " + summary(account, s);
                Icon icon = shown != null && account.id().equals(shown.id()) ? AllIcons.Actions.Checked
                        : switch (Accounts.attention(s, account.limit, percents, account.expiry(), today)) {
                            case NONE -> null;
                            case WARNING -> AllIcons.General.Warning;
                            case ERROR -> AllIcons.General.Error;
                        };
                group.add(DumbAwareAction.create(text, icon, e -> service.select(account.id())));
            }
            group.addSeparator();
            group.add(DumbAwareAction.create("Add Token…", AllIcons.General.Add,
                    e -> CreditsService.openSettings("", true)));
            group.add(DumbAwareAction.create("Manage Accounts…", AllIcons.General.Settings, e -> openSettings(project)));
            return group;
        }

        @Override
        protected JBPopup createActionPopup(DefaultActionGroup group, @NotNull DataContext context, Runnable disposeCallback) {
            return JBPopupFactory.getInstance().createActionGroupPopup(null, group, context,
                    JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true, disposeCallback, 20);
        }

        /** "$148.6 / 300", or why there are no numbers. */
        private static String summary(Account account, Snapshot s) {
            return switch (s.status()) {
                case NO_TOKEN -> "no token";
                case NOT_TRACKED -> "not tracked";
                case REJECTED -> "token turned down";
                case NOT_CONFIGURED -> "no group yet";
                default -> s.report() == null ? "" : CreditsView.statusText(s, account.limit);
            };
        }
    }
}
