package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.util.text.StringUtil;
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
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.Alert;
import duowatchdog.core.CreditsClient;
import duowatchdog.core.CreditsClient.GroupMatch;
import duowatchdog.core.GitLabAddress;
import duowatchdog.core.GitLabException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionListener;
import java.util.List;

/** Settings | Tools | Duo Watchdog: the connection, then the limit and alerts. */
public final class CreditsConfigurable implements Configurable {

    private static final JBColor INFO_BACKGROUND = new JBColor(0xEEF3FE, 0x25324D);
    private static final int HINT_WIDTH = 380;

    private JPanel panel;
    private JBTextField url;
    private JBTextField namespace;
    private JButton findGroup;
    private JBLabel groupHint;
    private JBPasswordField token;
    private JBTextField limit;
    private JBTextField alertPercents;
    private JBCheckBox alertOnForecast;
    private JBIntSpinner refreshMinutes;

    @Override
    public @Nls String getDisplayName() {
        return "Duo Watchdog";
    }

    @Override
    public @Nullable JComponent createComponent() {
        url = new JBTextField();
        namespace = new JBTextField();
        namespace.getEmptyText().setText("e.g. my-company");
        findGroup = new JButton("Find my group");
        findGroup.addActionListener(e -> findGroup());
        groupHint = hint("");
        url.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent e) {
                groupHint.setText(wrap(groupHintText()));
            }
        });
        // The saved token is never read back into the form: the field only replaces it.
        token = new JBPasswordField();
        token.getEmptyText().setText("Leave empty to keep the saved token");
        limit = new JBTextField(8);
        alertPercents = new JBTextField(8);
        alertOnForecast = new JBCheckBox("Alert when the month-end forecast passes the limit");
        refreshMinutes = new JBIntSpinner(15, 1, 1440);

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
                .addLabeledComponent("Group path:", groupRow)
                .addComponentToRightColumn(groupHint)
                .addLabeledComponent("Personal access token:", token)
                .addComponentToRightColumn(hint("Kept in the IDE password safe. Leave empty to keep the saved one."))
                .addComponentToRightColumn(tokenBox(), JBUI.scale(8))
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

    private String groupHintText() {
        String host = GitLabAddress.host(url.getText());
        return "The path from the group's address, " + (host != null ? host : "gitlab.com") + "/‹path›, "
                + "not the name GitLab shows on its pages. Find my group fills it in.";
    }

    /** "The token needs two scopes…" and the link to GitLab's new-token page with both ticked. */
    private JComponent tokenBox() {
        JPanel box = new JPanel(new BorderLayout(JBUI.scale(10), 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(INFO_BACKGROUND);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(16), JBUI.scale(16));
                g2.dispose();
            }
        };
        box.setOpaque(false);
        box.setBorder(JBUI.Borders.empty(12, 14));
        JBLabel icon = new JBLabel(AllIcons.General.BalloonInformation);
        icon.setVerticalAlignment(SwingConstants.TOP);
        box.add(icon, BorderLayout.WEST);

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JBLabel scopes = new JBLabel("<html><body style='width:" + JBUI.scale(HINT_WIDTH - 60) + "px'>"
                + "The token needs two scopes: <b>read_api</b> and <b>ai_features</b>. "
                + "Leave everything else unticked: <b>api</b> can change code.</body></html>");
        scopes.setAlignmentX(Component.LEFT_ALIGNMENT);
        ActionLink link = new ActionLink("Create the token in GitLab ↗",
                (ActionListener) e -> BrowserUtil.browse(GitLabAddress.tokenPage(url.getText())));
        link.setAlignmentX(Component.LEFT_ALIGNMENT);
        link.setBorder(JBUI.Borders.emptyTop(6));
        text.add(scopes);
        text.add(link);
        box.add(text, BorderLayout.CENTER);
        return box;
    }

    /** Asks GitLab which of the user's top-level groups give their credits, and fills in the one, or offers the few. */
    private void findGroup() {
        String gitlabUrl = url.getText().trim();
        String typed = new String(token.getPassword()).trim();
        CreditsClient.Groups groups;
        try {
            groups = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                    (ThrowableComputable<CreditsClient.Groups, GitLabException>) () -> {
                        String key = typed.isEmpty() ? CreditsSettings.getInstance().token() : typed;
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
        return "<html><body style='width:" + JBUI.scale(HINT_WIDTH) + "px'>" + StringUtil.escapeXmlEntities(text)
                + "</body></html>";
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
        token.setText("");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            if (!newToken.isEmpty()) {
                CreditsSettings.getInstance().setToken(newToken);
            }
            CreditsService.getInstance().reschedule();
        });
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
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        url = null;
        namespace = null;
        findGroup = null;
        groupHint = null;
        token = null;
        limit = null;
        alertPercents = null;
        alertOnForecast = null;
        refreshMinutes = null;
    }

    private static String plain(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
