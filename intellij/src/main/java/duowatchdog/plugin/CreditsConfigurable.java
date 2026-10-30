package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.TitledSeparator;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBFont;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.Alert;
import duowatchdog.core.CreditsClient;
import duowatchdog.core.CreditsClient.GroupMatch;
import duowatchdog.core.CreditsClient.TokenInfo;
import duowatchdog.core.CreditsView;
import duowatchdog.core.GitLabAddress;
import duowatchdog.core.GitLabException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Settings | Tools | Duo Watchdog: the connection (the token first, since Find my group asks GitLab with it), then
 * the limit and alerts.
 */
public final class CreditsConfigurable implements Configurable {

    private static final int HINT_WIDTH = 380;
    /** A token this close to its expiry turns the box amber. */
    private static final int EXPIRY_WARNING_DAYS = 7;
    private static final String SCOPES = "The token needs two scopes: <b>read_api</b> and <b>ai_features</b>. "
            + "Leave everything else unticked: <b>api</b> can change code.";

    private JPanel panel;
    private JBTextField url;
    private JBPasswordField token;
    private TokenBox tokenBox;
    private JBTextField namespace;
    private JButton findGroup;
    private JBLabel groupHint;
    private JBTextField limit;
    private JBTextField alertPercents;
    private JBCheckBox alertOnForecast;
    private JBIntSpinner refreshMinutes;

    /** Whether the password safe holds a token; null until it has been read, off the EDT. */
    private Boolean tokenSaved;
    /** What GitLab says of the saved token, asked quietly when the page opens; null until it answers. */
    private TokenInfo savedInfo;
    /** GitLab turned the saved token down (HTTP 401): it expired or was revoked. */
    private boolean savedRejected;
    private boolean checking;
    /** What Check connection found, until the URL, token or group changes. */
    private Checked checked;
    /** A token Apply is still writing to the password safe, which a look at the saved token takes instead. */
    private volatile String pendingToken;
    /** Raised by each look at the saved token and each check, so an answer to an older one is dropped. */
    private int savedTicket;
    private int checkTicket;

    /**
     * What Check connection found: why it failed, or whose the token is, its details when GitLab gave them, and
     * the group's credits or why they did not come.
     */
    private record Checked(String error, String user, TokenInfo info, String group, String groupError, boolean typed) {
    }

    @Override
    public @Nls String getDisplayName() {
        return "Duo Watchdog";
    }

