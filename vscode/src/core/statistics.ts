import { Account, AccountState, accountGroup, idOf, sortStates } from './accounts';
import { accountName, longDate } from './accountsView';
import { FeatureModel, features, step } from './dashboard';
import { IsoDate, monthName, plusMonths } from './dates';
import { flowsByCredits, History, mergeHistories, Month, months, nonBillableTotal, sumOf, total } from './history';
import { grouped, number } from './view';

/** The Statistics view: a year of one account's usage, or of every account's added up. */

export type Scope = 'account' | 'all';

/** The features that get a colour of their own; the rest are folded into Other. */
export const TOP_FEATURES = 5;

/** A colour slot of the chart: "1" to "5" for the top features, "other" for the rest. */
export type Slot = '1' | '2' | '3' | '4' | '5' | 'other';

export interface Segment {
    slot: Slot;
    label: string;
    value: number;
    /** "61.2 credits". */
    text: string;
}

export interface MonthBar {
    /** "Oct". */
    label: string;
    /** "2026" under January, else null. */
    year: string | null;
    /** "October 2026". */
    title: string;
    total: number;
    /** "148.6 credits". */
    totalText: string;
    /** Bottom to top: the top features in order, then Other. */
    segments: Segment[];
}

export interface MonthRow {
    month: string;
    credits: string;
    topFeature: string;
    notBilled: string;
}

export interface StatisticsModel {
    /** "This month so far 148.6 · Last month 201 · Monthly average 175 · 12 months 2,100". */
    summary: string;
    legend: { label: string; slot: Slot }[];
    /** Oldest first. */
    bars: MonthBar[];
    axisMax: number;
    ticks: { value: number; label: string }[];
    /** Newest first. */
    rows: MonthRow[];
    features: FeatureModel[];
    featuresError: string | null;
    /** "Not billed (promotional, beta): 12.5 credits. …", or null when GitLab billed everything or did not say. */
    notBilled: string | null;
    note: string;
    /** For all accounts: which accounts are added up; else null. */
    accounts: string | null;
}

function credits(value: number): string {
    return grouped(value) + (number(value) === '1' ? ' credit' : ' credits');
}

function monthTitle(month: string): string {
    return monthName(month + '-01') + ' ' + Number(month.slice(0, 4));
}

/** The chart, the table, the features and the notes for a history. */
export function statisticsModel(history: History, today: IsoDate): StatisticsModel {
    const all = months(history);
    const order = flowsByCredits(history);
    const top = order.slice(0, TOP_FEATURES);
    const otherLabel = top.length === 0 ? 'All features' : 'Other';
    let hasOther = false;

    const bars = all.map(m => {
        const segments: Segment[] = top.map((flow, i) => {
            const value = m.byFlow[i].credits;
            return { slot: String(i + 1) as Slot, label: flow.title, value, text: credits(value) };
        });
        const named = segments.reduce((sum, s) => sum + s.value, 0);
        const rest = m.byFlow.slice(top.length).reduce((sum, f) => sum + f.credits, 0);
        // Features past the top five, and credits GitLab did not give per feature.
        const other = Math.max(m.credits - named, rest, 0);
        if (other > 0.005) {
            hasOther = true;
            segments.push({ slot: 'other', label: otherLabel, value: other, text: credits(other) });
        }
        const sum = named + Math.max(other, 0);
        return {
            label: monthName(m.month + '-01').slice(0, 3),
            year: m.month.endsWith('-01') ? m.month.slice(0, 4) : null,
            title: monthTitle(m.month),
            total: sum,
            totalText: credits(sum),
            segments: segments.filter(s => s.value > 0),
        };
    });

    const legend: { label: string; slot: Slot }[] = top.map((flow, i) => ({ label: flow.title, slot: String(i + 1) as Slot }));
    if (hasOther) {
        legend.push({ label: otherLabel, slot: 'other' });
    }

    const max = Math.max(0, ...bars.map(b => b.total));
    const gridStep = step(max);
    const axisMax = Math.max(max * 1.15, gridStep);
    const ticks: { value: number; label: string }[] = [];
    for (let v = 0; v <= axisMax + 1e-9; v += gridStep) {
        ticks.push({ value: v, label: grouped(v) });
    }

    const notBilled = nonBillableTotal(history);
    return {
        summary: summary(all, today, total(history)),
        legend,
        bars,
        axisMax,
        ticks,
        rows: [...all].reverse().map(m => ({
            month: monthTitle(m.month),
            credits: grouped(m.credits),
            topFeature: topFeature(m, order.map(f => f.title)),
            notBilled: m.nonBillable > 0.005 ? grouped(m.nonBillable) : '—',
        })),
        features: features(order.map(f => ({ id: f.id, title: f.title, credits: sumOf(f.daily) }))),
        featuresError: history.flowsError != null ? 'Credits per feature did not load: ' + history.flowsError : null,
        notBilled: notBilled > 0.005
            ? 'Not billed (promotional, beta): ' + credits(notBilled) + ". GitLab's own usage page counts these in its total."
            : null,
        note: 'From ' + longDate(history.start) + ": GitLab keeps usage from the subscription's start and can lag a few "
            + "hours. A group member sees usage per day and per feature; per project, model or session takes the group's Owner.",
        accounts: null,
    };
}

