package duowatchdog.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatesTest {

    private static final String RELEASE = """
            {
              "tag_name": "v0.7.0",
              "html_url": "https://github.com/sergeystets/duo-watchdog/releases/tag/v0.7.0",
              "draft": false,
              "prerelease": false,
              "body": "  Updates from the app.\\n",
              "assets": [
                {"name": "duo-watchdog-0.7.0.vsix", "size": 1200,
                 "browser_download_url": "https://github.com/sergeystets/duo-watchdog/releases/download/v0.7.0/duo-watchdog-0.7.0.vsix"},
                {"name": "duo-watchdog-0.7.0.zip", "size": 3400, "digest": "sha256:ABCDEF",
                 "browser_download_url": "https://github.com/sergeystets/duo-watchdog/releases/download/v0.7.0/duo-watchdog-0.7.0.zip"},
                {"name": "duo-watchdog-evil.zip", "size": 1,
                 "browser_download_url": "http://example.com/duo-watchdog-evil.zip"}
              ]
            }""";

    @Test
    void theLatestReleaseHasItsVersionPageAndFiles() {
        Updates.Release r = Updates.parse(RELEASE);
        assertEquals("0.7.0", r.version());
        assertEquals("https://github.com/sergeystets/duo-watchdog/releases/tag/v0.7.0", r.page());
        assertEquals("Updates from the app.", r.notes());
        assertEquals(2, r.assets().size(), "a file anywhere but GitHub is left out");
        Updates.Asset zip = r.asset(".zip");
        assertEquals("duo-watchdog-0.7.0.zip", zip.name());
        assertEquals(3400, zip.size());
        assertEquals("abcdef", zip.sha256());
        assertNull(r.asset(".vsix").sha256(), "no digest, nothing to check against");
        assertNull(r.asset(".jar"));
    }

    @Test
    void draftsAndPreReleasesAreNotOffered() {
        assertNull(Updates.parse("{\"tag_name\": \"v0.8.0-rc.1\", \"prerelease\": true, \"assets\": []}"));
        assertNull(Updates.parse("{\"tag_name\": \"v0.8.0\", \"draft\": true}"));
        assertThrows(IllegalArgumentException.class, () -> Updates.parse("{\"message\": \"Not Found\"}"));
        assertThrows(IllegalArgumentException.class, () -> Updates.parse("<html>rate limited</html>"));
        assertThrows(IllegalArgumentException.class, () -> Updates.parse("{\"tag_name\": \"v1/../../x\"}"),
                "it names the downloaded file");
    }

    @Test
    void versionsAreComparedAsNumbers() {
        assertTrue(Updates.newer("0.7.0", "0.6.0"));
        assertTrue(Updates.newer("0.10.0", "0.9.3"));
        assertTrue(Updates.newer("v1.0", "0.99.99"));
        assertTrue(Updates.newer("0.6.1", "0.6"));
        assertFalse(Updates.newer("0.6.0", "0.6.0"));
        assertFalse(Updates.newer("0.6.0", "0.6.1"));
        assertFalse(Updates.newer("0.7.0-rc.1", "0.7.0"), "a pre-release comes before its release");
        assertTrue(Updates.newer("0.7.0", "0.7.0-rc.1"));
        assertEquals(0, Updates.compare("0.7.0+build.5", "0.7.0"));
        assertEquals("0.7.0", Updates.version("v0.7.0"));
        assertEquals("version-x", Updates.version("version-x"));
    }

    @Test
    void itAsksOnceADay() {
        Instant now = Instant.parse("2026-11-09T10:00:00Z");
        assertTrue(Updates.due(null, now));
        assertFalse(Updates.due(now.minusSeconds(3600), now));
        assertTrue(Updates.due(now.minus(Updates.EVERY), now));
        assertTrue(Updates.due(now.plusSeconds(3600), now), "a clock set back");
    }

    @Test
    void onlyGitHubIsTrusted() {
        assertTrue(Updates.trusted("https://github.com/sergeystets/duo-watchdog/releases/download/v1/a.zip"));
        assertTrue(Updates.trusted("https://objects.githubusercontent.com/github-production-release-asset/1"));
        assertFalse(Updates.trusted("http://github.com/a.zip"));
        assertFalse(Updates.trusted("https://github.com.example.com/a.zip"));
        assertFalse(Updates.trusted("https://evilgithub.com/a.zip"));
        assertFalse(Updates.trusted("not a url"));
    }

    @Test
    void aDownloadMustMatchItsRelease(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("duo-watchdog-0.7.0.vsix");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        String sha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824";
        assertEquals(sha, Updates.sha256(file));
        assertDoesNotThrow(() -> Updates.verify(file, new Updates.Asset("a", "u", 5, sha)));
        assertDoesNotThrow(() -> Updates.verify(file, new Updates.Asset("a", "u", -1, null)));
        assertThrows(IOException.class, () -> Updates.verify(file, new Updates.Asset("a", "u", 6, null)));
        assertThrows(IOException.class, () -> Updates.verify(file, new Updates.Asset("a", "u", 5, "00" + sha.substring(2))));
    }

    @Test
    void thePluginZipIsThisPluginOfThatVersion(@TempDir Path dir) throws IOException {
        Path good = zip(dir.resolve("good.zip"), "duo-watchdog/", "duo-watchdog/lib/duo-watchdog-0.7.0.jar",
                "duo-watchdog/lib/core.jar");
        assertDoesNotThrow(() -> Updates.checkPluginZip(good, "0.7.0"));
        assertThrows(IOException.class, () -> Updates.checkPluginZip(good, "0.8.0"), "another version");
        Path outside = zip(dir.resolve("outside.zip"), "duo-watchdog/lib/duo-watchdog-0.7.0.jar", "other/x.jar");
        assertThrows(IOException.class, () -> Updates.checkPluginZip(outside, "0.7.0"));
        Path climbing = zip(dir.resolve("climbing.zip"), "duo-watchdog/lib/duo-watchdog-0.7.0.jar", "duo-watchdog/../../x");
        assertThrows(IOException.class, () -> Updates.checkPluginZip(climbing, "0.7.0"));
    }

    private static Path zip(Path file, String... names) throws IOException {
        try (OutputStream out = Files.newOutputStream(file); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.closeEntry();
            }
        }
        return file;
    }
}