    @Override
    public @Nullable JComponent createComponent() {
        url = new JBTextField();
        // The saved token is never read back into the form: the field only replaces it.
        token = new JBPasswordField();
        token.getEmptyText().setText("Leave empty to keep the saved token");
        tokenBox = new TokenBox();
        namespace = new JBTextField();
        namespace.getEmptyText().setText("e.g. my-company, or click Find my group");
        findGroup = new JButton("Find my group", AllIcons.Actions.Find);
        findGroup.setToolTipText("Asks GitLab which of your groups gives you Duo credits, and fills in its path");
        findGroup.addActionListener(e -> findGroup());
        groupHint = hint("");
        limit = new JBTextField(8);
        alertPercents = new JBTextField(8);
        alertOnForecast = new JBCheckBox("Alert when the month-end forecast passes the limit");
        refreshMinutes = new JBIntSpinner(15, 1, 1440);

        onChange(url, () -> {
            forgetCheck();
            groupHint.setText(wrap(groupHintText()));
        });
        onChange(token, this::forgetCheck);
        onChange(namespace, () -> {
            forgetCheck();
            groupHint.setText(wrap(groupHintText()));
        });

        JPanel groupRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        groupRow.add(namespace, BorderLayout.CENTER);
        groupRow.add(findGroup, BorderLayout.EAST);

        JPanel refreshRow = left(refreshMinutes);
        JBLabel minutes = new JBLabel("minutes");
        minutes.setBorder(JBUI.Borders.emptyLeft(8));
        refreshRow.add(minutes);

        panel = FormBuilder.createFormBuilder()
                .addComponent(new TitledSeparator("Connection"))
                .addLabeledComponent("GitLab URL:", url)
                .addLabeledComponent("Personal access token:", token)
                .addComponentToRightColumn(hint("Kept in the IDE password safe. Leave empty to keep the saved one."))
                .addComponentToRightColumn(tokenBox, JBUI.scale(8))
                .addLabeledComponent("Group path:", groupRow, JBUI.scale(12))
                .addComponentToRightColumn(groupHint)
                .addComponent(new TitledSeparator("Limit and alerts"), JBUI.scale(12))
                .addLabeledComponent("Limit (credits):", left(limit))
                .addComponentToRightColumn(hint("Your GitLab cap, or a lower budget of your own."))
                .addLabeledComponent("Alert at % of limit:", left(alertPercents))
                .addComponentToRightColumn(alertOnForecast)
                .addLabeledComponent("Refresh every:", refreshRow)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    private static void onChange(javax.swing.text.JTextComponent field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                action.run();
            }
        });
    }

    private String host() {
        String host = GitLabAddress.host(url.getText());
        return host != null ? host : "gitlab.com";
    }

    private String groupHintText() {
        if (namespace.getText().isBlank()) {
            return "Not sure what goes here? Click Find my group: it asks GitLab which of your groups gives you Duo "
                    + "credits and fills in that group's path. It uses the token above.";
        }
        return "The path from the group's address, " + host() + "/‹path›, not the name GitLab shows on its pages. "
                + "Find my group asks GitLab for it.";
    }

    // --- The token box -------------------------------------------------------------------------------------------

    /** A check, running or done, was about the URL, token and group as they were: it no longer says anything. */
    private void forgetCheck() {
        checkTicket++;
        checking = false;
        checked = null;
        renderToken();
    }

    /** Paints the token box for what is known now: a check running or done, a typed token, or the saved one. */
    private void renderToken() {
        if (tokenBox == null) {
            return;
        }
        boolean typed = token.getPassword().length > 0;
        tokenBox.check.setEnabled(!checking && (typed || Boolean.TRUE.equals(tokenSaved)));
        tokenBox.create.setText(Boolean.TRUE.equals(tokenSaved) || typed
                ? "Create a new token in GitLab ↗" : "Create the token in GitLab ↗");
        LocalDate today = LocalDate.now();

        if (checking) {
            tokenBox.show(Look.BUSY, "Checking the connection to " + host() + "…", List.of());
        } else if (checked != null && checked.error() != null) {
            tokenBox.show(Look.ERROR, "Connection failed", List.of(escape(checked.error())));
        } else if (checked != null) {
            List<String> lines = new ArrayList<>();
            if (checked.group() != null) {
                lines.add(escape(checked.group()));
            }
            if (checked.groupError() != null) {
                lines.add(escape(checked.groupError()));
            }
            Look look = checked.groupError() != null ? Look.WARNING : Look.OK;
            look = worst(look, describe(checked.info(), checked.typed() ? null : savedAt(), today, lines));
            tokenBox.show(look, "Connected to " + host() + " as @" + checked.user(), lines);
        } else if (typed) {
            tokenBox.show(Look.INFO, "New token, not saved yet",
                    List.of("OK or Apply saves it; Check connection tries it first.", SCOPES));
        } else if (tokenSaved == null) {
            tokenBox.show(Look.INFO, "Personal access token", List.of(SCOPES));
        } else if (!tokenSaved) {
            tokenBox.show(Look.WARNING, "No token yet",
                    List.of("Create one in GitLab and paste it above. " + SCOPES));
        } else if (savedRejected) {
            List<String> lines = new ArrayList<>();
            describe(null, savedAt(), today, lines);
            lines.add("GitLab turns it down: it has expired or was revoked. Create a new one and paste it above.");
            tokenBox.show(Look.ERROR, "The saved token no longer works", lines);
        } else {
            List<String> lines = new ArrayList<>();
            Look look = describe(savedInfo, savedAt(), today, lines);
            tokenBox.show(look, look == Look.OK ? "Token saved"
                    : expiresSoon(savedInfo, today) ? "Token saved, but it expires soon"
                    : "Token saved, but it lacks a scope", lines);
        }
    }

    /**
     * Adds what is known of a token to {@code lines}: when it was saved, when it expires and its scopes. Returns
     * {@link Look#WARNING} when it expires within a week or lacks a scope, else {@link Look#OK}.
     */
    private static Look describe(@Nullable TokenInfo info, @Nullable Instant savedAt, LocalDate today, List<String> lines) {
        ZoneId zone = ZoneId.systemDefault();
        if (savedAt != null) {
            lines.add("Saved on " + CreditsView.moment(savedAt, zone, today));
        } else if (info != null && info.createdAt() != null) {
            lines.add("Created on " + CreditsView.moment(info.createdAt(), zone, today));
        }
        if (info == null) {
            return Look.OK;
        }
        Look look = Look.OK;
        String expiry = CreditsView.expiry(info.expiresAt(), today);
        if (expiresSoon(info, today)) {
            look = Look.WARNING;
            expiry = "<b>" + expiry + "</b>: create a new token before then.";
        }
        lines.add(expiry);
        if (!info.missingScopes().isEmpty()) {
            look = Look.WARNING;
            lines.add("<b>Missing the " + String.join(" and ", info.missingScopes()) + " scope"
                    + (info.missingScopes().size() > 1 ? "s" : "") + "</b>: Duo Watchdog needs read_api and ai_features.");
        } else if (!info.scopes().isEmpty()) {
            lines.add("Scopes: " + String.join(", ", info.scopes()));
        }
        return look;
    }

    private static boolean expiresSoon(@Nullable TokenInfo info, LocalDate today) {
        return info != null && info.expiresAt() != null
                && ChronoUnit.DAYS.between(today, info.expiresAt()) <= EXPIRY_WARNING_DAYS;
    }

    private static Look worst(Look a, Look b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private @Nullable Instant savedAt() {
        long at = CreditsSettings.getInstance().getState().tokenSavedAt;
        return at > 0 ? Instant.ofEpochMilli(at) : null;
    }

    /**
     * Reads whether a token is saved, then asks GitLab about it without a word to the user, unless GitLab turns it
     * down.
     */
    private void lookAtSavedToken() {
        int ticket = ++savedTicket;
        String gitlabUrl = url.getText().trim();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            String saved = savedToken();
            boolean present = saved != null && !saved.isBlank();
            onEdt(() -> {
                if (ticket == savedTicket) {
                    tokenSaved = present;
                    renderToken();
                }
            });
            if (!present) {
                return;
            }
            TokenInfo info = null;
            boolean rejected = false;
            try {
                info = new CreditsClient(gitlabUrl, saved.trim(), CreditsService.getInstance().transport()).tokenInfo();
            } catch (GitLabException e) {
                // Only a refusal is news here: an unreachable GitLab is Check connection's to report.
                rejected = e.status() == 401;
            }
            TokenInfo answer = info;
            boolean turnedDown = rejected;
            onEdt(() -> {
                if (ticket == savedTicket) {
                    savedInfo = answer;
                    savedRejected = turnedDown;
                    renderToken();
                }
            });
        });
    }

    private void checkConnection() {
        String gitlabUrl = url.getText().trim();
        String typed = new String(token.getPassword()).trim();
        String group = GitLabAddress.groupPath(namespace.getText(), gitlabUrl);
        int ticket = ++checkTicket;
        checking = true;
        checked = null;
        renderToken();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Checked result = check(gitlabUrl, typed, group);
            onEdt(() -> {
                if (ticket == checkTicket) {
                    checking = false;
                    checked = result;
                    renderToken();
                }
            });
        });
    }

    /** Off the EDT, as the password safe may block: the token Apply is writing, else the one in the safe. */
    private @Nullable String savedToken() {
        String pending = pendingToken;
        return pending != null ? pending : CreditsSettings.getInstance().token();
    }

    /** Off the EDT: whose the token is and its details, then the group's credits when there is a group. */
    private Checked check(String gitlabUrl, String typed, String group) {
        boolean isTyped = !typed.isEmpty();
        String key = isTyped ? typed : savedToken();
        if (key == null || key.isBlank()) {
            return new Checked("Paste a personal access token above first.", null, null, null, null, isTyped);
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
            if (group.isEmpty()) {
                return new Checked(null, user, info, null,
                        "No group path yet: Find my group below fills it in.", isTyped);
            }
            try {
                double used = client.fetch(group).creditsUsed();
                return new Checked(null, user, info,
                        group + ": " + CreditsView.number(used) + " credits used this billing period", null, isTyped);
            } catch (GitLabException e) {
                return new Checked(null, user, info, null, e.getMessage(), isTyped);
            }
        } catch (GitLabException e) {
            return new Checked(e.getMessage(), null, null, null, null, isTyped);
        }
    }

    /** Runs on the EDT even under the modal Settings dialog, unless the page has been closed meanwhile. */
    private void onEdt(Runnable action) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (panel != null) {
                action.run();
            }
        }, ModalityState.any());
    }

    private enum Look {
        INFO(new JBColor(0xEEF3FE, 0x25324D), null, AllIcons.General.BalloonInformation),
        BUSY(new JBColor(0xEEF3FE, 0x25324D), null, AnimatedIcon.Default.INSTANCE),
        OK(new JBColor(0xE8F5EC, 0x253627), new JBColor(0x1F6F35, 0x73C383), AllIcons.General.InspectionsOK),
        WARNING(new JBColor(0xFFF4E5, 0x3D3222), new JBColor(0xC46A00, 0xE3A24A), AllIcons.General.BalloonWarning),
        ERROR(new JBColor(0xFDECEC, 0x452B2B), new JBColor(0xC9302C, 0xE5605A), AllIcons.General.BalloonError);

        final Color background;
        /** The title's colour; null for the label's own. */
        final Color title;
        final Icon icon;

        Look(Color background, Color title, Icon icon) {
            this.background = background;
            this.title = title;
            this.icon = icon;
        }
    }

    /**
     * The rounded box under the token field: an icon and a title in the colour of the token's state (green when
     * saved, amber when missing or about to expire, red when GitLab turns it down), what is known of it, then
     * Check connection and the link to GitLab's new-token page.
     */
    private final class TokenBox extends JPanel {

        final JButton check = new JButton("Check connection");
        final ActionLink create = new ActionLink("Create the token in GitLab ↗",
                (ActionListener) e -> BrowserUtil.browse(GitLabAddress.tokenPage(url.getText())));
        private final JBLabel icon = new JBLabel();
        private final JBLabel title = new JBLabel();
        private final JBLabel details = new JBLabel();
        private Look look = Look.INFO;

        TokenBox() {
            super(new BorderLayout(JBUI.scale(10), 0));
            setOpaque(false);
            setBorder(JBUI.Borders.empty(12, 14));
            icon.setVerticalAlignment(SwingConstants.TOP);
            add(icon, BorderLayout.WEST);

            JPanel text = new JPanel();
            text.setOpaque(false);
            text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
            title.setFont(JBFont.label().asBold());
            details.setBorder(JBUI.Borders.emptyTop(4));
            check.setToolTipText("Asks GitLab whose the token is, when it expires, and for the group's credits");
            check.addActionListener(e -> checkConnection());
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            actions.setOpaque(false);
            actions.setBorder(JBUI.Borders.emptyTop(8));
            actions.add(check);
            create.setBorder(JBUI.Borders.emptyLeft(14));
            actions.add(create);
            for (JComponent part : List.of(title, details, actions)) {
                part.setAlignmentX(Component.LEFT_ALIGNMENT);
                text.add(part);
            }
            add(text, BorderLayout.CENTER);
        }

        /** @param lines HTML, escaped by the caller */
        void show(Look look, String titleText, List<String> lines) {
            this.look = look;
            icon.setIcon(look.icon);
            title.setText(titleText);
            title.setForeground(look.title != null ? look.title : UIUtil.getLabelForeground());
            details.setText(lines.isEmpty() ? ""
                    : "<html><body style='width:" + JBUI.scale(HINT_WIDTH - 60) + "px'>" + String.join("<br>", lines)
                            + "</body></html>");
            details.setVisible(!lines.isEmpty());
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(look.background);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(16), JBUI.scale(16));
            g2.dispose();
        }
    }

    // --- Find my group -------------------------------------------------------------------------------------------

    /** Asks GitLab which of the user's top-level groups give their credits, and fills in the one, or offers the few. */
    private void findGroup() {
        String gitlabUrl = url.getText().trim();
        String typed = new String(token.getPassword()).trim();
        CreditsClient.Groups groups;
        try {
            groups = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    (ThrowableComputable<CreditsClient.Groups, GitLabException>) () -> {
                        String key = typed.isEmpty() ? savedToken() : typed;
                        if (key == null || key.isBlank()) {
                            throw new GitLabException("Enter the personal access token first: Find my group asks GitLab with it.");
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
            String host = GitLabAddress.host(gitlabUrl);
            Messages.showWarningDialog(panel, groups.checked().isEmpty()
                    ? "The token's user is not a member of any group on " + host + "."
                    : "GitLab gave no Duo credits for you in any of your groups: " + String.join(", ", groups.checked())
                            + ". Check the token's read_api and ai_features scopes.", "Find My Group");
        } else {
            JBPopupFactory.getInstance().createPopupChooserBuilder(found)
                    .setTitle("Your Groups with Duo Credits")
                    .setItemChosenCallback(match -> namespace.setText(match.path()))
                    .createPopup()
                    .showUnderneathOf(findGroup);
        }
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

    private static String wrap(String text) {
        return "<html><body style='width:" + JBUI.scale(HINT_WIDTH) + "px'>" + escape(text) + "</body></html>";
    }

    private static String escape(String text) {
        return StringUtil.escapeXmlEntities(text);
    }

    @Override
    public boolean isModified() {
        CreditsSettings.Settings s = CreditsSettings.getInstance().getState();
        return !url.getText().trim().equals(s.gitlabUrl)
                || !namespace.getText().trim().equals(s.namespace)
                || token.getPassword().length > 0
                || !limit.getText().trim().equals(plain(s.limit))
                || !alertPercents.getText().trim().equals(s.alertPercents)
                || alertOnForecast.isSelected() != s.alertOnForecast
                || refreshMinutes.getNumber() != s.refreshMinutes;
    }

    @Override
    public void apply() throws ConfigurationException {
        String newUrl = url.getText().trim();
        try {
            CreditsClient.endpoint(newUrl);
        } catch (GitLabException e) {
            throw new ConfigurationException(e.getMessage());
        }
        String newNamespace = GitLabAddress.groupPath(namespace.getText(), newUrl);
        if (newNamespace.isEmpty()) {
            throw new ConfigurationException("Enter the group path from the group's address, e.g. my-company, "
                    + "or use Find my group.");
        }
        double newLimit;
        try {
            newLimit = Double.parseDouble(limit.getText().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new ConfigurationException("The limit must be a number of credits, e.g. 100.");
        }
        if (newLimit <= 0) {
            throw new ConfigurationException("The limit must be above zero.");
        }
        String newPercents = alertPercents.getText().trim();
        try {
            Alert.parsePercents(newPercents);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(e.getMessage() + ". Use whole percents, e.g. 80, 95.");
        }

        CreditsSettings.Settings s = CreditsSettings.getInstance().getState();
        s.gitlabUrl = newUrl;
        s.namespace = newNamespace;
        namespace.setText(newNamespace);
        s.limit = newLimit;
        s.alertPercents = newPercents;
        s.alertOnForecast = alertOnForecast.isSelected();
        s.refreshMinutes = refreshMinutes.getNumber();

        String newToken = new String(token.getPassword()).trim();
        if (!newToken.isEmpty()) {
            s.tokenSavedAt = System.currentTimeMillis();
        }
        token.setText("");
        if (!newToken.isEmpty()) {
            pendingToken = newToken;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!newToken.isEmpty()) {
                CreditsSettings.getInstance().setToken(newToken);
                pendingToken = null;
            }
            CreditsService.getInstance().reschedule();
        });
        if (!newToken.isEmpty()) {
            savedInfo = null;
            savedRejected = false;
            lookAtSavedToken();
        }
    }

    @Override
    public void reset() {
        CreditsSettings.Settings s = CreditsSettings.getInstance().getState();
        url.setText(s.gitlabUrl);
        namespace.setText(s.namespace);
        groupHint.setText(wrap(groupHintText()));
        token.setText("");
        limit.setText(plain(s.limit));
        alertPercents.setText(s.alertPercents);
        alertOnForecast.setSelected(s.alertOnForecast);
        refreshMinutes.setNumber(s.refreshMinutes);
        savedInfo = null;
        savedRejected = false;
        forgetCheck();
        lookAtSavedToken();
    }

    @Override
    public void disposeUIResources() {
        savedTicket++;
        checkTicket++;
        panel = null;
        url = null;
        token = null;
        tokenBox = null;
        namespace = null;
        findGroup = null;
        groupHint = null;
        limit = null;
        alertPercents = null;
        alertOnForecast = null;
        refreshMinutes = null;
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
