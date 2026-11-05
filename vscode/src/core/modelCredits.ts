/**
 * What GitLab bills for one call to a model, in credits. GitLab Duo bills each model call at its model's rate, not by
 * tokens, so a session's steps times the rate is what it cost, about. A port of ModelCredits in the plugin's core.
 */

/**
 * Credits per model call, as on GitLab's Credits page, checked 2026-11-04. When GitLab changes them, change them here
 * and in intellij/core/src/main/java/duowatchdog/core/ModelCredits.java.
 */
const PER_CALL: ReadonlyMap<string, number> = new Map(Object.entries({
    'Haiku 5.5': 0.125,
    'Haiku 4.5': 0.149,
    'Sonnet 5': 0.3125,
    'Sonnet 5.5': 0.3125,
    'Sonnet 4.5': 0.5,
    'Sonnet 4.6': 0.5,
    'Opus 5.5': 0.741,
    'Opus 4.5': 0.833,
    'Opus 5': 0.909,
    'Opus 4.6': 0.909,
    'Opus 4.7': 0.909,
    'Opus 4.8': 0.909,
    'Fable 5': 1.667,
    'Fable 5.1': 1.667,
}));

/** The models in the table, the cheapest first, as GitLab's Credits page names them: for the group's default model. */
export const MODELS: readonly string[] = [...PER_CALL.keys()].sort((a, b) => PER_CALL.get(a)! - PER_CALL.get(b)! || (a < b ? -1 : a > b ? 1 : 0));

/**
 * What a step is priced as when the Duo CLI's log names no model, the group's default, or one not in the table, unless
 * the settings say otherwise: GitLab's default model for the Duo Agent Platform.
 */
export const ASSUMED_MODEL = 'Sonnet 4.5';

/** claude_sonnet_5_5, claude_opus_5, claude-haiku-4-5-20251001: the family, the version and a minor one. */
const CLAUDE = /(haiku|sonnet|opus|fable)[_.-]?(\d{1,2})(?:[_.-](\d)(?!\d))?/;

/** "Opus 5" for claude_opus_5, as GitLab's Credits page names it; the identifier itself for another. */
export function modelName(identifier: string): string {
    const m = CLAUDE.exec(identifier.toLowerCase());
    if (m == null) {
        return identifier;
    }
    return m[1][0].toUpperCase() + m[1].slice(1) + ' ' + m[2] + (m[3] == null || m[3] === '0' ? '' : '.' + m[3]);
}

/** The credits GitLab bills per call to the model; null when the table does not have it. */
export function perCall(identifier: string | null): number | null {
    return identifier == null ? null : PER_CALL.get(modelName(identifier)) ?? null;
}

/** The model named, when the table has it; else ASSUMED_MODEL. */
export function assumedModel(name: string | null | undefined): string {
    return name != null && PER_CALL.has(name) ? name : ASSUMED_MODEL;
}
