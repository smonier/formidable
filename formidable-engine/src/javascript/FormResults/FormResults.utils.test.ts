import {afterEach, describe, expect, it, vi} from 'vitest';
import {formatFieldValue, parseFormFields} from './FormResults.utils';

/** The value as typed, in the reader's locale: the parts are formatted in UTC so no zone shifts them. */
const utcDate = (year: number, month: number, day: number, hours = 0, minutes = 0): Date => {
    const date = new Date(0);
    date.setUTCFullYear(year, month - 1, day);
    date.setUTCHours(hours, minutes, 0, 0);
    return date;
};
const asTypedDate = (year: number, month: number, day: number): string =>
    utcDate(year, month, day).toLocaleDateString(undefined, {timeZone: 'UTC'});
const asTypedDateTime = (year: number, month: number, day: number, hours: number, minutes: number): string =>
    utcDate(year, month, day, hours, minutes).toLocaleString(undefined, {dateStyle: 'short', timeStyle: 'short', timeZone: 'UTC'});

describe('formatFieldValue', () => {
    afterEach(() => {
        // Node re-reads TZ when the variable is assigned; the zone-specific cases stub it.
        vi.unstubAllEnvs();
    });

    it('shows a date field value as typed, not shifted by the reader\'s zone', () => {
        // "2026-09-09" parsed as a zoned instant would read as the 8th west of Greenwich.
        vi.stubEnv('TZ', 'America/Los_Angeles');
        expect(formatFieldValue('2026-09-09', 'date')).toEqual(asTypedDate(2026, 9, 9));
    });

    it('keeps a year below 100 as typed', () => {
        // Date.UTC() would read "0099" as 1999; the date input accepts any four-digit year.
        expect(formatFieldValue('0099-01-01', 'date')).toEqual(asTypedDate(99, 1, 1));
        expect(formatFieldValue('0099-01-01T08:00', 'datetime')).toEqual(asTypedDateTime(99, 1, 1, 8, 0));
        expect(formatFieldValue('0099-01-01', 'date')).not.toContain('1999');
    });

    it('shows a datetime field value as typed, in the reader\'s locale, without the ISO separator', () => {
        const expected = asTypedDateTime(2026, 9, 5, 12, 53);

        expect(formatFieldValue('2026-09-05T12:53', 'datetime')).toEqual(expected);
        expect(formatFieldValue('2026-09-05T12:53:00', 'datetime')).toEqual(expected);
        // The backend also accepts a fraction of a second.
        expect(formatFieldValue('2026-09-05T12:53:00.123', 'datetime')).toEqual(expected);
        expect(formatFieldValue('2026-09-05T12:53', 'datetime')).not.toContain('T');
    });

    it('keeps a wall-clock time that does not exist in the reader\'s zone (spring-forward gap)', () => {
        // 02:30 on 2026-03-08 is skipped in New York; the submitter typed it where it existed.
        vi.stubEnv('TZ', 'America/New_York');
        expect(formatFieldValue('2026-03-08T02:30', 'datetime')).toEqual(asTypedDateTime(2026, 3, 8, 2, 30));
    });

    it('leaves a value that does not parse as stored', () => {
        expect(formatFieldValue('yesterday', 'date')).toEqual('yesterday');
        expect(formatFieldValue('2026-09-05', 'datetime')).toEqual('2026-09-05');
        expect(formatFieldValue('2026-13-45', 'date')).toEqual('2026-13-45');
        expect(formatFieldValue('2026-09-05T25:61', 'datetime')).toEqual('2026-09-05T25:61');
    });

    it('leaves the value of any other field kind as stored', () => {
        expect(formatFieldValue('2026-09-09', undefined)).toEqual('2026-09-09');
    });

    it('follows a datetime value with the submitter\'s time zone when the submission recorded one', () => {
        const expected = asTypedDateTime(2026, 9, 5, 12, 53);
        expect(formatFieldValue('2026-09-05T12:53', 'datetime', 'Europe/Paris')).toEqual(`${expected} (Europe/Paris)`);
        // A generic zone is still where the submitter was: shown as recorded.
        expect(formatFieldValue('2026-09-05T12:53', 'datetime', 'UTC')).toEqual(`${expected} (UTC)`);
    });

    it('shows a datetime value alone when the submission recorded no zone', () => {
        const expected = asTypedDateTime(2026, 9, 5, 12, 53);
        expect(formatFieldValue('2026-09-05T12:53', 'datetime', null)).toEqual(expected);
        expect(formatFieldValue('2026-09-05T12:53', 'datetime', undefined)).toEqual(expected);
        expect(formatFieldValue('2026-09-05T12:53', 'datetime', '')).toEqual(expected);
    });

    it('never adds a zone to a date value or to a value that does not parse', () => {
        expect(formatFieldValue('2026-09-09', 'date', 'Europe/Paris')).toEqual(asTypedDate(2026, 9, 9));
        expect(formatFieldValue('yesterday', 'datetime', 'Europe/Paris')).toEqual('yesterday');
    });
});

describe('parseFormFields', () => {
    it('records which fields carry a date or a datetime', () => {
        const fields = parseFormFields({
            jcr: {nodeById: {fields: {nodes: [{descendants: {nodes: [
                {name: 'birthday', displayName: 'Birthday', isDate: true, isDatetime: false},
                {name: 'appointment', displayName: 'Appointment', isDate: false, isDatetime: true},
                {name: 'comment', displayName: 'Comment', isDate: false, isDatetime: false}
            ]}}]}}}
        });

        expect(fields.order).toEqual(['birthday', 'appointment', 'comment']);
        expect(fields.kinds.get('birthday')).toEqual('date');
        expect(fields.kinds.get('appointment')).toEqual('datetime');
        expect(fields.kinds.has('comment')).toEqual(false);
    });
});
