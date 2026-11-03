package duowatchdog.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(List.of("wsl.exe", "-d", "Ubuntu", "--", "bash", "-lc",
                        "exec glab auth login --hostname \"$0\" --stdin", "gitlab.com"),
                Glab.loginInWsl("Ubuntu", "gitlab.com"));
        assertEquals(List.of("wsl.exe", "--", "bash", "-lc", "command -v glab"), Glab.findInWsl(null),
                "no distribution: WSL's default one");
    }
}
