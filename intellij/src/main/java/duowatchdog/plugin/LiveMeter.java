package duowatchdog.plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.messages.Topic;
import duowatchdog.core.LiveLog;
import duowatchdog.core.LiveSession;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The Duo CLI session in progress, for the status bar and the Sessions tab. Every two seconds the CLI's newest logs,
 * here and in the open projects' WSL distributions, are looked at, and the lines a log gained since are read
 * ({@link LiveLog}). It is a poll rather than a file watcher: a folder in WSL, reached over \\wsl$, does not report
 * its changes. The CLI appends each line to its log as it logs it, so a step shows within the two seconds.
 */
@Service(Service.Level.APP)
public final class LiveMeter implements Disposable {

    /** Published on the application bus, on the EDT, when the session's numbers change. */
    public interface Listener {
        Topic<Listener> TOPIC = new Topic<>("Duo CLI session in progress", Listener.class);

        void changed();
    }

    private static final long POLL_SECONDS = 2;
    /** A log not written to for this long is of a run that ended, or that has waited for its user long enough. */
    private static final Duration ACTIVE = Duration.ofMinutes(30);
    /** Of each folder, the logs the CLI made last: its runs that may still be going. */
    private static final int NEWEST = 5;

    /** The logs being followed, on the poll's thread only. */
    private final Map<Path, LiveLog> tails = new HashMap<>();
    /** The runs still writing, most recently written first. */
    private volatile List<LiveSession.Run> runs = List.of();
    private ScheduledFuture<?> poll;

    public static LiveMeter getInstance() {
        return ApplicationManager.getApplication().getService(LiveMeter.class);
    }

    /** Starts looking at the logs, once. */
    public synchronized void start() {
        if (poll == null) {
            poll = AppExecutorUtil.getAppScheduledExecutorService()
                    .scheduleWithFixedDelay(this::poll, 0, POLL_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * The run to show for a project, with something counted: the CLI's in its folder, else the one written to last;
     * null for none.
     */
    public @Nullable LiveSession.Run run(@Nullable Project project) {
        return LiveSession.pick(runs, project != null ? project.getBasePath() : null);
    }

    /** The session to show for a project, as {@link #run} picks it; null for none. */
    public @Nullable LiveSession.Meter meter(@Nullable Project project) {
        LiveSession.Run run = run(project);
        return run != null ? run.meter() : null;
    }

    /** For Copy Diagnostics: the runs followed now, in counts. */
    String describe() {
        List<LiveSession.Run> now = runs;
        if (now.isEmpty()) {
            return "none";
        }
        List<String> lines = new ArrayList<>();
        for (LiveSession.Run r : now) {
            LiveSession.Meter m = r.meter();
            lines.add(r.log() + ": " + (m == null ? "nothing counted yet"
                    : (m.session() != null ? "session " + m.session() : "a new session") + ", "
                    + LiveSession.steps(m.steps()) + ", " + LiveSession.roundTrips(m.roundTrips()) + ", model "
                    + (m.model() != null ? m.model() : "not logged")));
        }
        return String.join("; ", lines);
    }

    private void poll() {
        try {
            long now = System.currentTimeMillis();
            List<LiveSession.Run> found = new ArrayList<>();
            Set<Path> followed = new HashSet<>();
            Set<String> names = new HashSet<>();
            for (Path folder : CliLogs.folders()) {
                for (Path log : newest(folder)) {
                    // The same log reached another way, as a WSL folder can be: followed once.
                    if (!names.add(log.getFileName().toString())) {
                        continue;
                    }
                    long modified = Files.getLastModifiedTime(log).toMillis();
                    if (now - modified > ACTIVE.toMillis()) {
                        continue;
                    }
                    LiveLog tail = tails.computeIfAbsent(log, p -> new LiveLog(ZoneId.systemDefault()));
                    tail.read(log);
                    followed.add(log);
                    found.add(new LiveSession.Run(log.getFileName().toString(), modified, tail.meter()));
                }
            }
            tails.keySet().retainAll(followed);
            found.sort(Comparator.comparingLong(LiveSession.Run::modified).reversed());
            List<LiveSession.Run> before = runs;
            runs = List.copyOf(found);
            if (!sameMeters(before, runs)) {
                ApplicationManager.getApplication().invokeLater(
                        () -> ApplicationManager.getApplication().getMessageBus().syncPublisher(Listener.TOPIC).changed());
            }
        } catch (IOException | RuntimeException e) {
            DuoLog.warn("Duo CLI session in progress: " + e.getMessage());
        }
    }

    /** The logs the CLI made last in a folder: their names start with the time it started. */
    private static List<Path> newest(Path folder) {
        List<Path> logs = new ArrayList<>();
        try (DirectoryStream<Path> all = Files.newDirectoryStream(folder, "duo-cli-log-*.log")) {
            all.forEach(logs::add);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
        logs.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return logs.subList(0, Math.min(NEWEST, logs.size()));
    }

    private static boolean sameMeters(List<LiveSession.Run> a, List<LiveSession.Run> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).log().equals(b.get(i).log()) || !Objects.equals(a.get(i).meter(), b.get(i).meter())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public synchronized void dispose() {
        if (poll != null) {
            poll.cancel(false);
        }
    }
}
