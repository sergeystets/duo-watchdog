package duowatchdog.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginInstaller;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.ide.startup.StartupActionScriptManager;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.updateSettings.impl.UpdateSettings;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.io.HttpRequests;
import duowatchdog.core.Updates;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Duo Watchdog's own updates, from its releases on GitHub. Once an IDE run and then once a day it asks for the latest
 * release; a newer one is said in a notice and on the tool window's toolbar. Update downloads the release's plugin zip,
 * checks it is this plugin of that version (and its SHA-256, when GitHub gives one), and has the IDE put it in place
 * when it next starts, as the IDE's own plugin updates do; Restart Now restarts the IDE to finish.
 */
@Service(Service.Level.APP)
public final class Updater implements Disposable {

    static final String PLUGIN_ID = "local.duo-watchdog";
    private static final long FIRST_SECONDS = 20;
    /** How often it looks whether a day has passed since it last asked. */
    private static final long LOOK_SECONDS = 3600;

    /** The versions said in a notice this IDE run, so the hourly look does not say them again. */
    private final Set<String> told = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean askedThisRun = new AtomicBoolean();
    /** A newer release with a plugin zip, as last asked; null when there is none. */
    private volatile Updates.Release available;
    /** The version downloaded and waiting for the IDE to restart; null before an Update. */
    private volatile String staged;
    private ScheduledFuture<?> schedule;

    /**
     * The latest release's list of plugins for the IDE: one entry, this plugin, with its version and its zip. With
     * it among the IDE's plugin repositories, the IDE offers a newer release itself, with Update on the plugin's
     * page in Settings | Plugins.
     */
    static final String IDE_REPOSITORY = Updates.RELEASES + "/latest/download/updatePlugins.xml";

    /** Puts the releases among the IDE's plugin repositories, or takes them out when updates are turned off. */
    static void listWithIde(boolean on) {
        try {
            List<String> hosts = UpdateSettings.getInstance().getStoredPluginHosts();
            if (!on) {
                hosts.remove(IDE_REPOSITORY);
            } else if (!hosts.contains(IDE_REPOSITORY)) {
                hosts.add(IDE_REPOSITORY);
            }
        } catch (RuntimeException | LinkageError e) {
            // An IDE that keeps its repositories otherwise: Duo Watchdog's own notice and Update still work.
        }
    }

    static Updater getInstance() {
        return ApplicationManager.getApplication().getService(Updater.class);
    }

    static @Nullable IdeaPluginDescriptor plugin() {
        return PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
    }

    /** "0.6.0"; null when the IDE does not say. */
    static @Nullable String currentVersion() {
        IdeaPluginDescriptor plugin = plugin();
        return plugin == null ? null : plugin.getVersion();
    }

    @Nullable Updates.Release available() {
        return available;
    }

    @Nullable String staged() {
        return staged;
    }

