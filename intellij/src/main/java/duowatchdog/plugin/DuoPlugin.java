package duowatchdog.plugin;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * This plugin: its id, the same in plugin.xml and on JetBrains Marketplace, and its version, which the build writes
 * into the jar ({@code /duowatchdog/plugin.properties}), so that no IDE has to be asked for it.
 */
final class DuoPlugin {

    /** Up to 0.7.2 it was local.duo-watchdog; an update from one of those replaces it, in the same folder. */
    static final String ID = "io.github.sergeystets.duowatchdog";

    private static final String VERSION = read();

    private DuoPlugin() {
    }

    /** "0.6.0"; null when the jar does not say. */
    static @Nullable String version() {
        return VERSION;
    }

    private static @Nullable String read() {
        try (InputStream in = DuoPlugin.class.getResourceAsStream("/duowatchdog/plugin.properties")) {
            if (in == null) {
                return null;
            }
            Properties properties = new Properties();
            properties.load(in);
            String version = properties.getProperty("version", "").trim();
            return version.isEmpty() || version.startsWith("$") ? null : version;
        } catch (IOException e) {
            return null;
        }
    }
}
