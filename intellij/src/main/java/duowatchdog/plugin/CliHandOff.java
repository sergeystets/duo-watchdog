package duowatchdog.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.execution.configurations.PathEnvironmentVariableUtil;
import com.intellij.openapi.util.SystemInfo;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Hands an account's token to glab, the GitLab CLI, which keeps one token per GitLab. The GitLab Duo CLI signs in
 * through glab when glab is signed in (glab 1.85.2 and duo 8.68.0 or later), unless it has a login of its own in
 * {@code ~/.gitlab/storage.json}, which Duo Watchdog leaves alone. All of it blocks: call off the EDT.
 */
final class CliHandOff {

    /** Where Homebrew and Linuxbrew put glab, for an IDE started from the Dock with a short PATH. */
    private static final List<String> USUAL_PLACES =
            List.of("/opt/homebrew/bin/glab", "/usr/local/bin/glab", "/home/linuxbrew/.linuxbrew/bin/glab");

    /** @param output what glab said, trimmed, with the token never in it */
    record Result(boolean ok, String output) {
    }

    private CliHandOff() {
    }

    /** The glab executable, or null when it is not installed where the IDE can find it. */
    static @Nullable File glab() {
        File found = PathEnvironmentVariableUtil.findInPath(SystemInfo.isWindows ? "glab.exe" : "glab");
        if (found != null) {
            return found;
        }
        return USUAL_PLACES.stream().map(File::new).filter(File::canExecute).findFirst().orElse(null);
    }

    /** {@code glab auth login --hostname <host> --stdin}, the token written to its input, never on the command line. */
    static Result login(String host, String token) {
        File glab = glab();
        if (glab == null) {
            return new Result(false, "glab is not installed, or not where the IDE looks: on the PATH and in Homebrew.");
        }
        try {
            Process process = new ProcessBuilder(glab.getPath(), "auth", "login", "--hostname", host, "--stdin")
                    .redirectErrorStream(true)
                    .start();
            try (OutputStream in = process.getOutputStream()) {
                in.write((token + "\n").getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(false, "glab did not finish within 30 seconds.");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                    .replace(token, "•••").trim();
            if (output.length() > 400) {
                output = output.substring(output.length() - 400);
            }
            return new Result(process.exitValue() == 0, output);
        } catch (IOException e) {
            return new Result(false, "Could not run glab: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(false, "Interrupted while glab was signing in.");
        }
    }

    /**
     * Whether the Duo CLI has a login of its own, which it uses instead of glab's: {@code ~/.gitlab/storage.json}
     * holds settings. Only its shape is looked at, never shown.
     */
    static boolean duoHasOwnLogin() {
        Path storage = Path.of(System.getProperty("user.home"), ".gitlab", "storage.json");
        try {
            if (!Files.isRegularFile(storage)) {
                return false;
            }
            JsonElement json = JsonParser.parseString(Files.readString(storage));
            return json.isJsonObject() && !json.getAsJsonObject().isEmpty();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
