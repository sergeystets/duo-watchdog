package duowatchdog.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import duowatchdog.core.Alert;
import duowatchdog.core.CreditsClient;
import duowatchdog.core.GitLabException;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

/** Settings | Tools | Duo Watchdog. */
public final class CreditsConfigurable implements Configurable {

    private JPanel panel;
    private JBTextField url;
    private JBTextField namespace;
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
        // The saved token is never read back into the form: the field only replaces it.
        token = new JBPasswordField();
        token.getEmptyText().setText("Leave empty to keep the saved token");
        limit = new JBTextField();
        alertPercents = new JBTextField();
        alertOnForecast = new JBCheckBox("Alert when the month-end forecast passes the limit");
        refreshMinutes = new JBIntSpinner(15, 1, 1440);
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("GitLab URL:", url)
                .addLabeledComponent("Top-level group:", namespace)
                .addComponentToRightColumn(hint("The group that owns the Duo subscription, e.g. my-company."))
                .addLabeledComponent("Personal access token:", token)
                .addComponentToRightColumn(hint("Scopes read_api and ai_features. Kept in the IDE password safe."))
                .addSeparator()
                .addLabeledComponent("Limit (credits):", limit)
                .addComponentToRightColumn(hint("Your GitLab cap, or a lower budget of your own."))
                .addLabeledComponent("Alert at % of limit:", alertPercents)
                .addComponentToRightColumn(hint("Comma-separated, e.g. 80, 95. Reaching the limit and a GitLab block always alert."))
                .addComponent(alertOnForecast)
                .addLabeledComponent("Refresh every (minutes):", refreshMinutes)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    private static JBLabel hint(String text) {
        return new JBLabel(text, UIUtil.ComponentStyle.SMALL, UIUtil.FontColor.BRIGHTER);
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
        String newNamespace = namespace.getText().trim();
        if (newNamespace.isEmpty() || newNamespace.contains("/")) {
            throw new ConfigurationException("Enter the top-level group path, without slashes, e.g. my-company.");
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
