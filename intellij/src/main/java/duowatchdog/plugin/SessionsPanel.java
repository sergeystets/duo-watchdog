package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ex.ComboBoxAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import duowatchdog.core.CreditsView;
import duowatchdog.core.GitLabAddress;
import duowatchdog.core.GitLabException;
import duowatchdog.core.History;
import duowatchdog.core.Sessions;
import duowatchdog.core.Snapshot;
import duowatchdog.core.ui.LiveSessionView;
import duowatchdog.core.ui.SessionsView;
import duowatchdog.plugin.CreditsSettings.Account;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tool window's Sessions tab: the chosen account's Agent Platform sessions over the chosen days, with what each
 * cost, estimated. A session can be looked up by its number, or the last one shown, wherever it falls. Above them,
 * while the Duo CLI runs a session, what it has used so far ({@link LiveMeter}).
 */
final class SessionsPanel extends SimpleToolWindowPanel implements Disposable {

    /** The days to show, as offered; a custom range is set by the dialog or by a session looked up. */
    private enum Preset {
        TODAY("Today", 1), YESTERDAY("Yesterday", 1), WEEK("Last 7 Days", 7), MONTH("Last 30 Days", Sessions.KEPT_DAYS);

        final String text;
        final int days;

        Preset(String text, int days) {
            this.text = text;
            this.days = days;
        }

        Sessions.Range range(LocalDate today) {
            return this == YESTERDAY ? new Sessions.Range(today.minusDays(1), today.minusDays(1)) : Sessions.Range.last(days, today);
        }
    }

    private final SessionsView view;
    private final LiveSessionView live;
    private final SearchTextField search = new SearchTextField(false);
    private Preset preset = Preset.WEEK;
    /** The days shown when not a preset's. */
    private @Nullable Sessions.Range custom;
    /** The session whose details show, else null. */
    private @Nullable Long focus;
    private boolean visible;
    /** Raised by each load, so an answer to an older one is dropped. */
    private int ticket;
    /** What the last load was for, so a refresh of the same account and days does not ask GitLab again. */
    private String loadedFor = "";

    /** What the last load found, and for which account and days: kept to filter and pick from without asking again. */
    private String foundFor = "";
    private List<Sessions.Estimate> estimates = List.of();
    private @Nullable History history;
    private String group = "";
    private String scope = "";
    private int left;

