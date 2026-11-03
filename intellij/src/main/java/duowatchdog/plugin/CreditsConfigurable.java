package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionToolbarPosition;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.MessageDialogBuilder;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ColoredTableCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBRadioButton;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.Accounts;
import duowatchdog.core.Alert;
import duowatchdog.core.CreditsClient;
import duowatchdog.core.CreditsClient.GroupMatch;
import duowatchdog.core.CreditsClient.TokenInfo;
import duowatchdog.core.CreditsReport;
import duowatchdog.core.CreditsView;
import duowatchdog.core.GitLabAddress;
import duowatchdog.core.GitLabException;
import duowatchdog.core.Snapshot;
import duowatchdog.plugin.CreditsSettings.Account;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionListener;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Settings | Tools | Duo Watchdog: the accounts, one token each, most credits left first, with a filter and a
 * search; the chosen account's group, limit, token and glab hand-off; then the alerts every account shares. Nothing
 * is saved, and no token written or handed to glab, until OK or Apply.
 */
public final class CreditsConfigurable implements Configurable {

    private static final int HINT_WIDTH = 440;
    /** What the locked token field holds when a token is saved: it shows as dots, and is never the token. */
    private static final String MASK = "xxxxxxxxxxxxxxxxxxxx";
    private static final JBColor WARNING_COLOR = new JBColor(0xA35A00, 0xE3A24A);
    private static final JBColor ERROR_COLOR = new JBColor(0xC9302C, 0xE5605A);
    private static final String[] COLUMNS = {"Account", "Group", "Credits left", "Token", "Expires"};

    private enum Filter {
        ALL("All"), CREDITS("Has credits"), EXPIRED("Expired");

        final String label;

        Filter(String label) {
            this.label = label;
        }
    }

    private JPanel panel;
    private Disposable uiDisposable;

    /** The accounts as this page has them: copies of the saved ones, plus what was added, changed and removed. */
    private final List<Account> working = new ArrayList<>();
    /** New tokens, by account id, that Apply writes to the password safe. */
    private final Map<String, String> pendingTokens = new HashMap<>();
    /** Saved accounts removed here, whose tokens Apply deletes. */
    private final Set<String> removed = new HashSet<>();
    /** Whether the password safe holds each account's token; absent until read, off the EDT. */
    private final Map<String, Boolean> tokenSaved = new ConcurrentHashMap<>();
    /** The limit as typed, by account id, until it parses. */
    private final Map<String, String> limitTexts = new HashMap<>();
    private final List<Account> rows = new ArrayList<>();
    private Filter filter = Filter.ALL;
    /** True while the rows are rebuilt, so the table's own selection events leave the details alone. */
    private boolean refreshing;

    private JBTable table;
    private AccountsModel model;
    private SearchTextField search;
    private final Map<Filter, JBRadioButton> filterButtons = new EnumMap<>(Filter.class);
    private JBLabel empty;

    private JPanel details;
    private JBLabel detailsTitle;
    private JBTextField namespace;
    private JButton findGroup;
    private JBLabel groupHint;
    private JBTextField limit;
    private JBPasswordField tokenField;
    private JButton replace;
    private JBLabel tokenCaption;
    private StateBox stateBox;
    private JButton check;
    private ActionLink create;
    private JBCheckBox glab;
    private JBLabel glabNote;
    private JBCheckBox duo;
    private JBLabel duoNote;
    /** The account the details show; null when none is chosen. */
    private Account shown;
    /** True while the details are filled in, so the fields' listeners leave the account alone. */
    private boolean filling;

    private boolean checking;
    /** What Check connection found, until the account, its token or its group changes. */
    private Checked checked;
    private int checkTicket;

    private JBTextField alertPercents;
    private JBCheckBox alertOnForecast;
    private JBIntSpinner refreshMinutes;

    /** What {@link #open} asked for before the page was built. */
    private String openId;
    private boolean openReplace;

    /**
     * What Check connection found: why it failed, or whose the token is, its details when GitLab gave them, and
     * the group's credits or why they did not come.
     */
    private record Checked(String error, String user, TokenInfo info, String group, String groupError) {
    }

    @Override
    public @Nls String getDisplayName() {
        return "Duo Watchdog";
    }

    /** Chooses the account, and opens its Replace Token dialog when asked; an empty id with replace adds a token. */
    void open(String accountId, boolean replace) {
        openId = accountId;
        openReplace = replace;
        if (panel != null) {
            ApplicationManager.getApplication().invokeLater(this::openAsked, ModalityState.any());
        }
    }

    private void openAsked() {
        if (panel == null || openId == null) {
            return;
        }
        String id = openId;
        boolean replaceToo = openReplace;
        openId = null;
        Account account = find(id);
        if (account != null) {
            selectRow(account.id());
        }
        if (replaceToo) {
            addToken(account);
        }
    }

