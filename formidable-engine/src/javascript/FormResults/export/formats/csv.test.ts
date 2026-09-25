import {describe, expect, it} from 'vitest';
import {csvFormat} from './csv';
import type {FormFields, SubmissionRow} from '../../FormResults.utils';

const t = (key: string) => key;

const submission = (fieldValues: Array<{name: string; values: string[]}>): SubmissionRow => ({
    uuid: 'uuid-1',
    path: '/path',
    name: 'submission-1',
    created: '2026-09-01',
    origin: null,
    locale: 'en',
    referer: null,
    timeZone: null,
    fieldValues,
    files: []
});

const formFields = (order: string[]): FormFields => ({labels: new Map(), order, kinds: new Map()});

const lastRow = (content: string): string => content.split('\n').at(-1)!;

describe('csv export', () => {
    it('quotes values containing separators, quotes and line breaks', () => {
        const content = csvFormat.buildContent(
            [submission([
                {name: 'a', values: ['with, comma']},
                {name: 'b', values: ['with "quote"']},
                {name: 'c', values: ['line\nbreak']},
                {name: 'd', values: ['carriage\rreturn']}
            ])],
            t,
            formFields(['a', 'b', 'c', 'd'])
        );

        // Asserted on the full content: a quoted line break splits naive row helpers.
        expect(content).toContain('"with, comma"');
        expect(content).toContain('"with ""quote"""');
        expect(content).toContain('"line\nbreak"');
        expect(content).toContain('"carriage\rreturn"');
    });

    it('neutralizes formulas submitted by visitors (CSV injection)', () => {
        const content = csvFormat.buildContent(
            [submission([
                {name: 'a', values: ['=HYPERLINK("https://evil.example","click")']},
                {name: 'b', values: ['+1+2']},
                {name: 'c', values: ['-2+3']},
                {name: 'd', values: ['@SUM(A1)']},
                {name: 'e', values: ['\t=1+1']},
                {name: 'f', values: ['\r=1+1']}
            ])],
            t,
            formFields(['a', 'b', 'c', 'd', 'e', 'f'])
        );

        const row = lastRow(content);
        expect(row).toContain(`'=HYPERLINK(""https://evil.example"",""click"")`);
        expect(row).toContain(`'+1+2`);
        expect(row).toContain(`'-2+3`);
        expect(row).toContain(`'@SUM(A1)`);
        expect(row).toContain(`'\t=1+1`);
        expect(content).toContain(`"'\r=1+1"`);
    });

    it('names every column and lands each metadata value under its own header', () => {
        // The header labels and the row values are two hand-maintained arrays that must stay
        // index-aligned: asserted by position, with a zone on the fixture so the column is filled.
        const content = csvFormat.buildContent(
            [{...submission([{name: 'a', values: ['plain value']}]), origin: 'formidable', referer: 'https://example.org/page', timeZone: 'Europe/Paris'}],
            t,
            formFields(['a'])
        );

        const [header, row] = content.split('\n').map(line => line.split(','));
        expect(header).toEqual([
            'formResults.export.columns.id',
            'formResults.export.columns.name',
            'formResults.table.date',
            'formResults.table.locale',
            'formResults.detail.origin',
            'formResults.detail.referer',
            'formResults.detail.timeZone',
            'formResults.detail.files',
            'a'
        ]);
        expect(row[header.indexOf('formResults.detail.timeZone')]).toEqual('Europe/Paris');
        expect(row[header.indexOf('formResults.detail.referer')]).toEqual('https://example.org/page');
        expect(row[header.indexOf('formResults.detail.origin')]).toEqual('formidable');
        expect(row[header.indexOf('a')]).toEqual('plain value');
    });

    it('leaves ordinary values untouched', () => {
        const content = csvFormat.buildContent(
            [submission([{name: 'a', values: ['plain value']}])],
            t,
            formFields(['a'])
        );

        expect(lastRow(content)).toContain('plain value');
        expect(lastRow(content)).not.toContain("'plain value");
    });
});
