package duowatchdog.plugin;

import com.intellij.ide.BrowserUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.net.ssl.CertificateManager;
import duowatchdog.core.Accounts;
import duowatchdog.core.Alert;
import duowatchdog.core.CreditsClient;
import duowatchdog.core.CreditsClient.TokenInfo;
import duowatchdog.core.CreditsView;
import duowatchdog.core.GitLabAddress;
import duowatchdog.core.GitLabException;
import duowatchdog.core.History;
import duowatchdog.core.HttpTransport;
import duowatchdog.core.Sessions;
import duowatchdog.core.Snapshot;
import duowatchdog.plugin.CreditsSettings.Account;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.StringSelection;
import java.net.Authenticator;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Refreshes every account's credits on a schedule and on demand; one per IDE, since the numbers are the user's, not
 * a project's. The tool window and the status bar show the selected account; the notices come for all of them.
 */
@Service(Service.Level.APP)
public final class CreditsService implements Disposable {

    /** Must match the {@code notificationGroup id}s in plugin.xml: balloons that go, and ones that stay. */
    static final String NOTIFICATION_GROUP = "Duo Watchdog";
    static final String STICKY_GROUP = "Duo Watchdog Important";

    private final AtomicBoolean loading = new AtomicBoolean();
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    /** Each account's months as Statistics last showed them, by account and range; kept {@link #HISTORY_MINUTES}. */
    private final Map<String, CachedHistory> histories = new ConcurrentHashMap<>();
    private static final long HISTORY_MINUTES = 60;
    /** At most this many months asked of GitLab at once. */
    private static final java.util.concurrent.ExecutorService MONTHS = AppExecutorUtil
            .createBoundedApplicationPoolExecutor("Duo Watchdog statistics", 3);

    private record CachedHistory(Instant at, String token, History history) {
    }
    /** Why the token saved before 0.4.0 could not be moved to its account yet, while there is no account. */
    private volatile String migrationError;
    private ScheduledFuture<?> schedule;
    private HttpClient http;

    public static CreditsService getInstance() {
        return ApplicationManager.getApplication().getService(CreditsService.class);
    }

    /** What is known of the account; loading until its first refresh. */
    public Snapshot snapshot(String id) {
        return snapshots.getOrDefault(id, Snapshot.loading());
    }

    /** The account the tool window and the status bar show: the chosen one, else the first; null when none. */
    public @Nullable Account selected() {
        CreditsSettings settings = CreditsSettings.getInstance();
        Account chosen = settings.account(settings.getState().selected);
        if (chosen != null) {
            return chosen;
        }
        List<Account> all = sorted();
        return all.isEmpty() ? null : all.get(0);
    }

    /** The selected account's snapshot, or why there is none. */
    public Snapshot selectedSnapshot() {
        Account account = selected();
        if (account != null) {
            return snapshot(account.id());
        }
        String why = migrationError;
        return why != null ? Snapshot.loading().failed(why) : Snapshot.notConfigured();
    }

    public void select(String id) {
        CreditsSettings.getInstance().getState().selected = id;
        ApplicationManager.getApplication().invokeLater(() ->
                ApplicationManager.getApplication().getMessageBus().syncPublisher(CreditsListener.TOPIC).updated());
        // glab follows the picked account, when that account may hand its token to glab.
        ApplicationManager.getApplication().executeOnPooledThread(() -> syncGlab(Set.of()));
    }

