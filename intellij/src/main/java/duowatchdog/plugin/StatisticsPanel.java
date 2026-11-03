package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.util.ui.JBUI;
import duowatchdog.core.CreditsView;
import duowatchdog.core.GitLabException;
import duowatchdog.core.History;
import duowatchdog.core.Snapshot;
import duowatchdog.core.ui.Statistics;
import duowatchdog.plugin.CreditsSettings.Account;
import org.jetbrains.annotations.NotNull;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The tool window's Statistics tab: the chosen account's last twelve months, or all accounts' together, read when
 * the tab is shown and kept for an hour.
 */
final class StatisticsPanel extends SimpleToolWindowPanel implements Disposable {

    private final Statistics statistics;
    private boolean allAccounts;
    /** Whether the tab is showing, so a refresh elsewhere does not ask GitLab for a year nobody looks at. */
    private boolean visible;
    /** Raised by each load, so an answer to an older one is dropped. */
    private int ticket;
    /** What the last load was for, so a refresh of the same account does not ask GitLab again. */
    private String loadedFor = "";

    StatisticsPanel(Project project) {
        super(true, true);
        Color background = JBUI.CurrentTheme.ToolWindow.background();
        statistics = new Statistics(DuoIcons.theme());
        statistics.setBackground(background);
        statistics.setFocusable(true);
        JScrollPane scroll = ScrollPaneFactory.createScrollPane(statistics, true);
        scroll.getViewport().setBackground(background);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        setContent(scroll);

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(DumbAwareAction.create("Reload Statistics", AllIcons.Actions.Refresh, e -> load(true)));
        actions.add(new RangeChooser());
        actions.add(new AllAccountsToggle());
        actions.addSeparator();
        actions.add(new CreditsPanel.AccountSwitcher(project));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("DuoWatchdogStatistics", actions, true);
        toolbar.setTargetComponent(this);
        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        bar.add(toolbar.getComponent(), BorderLayout.WEST);
        setToolbar(bar);

        ApplicationManager.getApplication().getMessageBus().connect(this)
                .subscribe(CreditsListener.TOPIC, () -> {
                    if (visible) {
                        load(false);
                    }
                });
        statistics.showMessage("Statistics load when this tab is shown.", false);
    }

    /** The tab was shown or hidden. */
    void shown(boolean shown) {
        visible = shown;
        if (shown) {
            load(false);
        }
    }

    JComponent focusTarget() {
        return statistics;
    }

    /** Reads the year off the EDT, from the cache unless {@code force}. */
    private void load(boolean force) {
        CreditsService service = CreditsService.getInstance();
        Account account = service.selected();
        int count = months();
        String key = (allAccounts ? "*" : account != null ? account.id() : "") + "|" + count;
        if (!force && key.equals(loadedFor)) {
            return;
        }
        if (account == null) {
            loadedFor = "";
            statistics.showMessage(CreditsView.reason(service.selectedSnapshot()), false);
            return;
        }
        List<Account> accounts = allAccounts ? readable(service.sorted()) : List.of(account);
        if (!allAccounts) {
            Snapshot snapshot = service.snapshot(account.id());
            if (!readable(snapshot)) {
                // Not loaded: a later refresh may make it readable.
                loadedFor = "";
                statistics.showMessage(CreditsView.reason(snapshot), snapshot.status() == Snapshot.Status.REJECTED);
                return;
            }
        } else if (accounts.isEmpty()) {
            // Not loaded: a later refresh may make one readable.
            loadedFor = "";
            statistics.showMessage("No account with readable usage yet.", false);
            return;
        }
        int mine = ++ticket;
        loadedFor = key;
        statistics.showMessage("Reading " + count + " months from GitLab…", false);
        String scope = allAccounts ? "All accounts" : account.label().substring(0, account.label().indexOf(" · "))
                + (account.namespace.isBlank() ? "" : " · " + account.namespace);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<History> histories = new ArrayList<>();
            String error = null;
            for (Account a : accounts) {
                try {
                    histories.add(service.history(a, force, count));
                } catch (GitLabException e) {
                    error = (allAccounts ? "@" + a.username + " on " + a.host() + ": " : "") + e.getMessage();
                }
            }
            String why = error;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (mine != ticket) {
                    return;
                }
                if (histories.isEmpty()) {
                    loadedFor = "";
                    statistics.showMessage("GitLab gave no history: " + why, true);
                } else {
                    statistics.show(histories.size() == 1 ? histories.get(0) : History.merge(histories), LocalDate.now(),
                            scope + (why != null && allAccounts ? " (one account did not load)" : ""));
                }
            });
        });
    }

    private static boolean readable(Snapshot s) {
        return s.status() != Snapshot.Status.NO_TOKEN && s.status() != Snapshot.Status.NOT_TRACKED
                && s.status() != Snapshot.Status.REJECTED && s.status() != Snapshot.Status.NOT_CONFIGURED;
    }

    private static List<Account> readable(List<Account> accounts) {
        CreditsService service = CreditsService.getInstance();
        return accounts.stream().filter(a -> readable(service.snapshot(a.id()))).toList();
    }

    @Override
    public void dispose() {
        ticket++;
    }

    /** How many months to show, as chosen: 3, 6 or 12; 3 until chosen. */
    private static int months() {
        int chosen = CreditsSettings.getInstance().getState().statisticsMonths;
        return History.RANGES.contains(chosen) ? chosen : History.RANGES.get(0);
    }

    /** "3 months ▾": how far back to look. Fewer months, fewer questions to GitLab. */
    private final class RangeChooser extends ComboBoxAction implements DumbAware {

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(months() + " months", false);
            e.getPresentation().setDescription("How many months Statistics shows");
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
            DefaultActionGroup group = new DefaultActionGroup();
            for (int count : History.RANGES) {
                group.add(DumbAwareAction.create(count + " months", e -> {
                    CreditsSettings.getInstance().getState().statisticsMonths = count;
                    load(false);
                }));
            }
            return group;
        }
    }

    /** "All Accounts": every account's months added up, features merged by name. */
    private final class AllAccountsToggle extends ToggleAction implements DumbAware {

        AllAccountsToggle() {
            super("All Accounts", "Add up every account's usage", AllIcons.Actions.GroupBy);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return allAccounts;
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            allAccounts = state;
            load(false);
        }
    }
}
