import { trimSlashes } from './address';
import { IsoDate, parseDate } from './dates';
import { CreditsReport, DailyUsage, FlowUsage } from './report';

/** One POST to the GraphQL endpoint. The extension backs it with Node's https; tests with canned JSON. */
export interface Transport {
    post(endpoint: URL, token: string, jsonBody: string): Promise<{ status: number; body: string }>;
}

/** A failure worth showing to the user as it is: the message never carries the token. */
export class GitLabError extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'GitLabError';
    }
}

export const SELF_QUERY = `query DuoMyCredits($namespacePath: ID!) {
  currentUser {
    username
    creditsUsage(namespacePath: $namespacePath) {
      enabled
      startDate
      endDate
      creditsUsed
      blockedStatus { blocked capType }
      dailyUsage { date creditsUsed }
      usedFlowTypes { id title }
    }
  }
}
`;

export const GROUPS_QUERY = `query DuoMyGroups {
  currentUser {
    groups(first: 100) { nodes { fullPath name } }
  }
}
`;

/** Each feature is one more aliased call to GitLab's billing backend; past this many the rest stay unpriced. */
export const MAX_PRICED_FLOWS = 12;

/** Find my group asks about at most this many top-level groups, in one aliased query. */
export const MAX_PROBED_GROUPS = 20;

/** path is the group path, as it goes in the settings; name what GitLab shows on its pages. */
export interface GroupMatch {
    path: string;
    name: string | null;
}

export function groupLabel(match: GroupMatch): string {
    return match.name == null || match.name === match.path ? match.path : match.path + '  (' + match.name + ')';
}

/** checked: the top-level groups asked about; withCredits: those GitLab gave the user's credits for. */
export interface Groups {
    checked: string[];
    withCredits: GroupMatch[];
}

type Json = Record<string, unknown>;

const NOT_A_USER = 'GitLab did not accept the token as a user. Check the token and its read_api and ai_features scopes.';

export function endpoint(gitlabUrl: string | null | undefined): URL {
    const base = trimSlashes(gitlabUrl);
    let uri: URL;
    try {
        uri = new URL(base + '/api/graphql');
    } catch {
        throw new GitLabError(/^https?:\/\//i.test(base)
            ? 'GitLab URL is not a valid address: ' + gitlabUrl
            : 'GitLab URL must look like https://gitlab.example.com, got: ' + gitlabUrl);
    }
    if (!(uri.protocol === 'https:' || uri.protocol === 'http:') || !uri.hostname) {
        throw new GitLabError('GitLab URL must look like https://gitlab.example.com, got: ' + gitlabUrl);
    }
    return uri;
}

/**
 * Reads the token owner's GitLab Credits through currentUser.creditsUsage, which any member of the top-level group
 * may read about themselves (the field is an Experiment since GitLab 19.4).
 */
export class CreditsClient {

    private readonly endpoint: URL;

    constructor(gitlabUrl: string, private readonly token: string, private readonly transport: Transport) {
        this.endpoint = endpoint(gitlabUrl);
    }

    async fetch(namespace: string): Promise<CreditsReport> {
        const self = await this.graphql(SELF_QUERY, { namespacePath: namespace });
        const user = obj(self.data, 'currentUser');
        if (user == null) {
            throw new GitLabError(self.errors.length === 0 ? NOT_A_USER : self.errors.join('; '));
        }
        const username = str(user, 'username');
        const usage = obj(user, 'creditsUsage');
        if (usage == null) {
            const why = self.errors.length === 0 ? 'no data returned' : self.errors.join('; ');
            throw new GitLabError('GitLab returned no credits for @' + username + ' in "' + namespace + '": ' + why
                + ". Check that the group path is the one in the group's address, not the name GitLab shows,"
                + ' or run the command Duo Watchdog: Find My Group.');
        }
        if (!bool(usage, 'enabled') && num(usage, 'creditsUsed') == null) {
            throw new GitLabError('GitLab Credits data is not enabled for ' + namespace + '.');
        }
        return this.withFlowCredits(report(username, usage), namespace);
    }