    /**
     * For each GitLab, hands glab the token of the account it should have ({@link Accounts#glabOwner}: the picked
     * one, else the one it has, else the first allowed) when glab does not have it yet or it was just replaced.
     * Says nothing while glab is not installed. Off the EDT.
     *
     * @param replaced ids of the accounts whose token was just saved
     */
    void syncGlab(Set<String> replaced) {
        CreditsSettings settings = CreditsSettings.getInstance();
        Account picked = selected();
        Map<String, List<Account>> allowed = new LinkedHashMap<>();
        for (Account a : sorted()) {
            if (a.glab || a.duo) {
                allowed.computeIfAbsent(a.host().toLowerCase(Locale.ROOT), host -> new ArrayList<>()).add(a);
            }
        }
        List<Account> due = new ArrayList<>();
        for (Map.Entry<String, List<Account>> onHost : allowed.entrySet()) {
            Account owner = Accounts.glabOwner(onHost.getValue(), picked, Account::glabHasToken);
            boolean hand = owner != null && (!owner.glabHasToken() || replaced.contains(owner.id())) && !owner.glabRefused();
            DuoLog.info("glab sync " + onHost.getKey() + ": allowed " + onHost.getValue().stream().map(Account::id).toList()
                    + ", picked " + (picked != null ? picked.id() : "none") + ", owner " + (owner != null ? owner.id() : "none")
                    + (owner == null ? "" : owner.glabHasToken() && !replaced.contains(owner.id()) ? ", glab has it"
                    : owner.glabRefused() ? ", glab refused this token before" : ", handing it over"));
            if (hand) {
                due.add(owner);
            }
        }
        if (!due.isEmpty()) {
            Map<String, String> tokens = new java.util.HashMap<>();
            due.forEach(a -> tokens.put(a.id(), settings.token(a.id())));
            handOff(due, tokens, true);
        }
    }

    /** Every account, most credits left first, the ones without numbers last. */
    public List<Account> sorted() {
        List<Account> all = new ArrayList<>(CreditsSettings.getInstance().getState().accounts);
        all.sort(Accounts.byCreditsLeft(a -> Accounts.left(snapshot(a.id()), a.limit), a -> a.username, Account::host));
        return all;
    }

    /**
     * The account's last {@code count} months, a month at a time, three at once: settled months from the disk cache
     * ({@link HistoryCache}), the others from GitLab. The whole answer is kept for an hour; {@code force} asks again
     * for the months still changing. May block: call off the EDT.
     */
    History history(Account account, boolean force, int count) throws GitLabException {
        String token = CreditsSettings.getInstance().token(account.id());
        if (token == null || token.isBlank()) {
            throw new GitLabException("No token saved for @" + account.username + " on " + account.host() + ".");
        }
        String group = GitLabAddress.groupPath(account.namespace, account.gitlabUrl);
        if (group.isEmpty()) {
            throw new GitLabException("No group path yet for @" + account.username + " on " + account.host()
                    + ": set it in Settings | Tools | Duo Watchdog.");
        }
        String key = account.id() + "|" + count;
        CachedHistory cached = histories.get(key);
        if (!force && cached != null && cached.token().equals(token)
                && cached.at().isAfter(Instant.now().minus(Duration.ofMinutes(HISTORY_MINUTES)))) {
            return cached.history();
        }
        LocalDate today = LocalDate.now();
        CreditsClient client = new CreditsClient(account.gitlabUrl, token.trim(), transport())
                .log(line -> DuoLog.info(account.id() + " " + line));
        List<java.time.YearMonth> wanted = History.monthsFor(count, today);
        List<java.util.concurrent.Future<History>> asked = new ArrayList<>();
        for (java.time.YearMonth month : wanted) {
            asked.add(MONTHS.submit(() -> month(client, account.id(), group, month, today)));
        }
        List<History> months = new ArrayList<>();
        for (int i = 0; i < asked.size(); i++) {
            try {
                History h = asked.get(i).get();
                if (h != null) {
                    months.add(h);
                }
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof GitLabException g && (i == 0 || g.status() > 0 && g.status() < 500)) {
                    // This month failing, or a refusal of any month, is the answer: not a gap at the old end.
                    throw g;
                }
                DuoLog.warn(account.id() + " history " + wanted.get(i) + ": " + e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GitLabException("Interrupted while reading the history.");
            }
        }
        History history = months.size() == 1 ? months.get(0) : History.merge(months);
        histories.put(key, new CachedHistory(Instant.now(), token, history));
        return history;
    }

