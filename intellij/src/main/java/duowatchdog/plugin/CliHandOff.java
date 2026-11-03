package duowatchdog.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.SystemInfo;
import duowatchdog.core.Glab;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands an account's token to glab, the GitLab CLI, which keeps one token per GitLab. glab is looked for where the
 * work happens: on the PATH (and Homebrew) on macOS and Linux; on Windows, inside WSL when a project lives there,
 * else on the Windows PATH, else in WSL's default distribution. The GitLab Duo CLI signs in through glab when glab is
 * signed in (glab 1.85.2 and duo 8.68.0 or later), unless it has a login of its own in {@code ~/.gitlab/storage.json},
 * which Duo Watchdog leaves alone.
 */
final class CliHandOff {

    /** Where Homebrew and Linuxbrew put glab, for an IDE started from the Dock with a short PATH. */
    private static final List<String> USUAL_PLACES =
            List.of("/opt/homebrew/bin/glab", "/usr/local/bin/glab", "/home/linuxbrew/.linuxbrew/bin/glab");
    /** How long the answer to "where is glab" holds: long enough not to ask WSL on every repaint. */
    private static final Duration KEEP = Duration.ofMinutes(5);

    /** @param output what glab said, trimmed, with the token never in it */
    record Result(boolean ok, String output) {
    }

    /**
     * Where glab is.
     *
     * @param exe    glab's own executable, or null for the one in WSL
     * @param distro the WSL distribution, or null for WSL's default one
     */
    record Target(@Nullable File exe, @Nullable String distro) {

        boolean wsl() {
            return exe == null;
        }

        /** "glab", or "glab in WSL (Ubuntu)". */
        String name() {
            return wsl() ? "glab in WSL" + (distro != null ? " (" + distro + ")" : "") : "glab";
        }
    }

    private static volatile Optional<Target> found;
    private static volatile Instant foundAt = Instant.EPOCH;
    /** Whether the Duo CLI had a login of its own when glab was last looked for. */
    private static volatile boolean duoOwnLogin;
    /** A background look is running, so another is not started. */
    private static final AtomicBoolean LOOKING = new AtomicBoolean();

    private CliHandOff() {
    }

    /** Where glab is, looking again when the last answer is old. May run wsl.exe: call off the EDT. */
    static @Nullable Target glab() {
        Optional<Target> last = found;
        if (last != null && foundAt.isAfter(Instant.now().minus(KEEP))) {
            return last.orElse(null);
        }
        Optional<Target> now = Optional.ofNullable(look());
        found = now;
        foundAt = Instant.now();
        duoOwnLogin = readDuoLogin(now.orElse(null));
        return now.orElse(null);
    }

