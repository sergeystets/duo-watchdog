package duowatchdog.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The commands that reach glab, the GitLab CLI: its own executable, or the one inside WSL when the IDE runs on
 * Windows and the project lives in WSL. The token always goes on glab's standard input, never on a command line.
 *
 * <p>The WSL scripts hold no double quotes: a Windows command line mangles an argument with a quote in it. And they
 * go through {@code wsl.exe -e}, which starts bash directly, not {@code wsl.exe --}, which hands the line to the
 * user's shell first, where {@code $0}, {@code $1} and {@code $HOME} would be expanded before bash sees them.
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
     * The same inside WSL: {@code wsl.exe [-d <distro>] -e bash -lc 'exec $1 auth login …'} with glab's path as
     * {@link #findInWsl} printed it. The host and the path go in as arguments, never into the script; neither has
     * spaces.
     *
     * @param distro the distribution, or null for WSL's default one
     */
    public static List<String> loginInWsl(String distro, String glab, String host) {
        return inWsl(distro, "exec $1 auth login --hostname $0 --stdin", host, glab);
    }

    /**
     * Where glab is in WSL, printed as a path on the last line: what a login shell finds, else the usual install
     * places, else what an interactive shell finds, since Ubuntu's ~/.bashrc, where Homebrew and ~/go/bin are often
     * added, does nothing for a shell that is not interactive.
     */
    public static List<String> findInWsl(String distro) {
        return inWsl(distro, String.join("; ",
                "for c in $(command -v glab 2>/dev/null) /usr/bin/glab /usr/local/bin/glab "
                        + "/home/linuxbrew/.linuxbrew/bin/glab $HOME/.linuxbrew/bin/glab $HOME/.local/bin/glab "
                        + "$HOME/go/bin/glab $HOME/bin/glab /snap/bin/glab",
                "do [ -x $c ] && { echo $c; exit 0; }",
                "done",
                "bash -ic 'command -v glab' 2>/dev/null | tail -n 1"));
    }

    /** The path {@link #findInWsl} printed: its last line that is an absolute path, or null. */
    public static String pathIn(String output) {
        if (output == null) {
            return null;
        }
        String[] lines = output.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (line.startsWith("/") && line.endsWith("glab")) {
                return line;
            }
        }
        return null;
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
        command.addAll(List.of("-e", "bash", "-lc", script));
        command.addAll(List.of(args));
        return command;
    }
}
