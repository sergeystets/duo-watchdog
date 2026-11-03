package duowatchdog.plugin;

import com.intellij.openapi.diagnostic.Logger;
import duowatchdog.core.Redact;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What Duo Watchdog does, for troubleshooting: into the IDE's log (idea.log, category {@code #duowatchdog}) and the
 * last {@link #KEEP} events in memory for Copy Diagnostics. Every line starts with {@link #TAG}, to find them among
 * the other plugins' lines. Tokens never get in: every line goes through {@link Redact}.
 */
final class DuoLog {

    private static final Logger LOG = Logger.getInstance("#duowatchdog");
    static final String TAG = "[duo-watchdog] ";
    private static final int KEEP = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Deque<String> RECENT = new ArrayDeque<>();

    private DuoLog() {
    }

    static void info(String message) {
        String line = TAG + Redact.tokens(message);
        LOG.info(line);
        keep("INFO " + line);
    }

    static void warn(String message) {
        String line = TAG + Redact.tokens(message);
        LOG.warn(line);
        keep("WARN " + line);
    }

    private static void keep(String line) {
        synchronized (RECENT) {
            RECENT.addLast(TIME.format(LocalTime.now()) + " " + line);
            while (RECENT.size() > KEEP) {
                RECENT.removeFirst();
            }
        }
    }

    /** The last {@code count} events, oldest first. */
    static List<String> recent(int count) {
        synchronized (RECENT) {
            List<String> all = new ArrayList<>(RECENT);
            return all.subList(Math.max(0, all.size() - count), all.size());
        }
    }
}
