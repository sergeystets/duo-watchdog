package duowatchdog.core;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What GitLab bills for one call to a model, in credits. GitLab Duo bills each model call at its model's rate, not by
 * tokens, so a session's steps times the rate is what it cost, about.
 */
public final class ModelCredits {

    /**
     * Credits per model call, as on GitLab's Credits page, checked 2026-11-04. When GitLab changes them, change them
     * here and in vscode/src/core/modelCredits.ts.
     */
    private static final Map<String, Double> PER_CALL = Map.ofEntries(
            Map.entry("Haiku 5.5", 0.125),
            Map.entry("Haiku 4.5", 0.149),
            Map.entry("Sonnet 5", 0.3125),
            Map.entry("Sonnet 5.5", 0.3125),
            Map.entry("Sonnet 4.5", 0.5),
            Map.entry("Sonnet 4.6", 0.5),
            Map.entry("Opus 5.5", 0.741),
            Map.entry("Opus 4.5", 0.833),
            Map.entry("Opus 5", 0.909),
            Map.entry("Opus 4.6", 0.909),
            Map.entry("Opus 4.7", 0.909),
            Map.entry("Opus 4.8", 0.909),
            Map.entry("Fable 5", 1.667),
            Map.entry("Fable 5.1", 1.667));

    /** The models in the table, the cheapest first, as GitLab's Credits page names them: for the group's default model. */
    public static final List<String> MODELS = PER_CALL.keySet().stream()
            .sorted(Comparator.comparing((String m) -> PER_CALL.get(m)).thenComparing(Comparator.naturalOrder())).toList();

    /**
     * What a step is priced as when the Duo CLI's log names no model, the group's default, or one not in the table,
     * unless the settings say otherwise: GitLab's default model for the Duo Agent Platform.
     */
    public static final String ASSUMED = "Sonnet 4.5";

    /** claude_sonnet_5_5, claude_opus_5, claude-haiku-4-5-20251001: the family, the version and a minor one. */
    private static final Pattern CLAUDE = Pattern.compile("(haiku|sonnet|opus|fable)[_.-]?(\\d{1,2})(?:[_.-](\\d)(?!\\d))?");

    private ModelCredits() {
    }

    /** "Opus 5" for {@code claude_opus_5}, as GitLab's Credits page names it; the identifier itself for another. */
    public static String name(String identifier) {
        if (identifier == null) {
            return null;
        }
        Matcher m = CLAUDE.matcher(identifier.toLowerCase(Locale.ROOT));
        if (!m.find()) {
            return identifier;
        }
        String family = Character.toUpperCase(m.group(1).charAt(0)) + m.group(1).substring(1);
        String minor = m.group(3);
        return family + " " + m.group(2) + (minor == null || minor.equals("0") ? "" : "." + minor);
    }

    /** The credits GitLab bills per call to the model; null when the table does not have it. */
    public static Double perCall(String identifier) {
        return identifier == null ? null : PER_CALL.get(name(identifier));
    }

    /** The model named, when the table has it; else {@link #ASSUMED}. */
    public static String assumed(String name) {
        return name != null && PER_CALL.containsKey(name) ? name : ASSUMED;
    }
}
