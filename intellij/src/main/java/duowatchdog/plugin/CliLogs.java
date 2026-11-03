package duowatchdog.plugin;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.SystemInfo;
import duowatchdog.core.DuoCliLogs;
import duowatchdog.core.Glab;
import duowatchdog.core.Sessions;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The GitLab Duo CLI's logs on this machine, and in the WSL distributions of the open projects, read for the agent's
 * steps in each session ({@link DuoCliLogs}). The CLI keeps its logs in the temporary folder, which is emptied now and
 * then, so what was counted is kept in the IDE's system folder; a log is read again only when it has changed.
 */
final class CliLogs {

    /** Counts of logs older than this are dropped: GitLab lists sessions for about a month. */
    private static final Duration KEEP = Duration.ofDays(120);
    private static final Object LOCK = new Object();

    private CliLogs() {
    }

    private static Path kept() {
        return Path.of(PathManager.getSystemPath(), "duo-watchdog", "cli-logs.json");
    }

    /** Where the CLI writes its logs, of the places it may: the temporary folders here and in WSL. */
    static List<Path> folders() {
        Set<Path> folders = new LinkedHashSet<>();
        folders.add(Path.of(System.getProperty("java.io.tmpdir"), DuoCliLogs.FOLDER));
        if (SystemInfo.isWindows) {
            for (Project p : ProjectManager.getInstance().getOpenProjects()) {
                String distro = Glab.wslDistro(p.getBasePath());
                if (distro != null) {
                    folders.add(Path.of("\\\\wsl.localhost\\" + distro + "\\tmp\\" + DuoCliLogs.FOLDER));
                    folders.add(Path.of("\\\\wsl$\\" + distro + "\\tmp\\" + DuoCliLogs.FOLDER));
                }
            }
        } else {
            folders.add(Path.of("/tmp", DuoCliLogs.FOLDER));
        }
        return folders.stream().filter(Files::isDirectory).toList();
    }

    /**
     * Each session's steps in the logs counted so far, reading the logs that are new or have changed. May block: call
     * off the EDT.
     */
    static Map<Long, Sessions.Steps> steps() {
        synchronized (LOCK) {
            Map<String, DuoCliLogs.Read> known = load();
            boolean changed = false;
            for (Path folder : folders()) {
                try (DirectoryStream<Path> logs = Files.newDirectoryStream(folder, "duo-cli-log-*.log")) {
                    for (Path log : logs) {
                        changed |= read(log, known);
                    }
                } catch (IOException | RuntimeException e) {
                    DuoLog.warn("Duo CLI logs: could not list " + folder + ": " + e.getMessage());
                }
            }
            long oldest = Instant.now().minus(KEEP).toEpochMilli();
            changed |= known.values().removeIf(r -> r.modified() < oldest);
            if (changed) {
                save(known);
            }
            Map<Long, Sessions.Steps> all = new HashMap<>();
            for (DuoCliLogs.Read r : known.values()) {
                all = DuoCliLogs.add(all, r.steps());
            }
            return all;
        }
    }

    /** Counts one log's steps when it is new or has changed; whether it did. */
    private static boolean read(Path log, Map<String, DuoCliLogs.Read> known) {
        String name = log.getFileName().toString();
        try {
            long size = Files.size(log);
            long modified = Files.getLastModifiedTime(log).toMillis();
            DuoCliLogs.Read before = known.get(name);
            if (before != null && before.size() == size && before.modified() == modified) {
                return false;
            }
            // A reader, not Files.newBufferedReader: a log with a stray byte is still read, the byte replaced.
            try (BufferedReader in = new BufferedReader(new InputStreamReader(Files.newInputStream(log), StandardCharsets.UTF_8))) {
                Map<Long, Sessions.Steps> steps = DuoCliLogs.read(in, name, ZoneId.systemDefault());
                known.put(name, new DuoCliLogs.Read(size, modified, steps));
                DuoLog.info("Duo CLI logs: " + name + ": " + steps.size() + " sessions, "
                        + steps.values().stream().mapToInt(Sessions.Steps::total).sum() + " steps");
                return true;
            }
        } catch (IOException e) {
            DuoLog.warn("Duo CLI logs: could not read " + name + ": " + e.getMessage());
            return false;
        }
    }

    private static Map<String, DuoCliLogs.Read> load() {
        try {
            Path file = kept();
            return Files.isRegularFile(file) ? DuoCliLogs.parse(Files.readString(file, StandardCharsets.UTF_8)) : new HashMap<>();
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private static void save(Map<String, DuoCliLogs.Read> known) {
        try {
            Files.createDirectories(kept().getParent());
            Files.writeString(kept(), DuoCliLogs.write(known), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DuoLog.warn("Duo CLI logs: could not keep the counts: " + e.getMessage());
        }
    }

    /** For Copy Diagnostics: where the logs were looked for and found, and how much was counted. */
    static String describe() {
        List<Path> found = folders();
        Map<Long, Sessions.Steps> steps = steps();
        return (found.isEmpty() ? "none found" : found.toString()) + ", " + steps.size() + " sessions counted, "
                + steps.values().stream().mapToInt(Sessions.Steps::total).sum() + " steps";
    }
}
