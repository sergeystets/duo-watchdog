package duowatchdog.plugin;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import duowatchdog.core.Alert;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Duo Watchdog's settings. The token is not here: it lives in the IDE's password safe. */
@Service(Service.Level.APP)
@State(name = "DuoWatchdogSettings", storages = @Storage("duo-watchdog.xml"))
public final class CreditsSettings implements PersistentStateComponent<CreditsSettings.Settings> {

    public static final class Settings {
        public String gitlabUrl = "https://gitlab.com";
        /** The top-level group that owns the Duo subscription, e.g. {@code my-company}. */
        public String namespace = "";
        /**
         * The credits the user means to stay within: their GitLab cap, or a lower budget
         * of their own. GitLab shows a user's cap only to the group owner, so the user keeps it here.
         */
        public double limit = 100;
        /** Percents of the limit that raise a notice, as {@link Alert#parsePercents} reads them. */
        public String alertPercents = Alert.DEFAULT_PERCENTS;
        public boolean alertOnForecast = true;
        public int refreshMinutes = 15;
        /** {@link Alert#key()} of the last notice shown, so it is not repeated on every refresh. */
        public String lastAlertKey = "";
    }

    private Settings settings = new Settings();

    public static CreditsSettings getInstance() {
        return ApplicationManager.getApplication().getService(CreditsSettings.class);
    }

    @Override
    public @NotNull Settings getState() {
        return settings;
    }

    @Override
    public void loadState(@NotNull Settings state) {
        settings = state;
    }

    /** Reads the password safe, which may block: call off the EDT. */
    public @Nullable String token() {
        Credentials credentials = PasswordSafe.getInstance().get(tokenAttributes());
        return credentials != null ? credentials.getPasswordAsString() : null;
    }

    /** Writes the password safe, which may block: call off the EDT. */
    public void setToken(@Nullable String token) {
        PasswordSafe.getInstance().set(tokenAttributes(), token != null ? new Credentials(null, token) : null);
    }

    private static CredentialAttributes tokenAttributes() {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("Duo Watchdog", "token"));
    }
}