/** "This month so far 148.6 · Last month 201 · Monthly average 175 · 12 months 2,100". */
function summary(all: Month[], today: IsoDate, sum: number): string {
    const thisMonth = today.slice(0, 7);
    const lastMonth = plusMonths(thisMonth + '-01', -1).slice(0, 7);
    const current = all.find(m => m.month === thisMonth)?.credits ?? 0;
    const last = all.find(m => m.month === lastMonth)?.credits ?? 0;
    // Over the months that are over, from the first with any use (as History.monthlyAverage in the plugin), so months
    // before the account used Duo do not pull it down; in the first month, that month so far.
    const over = all.filter(m => m.month < thisMonth);
    const first = over.findIndex(m => m.credits > 0);
    const complete = first < 0 ? [] : over.slice(first);
    const average = complete.length === 0 ? current : complete.reduce((s, m) => s + m.credits, 0) / complete.length;
    return ['This month so far ' + grouped(current), 'Last month ' + grouped(last), 'Monthly average ' + grouped(average),
        all.length + (all.length === 1 ? ' month ' : ' months ') + grouped(sum)].join(' · ');
}

function topFeature(month: Month, titles: string[]): string {
    let best = -1;
    month.byFlow.forEach((f, i) => {
        if (f.credits > 0.005 && (best < 0 || f.credits > month.byFlow[best].credits)) {
            best = i;
        }
    });
    return best < 0 ? '—' : titles[best];
}

/** A history being fetched, fetched, or failed; the last good one stays while the next is fetched. */
export interface HistoryEntry {
    history: History | null;
    error: string | null;
    loading: boolean;
}

/** What a history is cached under: a new group or a new token is a new history. */
export function historyKey(account: Account): string {
    return idOf(account) + '|' + accountGroup(account) + '|' + account.tokenSavedAt;
}

/** The accounts whose usage can be read: a token, a group, and nothing wrong at the last refresh. */
export function trackedStates(states: readonly AccountState[]): AccountState[] {
    return sortStates(states).filter(s => s.problem == null);
}

export interface StatisticsView {
    scope: Scope;
    /** For one account with a problem: show the Usage view's notice instead. */
    useNotice: boolean;
    /** Nothing to show yet. */
    loading: boolean;
    errors: string[];
    /** Why there is nothing to add up, or null. */
    empty: string | null;
    stats: StatisticsModel | null;
}

/** The Statistics view for the selected account, or for every tracked account added up. */
export function statisticsView(scope: Scope, selected: AccountState | null, states: readonly AccountState[],
                               entry: (account: Account) => HistoryEntry | undefined, today: IsoDate): StatisticsView {
    const view: StatisticsView = { scope, useNotice: false, loading: false, errors: [], empty: null, stats: null };
    if (scope === 'account') {
        if (selected == null) {
            return view;
        }
        if (selected.problem != null) {
            return { ...view, useNotice: true };
        }
        const e = entry(selected.account);
        if (e?.error != null) {
            view.errors.push('Statistics did not load: ' + e.error);
        }
        if (e?.history != null) {
            view.stats = statisticsModel(e.history, today);
        }
        view.loading = view.stats == null && view.errors.length === 0;
        return view;
    }

    const tracked = trackedStates(states);
    if (tracked.length === 0) {
        return { ...view, empty: 'No account can show its usage yet: All accounts adds up the accounts with a token that reads Duo usage.' };
    }
    const histories: History[] = [];
    const included: string[] = [];
    let pending = false;
    for (const s of tracked) {
        const e = entry(s.account);
        if (e?.history != null) {
            histories.push(e.history);
            included.push(accountName(s.account));
        }
        if (e?.error != null) {
            view.errors.push('Not included: ' + accountName(s.account) + ': ' + e.error);
        }
        pending ||= e == null || e.loading;
    }
    const merged = mergeHistories(histories);
    if (merged != null) {
        view.stats = {
            ...statisticsModel(merged, today),
            accounts: (included.length === 1 ? '1 account: ' : included.length + ' accounts: ') + included.join(', ')
                + (pending ? '. Loading the others…' : '.'),
        };
    }
    view.loading = view.stats == null && pending;
    return view;
}
