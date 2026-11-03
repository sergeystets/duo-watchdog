package duowatchdog.plugin;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.credentialStore.PasswordSafeSettings;
import com.intellij.credentialStore.ProviderType;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.util.SystemInfo;
import duowatchdog.core.Accounts;
import duowatchdog.core.Alert;
import duowatchdog.core.CreditsClient.TokenInfo;
import duowatchdog.core.GitLabAddress;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Duo Watchdog's settings. The tokens are not here: they live in the IDE's password safe, one per account. */
@Service(Service.Level.APP)
@State(name = "DuoWatchdogSettings", storages = @Storage("duo-watchdog.xml"))
public final class CreditsSettings implements PersistentStateComponent<CreditsSettings.Settings> {

    /** {@link Account#expiresAt} of a token that never expires. */
    static final String NEVER = "never";

    /**
     * One GitLab user on one GitLab, as GitLab named the owner of its token. A token for an account already listed
     * replaces its token and keeps the rest.
     */
    public static final class Account {
        public String gitlabUrl = "https://gitlab.com";
        public String username = "";
        /** The path of the top-level group that owns the Duo subscription, e.g. {@code my-company}. */
        public String namespace = "";
        /** The credits this account means to stay within: its GitLab cap, or a lower budget. */
        public double limit = 100;
        /** When its token was saved, in epoch milliseconds; 0 when not known. */
        public long tokenSavedAt = 0;
        /** The token's expiry day as GitLab last gave it (yyyy-MM-dd), {@link #NEVER}, or "" when not known. */
        public String expiresAt = "";
        /** The token's scopes as GitLab last gave them, comma-separated; "" when not known. */
        public String scopes = "";
        /** Hand the token to glab for this GitLab. Off unless turned on. */
        public boolean glab = false;
        /** Let the GitLab Duo CLI sign in with it, through glab. Off unless turned on. */
        public boolean duo = false;
        /** {@link Alert#key()} of the last notice shown, so it is not repeated on every refresh. */
        public String lastAlertKey = "";
        /** The expiry the "expires soon" popup was shown for, so it comes once. */
        public String lastExpiryKey = "";
        /** The token GitLab turned down when the popup said so, so it comes once. */
        public String lastRejectedKey = "";

        public String id() {
            return Accounts.id(gitlabUrl, username);
        }

        public String host() {
            String host = GitLabAddress.host(gitlabUrl);
            return host != null ? host : gitlabUrl;
        }

        /** "@dana.k · gitlab.com · acme". */
        public String label() {
            return "@" + username + " · " + host() + (namespace == null || namespace.isBlank() ? "" : " · " + namespace);
        }

        /** The token's expiry day, or null when it never expires or is not known. */
        public @Nullable LocalDate expiry() {
            if (expiresAt == null || expiresAt.isBlank() || NEVER.equals(expiresAt)) {
                return null;
            }
            try {
                return LocalDate.parse(expiresAt);
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        /** The token's scopes, or null when not known. */
        public @Nullable List<String> scopeList() {
            return scopes == null || scopes.isBlank() ? null : Arrays.stream(scopes.split(",")).map(String::trim).toList();
        }

        /** Keeps what GitLab said of the token, for the list and the popups. */
        public void remember(TokenInfo info) {
            expiresAt = info.expiresAt() == null ? NEVER : info.expiresAt().toString();
            scopes = String.join(",", info.scopes());
        }

        public Account copy() {
            Account a = new Account();
            a.gitlabUrl = gitlabUrl;
            a.username = username;
            a.namespace = namespace;
            a.limit = limit;
            a.tokenSavedAt = tokenSavedAt;
            a.expiresAt = expiresAt;
            a.scopes = scopes;
            a.glab = glab;
            a.duo = duo;
            a.lastAlertKey = lastAlertKey;
            a.lastExpiryKey = lastExpiryKey;
            a.lastRejectedKey = lastRejectedKey;
            return a;
        }

        /** Whether the settings page changed what the user edits of it. */
        public boolean sameEdits(Account other) {
            return Objects.equals(id(), other.id()) && Objects.equals(gitlabUrl, other.gitlabUrl)
                    && Objects.equals(namespace, other.namespace) && limit == other.limit
                    && glab == other.glab && duo == other.duo;
        }
    }

    public static final class Settings {
        /** Up to 0.3.0 there was one account, kept in these five; now they are read once, to move it to accounts. */
        public String gitlabUrl = "https://gitlab.com";
        public String namespace = "";
        public double limit = 100;
        public long tokenSavedAt = 0;
        public String lastAlertKey = "";

        public List<Account> accounts = new ArrayList<>();
        /** The {@link Account#id()} the tool window and the status bar show. */
        public String selected = "";
        /** Percents of each account's limit that raise a notice, as {@link Alert#parsePercents} reads them. */
        public String alertPercents = Alert.DEFAULT_PERCENTS;
        public boolean alertOnForecast = true;
        public int refreshMinutes = 15;
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

    /** The account with this id, or null. */
    public @Nullable Account account(String id) {
        return settings.accounts.stream().filter(a -> a.id().equals(id)).findFirst().orElse(null);
    }

    /** The alert percents, or the default ones while the saved text does not parse. */
    public List<Integer> percents() {
        try {
            return Alert.parsePercents(settings.alertPercents);
        } catch (IllegalArgumentException e) {
            return Alert.parsePercents(Alert.DEFAULT_PERCENTS);
        }
    }

    /** The account's token. Reads the password safe, which may block: call off the EDT. */
    public @Nullable String token(String id) {
        Credentials credentials = PasswordSafe.getInstance().get(tokenAttributes(id));
        return credentials != null ? credentials.getPasswordAsString() : null;
    }

    /** Saves or, with null, forgets the account's token. Writes the password safe, which may block: call off the EDT. */
    public void setToken(String id, @Nullable String token) {
        PasswordSafe.getInstance().set(tokenAttributes(id), token != null ? new Credentials(id, token) : null);
    }

    /** The one token saved before 0.4.0, still to be moved to its account. Call off the EDT. */
    @Nullable String legacyToken() {
        Credentials credentials = PasswordSafe.getInstance().get(legacyAttributes());
        return credentials != null ? credentials.getPasswordAsString() : null;
    }

    void clearLegacyToken() {
        PasswordSafe.getInstance().set(legacyAttributes(), null);
    }

    private static CredentialAttributes tokenAttributes(String id) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("Duo Watchdog", "token " + id), id);
    }

    private static CredentialAttributes legacyAttributes() {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName("Duo Watchdog", "token"));
    }

    /** Where the tokens are kept, as the IDE's password safe is set: "the macOS Keychain", "KeePass", … */
    static String storeName() {
        try {
            ProviderType type = ApplicationManager.getApplication().getService(PasswordSafeSettings.class).getProviderType();
            return switch (type) {
                case MEMORY_ONLY -> "memory, until the IDE restarts";
                case KEEPASS -> "the IDE's KeePass database";
                default -> SystemInfo.isMac ? "the macOS Keychain"
                        : SystemInfo.isWindows ? "the Windows Credential Manager" : "the system keyring";
            };
        } catch (RuntimeException | LinkageError e) {
            // An IDE that moved the class: the plain name is still true.
            return "the IDE password safe";
        }
    }
}
