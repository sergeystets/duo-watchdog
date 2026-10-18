package duowatchdog.plugin;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.net.ssl.CertificateManager;
import duowatchdog.core.Alert;
import duowatchdog.core.CreditsClient;
import duowatchdog.core.HttpTransport;
import duowatchdog.core.Snapshot;

import java.net.Authenticator;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Refreshes the user's credits on a schedule and on demand; one per IDE, since the numbers are the user's, not a project's. */
@Service(Service.Level.APP)
public final class CreditsService implements Disposable {

    /** Must match the {@code notificationGroup id} in plugin.xml. */
    static final String NOTIFICATION_GROUP = "Duo Watchdog";
    private static final Logger LOG = Logger.getInstance(CreditsService.class);

    private final AtomicBoolean loading = new AtomicBoolean();
    private volatile Snapshot snapshot = Snapshot.loading();
    private ScheduledFuture<?> schedule;
    private HttpClient http;

    public static CreditsService getInstance() {
        return ApplicationManager.getApplication().getService(CreditsService.class);
    }

    public Snapshot snapshot() {
        return snapshot;
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
                publish(load());
            } finally {
                loading.set(false);
            }
        });
    }

    private Snapshot load() {
        CreditsSettings.Settings settings = CreditsSettings.getInstance().getState();
        String token = CreditsSettings.getInstance().token();
        if (settings.namespace == null || settings.namespace.isBlank() || token == null || token.isBlank()) {
            return Snapshot.notConfigured();
        }
        try {
            CreditsClient client = new CreditsClient(settings.gitlabUrl, token.trim(), new HttpTransport(http()));
            return Snapshot.ok(client.fetch(settings.namespace.trim()), Instant.now());
        } catch (Exception e) {
            // The message is the user's to read; it never carries the token.
            LOG.info("Duo Watchdog refresh failed: " + e.getMessage());
            return snapshot.failed(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private void publish(Snapshot next) {
        snapshot = next;
        if (next.status() == Snapshot.Status.OK) {
            notifyOnce(next);
        }
        ApplicationManager.getApplication().invokeLater(() ->
                ApplicationManager.getApplication().getMessageBus().syncPublisher(CreditsListener.TOPIC).updated(next));
    }

    private static void notifyOnce(Snapshot s) {
        CreditsSettings.Settings settings = CreditsSettings.getInstance().getState();
        List<Integer> percents;
        try {
            percents = Alert.parsePercents(settings.alertPercents);
        } catch (IllegalArgumentException e) {
            percents = Alert.parsePercents(Alert.DEFAULT_PERCENTS);
        }
        Alert.of(s.report(), settings.limit, percents, settings.alertOnForecast, LocalDate.now())
                .filter(alert -> !alert.key().equals(settings.lastAlertKey))
                .ifPresent(alert -> {
                    settings.lastAlertKey = alert.key();
                    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                            .createNotification(alert.title(), alert.message(), type(alert.kind()))
                            .notify(null);
                });
    }

    private static NotificationType type(Alert.Kind kind) {
        return switch (kind) {
            case BLOCKED -> NotificationType.ERROR;
            case LIMIT, THRESHOLD -> NotificationType.WARNING;
            case FORECAST -> NotificationType.INFORMATION;
        };
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
