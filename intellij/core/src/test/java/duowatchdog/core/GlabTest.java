package duowatchdog.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class GlabTest {

    @Test
    void readsTheWslDistributionOffAWindowsPath() {
        assertEquals("Ubuntu", Glab.wslDistro("\\\\wsl$\\Ubuntu\\home\\sergii\\projects\\app"));
        assertEquals("Ubuntu-22.04", Glab.wslDistro("\\\\wsl.localhost\\Ubuntu-22.04\\home\\sergii"));
        assertEquals("Debian", Glab.wslDistro("//wsl$/Debian/home/sergii/app"), "the IDE's forward slashes");
        assertEquals("Ubuntu", Glab.wslDistro("//WSL.LOCALHOST/Ubuntu/home"));
        assertNull(Glab.wslDistro("C:\\Users\\sergii\\app"));
        assertNull(Glab.wslDistro("/home/sergii/app"));
        assertNull(Glab.wslDistro(null));
    }

    @Test
    void theTokenNeverGoesOnTheCommandLine() {
        assertEquals(List.of("/opt/homebrew/bin/glab", "auth", "login", "--hostname", "gitlab.com", "--stdin"),
                Glab.login("/opt/homebrew/bin/glab", "gitlab.com"));
        assertEquals(List.of("wsl.exe", "-d", "Ubuntu", "-e", "bash", "-lc",
                        "exec $1 auth login --hostname $0 --stdin", "gitlab.com", "/home/s/.local/bin/glab"),
                Glab.loginInWsl("Ubuntu", "/home/s/.local/bin/glab", "gitlab.com"));
        assertEquals(List.of("wsl.exe", "-e", "bash", "-lc"), Glab.findInWsl(null).subList(0, 4),
                "no distribution: WSL's default one, and bash started directly, not through the user's shell");
        for (List<String> command : List.of(Glab.findInWsl("Ubuntu"), Glab.loginInWsl("Ubuntu", "/usr/bin/glab", "gitlab.com"),
                Glab.duoStorageInWsl("Ubuntu"))) {
            command.forEach(arg -> assertFalse(arg.contains("\""), "a Windows command line mangles quotes: " + arg));
        }
    }

    @Test
    void findsGlabsPathInWhatTheShellPrinted() {
        assertEquals("/home/linuxbrew/.linuxbrew/bin/glab", Glab.pathIn("/home/linuxbrew/.linuxbrew/bin/glab\n"));
        assertEquals("/usr/bin/glab", Glab.pathIn("bash: cannot set terminal process group\nbash: no job control\n/usr/bin/glab"));
        assertNull(Glab.pathIn(""));
        assertNull(Glab.pathIn("glab: command not found"));
    }
}
