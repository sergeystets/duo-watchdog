package duowatchdog.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitLabAddressTest {

    private static String path(String typed) {
        return GitLabAddress.groupPath(typed, "https://gitlab.com");
    }

    @Test
    void keepsTheTopLevelGroupOfWhateverWasTyped() {
        assertEquals("acme", path("  acme "));
        assertEquals("acme", path("@acme"));
        assertEquals("acme", path("/acme/platform/"));
        assertEquals("acme", path("https://gitlab.com/acme/platform/-/issues"));
        assertEquals("acme", path("https://gitlab.com/groups/acme/-/settings/general"));
        assertEquals("acme", path("gitlab.com/acme"));
        assertEquals("my.group", path("my.group"), "a dot belongs to the path unless it is the GitLab host");
        assertEquals("", path("https://gitlab.com/"));
        assertEquals("", path(null));
    }

    @Test
    void theTokenPageAsksForTheNameAndBothScopes() {
        assertEquals("https://gitlab.example.com/-/user_settings/personal_access_tokens?name=Duo+Watchdog&scopes=read_api,ai_features",
                GitLabAddress.tokenPage(" https://gitlab.example.com/ "));
    }

    @Test
    void readsTheHost() {
        assertEquals("gitlab.com", GitLabAddress.host("https://gitlab.com"));
        assertNull(GitLabAddress.host("not a url"));
    }
}