    @Override
    public @Nullable JComponent createComponent() {
        uiDisposable = Disposer.newDisposable();
        model = new AccountsModel();
        table = new JBTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new AccountsRenderer());
        table.setRowHeight(JBUI.scale(28));
        table.setShowGrid(false);
        table.getEmptyText().setText("No accounts yet: add a token with +");
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !refreshing) {
                int row = table.getSelectedRow();
                showDetails(row >= 0 && row < rows.size() ? rows.get(row) : null);
            }
        });
        JPanel list = ToolbarDecorator.createDecorator(table)
                .setToolbarPosition(ActionToolbarPosition.TOP)
                .setAddAction(button -> addToken(null))
                .setAddActionName("Add Token…")
                .setRemoveAction(button -> removeSelected())
                .setRemoveActionName("Remove Account")
                .disableUpDownActions()
                .setPreferredSize(JBUI.size(600, 170))
                .createPanel();

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0));
        filters.add(new JBLabel("Show:"));
        ButtonGroup group = new ButtonGroup();
        for (Filter f : Filter.values()) {
            JBRadioButton button = new JBRadioButton(f.label, f == filter);
            button.addActionListener(e -> {
                filter = f;
                refreshRows();
            });
            group.add(button);
            filters.add(button);
            filterButtons.put(f, button);
        }
        search = new SearchTextField(false);
        search.getTextEditor().getEmptyText().setText("Search accounts");
        search.setPreferredSize(JBUI.size(220, -1));
        search.addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                refreshRows();
            }
        });
        JPanel filterRow = new JPanel(new BorderLayout());
        filterRow.add(filters, BorderLayout.WEST);
        filterRow.add(search, BorderLayout.EAST);

        empty = hint("");
        details = createDetails();

        alertPercents = new JBTextField(8);
        alertOnForecast = new JBCheckBox("Alert when the month-end forecast passes the limit");
        refreshMinutes = new JBIntSpinner(15, 1, 1440);
        JPanel refreshRow = left(refreshMinutes);
        JBLabel minutes = new JBLabel("minutes");
        minutes.setBorder(JBUI.Borders.emptyLeft(8));
        refreshRow.add(minutes);

        panel = FormBuilder.createFormBuilder()
                .addComponent(new TitledSeparator("Accounts"))
                .addComponent(hint("One token per account. When you add a token, GitLab says whose it is: a token for "
                        + "an account already in the list replaces that account's token. The list puts the most "
                        + "credits left first."))
                .addComponent(filterRow, JBUI.scale(8))
                .addComponent(list, JBUI.scale(6))
                .addComponent(empty, JBUI.scale(8))
                .addComponent(details, JBUI.scale(12))
                .addComponent(new TitledSeparator("Alerts"), JBUI.scale(16))
                .addLabeledComponent("Alert at % of limit:", left(alertPercents))
                .addComponentToRightColumn(hint("For every account, of that account's limit."))
                .addComponentToRightColumn(alertOnForecast)
                .addLabeledComponent("Refresh every:", refreshRow)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        ApplicationManager.getApplication().getMessageBus().connect(uiDisposable)
                .subscribe(CreditsListener.TOPIC, this::refreshRows);
        reset();
        if (openId != null) {
            ApplicationManager.getApplication().invokeLater(this::openAsked, ModalityState.any());
        }
        return panel;
    }

    // --- The chosen account ---------------------------------------------------------------------------------------

    private JPanel createDetails() {
        detailsTitle = new JBLabel();
        detailsTitle.setFont(JBFont.label().asBold());
        namespace = new JBTextField();
        namespace.getEmptyText().setText("e.g. my-company, or click Find my group");
        findGroup = new JButton("Find my group", AllIcons.Actions.Find);
        findGroup.setToolTipText("Asks GitLab which of this account's groups gives it Duo credits, and fills in its path");
        findGroup.addActionListener(e -> findGroup());
        groupHint = hint("");
        limit = new JBTextField(8);
        tokenField = new JBPasswordField();
        tokenField.setEditable(false);
        tokenField.getEmptyText().setText("No token saved");
        replace = new JButton("Replace token…");
        replace.addActionListener(e -> addToken(shown));
        tokenCaption = hint("");
        stateBox = new StateBox(HINT_WIDTH - 60);
        check = new JButton("Check connection");
        check.setToolTipText("Asks GitLab whose the token is, when it expires, and for the group's credits");
        check.addActionListener(e -> checkConnection());
        create = new ActionLink("Create a new token in GitLab ↗", (ActionListener) e -> {
            if (shown != null) {
                BrowserUtil.browse(GitLabAddress.tokenPage(shown.gitlabUrl, shown.glab || shown.duo));
            }
        });
        create.setBorder(JBUI.Borders.emptyLeft(14));
        stateBox.actions.add(check);
        stateBox.actions.add(create);
        glab = new JBCheckBox("Use for glab");
        glabNote = note();
        duo = new JBCheckBox("Use for the GitLab Duo CLI");
        duoNote = note();

        onChange(namespace, () -> {
            if (!filling && shown != null) {
                shown.namespace = namespace.getText().trim();
                forgetCheck();
                groupHint.setText(wrap(groupHintText()));
                table.repaint();
            }
        });
        onChange(limit, () -> {
            if (!filling && shown != null) {
                String text = limit.getText().trim();
                limitTexts.put(shown.id(), text);
                try {
                    shown.limit = Double.parseDouble(text.replace(',', '.'));
                } catch (NumberFormatException e) {
                    // Apply says so.
                }
                table.repaint();
            }
        });
        glab.addActionListener(e -> {
            if (shown != null) {
                shown.glab = glab.isSelected();
                if (!shown.glab) {
                    shown.duo = false;
                }
                renderDetails();
            }
        });
        duo.addActionListener(e -> {
            if (shown != null) {
                shown.duo = duo.isSelected();
                if (shown.duo) {
                    shown.glab = true;
                }
                renderDetails();
            }
        });

        JPanel groupRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        groupRow.add(namespace, BorderLayout.CENTER);
        groupRow.add(findGroup, BorderLayout.EAST);
        JPanel limitRow = left(limit);
        JBLabel limitHint = new JBLabel("Your GitLab cap, or a lower budget of your own.", UIUtil.ComponentStyle.SMALL,
                UIUtil.FontColor.BRIGHTER);
        limitHint.setBorder(JBUI.Borders.emptyLeft(12));
        limitRow.add(limitHint);
        JPanel tokenRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        tokenRow.add(tokenField, BorderLayout.CENTER);
        tokenRow.add(replace, BorderLayout.EAST);

        JPanel form = FormBuilder.createFormBuilder()
                .addComponent(detailsTitle)
                .addLabeledComponent("Group path:", groupRow, JBUI.scale(8))
                .addComponentToRightColumn(groupHint)
                .addLabeledComponent("Limit (credits):", limitRow)
                .addLabeledComponent("Personal access token:", tokenRow)
                .addComponentToRightColumn(tokenCaption)
                .addComponentToRightColumn(stateBox, JBUI.scale(6))
                .addLabeledComponent("Also use for:", glab, JBUI.scale(10))
                .addComponentToRightColumn(glabNote)
                .addComponentToRightColumn(duo, JBUI.scale(4))
                .addComponentToRightColumn(duoNote)
                .getPanel();
        form.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(12, 14)));
        return form;
    }

    private void showDetails(@Nullable Account account) {
        if (account != null && account == shown) {
            renderDetails();
            return;
        }
        forgetCheck();
        shown = account;
        details.setVisible(account != null);
        if (account == null) {
            return;
        }
        filling = true;
        try {
            detailsTitle.setText("@" + account.username + " on " + account.host());
            namespace.setText(account.namespace);
            limit.setText(limitTexts.getOrDefault(account.id(), plain(account.limit)));
            groupHint.setText(wrap(groupHintText()));
        } finally {
            filling = false;
        }
        renderDetails();
    }

    /** The token row, its box, and the glab and Duo CLI boxes, for what is known now. */
    private void renderDetails() {
        Account a = shown;
        if (a == null) {
            return;
        }
        String store = CreditsSettings.storeName();
        boolean pending = pendingTokens.containsKey(a.id());
        Boolean saved = pending ? Boolean.TRUE : tokenSaved.get(a.id());
        tokenField.setText(Boolean.TRUE.equals(saved) ? MASK : "");
        replace.setText(Boolean.FALSE.equals(saved) ? "Add token…" : "Replace token…");
        if (pending) {
            tokenCaption.setText(wrap("A new token, saved to " + store + " when you click OK or Apply. It is never shown."));
            tokenCaption.setForeground(UIUtil.getContextHelpForeground());
        } else if (Boolean.FALSE.equals(saved)) {
            tokenCaption.setText(wrap("Nothing in " + store + " for this account."));
            tokenCaption.setForeground(WARNING_COLOR);
        } else {
            tokenCaption.setText(wrap("In " + store + ". The dots stand for the saved token; it is never shown."));
            tokenCaption.setForeground(UIUtil.getContextHelpForeground());
        }
        renderState(a, saved, pending);
        create.setText(Boolean.FALSE.equals(saved) ? "Create the token in GitLab ↗" : "Create a new token in GitLab ↗");

        String host = a.host();
        glab.setText("glab on " + host);
        duo.setText("the GitLab Duo CLI on " + host);
        glab.setSelected(a.glab);
        duo.setSelected(a.duo);
        Account live = CreditsSettings.getInstance().account(a.id());
        List<String> notes = new ArrayList<>();
        // glab follows the picked account, among those with glab or the Duo CLI ticked on this GitLab.
        CreditsSettings settings = CreditsSettings.getInstance();
        java.util.function.Predicate<Account> hasIt = w -> {
            Account stored = settings.account(w.id());
            return stored != null && stored.glabHasToken() && !pendingTokens.containsKey(w.id());
        };
        List<Account> allowed = working.stream()
                .filter(w -> (w.glab || w.duo) && w.host().equalsIgnoreCase(host))
                .sorted(Accounts.byCreditsLeft(this::left, w -> w.username, Account::host))
                .toList();
        Account picked = CreditsService.getInstance().selected();
        Account owner = Accounts.glabOwner(allowed, picked != null ? find(picked.id()) : null, hasIt);
        Account holder = working.stream().filter(w -> w != a && w.host().equalsIgnoreCase(host) && hasIt.test(w))
                .findFirst().orElse(null);
        // Known without waiting: looking in WSL takes a moment, so the details are drawn again when it is done.
        Boolean known = CliHandOff.installed(() -> onEdt(this::renderDetails));
        boolean installed = !Boolean.FALSE.equals(known);
        if (a.glab) {
            boolean newlyOn = live == null || !live.glab;
            if (!installed) {
                notes.add("glab is not installed where the IDE looks (" + CliHandOff.whereLooked() + "), so it "
                        + "doesn't have this token. " + CliHandOff.howToInstall() + ", and Duo Watchdog hands the token "
                        + "over on its next refresh."
                        + (CliHandOff.wslSaid().isEmpty() ? "" : " WSL said: " + CliHandOff.wslSaid()));
            } else if (hasIt.test(a)) {
                notes.add("glab signs in to " + host + " with this token.");
            } else if (live != null && live.glabRefused() && !pending) {
                notes.add("glab turned this token down the last time; the notice said why. Replace the token, or "
                        + "untick this.");
            } else if (owner == a) {
                notes.add(newlyOn || pending ? "glab gets this token for " + host + " when you click OK or Apply."
                        : "glab gets this token on Duo Watchdog's next refresh.");
            } else {
                notes.add("glab switches to this token when you pick @" + a.username + " in the tool window"
                        + (holder != null ? "; it has @" + holder.username + "'s now." : "."));
            }
            String scopes = Accounts.glabScopeNote(a.scopeList());
            if (scopes != null) {
                notes.add(scopes);
            }
        } else {
            notes.add("Off: picking @" + a.username + " leaves glab's login alone. glab keeps one token per GitLab and "
                    + "follows the account you pick, among those with this ticked.");
        }
        if (!installed && !a.glab) {
            notes.add("glab is not installed where the IDE looks: " + CliHandOff.whereLooked() + ".");
        }
        glabNote.setText(wrap(String.join(" ", notes)));
        String duoText = "The Duo CLI signs in through glab (glab 1.85.2 and duo 8.68.0 or later), so this turns glab on too.";
        String duoScopes = Accounts.duoScopeNote(a.scopeList());
        if (a.duo && duoScopes != null) {
            duoText += " " + duoScopes + " Create one with the link above, then Replace token….";
        }
        if (a.duo && CliHandOff.duoHasOwnLogin()) {
            duoText += " It keeps settings of its own in ~/.gitlab/storage.json; if it signed in there, it keeps "
                    + "that login: delete its settings there to make it use glab's.";
        }
        duoNote.setText(wrap(duoText));
        if (panel != null) {
            panel.revalidate();
        }
    }

    /** The token box: a check running or done, a new token, a missing one, one GitLab turns down, or the saved one. */
    private void renderState(Account a, @Nullable Boolean saved, boolean pending) {
        check.setEnabled(!checking && Boolean.TRUE.equals(saved));
        LocalDate today = LocalDate.now();
        Snapshot snapshot = CreditsService.getInstance().snapshot(a.id());
        String store = CreditsSettings.storeName();
        if (checking) {
            stateBox.show(StateBox.Look.BUSY, "Checking the connection to " + a.host() + "…", List.of());
        } else if (checked != null && checked.error() != null) {
            stateBox.show(StateBox.Look.ERROR, "Connection failed", List.of(escape(checked.error())));
        } else if (checked != null) {
            List<String> lines = new ArrayList<>();
            if (checked.group() != null) {
                lines.add(escape(checked.group()));
            }
            if (checked.groupError() != null) {
                lines.add(escape(checked.groupError()));
            }
            if (checked.info() != null) {
                a.remember(checked.info());
            }
            StateBox.Look look = describe(a, pending, today, lines);
            if (checked.groupError() != null && look == StateBox.Look.OK) {
                look = StateBox.Look.WARNING;
            }
            stateBox.show(look, "Connected to " + a.host() + " as @" + checked.user(), lines);
        } else if (pending) {
            List<String> lines = new ArrayList<>();
            describe(a, true, today, lines);
            stateBox.show(StateBox.Look.INFO, "New token, not saved yet", lines);
        } else if (Boolean.FALSE.equals(saved)) {
            stateBox.show(StateBox.Look.WARNING, "No token for this account",
                    List.of("Nothing in " + escape(store) + " for @" + escape(a.username) + ": it was deleted there, "
                            + "or the IDE keeps passwords only until it restarts. Add token… saves one."));
        } else if (snapshot.status() == Snapshot.Status.REJECTED) {
            List<String> lines = new ArrayList<>();
            describe(a, false, today, lines);
            lines.add("GitLab turns it down: it has expired or was revoked. Create a new one and replace this one.");
            stateBox.show(StateBox.Look.ERROR, "The saved token no longer works", lines);
        } else if (!Accounts.readsUsage(a.scopeList())) {
            List<String> lines = new ArrayList<>();
            describe(a, false, today, lines);
            lines.add("<b>No ai_features scope</b>: GitLab does not show this account's Duo usage to the token, so "
                    + "there is nothing to track. glab and the Duo CLI can still use it.");
            stateBox.show(StateBox.Look.WARNING, "Token saved, but its usage is not tracked", lines);
        } else {
            List<String> lines = new ArrayList<>();
            StateBox.Look look = describe(a, false, today, lines);
            stateBox.show(look, look == StateBox.Look.OK ? "Token saved"
                    : Accounts.expiresWithin(a.expiry(), today, Accounts.EXPIRY_WARNING_DAYS)
                    ? "Token saved, but it expires soon" : "Token saved, but it lacks a scope", lines);
        }
    }

    /**
     * Adds what is known of the account's token to {@code lines}: when it was saved, when it expires, its scopes.
     * Returns WARNING_COLOR when it expires within a week or lacks a scope, else OK.
     */
    private static StateBox.Look describe(Account a, boolean pending, LocalDate today, List<String> lines) {
        if (a.tokenSavedAt > 0 && !pending) {
            lines.add("Saved on " + CreditsView.moment(Instant.ofEpochMilli(a.tokenSavedAt), ZoneId.systemDefault(), today));
        }
        StateBox.Look look = StateBox.Look.OK;
        LocalDate expiry = a.expiry();
        if (expiry != null || CreditsSettings.NEVER.equals(a.expiresAt)) {
            String text = CreditsView.expiry(expiry, today);
            if (Accounts.expiresWithin(expiry, today, Accounts.EXPIRY_WARNING_DAYS)) {
                look = StateBox.Look.WARNING;
                text = "<b>" + text + "</b>: create a new token before then.";
            }
            lines.add(text);
        }
        List<String> scopes = a.scopeList();
        if (scopes != null) {
            List<String> missing = new ArrayList<>();
            for (String scope : CreditsClient.SCOPES) {
                if (!scopes.contains(scope) && !("read_api".equals(scope) && scopes.contains("api"))) {
                    missing.add(scope);
                }
            }
            if (!missing.isEmpty() && Accounts.readsUsage(scopes)) {
                look = StateBox.Look.WARNING;
                lines.add("<b>Missing the " + String.join(" and ", missing) + " scope</b>: Duo Watchdog needs "
                        + "read_api and ai_features.");
            } else {
                lines.add("Scopes: " + escape(String.join(", ", scopes)));
            }
        }
        return look;
    }

    private String groupHintText() {
        if (shown == null || namespace.getText().isBlank()) {
            return "Not sure what goes here? Click Find my group: it asks GitLab which of this account's groups gives "
                    + "it Duo credits and fills in that group's path.";
        }
        return "The path from the group's address, " + shown.host() + "/‹path›, not the name GitLab shows on its pages.";
    }

    // --- The list -------------------------------------------------------------------------------------------------

    private @Nullable Account find(String id) {
        return working.stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    /** Credits left, from the last refresh, while its token is the saved one. */
    private @Nullable Double left(Account a) {
        if (pendingTokens.containsKey(a.id()) || CreditsSettings.getInstance().account(a.id()) == null) {
            return null;
        }
        return Accounts.left(CreditsService.getInstance().snapshot(a.id()), a.limit);
    }

    private boolean rejected(Account a) {
        return !pendingTokens.containsKey(a.id()) && CreditsSettings.getInstance().account(a.id()) != null
                && CreditsService.getInstance().snapshot(a.id()).status() == Snapshot.Status.REJECTED;
    }

    private boolean passes(Account a, Filter f) {
        return switch (f) {
            case ALL -> true;
            case CREDITS -> Accounts.hasCredits(left(a));
            case EXPIRED -> Accounts.expired(a.expiry(), rejected(a), LocalDate.now());
        };
    }

    /** Filters, searches and sorts the accounts again, keeping the chosen one chosen. */
    private void refreshRows() {
        if (table == null) {
            return;
        }
        String chosen = shown != null ? shown.id() : null;
        String query = search.getText().trim().toLowerCase();
        rows.clear();
        for (Account a : working) {
            String text = (a.username + " " + a.host() + " " + a.namespace).toLowerCase();
            if (passes(a, filter) && (query.isEmpty() || text.contains(query))) {
                rows.add(a);
            }
        }
        rows.sort(Accounts.byCreditsLeft(this::left, a -> a.username, Account::host));
        for (Filter f : Filter.values()) {
            long count = working.stream().filter(a -> passes(a, f)).count();
            filterButtons.get(f).setText(f.label + " (" + count + ")");
        }
        refreshing = true;
        try {
            model.fireTableDataChanged();
        } finally {
            refreshing = false;
        }
        empty.setText(wrap(working.isEmpty() ? "" : rows.isEmpty()
                ? (query.isEmpty() ? "No account here." : "No account matches “" + search.getText().trim() + "”.") : ""));
        empty.setVisible(!working.isEmpty() && rows.isEmpty());
        selectRow(chosen != null && rows.stream().anyMatch(a -> a.id().equals(chosen)) ? chosen
                : rows.isEmpty() ? null : rows.get(0).id());
    }

    private void selectRow(@Nullable String id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id().equals(id)) {
                table.getSelectionModel().setSelectionInterval(i, i);
                showDetails(rows.get(i));
                return;
            }
        }
        table.clearSelection();
        showDetails(null);
    }

    private final class AccountsModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return column == 2 ? COLUMNS[column] + " ↓" : COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row);
        }
    }

    private final class AccountsRenderer extends ColoredTableCellRenderer {
        @Override
        protected void customizeCellRenderer(@NotNull JTable table, @Nullable Object value, boolean selected,
                                             boolean hasFocus, int row, int column) {
            if (!(value instanceof Account a)) {
                return;
            }
            LocalDate today = LocalDate.now();
            switch (column) {
                case 0 -> {
                    append("@" + a.username, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append("  " + a.host(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
                case 1 -> append(a.namespace.isBlank() ? "—" : a.namespace,
                        a.namespace.isBlank() ? SimpleTextAttributes.GRAYED_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
                case 2 -> credits(a);
                case 3 -> token(a);
                case 4 -> expires(a, today);
                default -> {
                }
            }
        }

        private void credits(Account a) {
            Double left = left(a);
            Snapshot s = CreditsService.getInstance().snapshot(a.id());
            CreditsReport report = s.report();
            if (left != null && report != null) {
                boolean low = Accounts.attention(s, a.limit, CreditsSettings.getInstance().percents(), null,
                        LocalDate.now()) != Accounts.Attention.NONE;
                append(CreditsView.number(left), low ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, WARNING_COLOR)
                        : SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + CreditsView.number(report.creditsUsed()) + " of " + CreditsView.number(a.limit) + " used",
                        SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                return;
            }
            if (pendingTokens.containsKey(a.id()) || CreditsSettings.getInstance().account(a.id()) == null) {
                append("after Apply", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            switch (s.status()) {
                case NO_TOKEN -> append("no token", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, WARNING_COLOR));
                case NOT_TRACKED -> append("not tracked", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                case REJECTED -> append("token turned down", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ERROR_COLOR));
                case NOT_CONFIGURED -> append("no group yet", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, WARNING_COLOR));
                case ERROR -> append("refresh failed", new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ERROR_COLOR));
                default -> append("loading…", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        private void token(Account a) {
            boolean pending = pendingTokens.containsKey(a.id());
            Boolean saved = pending ? Boolean.TRUE : tokenSaved.get(a.id());
            if (Boolean.TRUE.equals(saved)) {
                setIcon(AllIcons.Ide.Readonly);
                append("••••••••••", SimpleTextAttributes.REGULAR_ATTRIBUTES);
                if (pending) {
                    append("  new", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                }
            } else if (Boolean.FALSE.equals(saved)) {
                append("No token", new SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, WARNING_COLOR));
            } else {
                append("…", SimpleTextAttributes.GRAYED_ATTRIBUTES);
            }
        }

        private void expires(Account a, LocalDate today) {
            LocalDate expiry = a.expiry();
            if (expiry == null) {
                append(CreditsSettings.NEVER.equals(a.expiresAt) ? "Never" : "—", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                return;
            }
            long days = ChronoUnit.DAYS.between(today, expiry);
            boolean rejected = rejected(a);
            SimpleTextAttributes date = days <= 0 || rejected ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ERROR_COLOR)
                    : days <= Accounts.EXPIRY_WARNING_DAYS ? new SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, WARNING_COLOR)
                    : SimpleTextAttributes.REGULAR_ATTRIBUTES;
            append(CreditsView.expiry(expiry, today).replaceFirst("^Expire[sd] (on |tomorrow, )?", "")
                    .replaceFirst(", in \\d+ days$", ""), date);
            String note = days <= 0 ? "expired" : days == 1 ? "tomorrow" : days <= 60 ? "in " + days + " days"
                    : "in " + (days / 30) + " months";
            append("  " + note, days <= Accounts.EXPIRY_WARNING_DAYS ? date : SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
        }
    }

    // --- Adding, replacing and removing ---------------------------------------------------------------------------

    /** Add GitLab Token; for {@code replacing}, its URL is filled in. GitLab's answer decides which account it is. */
    private void addToken(@Nullable Account replacing) {
        String url = replacing != null ? replacing.gitlabUrl
                : shown != null ? shown.gitlabUrl : CreditsSettings.getInstance().getState().gitlabUrl;
        AddTokenDialog dialog = new AddTokenDialog(panel, List.copyOf(working), url, replacing);
        if (!dialog.showAndGet()) {
            return;
        }
        AddTokenDialog.Result result = dialog.result();
        Account account = result.account();
        Account listed = find(account.id());
        if (listed != null) {
            listed.tokenSavedAt = account.tokenSavedAt;
            listed.expiresAt = account.expiresAt;
            listed.scopes = account.scopes;
            listed.glab = account.glab;
            listed.duo = account.duo;
            listed.lastRejectedKey = "";
            account = listed;
        } else {
            working.add(account);
        }
        pendingTokens.put(account.id(), result.token());
        removed.remove(account.id());
        if (search.getText().isBlank() && !passes(account, filter)) {
            filterButtons.get(Filter.ALL).setSelected(true);
            filter = Filter.ALL;
        }
        shown = null;
        refreshRows();
        selectRow(account.id());
    }

    private void removeSelected() {
        Account account = shown;
        if (account == null) {
            return;
        }
        boolean saved = CreditsSettings.getInstance().account(account.id()) != null;
        boolean remove = MessageDialogBuilder.yesNo("Remove Account",
                        "Remove @" + account.username + " on " + account.host() + "?"
                                + (saved ? " Its token is deleted from " + CreditsSettings.storeName()
                                + " when you click OK or Apply." : "") + " glab keeps any login it has.")
                .yesText("Remove")
                .noText("Cancel")
                .ask(panel);
        if (!remove) {
            return;
        }
        working.remove(account);
        pendingTokens.remove(account.id());
        limitTexts.remove(account.id());
        if (saved) {
            removed.add(account.id());
        }
        shown = null;
        refreshRows();
    }

    // --- Check connection and Find my group -----------------------------------------------------------------------

    private void forgetCheck() {
        checkTicket++;
        checking = false;
        checked = null;
    }

    /** Off the EDT, as the password safe may block: the account's new token, else its saved one. */
    private @Nullable String tokenOf(String id) {
        String pending = pendingTokens.get(id);
        return pending != null ? pending : CreditsSettings.getInstance().token(id);
    }

    private void checkConnection() {
        Account a = shown;
        if (a == null) {
            return;
        }
        String id = a.id();
        String gitlabUrl = a.gitlabUrl;
        String group = GitLabAddress.groupPath(a.namespace, gitlabUrl);
        String pending = pendingTokens.get(id);
        int ticket = ++checkTicket;
        checking = true;
        checked = null;
        renderDetails();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String key = pending != null ? pending : CreditsSettings.getInstance().token(id);
            Checked result = check(gitlabUrl, key, group);
            onEdt(() -> {
                if (ticket == checkTicket) {
                    checking = false;
                    checked = result;
                    renderDetails();
                }
            });
        });
    }

    /** Off the EDT: whose the token is and its details, then the group's credits when there is a group. */
    private Checked check(String gitlabUrl, @Nullable String key, String group) {
        if (key == null || key.isBlank()) {
            return new Checked("No token saved for this account: Add token… saves one.", null, null, null, null);
        }
        try {
            CreditsClient client = new CreditsClient(gitlabUrl, key.trim(), CreditsService.getInstance().transport());
            String user = client.username();
            TokenInfo info = null;
            try {
                info = client.tokenInfo();
            } catch (GitLabException e) {
                // An older GitLab or a proxy may not answer it; the details are a bonus.
            }
            if (info != null && !Accounts.readsUsage(info.scopes())) {
                return new Checked(null, user, info, null, "The token lacks ai_features, so its usage is not tracked.");
            }
            if (group.isEmpty()) {
                return new Checked(null, user, info, null, "No group path yet: Find my group fills it in.");
            }
            try {
                double used = client.fetch(group).creditsUsed();
                return new Checked(null, user, info,
                        group + ": " + CreditsView.number(used) + " credits used this billing period", null);
            } catch (GitLabException e) {
                return new Checked(null, user, info, null, e.getMessage());
            }
        } catch (GitLabException e) {
            return new Checked(e.getMessage(), null, null, null, null);
        }
    }

    /** Asks GitLab which of the account's top-level groups give its credits, and fills in the one, or offers the few. */
    private void findGroup() {
        Account a = shown;
        if (a == null) {
            return;
        }
        String id = a.id();
        String gitlabUrl = a.gitlabUrl;
        CreditsClient.Groups groups;
        try {
            groups = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    (ThrowableComputable<CreditsClient.Groups, GitLabException>) () -> {
                        String key = tokenOf(id);
                        if (key == null || key.isBlank()) {
                            throw new GitLabException("This account has no token: Find my group asks GitLab with it.");
                        }
                        return new CreditsClient(gitlabUrl, key.trim(), CreditsService.getInstance().transport()).findGroups();
                    }, "Finding your group", true, null);
        } catch (ProcessCanceledException e) {
            return;
        } catch (GitLabException e) {
            Messages.showErrorDialog(panel, e.getMessage(), "Find My Group");
            return;
        }

        List<GroupMatch> found = groups.withCredits();
        if (found.size() == 1) {
            namespace.setText(found.get(0).path());
        } else if (found.isEmpty()) {
            Messages.showWarningDialog(panel, groups.checked().isEmpty()
                    ? "@" + a.username + " is not a member of any group on " + a.host() + "."
                    : "GitLab gave no Duo credits for @" + a.username + " in any of its groups: "
                            + String.join(", ", groups.checked()) + ". Check the token's read_api and ai_features scopes.",
                    "Find My Group");
        } else {
            JBPopupFactory.getInstance().createPopupChooserBuilder(found)
                    .setTitle("Groups with Duo Credits")
                    .setItemChosenCallback(match -> namespace.setText(match.path()))
                    .createPopup()
                    .showUnderneathOf(findGroup);
        }
    }

    /** Reads, off the EDT, which accounts have a token in the password safe. */
    private void readTokens() {
        List<String> ids = working.stream().map(Account::id).toList();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            CreditsSettings settings = CreditsSettings.getInstance();
            for (String id : ids) {
                String token = settings.token(id);
                tokenSaved.put(id, token != null && !token.isBlank());
            }
            onEdt(() -> {
                table.repaint();
                renderDetails();
            });
        });
    }

    /** Runs on the EDT even under the modal Settings dialog, unless the page has been closed meanwhile. */
    private void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (panel != null) {
                action.run();
            }
        }, ModalityState.any());
    }

    // --- Apply and reset ------------------------------------------------------------------------------------------

    @Override
    public boolean isModified() {
        CreditsSettings.Settings s = CreditsSettings.getInstance().getState();
        if (!pendingTokens.isEmpty() || !removed.isEmpty() || working.size() != s.accounts.size()) {
            return true;
        }
        for (Account w : working) {
            Account live = CreditsSettings.getInstance().account(w.id());
            if (live == null || !w.sameEdits(live)) {
                return true;
            }
            String typed = limitTexts.get(w.id());
            if (typed != null && !typed.equals(plain(live.limit))) {
                return true;
            }
        }
        return !alertPercents.getText().trim().equals(s.alertPercents)
                || alertOnForecast.isSelected() != s.alertOnForecast
                || refreshMinutes.getNumber() != s.refreshMinutes;
    }

    @Override
    public void apply() throws ConfigurationException {
        for (Account w : working) {
            String typed = limitTexts.getOrDefault(w.id(), plain(w.limit));
            double value;
            try {
                value = Double.parseDouble(typed.replace(',', '.'));
            } catch (NumberFormatException e) {
                selectRow(w.id());
                throw new ConfigurationException("The limit of @" + w.username + " must be a number of credits, e.g. 100.");
            }
            if (value <= 0) {
                selectRow(w.id());
                throw new ConfigurationException("The limit of @" + w.username + " must be above zero.");
            }
            w.limit = value;
        }
        String newPercents = alertPercents.getText().trim();
        try {
            Alert.parsePercents(newPercents);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(e.getMessage() + ". Use whole percents, e.g. 80, 95.");
        }

        CreditsSettings settings = CreditsSettings.getInstance();
        CreditsSettings.Settings s = settings.getState();
        List<Account> next = new ArrayList<>();
        for (Account w : working) {
            Account live = settings.account(w.id());
            boolean newToken = pendingTokens.containsKey(w.id());
            Account kept = live != null ? live : w.copy();
            kept.gitlabUrl = w.gitlabUrl;
            kept.namespace = GitLabAddress.groupPath(w.namespace, w.gitlabUrl);
            kept.limit = w.limit;
            kept.glab = w.glab;
            kept.duo = w.duo;
            if (newToken) {
                kept.tokenSavedAt = w.tokenSavedAt;
                kept.expiresAt = w.expiresAt;
                kept.scopes = w.scopes;
                kept.lastRejectedKey = "";
                kept.lastExpiryKey = "";
            }
            next.add(kept);
        }
        s.accounts.clear();
        s.accounts.addAll(next);
        if (next.stream().noneMatch(a -> a.id().equals(s.selected))) {
            s.selected = next.isEmpty() ? "" : CreditsService.getInstance().sorted().get(0).id();
        }
        s.alertPercents = newPercents;
        s.alertOnForecast = alertOnForecast.isSelected();
        s.refreshMinutes = refreshMinutes.getNumber();

        Map<String, String> tokens = new HashMap<>(pendingTokens);
        Set<String> gone = new HashSet<>(removed);
        tokens.keySet().forEach(id -> tokenSaved.put(id, true));
        pendingTokens.clear();
        removed.clear();
        limitTexts.clear();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            for (String id : gone) {
                settings.setToken(id, null);
            }
            tokens.forEach(settings::setToken);
            CreditsService.getInstance().syncGlab(tokens.keySet());
            CreditsService.getInstance().reschedule();
        });

        String chosen = shown != null ? shown.id() : null;
        working.clear();
        s.accounts.forEach(a -> working.add(a.copy()));
        shown = null;
        refreshRows();
        selectRow(chosen);
    }

    @Override
    public void reset() {
        CreditsSettings.Settings s = CreditsSettings.getInstance().getState();
        working.clear();
        s.accounts.forEach(a -> working.add(a.copy()));
        pendingTokens.clear();
        removed.clear();
        limitTexts.clear();
        forgetCheck();
        alertPercents.setText(s.alertPercents);
        alertOnForecast.setSelected(s.alertOnForecast);
        refreshMinutes.setNumber(s.refreshMinutes);
        shown = null;
        refreshRows();
        Account selected = CreditsService.getInstance().selected();
        if (selected != null) {
            selectRow(selected.id());
        }
        readTokens();
    }

    @Override
    public void disposeUIResources() {
        checkTicket++;
        if (uiDisposable != null) {
            Disposer.dispose(uiDisposable);
            uiDisposable = null;
        }
        panel = null;
        table = null;
        details = null;
        shown = null;
        filterButtons.clear();
    }

    // --- Little helpers -------------------------------------------------------------------------------------------

    private static void onChange(javax.swing.text.JTextComponent field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                action.run();
            }
        });
    }

    private static JPanel left(JComponent component) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        row.add(component);
        return row;
    }

    private static JBLabel hint(String text) {
        JBLabel label = new JBLabel(wrap(text), UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
        label.setBorder(JBUI.Borders.emptyBottom(2));
        return label;
    }

    private static JBLabel note() {
        JBLabel label = new JBLabel("", UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
        label.setBorder(JBUI.Borders.emptyLeft(24));
        return label;
    }

    private static String wrap(String text) {
        return "<html><body style='width:" + JBUI.scale(HINT_WIDTH) + "px'>" + escape(text) + "</body></html>";
    }

    private static String escape(String text) {
        return StringUtil.escapeXmlEntities(text == null ? "" : text);
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