    /**
     * What is known without waiting: true or false once looked for, null while not yet. Starts a look in the
     * background when the answer is missing or old, and runs {@code then} after it, off the EDT.
     */
    static @Nullable Boolean installed(Runnable then) {
        Optional<Target> last = found;
        if ((last == null || !foundAt.isAfter(Instant.now().minus(KEEP))) && LOOKING.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                try {
                    glab();
                } finally {
                    LOOKING.set(false);
                }
                then.run();
            });
        }
        return last == null ? null : last.isPresent();
    }

    /** "on the PATH and in Homebrew", "on the Windows PATH and in WSL": where {@link #glab()} looks. */
    static String whereLooked() {
        if (SystemInfo.isWindows) {
            String distro = projectDistro();
            return "on the Windows PATH and in WSL" + (distro != null ? " (" + distro + ")" : "");
        }
        return SystemInfo.isMac ? "on the PATH and in Homebrew" : "on the PATH";
    }

    /** How to install glab where it is looked for. */
    static String howToInstall() {
        if (SystemInfo.isWindows) {
            return projectDistro() != null ? "Install it in WSL, where the project is" : "Install it, in WSL or on Windows";
        }
        return SystemInfo.isMac ? "Install it, e.g. brew install glab" : "Install it with your package manager";
    }

    private static @Nullable Target look() {
        if (!SystemInfo.isWindows) {
            File onPath = PathEnvironmentVariableUtil.findInPath("glab");
            if (onPath != null) {
                return new Target(onPath, null);
            }
            return USUAL_PLACES.stream().map(File::new).filter(File::canExecute).findFirst()
                    .map(f -> new Target(f, null)).orElse(null);
        }
        // On Windows the project decides: in WSL, its glab is the one that matters.
        String distro = projectDistro();
        if (distro != null && inWsl(distro)) {
            return new Target(null, distro);
        }
        File exe = PathEnvironmentVariableUtil.findInPath("glab.exe");
        if (exe != null) {
            return new Target(exe, null);
        }
        return distro == null && wslExe() != null && inWsl(null) ? new Target(null, null) : null;
    }

    /** The WSL distribution of the first open project that lives in WSL, or null. */
    private static @Nullable String projectDistro() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String distro = Glab.wslDistro(project.getBasePath());
            if (distro != null) {
                return distro;
            }
        }
        return null;
    }

    private static @Nullable File wslExe() {
        File onPath = PathEnvironmentVariableUtil.findInPath("wsl.exe");
        if (onPath != null) {
            return onPath;
        }
        File system = new File(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32\\wsl.exe");
        return system.isFile() ? system : null;
    }

    private static boolean inWsl(@Nullable String distro) {
        Result result = run(Glab.findInWsl(distro), null, 15);
        return result.ok() && !result.output().isBlank();
    }

    /** {@code glab auth login --hostname <host> --stdin}, the token written to its input, never on the command line. */
    static Result login(String host, String token) {
        Target target = glab();
        if (target == null) {
            return new Result(false, "glab is not installed, or not where the IDE looks: " + whereLooked() + ".");
        }
        List<String> command = target.wsl() ? Glab.loginInWsl(target.distro(), host) : Glab.login(target.exe().getPath(), host);
        Result result = run(command, token, 60);
        return new Result(result.ok(), result.output().replace(token, "•••"));
    }

    /** Whether the Duo CLI had a login of its own when glab was last looked for; no waiting, fine on the EDT. */
    static boolean duoHasOwnLogin() {
        return duoOwnLogin;
    }

    /** The same, read now. May run wsl.exe: call off the EDT. */
    static boolean checkDuoHasOwnLogin() {
        duoOwnLogin = readDuoLogin(glab());
        return duoOwnLogin;
    }

    /**
     * Whether the Duo CLI has a login of its own, which it uses instead of glab's: {@code ~/.gitlab/storage.json},
     * in WSL when glab is there, holds settings. Only its shape is looked at, never shown.
     */
    private static boolean readDuoLogin(@Nullable Target target) {
        try {
            String json;
            if (target != null && target.wsl()) {
                Result result = run(Glab.duoStorageInWsl(target.distro()), null, 15);
                json = result.ok() ? result.output() : "";
            } else {
                Path storage = Path.of(System.getProperty("user.home"), ".gitlab", "storage.json");
                json = Files.isRegularFile(storage) ? Files.readString(storage) : "";
            }
            if (json.isBlank()) {
                return false;
            }
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() && !parsed.getAsJsonObject().isEmpty();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Runs a command, writing {@code input} to it when given, and keeps the end of what it printed. */
    private static Result run(List<String> command, @Nullable String input, int seconds) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (OutputStream in = process.getOutputStream()) {
                if (input != null) {
                    in.write((input + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, command.get(0) + " did not finish within " + seconds + " seconds.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\u0000", "").trim();
            if (output.length() > 400) {
                output = output.substring(output.length() - 400);
            }
            return new Result(process.exitValue() == 0, output);
        } catch (IOException e) {
            return new Result(false, "Could not run " + command.get(0) + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "Interrupted while running " + command.get(0) + ".");
        }
    }
}
