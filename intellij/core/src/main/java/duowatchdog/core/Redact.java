package duowatchdog.core;

import java.util.regex.Pattern;

/** What may go into a log or a diagnostics report: everything but tokens. */
public final class Redact {

    /** GitLab's tokens carry a prefix: glpat-, gloas-, gldt-, glrt-, glsoat-, … */
    private static final Pattern TOKEN = Pattern.compile("\\bgl[a-z]{2,8}-[A-Za-z0-9_\\-]{8,}");

    private Redact() {
    }

    /** {@code text} with every GitLab token cut to its prefix: "glpat-•••". */
    public static String tokens(String text) {
        if (text == null) {
            return "";
        }
        return TOKEN.matcher(text).replaceAll(m -> m.group().substring(0, m.group().indexOf('-') + 1) + "•••");
    }

    /** The same, and {@code token} itself wherever it appears, whatever its form. */
    public static String tokens(String text, String token) {
        String masked = token == null || token.isBlank() ? text : (text == null ? "" : text.replace(token.trim(), "•••"));
        return tokens(masked);
    }
}
