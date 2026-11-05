/** Calendar days as "2026-10-18", the way GitLab sends them: no time, no zone. */
export type IsoDate = string;

const DAY_MS = 86_400_000;
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
    'November', 'December'];

function utc(date: IsoDate): number {
    const [y, m, d] = date.split('-').map(Number);
    return Date.UTC(y, m - 1, d);
}

function iso(ms: number): IsoDate {
    return new Date(ms).toISOString().slice(0, 10);
}

function pad(n: number): string {
    return String(n).padStart(2, '0');
}

/** "2026-10-01" from "2026-10-01" or "2026-10-01T00:00:00Z"; null when it is not a real day. */
export function parseDate(value: string | null | undefined): IsoDate | null {
    if (value == null) {
        return null;
    }
    const day = value.length > 10 ? value.slice(0, 10) : value;
    if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) {
        return null;
    }
    return iso(utc(day)) === day ? day : null;
}

/** Today on this machine's calendar. */
export function localToday(now: Date = new Date()): IsoDate {
    return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

export function addDays(date: IsoDate, days: number): IsoDate {
    return iso(utc(date) + days * DAY_MS);
}

/** Days from {@code from} to {@code to}: 0 for the same day, negative when {@code to} is earlier. */
export function daysBetween(from: IsoDate, to: IsoDate): number {
    return Math.round((utc(to) - utc(from)) / DAY_MS);
}

/** As Java's LocalDate.plusMonths: Jan 31 plus a month is Feb 28 (or 29). */
export function plusMonths(date: IsoDate, months: number): IsoDate {
    const [y, m, d] = date.split('-').map(Number);
    const total = y * 12 + (m - 1) + months;
    const year = Math.floor(total / 12);
    const month = total % 12;
    const last = new Date(Date.UTC(year, month + 1, 0)).getUTCDate();
    return `${String(year).padStart(4, '0')}-${pad(month + 1)}-${pad(Math.min(d, last))}`;
}

export function firstOfMonth(date: IsoDate): IsoDate {
    return date.slice(0, 8) + '01';
}

export function dayOfMonth(date: IsoDate): number {
    return Number(date.slice(8, 10));
}

export function monthName(date: IsoDate): string {
    return MONTHS[Number(date.slice(5, 7)) - 1];
}

/** "2026-10-18 14:05" on this machine's clock. */
export function dateTime(at: Date): string {
    return `${localToday(at)} ${time(at)}`;
}

/** "14:05" on this machine's clock. */
export function time(at: Date): string {
    return `${pad(at.getHours())}:${pad(at.getMinutes())}`;
}

/** Minutes a zone's clock is ahead of UTC at a moment: 120 in Kyiv in winter, 180 in summer. */
export type ZoneOffset = (at: Date) => number;

/** This machine's zone. */
export const LOCAL_ZONE: ZoneOffset = at => -at.getTimezoneOffset();

/** The day a moment falls on in a zone. */
export function dayIn(at: Date, zone: ZoneOffset = LOCAL_ZONE): IsoDate {
    return iso(at.getTime() + zone(at) * 60_000);
}

/** "UTC+2", "UTC+5:30", "UTC-3", or "UTC": a zone by its offset in minutes, worded as the plugin words it. */
export function zoneText(minutes: number): string {
    if (minutes === 0) {
        return 'UTC';
    }
    const m = Math.abs(minutes);
    return 'UTC' + (minutes < 0 ? '-' : '+') + Math.floor(m / 60) + (m % 60 === 0 ? '' : ':' + pad(m % 60));
}

/**
 * "today 14:05 UTC+2", "yesterday 23:50 UTC+2", "Nov 3, 10:00 UTC+2", or "Dec 31, 2025, 10:00 UTC+2": a moment on a
 * zone's clock, its day told against today's, and the zone named, as the plugin's CreditsView.clockText says it.
 * @param today today in that zone
 */
export function clockText(at: Date, today: IsoDate, zone: ZoneOffset = LOCAL_ZONE): string {
    const offset = zone(at);
    const local = new Date(at.getTime() + offset * 60_000);
    const day = iso(local.getTime());
    const days = daysBetween(day, today);
    const date = days === 0 ? 'today' : days === 1 ? 'yesterday'
        : MONTHS[local.getUTCMonth()].slice(0, 3) + ' ' + local.getUTCDate()
        + (day.slice(0, 4) === today.slice(0, 4) ? '' : ', ' + local.getUTCFullYear()) + ',';
    return date + ' ' + pad(local.getUTCHours()) + ':' + pad(local.getUTCMinutes()) + ' ' + zoneText(offset);
}
