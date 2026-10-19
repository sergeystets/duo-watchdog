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