    /**
     * The top-level groups of the groups the user belongs to, and which of them GitLab gives the user's credits
     * for: what Find my group offers.
     */
    async findGroups(): Promise<Groups> {
        const result = await this.graphql(GROUPS_QUERY, {});
        const user = obj(result.data, 'currentUser');
        if (user == null) {
            throw new GitLabError(result.errors.length === 0 ? NOT_A_USER : result.errors.join('; '));
        }
        const names = new Map<string, string | null>();
        for (const e of arr(obj(user, 'groups'), 'nodes')) {
            const node = isObj(e) ? e : null;
            const fullPath = str(node, 'fullPath');
            if (fullPath == null || fullPath.trim() === '') {
                continue;
            }
            const top = fullPath.split('/')[0];
            const name = fullPath === top ? str(node, 'name') : null;
            if (name != null || !names.has(top)) {
                names.set(top, name);
            }
        }
        const checked = [...names.keys()].slice(0, MAX_PROBED_GROUPS);
        if (checked.length === 0) {
            return { checked: [], withCredits: [] };
        }

        let header = 'query DuoProbeGroups(';
        let fields = '';
        const variables: Json = {};
        checked.forEach((group, i) => {
            header += (i === 0 ? '' : ', ') + '$g' + i + ': ID!';
            fields += '    g' + i + ': creditsUsage(namespacePath: $g' + i + ') { enabled creditsUsed }\n';
            variables['g' + i] = group;
        });
        const query = header + ') {\n  currentUser {\n' + fields + '  }\n}\n';
        const probed = obj((await this.graphql(query, variables)).data, 'currentUser');
        const matches: GroupMatch[] = [];
        checked.forEach((group, i) => {
            const usage = obj(probed, 'g' + i);
            if (usage != null && (bool(usage, 'enabled') || num(usage, 'creditsUsed') != null)) {
                matches.push({ path: group, name: names.get(group) ?? null });
            }
        });
        return { checked, withCredits: matches };
    }

    /**
     * Prices each feature the user spent on with one aliased query, creditsUsage filtered by flow type. The
     * breakdown is a detail: when it fails, the total still stands, the features stay listed unpriced, and the
     * report says why.
     */
    private async withFlowCredits(report: CreditsReport, namespace: string): Promise<CreditsReport> {
        const all = report.flows;
        const priced = Math.min(all.length, MAX_PRICED_FLOWS);
        if (priced === 0) {
            return report;
        }
        let header = 'query DuoMyCreditsByFlow($namespacePath: ID!';
        let fields = '';
        const variables: Json = { namespacePath: namespace };
        for (let i = 0; i < priced; i++) {
            header += ', $f' + i + ': [String!]';
            fields += '    f' + i + ': creditsUsage(namespacePath: $namespacePath, flowTypes: $f' + i
                + ') { creditsUsed }\n';
            variables['f' + i] = [all[i].id];
        }
        const query = header + ') {\n  currentUser {\n' + fields + '  }\n}\n';
        try {
            const result = await this.graphql(query, variables);
            const user = obj(result.data, 'currentUser');
            const why = result.errors.length === 0 ? 'GitLab returned no figures' : result.errors.join('; ');
            if (user == null) {
                return { ...report, flows: all, flowsError: why };
            }
            const flows: FlowUsage[] = [];
            let missing = false;
            for (let i = 0; i < priced; i++) {
                const credits = num(obj(user, 'f' + i), 'creditsUsed');
                missing ||= credits == null;
                flows.push({ id: all[i].id, title: all[i].title, credits });
            }
            flows.push(...all.slice(priced));
            return { ...report, flows, flowsError: missing ? why : null };
        } catch (e) {
            if (e instanceof GitLabError) {
                return { ...report, flows: all, flowsError: e.message };
            }
            throw e;
        }
    }

