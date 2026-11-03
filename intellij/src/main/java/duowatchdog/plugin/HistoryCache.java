package duowatchdog.plugin;

import com.intellij.openapi.application.PathManager;
import duowatchdog.core.History;
import duowatchdog.core.HistoryJson;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;

/**
 * Settled months of each account's history, kept in the IDE's system folder so Statistics asks GitLab only for the
 * months still changing. One file a month, named by the account, its group and the month; a changed group starts
 * fresh. Nothing in them is secret.
 */
final class HistoryCache {

    private HistoryCache() {
    }

    private static Path folder() {
        return Path.of(PathManager.getSystemPath(), "duo-watchdog", "history");
    }

    private static String safe(String text) {
        return text.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path file(String accountId, String group, YearMonth month) {
        return folder().resolve(safe(accountId) + "__" + safe(group) + "__" + month + ".json");
    }

    static @Nullable History read(String accountId, String group, YearMonth month) {
        Path file = file(accountId, group, month);
        try {
            return Files.isRegularFile(file) ? HistoryJson.read(Files.readString(file, StandardCharsets.UTF_8)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    static void write(String accountId, String group, YearMonth month, History history) {
        try {
            Files.createDirectories(folder());
            Files.writeString(file(accountId, group, month), HistoryJson.write(history), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DuoLog.warn("history cache: could not write " + month + " of " + accountId + ": " + e.getMessage());
        }
    }

    /** Drops an account's months, when it is removed. */
    static void forget(String accountId) {
        Path dir = folder();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, safe(accountId) + "__*")) {
            for (Path f : files) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            DuoLog.warn("history cache: could not forget " + accountId + ": " + e.getMessage());
        }
    }
}