    /** Each account's sessions since a day, as the Sessions tab last showed them; kept {@link #SESSIONS_MINUTES}. */
    private final Map<String, CachedSessions> sessions = new ConcurrentHashMap<>();
    private static final long SESSIONS_MINUTES = 5;

    private record CachedSessions(Instant at, String token, List<Sessions.Session> sessions) {
    }

    /** A client for the account's GitLab, with its token. */
    private CreditsClient client(Account account) throws GitLabException {
        String token = CreditsSettings.getInstance().token(account.id());
        if (token == null || token.isBlank()) {
            throw new GitLabException("No token saved for @" + account.username + " on " + account.host() + ".");
        }
        return new CreditsClient(account.gitlabUrl, token.trim(), transport())
                .log(line -> DuoLog.info(account.id() + " " + line));
    }

    /**
     * The account's sessions updated since the start of {@code from}, a UTC day, last updated first; kept a few
     * minutes unless {@code force}. May block: call off the EDT.
     */
    List<Sessions.Session> sessions(Account account, boolean force, LocalDate from) throws GitLabException {
        String token = CreditsSettings.getInstance().token(account.id());
        String key = account.id() + "|" + from;
        CachedSessions cached = sessions.get(key);
        if (!force && cached != null && cached.token().equals(token)
                && cached.at().isAfter(Instant.now().minus(Duration.ofMinutes(SESSIONS_MINUTES)))) {
            return cached.sessions();
        }
        List<Sessions.Session> found = client(account).sessions(from.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        sessions.put(key, new CachedSessions(Instant.now(), token, found));
        return found;
    }

    /** One of the account's sessions by its number, or null. May block. */
    @Nullable Sessions.Session session(Account account, long id) throws GitLabException {
        return client(account).session(id);
    }

    /** The account's session last updated, or null. May block. */
    @Nullable Sessions.Session lastSession(Account account) throws GitLabException {
        return client(account).lastSession();
    }

    /**
     * One month: from the disk cache when it is settled and kept, else from GitLab, kept when settled. A settled month
     * GitLab fails on with a server error lies before the subscription: kept as empty, so it is not asked again.
     */
    private static History month(CreditsClient client, String accountId, String group, java.time.YearMonth month,
                                 LocalDate today) throws GitLabException {
        boolean settled = History.settled(month, today);
        if (settled) {
            History kept = HistoryCache.read(accountId, group, month);
            if (kept != null) {
                return kept;
            }
        }
        try {
            History h = client.month(group, month, today);
            // A month whose breakdown by feature did not come is asked again next time, not kept without it.
            if (settled && h.flowsError() == null) {
                HistoryCache.write(accountId, group, month, h);
            }
            return h;
        } catch (GitLabException e) {
            if (settled && e.status() >= 500) {
                DuoLog.info(accountId + " history " + month + ": " + e.getMessage() + ", kept as empty");
                HistoryCache.write(accountId, group, month, History.none(month));
                return History.none(month);
            }
            throw e;
        }
    }

    /** Starts the periodic refresh; later calls do nothing. */
    public synchronized void start() {
        if (schedule == null) {
            reschedule();
        }
    }

    /** Restarts the schedule with the current settings and refreshes now. */
    public synchronized void reschedule() {
        if (schedule != null) {
            schedule.cancel(false);
        }
        long minutes = Math.max(1, CreditsSettings.getInstance().getState().refreshMinutes);
        schedule = AppExecutorUtil.getAppScheduledExecutorService()
                .scheduleWithFixedDelay(this::refresh, 0, minutes, TimeUnit.MINUTES);
    }

    public void refresh() {
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                migrate();
                CreditsSettings settings = CreditsSettings.getInstance();
                List<Account> accounts = new ArrayList<>(settings.getState().accounts);
                snapshots.keySet().retainAll(accounts.stream().map(Account::id).toList());
                for (Account account : accounts) {
                    Snapshot next = load(account, settings.token(account.id()));
                    snapshots.put(account.id(), next);
                    DuoLog.info("refresh " + account.id() + ": " + next.status()
                            + (next.report() != null ? ", " + CreditsView.number(next.report().creditsUsed()) + " used" : "")
                            + (next.error() != null ? ", " + next.error() : ""));
                    notifyOnce(account, next);
                }
                // glab still to get a token: installed since, or its hand-off was not done yet.
                syncGlab(Set.of());
            } finally {
                loading.set(false);
                ApplicationManager.getApplication().invokeLater(() ->
                        ApplicationManager.getApplication().getMessageBus().syncPublisher(CreditsListener.TOPIC).updated());
            }
        });
    }

    /** Moves the one token of 0.3.0 and before to an account named by GitLab, with its group, limit and last alert. */
    private void migrate() {
        CreditsSettings settings = CreditsSettings.getInstance();
        CreditsSettings.Settings state = settings.getState();
        if (!state.accounts.isEmpty()) {
            migrationError = null;
            return;
        }
        String token = settings.legacyToken();
        if (token == null || token.isBlank()) {
            migrationError = null;
            return;
        }
        try {
            CreditsClient client = new CreditsClient(state.gitlabUrl, token.trim(), transport());
            Account account = new Account();
            account.gitlabUrl = state.gitlabUrl;
            account.username = client.username();
            account.namespace = state.namespace;
            account.limit = state.limit;
            account.tokenSavedAt = state.tokenSavedAt;
            account.lastAlertKey = state.lastAlertKey;
            try {
                account.remember(client.tokenInfo());
            } catch (GitLabException e) {
                // The details are a bonus; the next refresh asks again.
            }
            settings.setToken(account.id(), token.trim());
            state.accounts.add(account);
            state.selected = account.id();
            settings.clearLegacyToken();
            migrationError = null;
            DuoLog.info("migrated the 0.3.0 token to " + account.id());
        } catch (GitLabException e) {
            // Kept until GitLab says whose it is, so it can go to the right account.
            migrationError = "The saved token is not moved to its account yet: " + e.getMessage();
            DuoLog.warn("migration: " + e.getMessage());
        }
    }

    private Snapshot load(Account account, @Nullable String token) {
        if (token == null || token.isBlank()) {
            return Snapshot.noToken("No token saved for @" + account.username + " on " + account.host()
                    + " in " + CreditsSettings.storeName() + ". Replace token… in Settings saves one.");
        }
        Snapshot previous = snapshot(account.id());
        try {
            CreditsClient client = new CreditsClient(account.gitlabUrl, token.trim(), transport());
            try {
                TokenInfo info = client.tokenInfo();
                account.remember(info);
                String rotated = rotateIfDue(account, client, info);
                if (rotated != null) {
                    client = new CreditsClient(account.gitlabUrl, rotated, transport());
                }
            } catch (GitLabException e) {
                if (e.status() == 401) {
                    return rejected(account);
                }
                // An older GitLab or a proxy may not answer it; the credits still come.
            }
            if (!Accounts.readsUsage(account.scopeList())) {
                return Snapshot.notTracked(notTracked(account));
            }
            String group = GitLabAddress.groupPath(account.namespace, account.gitlabUrl);
            if (group.isEmpty()) {
                return Snapshot.notConfigured("No group path yet for @" + account.username + " on " + account.host()
                        + ": set it in Settings | Tools | Duo Watchdog, or use Find my group there.");
            }
            return Snapshot.ok(client.fetch(group), Instant.now());
        } catch (GitLabException e) {
            if (e.status() == 401) {
                return rejected(account);
            }
            if (e.status() == 403) {
                return Snapshot.notTracked(notTracked(account));
            }
            // The message is the user's to read; it never carries the token.
            DuoLog.warn("refresh " + account.id() + ": " + e.getMessage());
            return previous.failed(e.getMessage());
        } catch (RuntimeException e) {
            DuoLog.warn("refresh " + account.id() + ": " + e);
            return previous.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * When rotating is on and the token expires within a week, has GitLab rotate it, saves the new one at once (GitLab
     * has revoked the old one) and says so. Only while the IDE is focused, and only a token GitLab just took, so two
     * IDEs with the same token do not both rotate it: rotating a revoked token makes GitLab revoke the new one too.
     *
     * @param info what GitLab just said of the token
     * @return the new token, or null when the token stays
     */
    private @Nullable String rotateIfDue(Account account, CreditsClient client, TokenInfo info) {
        CreditsSettings settings = CreditsSettings.getInstance();
        LocalDate today = LocalDate.now();
        String key = String.valueOf(account.tokenSavedAt);
        if (!settings.getState().rotateTokens || !ApplicationManager.getApplication().isActive()
                || key.equals(account.rotateFailedKey) || !Accounts.rotationDue(info.expiresAt(), info.scopes(), today)) {
            return null;
        }
        LocalDate createdOn = info.createdAt() == null ? null : info.createdAt().atZone(ZoneOffset.UTC).toLocalDate();
        CreditsClient.Rotated rotated;
        try {
            rotated = client.rotate(Accounts.rotatedExpiry(createdOn, info.expiresAt(), today));
        } catch (GitLabException e) {
            DuoLog.warn("rotate " + account.id() + ": " + e.getMessage());
            if (e.status() >= 400 && e.status() < 500 && e.status() != 401) {
                // Asked again only for another token: GitLab said why, and the expiry popup still comes.
                account.rotateFailedKey = key;
                Notification n = notification(true, "Token not rotated", StringUtil.escapeXmlEntities(
                        CreditsView.expiry(info.expiresAt(), today) + ". " + e.getMessage() + " Replace it before then."),
                        NotificationType.WARNING);
                n.setSubtitle(account.label());
                n.addAction(NotificationAction.createSimpleExpiring("Replace Token…", () -> openSettings(account.id(), true)));
                boolean forCli = account.glab || account.duo;
                n.addAction(NotificationAction.createSimple("Create a New Token in GitLab",
                        () -> BrowserUtil.browse(GitLabAddress.tokenPage(account.gitlabUrl, forCli))));
                n.notify(null);
            }
            return null;
        }
        String token = rotated.token();
        NotificationAction copy = NotificationAction.createSimpleExpiring("Copy the New Token",
                () -> CopyPasteManager.getInstance().setContents(new StringSelection(token)));
        try {
            settings.setToken(account.id(), token);
        } catch (RuntimeException e) {
            DuoLog.warn("rotate " + account.id() + ": GitLab rotated the token, but it could not be saved: " + e);
            Notification n = notification(true, "Rotated token not saved", "GitLab rotated the token, but "
                    + StringUtil.escapeXmlEntities(CreditsSettings.storeName()) + " did not take the new one. Copy it "
                    + "and save it with Replace Token… in Settings.", NotificationType.ERROR);
            n.setSubtitle(account.label());
            n.addAction(copy);
            n.notify(null);
            return token;
        }
        account.tokenSavedAt = System.currentTimeMillis();
        account.remember(rotated.info());
        DuoLog.info("rotate " + account.id() + ": done, the new token expires on "
                + (rotated.info().expiresAt() != null ? rotated.info().expiresAt() : "no date"));
        Notification n = notification(true, "Token rotated", StringUtil.escapeXmlEntities("Rotated before it expired: the "
                + "new token " + StringUtil.decapitalize(CreditsView.expiry(rotated.info().expiresAt(), today))
                + ". GitLab revoked the old one: if you use it anywhere else, use the new one there."),
                NotificationType.INFORMATION);
        n.setSubtitle(account.label());
        n.addAction(copy);
        n.notify(null);
        return token;
    }

    private static Snapshot rejected(Account account) {
        LocalDate expiry = account.expiry();
        boolean expired = expiry != null && !expiry.isAfter(LocalDate.now());
        return Snapshot.rejected("GitLab turns down the token of @" + account.username + " on " + account.host()
                + (expired ? ": it expired on " + CreditsView.expiry(expiry, LocalDate.now()).replace("Expired on ", "") + "."
                        : ": it has expired or was revoked.")
                + " Replace it to see this account again.");
    }

    private static String notTracked(Account account) {
        return "Usage not tracked: the token of @" + account.username + " on " + account.host()
                + " lacks the ai_features scope, so GitLab does not show its Duo usage."
                + (account.glab || account.duo ? " glab and the Duo CLI can still use it." : "");
    }

    /** The account's notices, each once: its alert of the period, a token about to expire, a token turned down. */
    private void notifyOnce(Account account, Snapshot s) {
        CreditsSettings settings = CreditsSettings.getInstance();
        List<Integer> percents = settings.percents();
        LocalDate today = LocalDate.now();
        if (s.status() == Snapshot.Status.OK) {
            Alert.of(s.report(), account.limit, percents, settings.getState().alertOnForecast, today)
                    .filter(alert -> !alert.key().equals(account.lastAlertKey))
                    .ifPresent(alert -> {
                        account.lastAlertKey = alert.key();
                        Notification n = notification(alert.urgent(percents), alert.title(), alert.message(), type(alert.kind()));
                        n.setSubtitle(account.label());
                        n.addAction(NotificationAction.create("Open Duo Watchdog", (event, notification) -> {
                            select(account.id());
                            CreditsToolWindowFactory.show(event.getProject());
                        }));
                        n.notify(null);
                    });
        }
        if (s.status() == Snapshot.Status.REJECTED) {
            String key = String.valueOf(account.tokenSavedAt);
            if (!key.equals(account.lastRejectedKey)) {
                account.lastRejectedKey = key;
                Notification n = notification(true, "The token no longer works", StringUtil.escapeXmlEntities(s.error()), NotificationType.ERROR);
                n.setSubtitle(account.label());
                n.addAction(NotificationAction.createSimpleExpiring("Replace Token…", () -> openSettings(account.id(), true)));
                n.addAction(NotificationAction.createSimple("Open Settings", () -> openSettings(account.id(), false)));
                n.notify(null);
            }
            return;
        }
        LocalDate expiry = account.expiry();
        if (Accounts.expiresWithin(expiry, today, Accounts.EXPIRY_POPUP_DAYS)) {
            String key = account.id() + "|" + expiry;
            if (!key.equals(account.lastExpiryKey)) {
                account.lastExpiryKey = key;
                long days = ChronoUnit.DAYS.between(today, expiry);
                Notification n = notification(true, days == 1 ? "Token expires tomorrow" : "Token expires in " + days + " days",
                        CreditsView.expiry(expiry, today).replaceFirst(", in \\d+ days$", "")
                                + ". After that, this account's credits are no longer tracked"
                                + (account.glab || account.duo ? ", and glab can't use it." : "."),
                        NotificationType.WARNING);
                n.setSubtitle(account.label());
                n.addAction(NotificationAction.createSimpleExpiring("Replace Token…", () -> openSettings(account.id(), true)));
                boolean forCli = account.glab || account.duo;
                n.addAction(NotificationAction.createSimple("Create a New Token in GitLab",
                        () -> BrowserUtil.browse(GitLabAddress.tokenPage(account.gitlabUrl, forCli))));
                n.notify(null);
            }
        }
    }

    static Notification notification(boolean sticky, String title, String content, NotificationType type) {
        return NotificationGroupManager.getInstance().getNotificationGroup(sticky ? STICKY_GROUP : NOTIFICATION_GROUP)
                .createNotification(title, content, type);
    }

    /** Settings | Tools | Duo Watchdog with the account chosen, and its Replace Token dialog open when asked. */
    static void openSettings(String accountId, boolean replace) {
        ApplicationManager.getApplication().invokeLater(() -> ShowSettingsUtil.getInstance()
                .showSettingsDialog(null, CreditsConfigurable.class, page -> page.open(accountId, replace)));
    }

    private static NotificationType type(Alert.Kind kind) {
        return switch (kind) {
            case BLOCKED -> NotificationType.ERROR;
            case LIMIT, THRESHOLD -> NotificationType.WARNING;
            case FORECAST -> NotificationType.INFORMATION;
        };
    }

    /**
     * Hands the accounts' tokens to glab, each account whose glab or Duo CLI box is on, and says how it went. Off
     * the EDT.
     *
     * @param tokens the account ids with the token to hand off
     * @param quiet  a refresh's try: says nothing while glab is not installed, and tries again on the next one
     */
    static void handOff(List<Account> accounts, Map<String, String> tokens, boolean quiet) {
        for (Account account : accounts) {
            String token = tokens.get(account.id());
            if (token == null || token.isBlank()) {
                continue;
            }
            boolean installed = CliHandOff.glab() != null;
            if (quiet && !installed) {
                DuoLog.info("glab hand-off " + account.id() + ": glab not found, " + CliHandOff.whereLooked());
                return;
            }
            String key = String.valueOf(account.tokenSavedAt);
            CliHandOff.Result result = CliHandOff.login(account.host(), token.trim());
            DuoLog.info("glab hand-off " + account.id() + ": " + (result.ok() ? "done" : "failed") + ", " + result.output());
            if (!result.ok()) {
                if (installed) {
                    // Not tried again until the token changes: glab said why, and saying it every refresh helps no one.
                    account.glabFailedKey = key;
                }
                Notification n = notification(true, installed ? "glab did not take the token" : "glab is not installed",
                        installed ? StringUtil.escapeXmlEntities(result.output().isEmpty() ? "glab gave no reason." : result.output())
                                : "Duo Watchdog looked " + CliHandOff.whereLooked() + ". " + CliHandOff.howToInstall()
                                + ", and it gets the token on the next refresh.",
                        NotificationType.WARNING);
                n.setSubtitle(account.label());
                n.notify(null);
                continue;
            }
            account.glabHandedKey = key;
            account.glabFailedKey = "";
            // glab keeps one token per GitLab: the others on this one no longer have it there.
            for (Account other : CreditsSettings.getInstance().getState().accounts) {
                if (other != account && other.host().equalsIgnoreCase(account.host())) {
                    other.glabHandedKey = "";
                }
            }
            String scopes = Accounts.glabScopeNote(account.scopeList());
            CliHandOff.Target target = CliHandOff.glab();
            String text = (target != null ? target.name() : "glab") + " now signs in to " + account.host() + " as @"
                    + account.username + "."
                    + (scopes == null ? "" : " " + scopes);
            if (account.duo) {
                text += CliHandOff.checkDuoHasOwnLogin()
                        ? " The Duo CLI keeps settings of its own in ~/.gitlab/storage.json; if it signed in there, it "
                        + "keeps that login: delete its settings there to make it use glab's."
                        : " The Duo CLI signs in through glab (glab 1.85.2 and duo 8.68.0 or later).";
            }
            String duoScopes = account.duo ? Accounts.duoScopeNote(account.scopeList()) : null;
            if (duoScopes != null) {
                text += " " + duoScopes;
            }
            Notification n = notification(duoScopes != null, "Token handed to glab", text,
                    scopes == null && duoScopes == null ? NotificationType.INFORMATION : NotificationType.WARNING);
            n.setSubtitle(account.label());
            n.notify(null);
        }
    }

    /** GitLab through the IDE's proxy and certificates; the settings page goes the same way. */
    HttpTransport transport() {
        return new HttpTransport(http());
    }

    /** One client for the IDE's lifetime, through the IDE's proxy settings and trusted certificates. */
    private synchronized HttpClient http() {
        if (http == null) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .sslContext(CertificateManager.getInstance().getSslContext());
            ProxySelector proxy = ProxySelector.getDefault();
            if (proxy != null) {
                builder.proxy(proxy);
            }
            Authenticator authenticator = Authenticator.getDefault();
            if (authenticator != null) {
                builder.authenticator(authenticator);
            }
            http = builder.build();
        }
        return http;
    }

    @Override
    public synchronized void dispose() {
        if (schedule != null) {
            schedule.cancel(false);
        }
    }
}