    private async graphql(query: string, variables: Json): Promise<{ data: Json | null; errors: string[] }> {
        const body = JSON.stringify({ query, variables });
        const host = this.endpoint.hostname;
        let response: { status: number; body: string };
        try {
            response = await this.transport.post(this.endpoint, this.token, body);
        } catch (e) {
            const message = e instanceof Error && e.message ? e.message : e instanceof Error ? e.name : String(e);
            throw new GitLabError('Cannot reach ' + host + ': ' + message);
        }

        if (response.status === 401) {
            throw new GitLabError('GitLab rejected the token (HTTP 401). Create a personal access token with the read_api and ai_features scopes.');
        }
        if (response.status === 403) {
            throw new GitLabError('GitLab refused the request (HTTP 403). The token needs the read_api and ai_features scopes.');
        }
        if (response.status < 200 || response.status >= 300) {
            throw new GitLabError('GitLab returned HTTP ' + response.status + ' from ' + host + '.');
        }

        let parsed: unknown;
        try {
            parsed = JSON.parse(response.body ?? '');
        } catch {
            parsed = null;
        }
        if (!isObj(parsed)) {
            throw new GitLabError('GitLab returned a response that is not JSON. Is the URL the GitLab root?');
        }
        const errors: string[] = [];
        for (const e of arr(parsed, 'errors')) {
            const message = isObj(e) ? str(e, 'message') : null;
            if (message != null) {
                errors.push(message);
            }
        }
        return { data: obj(parsed, 'data'), errors };
    }
}

function report(username: string | null, usage: Json): CreditsReport {
    const blocked = obj(usage, 'blockedStatus');
    const daily: DailyUsage[] = [];
    for (const e of arr(usage, 'dailyUsage')) {
        if (isObj(e)) {
            const date = date_(e, 'date');
            if (date != null) {
                daily.push({ date, credits: num(e, 'creditsUsed') ?? 0 });
            }
        }
    }
    return {
        username, startDate: date_(usage, 'startDate'), endDate: date_(usage, 'endDate'),
        creditsUsed: num(usage, 'creditsUsed') ?? 0, blocked: bool(blocked, 'blocked'),
        blockedBy: str(blocked, 'capType'), daily, flows: flows(usage), flowsError: null,
    };
}

function flows(usage: Json): FlowUsage[] {
    const result: FlowUsage[] = [];
    for (const e of arr(usage, 'usedFlowTypes')) {
        if (isObj(e)) {
            const id = str(e, 'id');
            if (id != null) {
                result.push({ id, title: str(e, 'title') ?? id, credits: null });
            }
        }
    }
    return result;
}

function isObj(v: unknown): v is Json {
    return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function obj(o: Json | null, key: string): Json | null {
    const v = o == null ? undefined : o[key];
    return isObj(v) ? v : null;
}

function arr(o: Json | null, key: string): unknown[] {
    const v = o == null ? undefined : o[key];
    return Array.isArray(v) ? v : [];
}

function str(o: Json | null, key: string): string | null {
    const v = o == null ? undefined : o[key];
    return typeof v === 'string' ? v : typeof v === 'number' || typeof v === 'boolean' ? String(v) : null;
}

function num(o: Json | null, key: string): number | null {
    const v = o == null ? undefined : o[key];
    if (typeof v === 'number') {
        return Number.isFinite(v) ? v : null;
    }
    if (typeof v === 'string' && v.trim() !== '') {
        const n = Number(v);
        return Number.isFinite(n) ? n : null;
    }
    return null;
}

function bool(o: Json | null, key: string): boolean {
    const v = o == null ? undefined : o[key];
    return v === true || (typeof v === 'string' && v.toLowerCase() === 'true');
}

function date_(o: Json | null, key: string): IsoDate | null {
    return parseDate(str(o, key));
}
