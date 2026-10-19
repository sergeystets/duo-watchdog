/** The GitLab addresses the settings deal in: the group path a user types, and the token page. */

/**
 * The top-level group path in what was typed: "@my-company", "my-company/team" and
 * "https://gitlab.com/groups/my-company/-/settings" all give "my-company".
 */
export function groupPath(input: string | null | undefined, gitlabUrl: string | null | undefined): string {
    let s = (input ?? '').trim();
    while (s.startsWith('@')) {
        s = s.slice(1);
    }
    const scheme = s.indexOf('://');
    if (scheme >= 0) {
        const rest = s.slice(scheme + 3);
        const slash = rest.indexOf('/');
        s = slash >= 0 ? rest.slice(slash) : '';
    } else {
        const h = host(gitlabUrl);
        if (h != null && s.toLowerCase().startsWith(h.toLowerCase() + '/')) {
            s = s.slice(h.length);
        }
    }
    let segments = s.split('/').map(p => p.trim()).filter(p => p !== '');
    if (segments.length > 1 && segments[0] === 'groups') {
        segments = segments.slice(1);
    }
    return segments.length === 0 || segments[0] === '-' ? '' : segments[0];
}

/** "gitlab.com", or null when the URL has no host. */
export function host(gitlabUrl: string | null | undefined): string | null {
    try {
        return new URL((gitlabUrl ?? '').trim()).hostname || null;
    } catch {
        return null;
    }
}

/** GitLab's new-token page with the name and both scopes filled in. */
export function tokenPage(gitlabUrl: string | null | undefined): string {
    return trimSlashes(gitlabUrl) + '/-/user_settings/personal_access_tokens?name=Duo+Watchdog&scopes=read_api,ai_features';
}

export function trimSlashes(gitlabUrl: string | null | undefined): string {
    let base = (gitlabUrl ?? '').trim();
    while (base.endsWith('/')) {
        base = base.slice(0, -1);
    }
    return base;
}
