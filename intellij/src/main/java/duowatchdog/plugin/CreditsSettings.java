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
import com.intellij.openapi.util.SystemInfo;
import duowatchdog.core.Accounts;
import duowatchdog.core.Alert;
import duowatchdog.core.CliLaunch;
import duowatchdog.core.CreditsClient.TokenInfo;
import duowatchdog.core.GitLabAddress;
import duowatchdog.core.LiveSession;
import duowatchdog.core.ModelCredits;
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
        /** {@link #tokenSavedAt} of the token glab took; until it matches, glab is still to get it. */
        public String glabHandedKey = "";
        /** {@link #tokenSavedAt} of the token glab turned down, so it is not tried again on every refresh. */
        public String glabFailedKey = "";
        /** {@link #tokenSavedAt} of a token GitLab would not rotate, so it is not tried again until the token changes. */
        public String rotateFailedKey = "";

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

        /** Whether glab has this account's current token. */
        public boolean glabHasToken() {
            return String.valueOf(tokenSavedAt).equals(glabHandedKey);
        }

        /** Whether glab turned this account's current token down. */
        public boolean glabRefused() {
            return String.valueOf(tokenSavedAt).equals(glabFailedKey);
        }

        /** Whether GitLab refused to rotate this account's current token. */
        public boolean rotateRefused() {
            return String.valueOf(tokenSavedAt).equals(rotateFailedKey);
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
            a.glabHandedKey = glabHandedKey;
            a.glabFailedKey = glabFailedKey;
            a.rotateFailedKey = rotateFailedKey;
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
        /** The round trips per Duo CLI session recommended at most: the status bar's session goes amber, then red. */
        public int roundTripLimit = LiveSession.RECOMMENDED_ROUND_TRIPS;
        /**
         * The model a Duo CLI step is priced as when the log names none or one with no known rate: the group's default,
         * as {@link ModelCredits#MODELS} names it.
         */
        public String groupDefaultModel = ModelCredits.ASSUMED;
        /** How Usage's quick actions start the Duo CLI: "duo", or "glab duo cli", as {@link CliLaunch.Cli} names them. */
        public String cli = CliLaunch.Cli.DUO.command();
        /** Whether the Duo CLI they start asks before each tool: "ask", or "autoApprove", as {@link CliLaunch.Mode} names them. */
        public String cliMode = CliLaunch.Mode.ASK.id();
        /** Quick commands of your own on Usage: a button each, that types its command at the Duo CLI's prompt. */
        public List<QuickCommand> quickCommands = new ArrayList<>();
        /** Have GitLab rotate a token a week before it expires. */
        public boolean rotateTokens = true;
        /** How many months Statistics shows: one of History.RANGES. */
        public int statisticsMonths = 3;
        /** The plugin version the What's New page was last offered for. */
        public String lastSeenVersion = "";
        /** Ask GitHub once a day whether a newer Duo Watchdog is out, and say so. */
        public boolean checkForUpdates = true;
        /** When GitHub was last asked, in epoch milliseconds; 0 for never. */
        public long lastUpdateCheck = 0;
        /** A version the notice's Skip This Version passed over; asked about again only from Check for Updates. */
        public String skippedUpdate = "";
    }

    /** A quick command of your own, as kept: its button's name and what it types at the Duo CLI's prompt. */
    public static final class QuickCommand {
        public String name = "";
        public String command = "";

        public QuickCommand() {
        }

        QuickCommand(CliLaunch.QuickCommand c) {
            name = c.name();
            command = c.command();
        }
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

    /** The quick commands of your own that can be, in their order. */
    public List<CliLaunch.QuickCommand> quickCommands() {
        return CliLaunch.quickCommands(settings.quickCommands.stream()
                .map(c -> new CliLaunch.QuickCommand(c.name, c.command)).toList());
    }

    public void setQuickCommands(List<CliLaunch.QuickCommand> commands) {
        settings.quickCommands = new ArrayList<>(CliLaunch.quickCommands(commands).stream().map(QuickCommand::new).toList());
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

    /**
     * Where the tokens are kept, as far as the IDE says in its public API: only whether its password safe keeps them
     * in memory. Otherwise the store the IDE uses unless told otherwise in Settings | Passwords: the system's own on
     * macOS and Windows; on Linux the keyring or the IDE's KeePass database, so neither is named.
     */
    static String storeName() {
        if (PasswordSafe.getInstance().isMemoryOnly()) {
            return "memory, until the IDE restarts";
        }
        return SystemInfo.isMac ? "the macOS Keychain"
                : SystemInfo.isWindows ? "the Windows Credential Manager" : "the IDE's password store";
    }
}
