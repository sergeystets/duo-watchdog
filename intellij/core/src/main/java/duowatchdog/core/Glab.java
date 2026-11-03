package duowatchdog.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The commands that reach glab, the GitLab CLI: its own executable, or the one inside WSL when the IDE runs on
 * Windows and the project lives in WSL. The token always goes on glab's standard input, never on a command line.
 */
public final class Glab {

    /** {@code \\wsl$\Ubuntu\…}, {@code \\wsl.localhost\Ubuntu\…}, and the same with forward slashes. */
    private static final Pattern WSL_PATH = Pattern.compile("^[\\\\/]{2}wsl(?:\\$|\\.localhost)[\\\\/]([^\\\\/]+)",
            Pattern.CASE_INSENSITIVE);

    private Glab() {
    }

    /** The WSL distribution a Windows path lies in, "Ubuntu" for {@code \\wsl$\Ubuntu\home\…}; else null. */
    public static String wslDistro(String path) {
        if (path == null) {
            return null;
        }
        Matcher m = WSL_PATH.matcher(path.trim());
        return m.find() ? m.group(1) : null;
    }

    /** {@code glab auth login --hostname <host> --stdin} with glab's own executable. */
    public static List<String> login(String glab, String host) {
        return List.of(glab, "auth", "login", "--hostname", host, "--stdin");
    }

    /**
     * The same inside WSL: {@code wsl.exe [-d <distro>] -- bash -lc 'exec glab auth login …'}. A login shell, so
     * glab is found where the user's shell finds it (~/.local/bin, Homebrew); the host goes in as an argument,
     * never into the script.
     *
     * @param distro the distribution, or null for WSL's default one
     */
    public static List<String> loginInWsl(String distro, String host) {
        return inWsl(distro, "exec glab auth login --hostname \"$0\" --stdin", host);
    }

    /** Whether glab is installed in WSL: {@code command -v glab} in a login shell, which prints its path. */
    public static List<String> findInWsl(String distro) {
        return inWsl(distro, "command -v glab");
    }

    /** The Duo CLI's own login file in WSL, printed when there is one. */
    public static List<String> duoStorageInWsl(String distro) {
        return inWsl(distro, "cat ~/.gitlab/storage.json 2>/dev/null || true");
    }

    private static List<String> inWsl(String distro, String script, String... args) {
        List<String> command = new ArrayList<>(List.of("wsl.exe"));
        if (distro != null && !distro.isBlank()) {
            command.add("-d");
            command.add(distro);
        }
        command.addAll(List.of("--", "bash", "-lc", script));
        command.addAll(List.of(args));
        return command;
    }
}
