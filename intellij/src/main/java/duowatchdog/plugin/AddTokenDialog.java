package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.Alarm;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.Accounts;
import duowatchdog.core.CreditsClient;
import duowatchdog.core.CreditsClient.GroupMatch;
import duowatchdog.core.CreditsClient.TokenInfo;
import duowatchdog.core.CreditsView;
import duowatchdog.core.GitLabAddress;
import duowatchdog.core.GitLabException;
import duowatchdog.plugin.CreditsSettings.Account;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionListener;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Add GitLab Token: as soon as a token is pasted, GitLab says whose it is. A token for an account already listed
 * replaces its token, keeping its group and limit; a token for another account adds it, with its group found and
 * a limit; a token GitLab takes but that cannot read usage is added anyway, as not tracked. glab and the Duo CLI
 * get the token only when their boxes are ticked.
 */
final class AddTokenDialog extends DialogWrapper {

    private static final int WIDTH = 440;

    /** What OK gives: the account, new or the one whose token this replaces, with its new token. */
    record Result(Account account, String token, boolean replaces) {
    }

    /** What GitLab said of the token typed. */
    private record Found(String gitlabUrl, String token, String error, String username, TokenInfo info,
                         List<GroupMatch> groups) {
    }

    private final List<Account> existing;
    private final @Nullable Account replacing;
    private final JBTextField url = new JBTextField();
    private final JBPasswordField token = new JBPasswordField();
    private final ActionLink create = new ActionLink("Create the token in GitLab ↗",
            (ActionListener) e -> BrowserUtil.browse(GitLabAddress.tokenPage(url.getText(), forCli())));
    private final StateBox box = new StateBox(WIDTH - 60);
    private final JBTextField group = new JBTextField();
    private final JBTextField limit = new JBTextField("100", 8);
    private final JBCheckBox glab = new JBCheckBox("Also use it for glab");
    private final JBCheckBox duo = new JBCheckBox("Also use it for the GitLab Duo CLI");
    private final JBLabel glabNote = note();
    private final JBLabel duoNote = note();
    private JPanel newAccountFields;
    private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, getDisposable());
    private int ticket;
    private Found found;
    /** The listed account whose glab and Duo CLI choices the boxes were set from. */
    private String flagsFor = "";

    /**
     * @param existing  the accounts listed now, as the settings page holds them
     * @param replacing the account whose Replace token… opened this, or null
     */
    AddTokenDialog(Component parent, List<Account> existing, String gitlabUrl, @Nullable Account replacing) {
        super(parent, true);
        this.existing = existing;
        this.replacing = replacing;
        setTitle(replacing != null ? "Replace GitLab Token" : "Add GitLab Token");
        url.setText(gitlabUrl);
        token.getEmptyText().setText("glpat-…");
        group.getEmptyText().setText("e.g. my-company");
        DocumentAdapter changed = new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                ask();
            }
        };
        url.getDocument().addDocumentListener(changed);
        token.getDocument().addDocumentListener(changed);
        glab.addActionListener(e -> {
            if (!glab.isSelected()) {
                duo.setSelected(false);
            }
            render();
        });
        duo.addActionListener(e -> {
            if (duo.isSelected()) {
                glab.setSelected(true);
            }
            render();
        });
        init();
        render();
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JButton findGroup = new JButton("Find my group", AllIcons.Actions.Find);
        findGroup.addActionListener(e -> findGroup());
        JPanel groupRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        groupRow.add(group, BorderLayout.CENTER);
        groupRow.add(findGroup, BorderLayout.EAST);
        newAccountFields = FormBuilder.createFormBuilder()
                .addLabeledComponent("Group path:", groupRow)
                .addLabeledComponent("Limit (credits):", left(limit))
                .addComponentToRightColumn(hint("Your GitLab cap, or a lower budget of your own."))
                .getPanel();

        JPanel cli = FormBuilder.createFormBuilder()
                .addComponent(glab)
                .addComponent(glabNote)
                .addComponent(duo, JBUI.scale(6))
                .addComponent(duoNote)
                .getPanel();
        cli.setBorder(JBUI.Borders.customLineTop(UIUtil.getBoundsColor()));

        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("GitLab URL:", url)
                .addLabeledComponent("Personal access token:", token)
                .addComponentToRightColumn(hint("For usage: read_api and ai_features. For glab and the Duo CLI too: "
                        + "api, write_repository and ai_features. Kept in " + CreditsSettings.storeName() + "."))
                .addComponentToRightColumn(create)
                .addComponent(box, JBUI.scale(12))
                .addComponent(newAccountFields, JBUI.scale(12))
                .addComponent(cli, JBUI.scale(12))
                .getPanel();
        panel.setPreferredSize(JBUI.size(WIDTH + 160, -1));
        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return token;
    }

    private boolean forCli() {
        return glab.isSelected() || duo.isSelected();
    }

    /** Asks GitLab, half a second after the typing stops, whose the token is. */
    private void ask() {
        String gitlabUrl = url.getText().trim();
        String key = new String(token.getPassword()).trim();
        int mine = ++ticket;
        found = null;
        alarm.cancelAllRequests();
        if (!key.isEmpty()) {
            alarm.addRequest(() -> {
                Found result = lookUp(gitlabUrl, key);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (mine == ticket) {
                        found = result;
                        render();
                    }
                }, ModalityState.any());
            }, 500);
        }
        render();
    }

    /** Off the EDT: whose the token is, its details, and for a new account the groups that give it credits. */
    private Found lookUp(String gitlabUrl, String key) {
        try {
            CreditsClient client = new CreditsClient(gitlabUrl, key, CreditsService.getInstance().transport());
            String username = client.username();
            TokenInfo info = null;
            try {
                info = client.tokenInfo();
            } catch (GitLabException e) {
                // An older GitLab or a proxy may not answer it; the details are a bonus.
            }
            List<GroupMatch> groups = List.of();
            boolean known = existing.stream().anyMatch(a -> a.id().equals(Accounts.id(gitlabUrl, username)));
            if (!known && Accounts.readsUsage(info != null ? info.scopes() : null)) {
                try {
                    groups = client.findGroups().withCredits();
                } catch (GitLabException e) {
                    // Find my group is still there to try.
                }
            }
            return new Found(gitlabUrl, key, null, username, info, groups);
        } catch (GitLabException e) {
            return new Found(gitlabUrl, key, e.getMessage(), null, null, List.of());
        }
    }

    private @Nullable Account match() {
        if (found == null || found.username() == null) {
            return null;
        }
        String id = Accounts.id(found.gitlabUrl(), found.username());
        return existing.stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    private void render() {
        boolean typed = token.getPassword().length > 0;
        Account match = match();
        boolean ok = found != null && found.error() == null;
        boolean tracked = ok && Accounts.readsUsage(found.info() != null ? found.info().scopes() : null);
        LocalDate today = LocalDate.now();

        if (!typed) {
            box.show(StateBox.Look.INFO, "Paste a personal access token",
                    List.of("GitLab says whose it is: a token for an account already listed replaces its token."));
        } else if (found == null) {
            box.show(StateBox.Look.BUSY, "Asking " + host() + " whose token this is…", List.of());
        } else if (!ok) {
            box.show(StateBox.Look.ERROR, "GitLab did not take the token", List.of(escape(found.error())));
        } else {
            List<String> lines = new ArrayList<>();
            String who = "@" + escape(found.username()) + " on " + escape(host());
            if (replacing != null && match != null && !match.id().equals(replacing.id())) {
                lines.add("This token is " + who + "'s, not @" + escape(replacing.username) + "'s.");
            }
            if (match != null) {
                lines.add("OK replaces its token" + (match.tokenSavedAt > 0 ? ", saved on "
                        + CreditsView.moment(Instant.ofEpochMilli(match.tokenSavedAt), ZoneId.systemDefault(), today) : "")
                        + ". Its group" + (match.namespace.isBlank() ? "" : ", " + escape(match.namespace) + ",")
                        + " and its limit, " + CreditsView.number(match.limit) + ", stay.");
            } else if (tracked) {
                lines.add("OK adds it to the list.");
            }
            if (found.info() != null) {
                lines.add(CreditsView.expiry(found.info().expiresAt(), today));
                lines.add("Scopes: " + escape(String.join(", ", found.info().scopes())));
            }
            if (!tracked) {
                lines.add("<b>No ai_features scope</b>: GitLab doesn't show this account's Duo usage to the token. "
                        + "Duo Watchdog keeps it anyway, for glab and the Duo CLI, and marks the account “not tracked”.");
            }
            if (match != null) {
                box.show(tracked ? StateBox.Look.INFO : StateBox.Look.WARNING, "Already in the list: " + who, lines);
            } else if (tracked) {
                box.show(StateBox.Look.OK, "A new account: " + who, lines);
            } else {
                box.show(StateBox.Look.WARNING, "A new account, but its usage can't be tracked", prepend(who, lines));
            }
        }
        if (ok && match == null && tracked && group.getText().isBlank() && found.groups().size() == 1) {
            group.setText(found.groups().get(0).path());
        }
        boolean fields = ok && match == null && tracked;
        boolean resize = newAccountFields != null && newAccountFields.isVisible() != fields;
        if (newAccountFields != null) {
            newAccountFields.setVisible(fields);
        }
        renderCli(ok, match);
        setOKButtonText(match != null ? "Replace Token" : "Add Account");
        setOKActionEnabled(ok);
        if (resize) {
            pack();
        }
    }

    private void renderCli(boolean ok, @Nullable Account match) {
        glab.setEnabled(ok);
        duo.setEnabled(ok);
        String host = host();
        glab.setText("Also use it for glab on " + host);
        duo.setText("Also use it for the GitLab Duo CLI on " + host);
        List<String> notes = new ArrayList<>();
        String self = found != null && found.username() != null ? Accounts.id(found.gitlabUrl(), found.username()) : "";
        existing.stream()
                .filter(a -> a.glab && a.host().equalsIgnoreCase(host) && !a.id().equals(self))
                .findFirst()
                .ifPresentOrElse(
                        other -> notes.add("glab keeps one token per GitLab. It uses @" + other.username + "'s for " + host
                                + " now; ticking this switches it" + (found != null && found.username() != null
                                ? " to @" + found.username() : "") + "."),
                        () -> notes.add("glab keeps one token per GitLab; Duo Watchdog gives it this one."));
        if (ok && found.info() != null) {
            List<String> missing = Accounts.missingForGlab(found.info().scopes());
            if (!missing.isEmpty()) {
                notes.add("This token lacks " + String.join(" and ", missing) + ", so glab could only read with it.");
            }
        }
        if (CliHandOff.glab() == null) {
            notes.add("glab is not installed where the IDE looks: on the PATH and in Homebrew.");
        }
        glabNote.setText(wrap(String.join(" ", notes)));
        duoNote.setText(wrap("The Duo CLI signs in through glab (glab 1.85.2 and duo 8.68.0 or later), so this "
                + "turns glab on too."));
        if (match != null && !match.id().equals(flagsFor)) {
            // An account keeps what was chosen for it, until changed here.
            flagsFor = match.id();
            glab.setSelected(match.glab);
            duo.setSelected(match.duo);
        }
    }

    private String host() {
        String host = GitLabAddress.host(url.getText());
        return host != null ? host : "GitLab";
    }

    private void findGroup() {
        Found result = found;
        if (result == null || result.error() != null) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            List<GroupMatch> groups;
            try {
                groups = new CreditsClient(result.gitlabUrl(), result.token(), CreditsService.getInstance().transport())
                        .findGroups().withCredits();
            } catch (GitLabException e) {
                groups = List.of();
            }
            List<GroupMatch> matches = groups;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (matches.size() == 1) {
                    group.setText(matches.get(0).path());
                } else if (!matches.isEmpty()) {
                    com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createPopupChooserBuilder(matches)
                            .setTitle("Your Groups with Duo Credits")
                            .setItemChosenCallback(match -> group.setText(match.path()))
                            .createPopup()
                            .showUnderneathOf(group);
                }
            }, ModalityState.any());
        });
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (newAccountFields != null && newAccountFields.isVisible()) {
            try {
                if (Double.parseDouble(limit.getText().trim().replace(',', '.')) <= 0) {
                    return new ValidationInfo("The limit must be above zero.", limit);
                }
            } catch (NumberFormatException e) {
                return new ValidationInfo("The limit must be a number of credits, e.g. 100.", limit);
            }
        }
        return null;
    }

    /** The account to save, with its new token; only after OK. */
    Result result() {
        Account match = match();
        Account account = match != null ? match.copy() : new Account();
        if (match == null) {
            account.gitlabUrl = found.gitlabUrl();
            account.username = found.username();
            account.namespace = GitLabAddress.groupPath(group.getText(), found.gitlabUrl());
            try {
                account.limit = Double.parseDouble(limit.getText().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                account.limit = 100;
            }
        }
        account.glab = glab.isSelected();
        account.duo = duo.isSelected();
        account.tokenSavedAt = System.currentTimeMillis();
        if (found.info() != null) {
            account.remember(found.info());
        } else {
            account.expiresAt = "";
            account.scopes = "";
        }
        account.lastRejectedKey = "";
        return new Result(account, found.token(), match != null);
    }

    private static List<String> prepend(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private static JPanel left(JComponent component) {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
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
        return "<html><body style='width:" + JBUI.scale(WIDTH) + "px'>" + escape(text) + "</body></html>";
    }

    private static String escape(String text) {
        return StringUtil.escapeXmlEntities(text == null ? "" : text);
    }
}