    SessionsPanel(Project project) {
        super(true, true);
        Color background = JBUI.CurrentTheme.ToolWindow.background();
        view = new SessionsView(DuoIcons.theme());
        view.setBackground(background);
        view.setFocusable(true);
        view.listen(new SessionsView.Listener() {
            @Override
            public void pick(long id) {
                focus = id;
                render();
            }

            @Override
            public void open(String url) {
                BrowserUtil.browse(url);
            }

            @Override
            public void close() {
                focus = null;
                render();
            }
        });
        JScrollPane scroll = ScrollPaneFactory.createScrollPane(view, true);
        scroll.getViewport().setBackground(background);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        live = new LiveSessionView(DuoIcons.theme());
        live.setBackground(background);
        live.onPick(this::find);
        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(background);
        content.add(live, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        setContent(content);

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(DumbAwareAction.create("Reload Sessions", AllIcons.Actions.Refresh, e -> load(true)));
        actions.add(new RangeChooser());
        actions.add(new LastSession());
        actions.addSeparator();
        actions.add(new CreditsPanel.AccountSwitcher(project));
        ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("DuoWatchdogSessions", actions, true);
        toolbar.setTargetComponent(this);

        search.getTextEditor().getEmptyText().setText("Session ID or link, or a title or project");
        search.getTextEditor().setToolTipText("A session's number, as the Duo CLI shows it, or its link, then Enter;"
                + " other text narrows the list");
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                if (Sessions.id(search.getText()) == null) {
                    render();
                }
            }
        });
        search.getTextEditor().addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    Long id = Sessions.id(search.getText());
                    if (id != null) {
                        find(id);
                    }
                }
            }
        });

        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(JBUI.Borders.customLineBottom(JBColor.border()));
        bar.add(toolbar.getComponent(), BorderLayout.NORTH);
        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setOpaque(false);
        searchRow.setBorder(JBUI.Borders.empty(0, 6, 4, 6));
        searchRow.add(search, BorderLayout.CENTER);
        bar.add(searchRow, BorderLayout.SOUTH);
        setToolbar(bar);

        MessageBusConnection bus = ApplicationManager.getApplication().getMessageBus().connect(this);
        bus.subscribe(CreditsListener.TOPIC, () -> {
            if (visible) {
                load(false);
            }
        });
        bus.subscribe(LiveMeter.Listener.TOPIC, () -> live.show(LiveMeter.getInstance().meter(project)));
        LiveMeter.getInstance().start();
        live.show(LiveMeter.getInstance().meter(project));
        view.showMessage("Sessions load when this tab is shown.", false);
    }

    /** The tab was shown or hidden. */
    void shown(boolean shown) {
        visible = shown;
        if (shown) {
            load(false);
        }
    }

    JComponent focusTarget() {
        return search;
    }

    private Sessions.Range range() {
        return custom != null ? custom : preset.range(Sessions.today());
    }

    private String rangeText() {
        if (custom == null) {
            return preset.text;
        }
        java.time.format.DateTimeFormatter day = java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
        return custom.from().equals(custom.to()) ? day.format(custom.from())
                : day.format(custom.from()) + " – " + day.format(custom.to());
    }

    /** Reads the sessions and the months they fall in off the EDT, from the caches unless {@code force}. */
    private void load(boolean force) {
        CreditsService service = CreditsService.getInstance();
        Account account = service.selected();
        Sessions.Range range = range();
        String key = (account != null ? account.id() : "") + "|" + range;
        if (!force && key.equals(loadedFor)) {
            return;
        }
        if (account == null) {
            loadedFor = "";
            view.showMessage(CreditsView.reason(service.selectedSnapshot()), false);
            return;
        }
        Snapshot snapshot = service.snapshot(account.id());
        if (!StatisticsPanel.readable(snapshot)) {
            // Not loaded: a later refresh may make it readable.
            loadedFor = "";
            view.showMessage(CreditsView.reason(snapshot), snapshot.status() == Snapshot.Status.REJECTED);
            return;
        }
        int mine = ++ticket;
        loadedFor = key;
        view.showMessage("Reading sessions from GitLab…", false);
        String accountGroup = GitLabAddress.groupPath(account.namespace, account.gitlabUrl);
        String accountScope = account.label().substring(0, account.label().indexOf(" · "))
                + (account.namespace.isBlank() ? "" : " · " + account.namespace);
        // The months from the range's first to this one, as Statistics asks for them; at most a year.
        int months = (int) Math.min(ChronoUnit.MONTHS.between(YearMonth.from(range.from()), YearMonth.now()) + 1, History.MONTHS);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                List<Sessions.Session> all = service.sessions(account, force, range.from());
                History usage = service.history(account, force, Math.max(months, 1));
                Map<Long, Sessions.Steps> steps = CliLogs.steps();
                List<Sessions.Session> here = all.stream().filter(s -> Sessions.inGroup(s, accountGroup)).toList();
                int elsewhere = (int) all.stream().filter(s -> !Sessions.inGroup(s, accountGroup))
                        .filter(s -> new Sessions.Estimate(s, steps.get(s.id()), List.of()).in(range)).count();
                List<Sessions.Estimate> estimated = Sessions.estimate(here, steps, usage);
                DuoLog.info(account.id() + " sessions " + range.from() + ".." + range.to() + ": " + here.size()
                        + " in the group, " + elsewhere + " elsewhere, " + here.stream().filter(s -> steps.containsKey(s.id())).count()
                        + " with steps in the Duo CLI's logs");
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (mine != ticket) {
                        return;
                    }
                    foundFor = key;
                    estimates = estimated;
                    history = usage;
                    group = accountGroup;
                    scope = accountScope;
                    left = elsewhere;
                    render();
                });
            } catch (GitLabException e) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (mine != ticket) {
                        return;
                    }
                    loadedFor = "";
                    view.showMessage("GitLab gave no sessions: " + e.getMessage(), true);
                });
            }
        });
    }

    /** Shows what the last load found, narrowed by the search text, with the picked session's details. */
    private void render() {
        if (history == null || !foundFor.equals(loadedFor)) {
            return;
        }
        String text = search.getText().trim().toLowerCase(Locale.ROOT);
        List<Sessions.Estimate> shown = text.isEmpty() || Sessions.id(text) != null ? estimates
                : estimates.stream().filter(e -> matches(e, text)).toList();
        Sessions.Estimate focused = focus == null ? null : estimates.stream()
                .filter(e -> e.session().id() == focus && e.in(range())).findFirst().orElse(null);
        view.show(Sessions.summarize(shown, history, range(), group), focused, scope, group, left, ZoneId.systemDefault());
    }

    private boolean matches(Sessions.Estimate e, String text) {
        Sessions.Session s = e.session();
        return contains(s.title(), text) || contains(Sessions.place(s, e.steps(), group), text) || contains(s.flow(), text)
                || contains(s.status(), text) || contains(s.model(), text);
    }

    private static boolean contains(@Nullable String field, String text) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(text);
    }

    /**
     * Shows a session's details: at once when it is in the days shown, else after asking GitLab for it and showing
     * the days it ran on.
     */
    private void find(long id) {
        if (estimates.stream().anyMatch(e -> e.session().id() == id && e.in(range()))) {
            focus = id;
            render();
            return;
        }
        Account account = CreditsService.getInstance().selected();
        if (account == null) {
            return;
        }
        int mine = ++ticket;
        view.showMessage("Looking for session " + id + "…", false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String error = null;
            Sessions.Session found = null;
            try {
                found = CreditsService.getInstance().session(account, id);
            } catch (GitLabException e) {
                error = e.getMessage();
            }
            show(mine, account, found, error == null ? "GitLab has no session " + id + " for @" + account.username
                    + ": it may be another account's, or older than GitLab keeps." : error);
        });
    }

    /** Asks GitLab for the session last updated and shows it. */
    private void last() {
        Account account = CreditsService.getInstance().selected();
        if (account == null) {
            return;
        }
        int mine = ++ticket;
        view.showMessage("Looking for your last session…", false);
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String error = null;
            Sessions.Session found = null;
            try {
                found = CreditsService.getInstance().lastSession(account);
            } catch (GitLabException e) {
                error = e.getMessage();
            }
            show(mine, account, found, error == null ? "@" + account.username + " has no session GitLab still lists." : error);
        });
    }

    /**
     * Shows a session looked up: in the days shown when it falls in them, else in the days it ran on; or why not, when
     * there is none or it ran in another group.
     */
    private void show(int mine, Account account, @Nullable Sessions.Session found, String otherwise) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (mine != ticket) {
                return;
            }
            String accountGroup = GitLabAddress.groupPath(account.namespace, account.gitlabUrl);
            if (found == null || !Sessions.inGroup(found, accountGroup)) {
                loadedFor = "";
                view.showMessage(found == null ? otherwise : "Session " + found.id() + ", \"" + found.title()
                        + "\", ran in " + (found.project() != null ? found.project() : found.group())
                        + ", outside " + accountGroup + ": its credits are another group's. Pick the account of that"
                        + " group to see it.", false);
                return;
            }
            focus = found.id();
            Sessions.Range around = Sessions.Range.around(found);
            if (!(range().contains(around.from()) && range().contains(around.to()))) {
                custom = around;
            }
            loadedFor = "";
            load(false);
        });
    }

    @Override
    public void dispose() {
        ticket++;
    }

    /** "Last 7 Days ▾": which days to show. */
    private final class RangeChooser extends ComboBoxAction implements DumbAware {

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setText(rangeText(), false);
            e.getPresentation().setDescription("Which days' sessions to show");
        }

        @Override
        protected @NotNull DefaultActionGroup createPopupActionGroup(@NotNull JComponent button, @NotNull DataContext context) {
            DefaultActionGroup group = new DefaultActionGroup();
            for (Preset p : Preset.values()) {
                group.add(DumbAwareAction.create(p.text, e -> {
                    preset = p;
                    custom = null;
                    load(false);
                }));
            }
            group.addSeparator();
            group.add(DumbAwareAction.create("Custom Range…", e -> {
                RangeDialog dialog = new RangeDialog(range());
                if (dialog.showAndGet()) {
                    custom = dialog.range();
                    load(false);
                }
            }));
            return group;
        }
    }

    /** "Last Session": the session last updated, wherever it falls. */
    private final class LastSession extends DumbAwareAction {

        LastSession() {
            super("Last Session", "Show the session you last worked in", null);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            last();
        }
    }

    /** Two days, from and to, as yyyy-MM-dd. */
    private static final class RangeDialog extends DialogWrapper {

        private final JBTextField from = new JBTextField(10);
        private final JBTextField to = new JBTextField(10);

        RangeDialog(Sessions.Range range) {
            super(true);
            setTitle("Sessions: Custom Range");
            from.setText(range.from().toString());
            to.setText(range.to().toString());
            init();
        }

        @Override
        protected @Nullable JComponent createCenterPanel() {
            return FormBuilder.createFormBuilder()
                    .addLabeledComponent("From:", from)
                    .addLabeledComponent("To:", to)
                    .addComponentToRightColumn(new JBLabel("Days as yyyy-MM-dd, in UTC. GitLab lists sessions for about "
                            + Sessions.KEPT_DAYS + " days.").withFont(JBUI.Fonts.smallFont()))
                    .getPanel();
        }

        @Override
        public @Nullable JComponent getPreferredFocusedComponent() {
            return from;
        }

        @Override
        protected @Nullable ValidationInfo doValidate() {
            LocalDate a = day(from);
            LocalDate b = day(to);
            if (a == null) {
                return new ValidationInfo("A day such as 2026-10-28", from);
            }
            if (b == null) {
                return new ValidationInfo("A day such as 2026-10-28", to);
            }
            if (b.isBefore(a)) {
                return new ValidationInfo("Not before the first day", to);
            }
            if (b.isAfter(Sessions.today())) {
                return new ValidationInfo("Not after today", to);
            }
            return null;
        }

        Sessions.Range range() {
            return new Sessions.Range(day(from), day(to));
        }

        private static @Nullable LocalDate day(JBTextField field) {
            try {
                return LocalDate.parse(field.getText().trim());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }
}