    /** Asks now, once the IDE has settled, and then looks every hour whether a day has passed. */
    synchronized void start() {
        if (schedule == null) {
            listWithIde(CreditsSettings.getInstance().getState().checkForUpdates);
            schedule = AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay(this::checkIfDue, FIRST_SECONDS, LOOK_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public synchronized void dispose() {
        if (schedule != null) {
            schedule.cancel(false);
            schedule = null;
        }
    }

    /** Once an IDE run, so a release found in the last one is offered again, then once a day; never when turned off. */
    private void checkIfDue() {
        CreditsSettings.Settings s = CreditsSettings.getInstance().getState();
        if (!s.checkForUpdates || staged != null) {
            return;
        }
        Instant last = s.lastUpdateCheck <= 0 ? null : Instant.ofEpochMilli(s.lastUpdateCheck);
        if (askedThisRun.compareAndSet(false, true) || Updates.due(last, Instant.now())) {
            check(false);
        }
    }

    /**
     * Asks GitHub for the latest release, off the UI thread. Manual (Check for Updates) says what it found even when
     * nothing is new or something went wrong, and offers a version that Skip This Version passed over.
     */
    void check(boolean manual) {
        if (staged != null) {
            if (manual) {
                askRestart(null, staged);
            }
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        try {
            String current = currentVersion();
            if (current == null) {
                return;
            }
            Updates.Release latest = latest();
            CreditsSettings.Settings s = CreditsSettings.getInstance().getState();
            s.lastUpdateCheck = System.currentTimeMillis();
            if (latest == null || !Updates.newer(latest.version(), current)) {
                available = null;
                DuoLog.info("updates: " + current + " is the latest" + (latest == null ? " (no release on GitHub)" : ""));
                if (manual) {
                    CreditsService.notification(false, "Duo Watchdog is up to date",
                            "You have " + current + ", the latest release.", NotificationType.INFORMATION).notify(null);
                }
                return;
            }
            boolean hasZip = latest.asset(".zip") != null;
            available = hasZip ? latest : null;
            DuoLog.info("updates: " + latest.version() + " is out, this is " + current + (hasZip ? "" : "; it has no plugin zip"));
            if (manual || (!latest.version().equals(s.skippedUpdate) && told.add(latest.version()))) {
                offer(latest, current, hasZip);
            }
        } catch (IOException | RuntimeException e) {
            DuoLog.warn("updates: could not ask GitHub: " + e.getMessage());
            if (manual) {
                Notification n = CreditsService.notification(false, "Could not check for Duo Watchdog updates",
                        "GitHub did not answer: " + e.getMessage(), NotificationType.WARNING);
                n.addAction(NotificationAction.createSimpleExpiring("Open Releases", () -> BrowserUtil.browse(Updates.RELEASES)));
                n.notify(null);
            }
        } finally {
            busy.set(false);
        }
    }

    /** The latest release; null when the repository has none yet. */
    private static @Nullable Updates.Release latest() throws IOException {
        try {
            return Updates.parse(HttpRequests.request(Updates.LATEST)
                    .accept("application/vnd.github+json")
                    .productNameAsUserAgent()
                    .connectTimeout(15_000)
                    .readTimeout(30_000)
                    .readString());
        } catch (HttpRequests.HttpStatusException e) {
            if (e.getStatusCode() == 404) {
                return null;
            }
            throw new IOException(e.getStatusCode() == 403 || e.getStatusCode() == 429
                    ? "it limits how often it is asked; try again in an hour" : "HTTP " + e.getStatusCode(), e);
        } catch (IllegalArgumentException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void offer(Updates.Release release, String current, boolean hasZip) {
        String version = release.version();
        Notification n = CreditsService.notification(false, "Duo Watchdog " + version + " is available",
                "You have " + current + "." + (hasZip ? " Update downloads it; it is installed when the IDE restarts."
                        : " Its release has no IntelliJ plugin to download."), NotificationType.INFORMATION);
        if (hasZip) {
            n.addAction(NotificationAction.createSimpleExpiring("Update", () -> install(null, release)));
        }
        n.addAction(NotificationAction.createSimple("Release Notes", () -> BrowserUtil.browse(release.page())));
        n.addAction(NotificationAction.createSimpleExpiring("Skip This Version", () -> {
            CreditsSettings.getInstance().getState().skippedUpdate = version;
            DuoLog.info("updates: skipping " + version);
        }));
        n.notify(null);
    }

    /** Downloads the release's plugin zip, checks it, and has the IDE install it when it restarts. */
    void install(@Nullable Project project, Updates.Release release) {
        Updates.Asset zip = release.asset(".zip");
        if (staged != null) {
            askRestart(project, staged);
            return;
        }
        if (zip == null) {
            BrowserUtil.browse(release.page());
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        String version = release.version();
        new Task.Backgroundable(project, "Downloading Duo Watchdog " + version, true) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                // Where the IDE downloads plugin updates to itself.
                Path file = Path.of(PathManager.getSystemPath(), "plugins", Updates.PLUGIN_FOLDER + "-" + version + ".zip");
                boolean done = false;
                try {
                    Files.createDirectories(file.getParent());
                    HttpRequests.request(zip.url())
                            .productNameAsUserAgent()
                            .connectTimeout(15_000)
                            .readTimeout(60_000)
                            .saveToFile(file, indicator);
                    indicator.setText("Checking Duo Watchdog " + version);
                    Updates.verify(file, zip);
                    Updates.checkPluginZip(file, version);
                    IdeaPluginDescriptor plugin = plugin();
                    if (plugin == null) {
                        throw new IOException("the IDE does not list Duo Watchdog");
                    }
                    stage(plugin, file);
                    done = true;
                    staged = version;
                    DuoLog.info("updates: " + version + " downloaded; installed when the IDE restarts");
                    askRestart(project, version);
                } catch (ProcessCanceledException e) {
                    throw e;
                } catch (IOException | RuntimeException | LinkageError e) {
                    DuoLog.warn("updates: could not install " + version + ": " + e.getMessage());
                    Notification n = CreditsService.notification(false, "Could not update Duo Watchdog",
                            "Duo Watchdog " + version + " was not installed: " + e.getMessage(), NotificationType.ERROR);
                    n.addAction(NotificationAction.createSimpleExpiring("Open the Release",
                            () -> BrowserUtil.browse(release.page())));
                    n.notify(project);
                } finally {
                    if (!done) {
                        try {
                            Files.deleteIfExists(file);
                        } catch (IOException ignored) {
                            // The IDE clears its temp folder too.
                        }
                    }
                    busy.set(false);
                }
            }
        }.queue();
    }

    /**
     * Has the IDE replace this plugin's folder with the zip's when it next starts, then delete the zip, as its own
     * plugin updates do. PluginInstaller's method has changed its parameters between IDE versions; where it is not the
     * one compiled against, the same commands go to the IDE's startup script directly.
     */
    private static void stage(IdeaPluginDescriptor plugin, Path zip) throws IOException {
        try {
            PluginInstaller.installAfterRestart(plugin, zip, plugin.getPluginPath(), true);
        } catch (NoSuchMethodError e) {
            DuoLog.info("updates: this IDE has another PluginInstaller; using its startup script");
            StartupActionScriptManager.addActionCommands(List.of(
                    new StartupActionScriptManager.DeleteCommand(plugin.getPluginPath()),
                    new StartupActionScriptManager.UnzipCommand(zip, PathManager.getPluginsDir()),
                    new StartupActionScriptManager.DeleteCommand(zip)));
        }
    }

    private static void askRestart(@Nullable Project project, String version) {
        Notification n = CreditsService.notification(true, "Duo Watchdog " + version + " is ready",
                "It is installed when the IDE restarts.", NotificationType.INFORMATION);
        n.addAction(NotificationAction.createSimpleExpiring("Restart Now", () -> ApplicationManager.getApplication().restart()));
        n.addAction(NotificationAction.createSimpleExpiring("Later", () -> { }));
        n.notify(project);
    }

    /** Tools | Duo Watchdog: Check for Updates, and Help | Check for Duo Watchdog Updates. */
    static final class CheckAction extends DumbAwareAction {
        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            new Task.Backgroundable(e.getProject(), "Checking for Duo Watchdog updates", false) {
                @Override
                public void run(@NotNull ProgressIndicator indicator) {
                    getInstance().check(true);
                }
            }.queue();
        }
    }

    /** The tool window's "Update to 0.7.0", then "Restart to Update"; hidden while nothing newer is known. */
    static final class ToolbarAction extends DumbAwareAction {

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.BGT;
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            Updater updater = getInstance();
            String staged = updater.staged();
            Updates.Release release = updater.available();
            Presentation p = e.getPresentation();
            p.setEnabledAndVisible(staged != null || release != null);
            p.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true);
            if (staged != null) {
                p.setText("Restart to Update");
                p.setDescription("Restart the IDE to finish installing Duo Watchdog " + staged);
                p.setIcon(AllIcons.Actions.Restart);
            } else if (release != null) {
                p.setText("Update to " + release.version());
                p.setDescription("Download Duo Watchdog " + release.version() + "; it is installed when the IDE restarts");
                p.setIcon(AllIcons.Actions.Download);
            }
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Updater updater = getInstance();
            if (updater.staged() != null) {
                ApplicationManager.getApplication().restart();
            } else if (updater.available() != null) {
                updater.install(e.getProject(), updater.available());
            }
        }
    }

    /** Starts the daily check when the first project opens. */
    static final class Activity implements ProjectActivity {
        @Override
        public @Nullable Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
            getInstance().start();
            return Unit.INSTANCE;
        }
    }
}
