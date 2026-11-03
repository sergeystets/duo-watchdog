import { trimSlashes } from './address';
import { addDays, firstOfMonth, IsoDate, localToday, parseDate, plusMonths } from './dates';
import { FlowHistory, History, mergeHistories, months, sumOf } from './history';
import { Logger, NO_LOG } from './log';
import { CreditsReport, DailyUsage, FlowUsage } from './report';

export interface Response {
    status: number;
    body: string;
}

/**
 * Requests to GitLab: a POST to the GraphQL endpoint, or a GET of a REST one for the token's own details. The
 * extension backs it with Node's https; tests with canned JSON.
 */
export interface Transport {
    post(endpoint: URL, token: string, jsonBody: string): Promise<Response>;

    /** A transport that only posts, as the tests' fakes, cannot GET. */
    get?(uri: URL, token: string): Promise<Response>;
}

/** A failure worth showing to the user as it is: the message never carries the token. */
export class GitLabError extends Error {
    /** GitLab's HTTP status, e.g. 401 when it turned the token down; 0 when the request did not get that far. */
    readonly status: number;

    constructor(message: string, status = 0) {
        super(message);
        this.name = 'GitLabError';
        this.status = status;
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

export const HISTORY_QUERY = `query DuoMyHistory($namespacePath: ID!, $startDate: ISO8601Date, $endDate: ISO8601Date) {
  currentUser {
    creditsUsage(namespacePath: $namespacePath, startDate: $startDate, endDate: $endDate) {
      startDate
      endDate
      dailyUsage { date creditsUsed }
      usedFlowTypes { id title }
    }
  }
}
`;

/** Asked on its own: GitLab before 19.6 has no such field, and the rest still stands without it. */
export const NON_BILLABLE_QUERY = `query DuoMyNonBillable($namespacePath: ID!, $startDate: ISO8601Date, $endDate: ISO8601Date) {
  currentUser {
    creditsUsage(namespacePath: $namespacePath, startDate: $startDate, endDate: $endDate) {
      nonBillableUsage { dailyUsage { date creditsUsed } }
    }
  }
}
`;

export const USER_QUERY = `query DuoWhoAmI {
  currentUser { username }
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

/** The token's own details, as GET /personal_access_tokens/self gives them. */
export interface TokenInfo {
    name: string | null;
    scopes: string[];
    /** The day GitLab stops taking it; null when it never expires. */
    expiresAt: IsoDate | null;
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

    constructor(gitlabUrl: string, private readonly token: string, private readonly transport: Transport,
                private readonly log: Logger = NO_LOG) {
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

    /** Whose the token is: the proof that GitLab takes it for GraphQL. */
    async username(): Promise<string> {
        const result = await this.graphql(USER_QUERY, {});
        const username = str(obj(result.data, 'currentUser'), 'username');
        if (username == null) {
            throw new GitLabError(result.errors.length === 0 ? NOT_A_USER : result.errors.join('; '));
        }
        return username;
    }

    /**
     * The user's usage from `from` to `to`, at most a year: per day, per feature per day, and what GitLab did not
     * bill. GitLab moves the start to the subscription's. The per-feature and not-billed figures are details: when
     * they fail, the totals still stand. When GitLab fails on the whole range (HTTP 5xx, as gitlab.com does on a
     * year), it is asked a month at a time.
     */
    async history(namespace: string, from: IsoDate, to: IsoDate): Promise<History> {
        this.log.info('history ' + namespace + ': asking ' + from + ' to ' + to + ' at once');
        try {
            const year = await this.historyOf(namespace, from, to);
            this.log.info('history ' + namespace + ': answered ' + year.start + ' to ' + year.end + ', ' + described(year));
            return year;
        } catch (e) {
            if (!(e instanceof GitLabError) || e.status < 500) {
                throw e;
            }
            this.log.warn('history ' + namespace + ': the range failed (' + e.message + '); asking month by month');
            return this.byMonth(namespace, from, to, e);
        }
    }

    /**
     * history() a month at a time, newest first: the current month as the Usage view asks for it, without dates,
     * then each month before it, until GitLab fails twice in a row, which is where its data ends.
     */
    private async byMonth(namespace: string, from: IsoDate, to: IsoDate, whole: GitLabError): Promise<History> {
        const months: History[] = [];
        let failures = 0;
        for (let month = firstOfMonth(to); month.slice(0, 7) >= from.slice(0, 7) && failures < 2;
             month = plusMonths(month, -1)) {
            try {
                const current = month.slice(0, 7) === to.slice(0, 7);
                const answer = current ? await this.historyOf(namespace, null, null)
                    : await this.historyOf(namespace, month < from ? from : month, addDays(plusMonths(month, 1), -1));
                months.push(answer);
                this.log.info('history ' + namespace + ' ' + month.slice(0, 7) + (current ? ' (no dates, as Usage asks)' : '')
                    + ': answered, ' + described(answer));
                failures = 0;
            } catch (e) {
                if (!(e instanceof GitLabError) || (e.status > 0 && e.status < 500)) {
                    // Turned down, not out of range: the same for every month.
                    throw e;
                }
                this.log.warn('history ' + namespace + ' ' + month.slice(0, 7) + ': failed (' + e.message + ')');
                failures++;
            }
        }
        if (months.length === 0) {
            this.log.warn('history ' + namespace + ': no month answered');
            throw whole;
        }
        this.log.info('history ' + namespace + ': ' + months.length + (months.length === 1 ? ' month' : ' months')
            + ' answered month by month');
        return months.length === 1 ? months[0] : mergeHistories(months)!;
    }

    /** One range, or with null dates the current period, as the Usage view asks. */
    private async historyOf(namespace: string, from: IsoDate | null, to: IsoDate | null): Promise<History> {
        const variables: Json = { namespacePath: namespace };
        if (from != null) {
            variables.startDate = from;
        }
        if (to != null) {
            variables.endDate = to;
        }
        const result = await this.graphql(HISTORY_QUERY, variables);
        const user = obj(result.data, 'currentUser');
        if (user == null) {
            throw new GitLabError(result.errors.length === 0 ? NOT_A_USER : result.errors.join('; '));
        }
        const usage = obj(user, 'creditsUsage');
        if (usage == null) {
            const why = result.errors.length === 0 ? 'no data returned' : result.errors.join('; ');
            throw new GitLabError('GitLab returned no history for "' + namespace + '": ' + why + '.');
        }
        const used = flows(usage);

        const perFlow: FlowHistory[] = [];
        let flowsError: string | null = null;
        const priced = Math.min(used.length, MAX_PRICED_FLOWS);
        if (priced > 0) {
            let header = 'query DuoMyHistoryByFlow($namespacePath: ID!, $startDate: ISO8601Date, $endDate: ISO8601Date';
            let fields = '';
            const flowVariables: Json = { ...variables };
            for (let i = 0; i < priced; i++) {
                header += ', $f' + i + ': [String!]';
                fields += '    f' + i + ': creditsUsage(namespacePath: $namespacePath, startDate: $startDate, '
                    + 'endDate: $endDate, flowTypes: $f' + i + ') { dailyUsage { date creditsUsed } }\n';
                flowVariables['f' + i] = [used[i].id];
            }
            try {
                const byFlow = await this.graphql(header + ') {\n  currentUser {\n' + fields + '  }\n}\n', flowVariables);
                const figures = obj(byFlow.data, 'currentUser');
                for (let i = 0; i < priced; i++) {
                    const flow = obj(figures, 'f' + i);
                    if (flow == null) {
                        flowsError = byFlow.errors.length === 0 ? 'GitLab returned no figures' : byFlow.errors.join('; ');
                        continue;
                    }
                    perFlow.push({ id: used[i].id, title: used[i].title, daily: dailies(flow) });
                }
            } catch (e) {
                if (!(e instanceof GitLabError)) {
                    throw e;
                }
                flowsError = e.message;
            }
        }

        const nonBillable: DailyUsage[] = [];
        try {
            const extra = obj(obj((await this.graphql(NON_BILLABLE_QUERY, variables)).data, 'currentUser'), 'creditsUsage');
            const entries = extra == null ? undefined : extra['nonBillableUsage'];
            if (Array.isArray(entries)) {
                for (const e of entries) {
                    if (isObj(e)) {
                        nonBillable.push(...dailies(e));
                    }
                }
            } else if (isObj(entries)) {
                nonBillable.push(...dailies(entries));
            }
        } catch (e) {
            // Before GitLab 19.6: nothing to say about it.
            if (!(e instanceof GitLabError)) {
                throw e;
            }
        }
        const today = localToday();
        return {
            start: date_(usage, 'startDate') ?? from ?? firstOfMonth(today), end: date_(usage, 'endDate') ?? to ?? today,
            daily: dailies(usage), flows: perFlow, nonBillable, flowsError,
        };
    }

    /** The token's name, scopes and expiry, from the REST API, which tells any token about itself. */
    async tokenInfo(): Promise<TokenInfo> {
        const uri = new URL('v4/personal_access_tokens/self', this.endpoint);
        const transport = this.transport;
        const parsed = json(await this.request(() => transport.get
            ? transport.get(uri, this.token)
            : Promise.reject(new Error('this transport sends only GraphQL requests'))));
        if (!isObj(parsed)) {
            throw new GitLabError(NOT_JSON);
        }
        const scopes = arr(parsed, 'scopes').filter((e): e is string => typeof e === 'string');
        return { name: str(parsed, 'name'), scopes, expiresAt: date_(parsed, 'expires_at') };
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
        const parsed = json(await this.request(() => this.transport.post(this.endpoint, this.token, body)));
        if (!isObj(parsed)) {
            throw new GitLabError(NOT_JSON);
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

    /** Sends one request and turns what goes wrong on the way into the user's words. */
    private async request(send: () => Promise<Response>): Promise<Response> {
        const host = this.endpoint.hostname;
        let response: Response;
        try {
            response = await send();
        } catch (e) {
            const message = e instanceof Error && e.message ? e.message : e instanceof Error ? e.name : String(e);
            throw new GitLabError('Cannot reach ' + host + ': ' + message);
        }

        if (response.status === 401) {
            throw new GitLabError('GitLab rejected the token (HTTP 401): it is mistyped, expired or revoked. '
                + 'Create a personal access token with the read_api and ai_features scopes.', 401);
        }
        if (response.status === 403) {
            throw new GitLabError('GitLab refused the request (HTTP 403). The token needs the read_api and ai_features scopes.', 403);
        }
        if (response.status < 200 || response.status >= 300) {
            throw new GitLabError('GitLab returned HTTP ' + response.status + ' from ' + host + '.', response.status);
        }
        return response;
    }
}

const NOT_JSON = 'GitLab returned a response that is not JSON. Is the URL the GitLab root?';

/** "12 credits, 2 features": what a history holds, for the log. */
function described(history: History): string {
    const credits = Math.round(sumOf(history.daily) * 10) / 10;
    return credits + ' credits over ' + months(history).length + ' months, ' + history.flows.length
        + (history.flows.length === 1 ? ' feature' : ' features') + (history.flowsError != null ? ' (features: ' + history.flowsError + ')' : '');
}

function json(response: Response): unknown {
    try {
        return JSON.parse(response.body ?? '');
    } catch {
        return null;
    }
}

function report(username: string | null, usage: Json): CreditsReport {
    const blocked = obj(usage, 'blockedStatus');
    return {
        username, startDate: date_(usage, 'startDate'), endDate: date_(usage, 'endDate'),
        creditsUsed: num(usage, 'creditsUsed') ?? 0, blocked: bool(blocked, 'blocked'),
        blockedBy: str(blocked, 'capType'), daily: dailies(usage), flows: flows(usage), flowsError: null,
    };
}

function dailies(usage: Json): DailyUsage[] {
    const daily: DailyUsage[] = [];
    for (const e of arr(usage, 'dailyUsage')) {
        if (isObj(e)) {
            const date = date_(e, 'date');
            if (date != null) {
                daily.push({ date, credits: num(e, 'creditsUsed') ?? 0 });
            }
        }
    }
    return daily;
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
