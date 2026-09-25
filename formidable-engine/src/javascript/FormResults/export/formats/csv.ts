import {sortByFormOrder, type FormFields, type SubmissionRow} from '../../FormResults.utils';
import type {ExportFormat} from './ExportFormat';

// A leading =, +, -, @, tab or CR makes a spreadsheet evaluate the cell as a
// formula, and these cells carry values typed by anonymous visitors while the
// file is opened by an editor. Such cells are neutralized with a leading quote
// (the OWASP CSV-injection mitigation); spreadsheets render it as plain text.
const FORMULA_TRIGGERS = new Set(['=', '+', '-', '@', '\t', '\r']);

const escapeCsvValue = (value: string): string => {
    const neutralized = FORMULA_TRIGGERS.has(value.charAt(0)) ? `'${value}` : value;
    if (/[",\n\r]/.test(neutralized)) {
        return `"${neutralized.replace(/"/g, '""')}"`;
    }

    return neutralized;
};

const toAbsoluteUrl = (url: string): string => {
    if (url.startsWith('http://') || url.startsWith('https://')) {
        return url;
    }

    return new URL(url, window.location.origin).toString();
};

const formatFilesValue = (submission: SubmissionRow): string => {
    const grouped = new Map<string, string[]>();
    for (const file of submission.files) {
        const urls = grouped.get(file.fieldName) ?? [];
        urls.push(`  ${file.fileName} : ${toAbsoluteUrl(file.fileUrl)}`);
        grouped.set(file.fieldName, urls);
    }

    return Array.from(grouped.entries())
        .map(([fieldName, urls]) => `${fieldName}:\n${urls.join('\n')}`)
        .join('\n');
};

const buildCsvContent = (
    submissions: SubmissionRow[],
    t: (key: string) => string,
    formFields: FormFields
): string => {
    const formFieldLabels = formFields.labels;
    // One column per field ever submitted, in the order the form displays them.
    const fieldNames = sortByFormOrder(
        Array.from(new Set(submissions.flatMap(s => s.fieldValues.map(f => f.name)))),
        name => name,
        formFields.order
    );

    const headerRow = [
        t('formResults.export.columns.id'),
        t('formResults.export.columns.name'),
        t('formResults.table.date'),
        t('formResults.table.locale'),
        t('formResults.detail.origin'),
        t('formResults.detail.referer'),
        t('formResults.detail.timeZone'),
        t('formResults.detail.files'),
        ...fieldNames.map(name => {
            const label = formFieldLabels.get(name);
            return label ? `${label} (${name})` : name;
        })
    ];

    const rows = submissions.map(submission => {
        const fieldValues = new Map(submission.fieldValues.map(f => [f.name, f.values.join(' | ')]));

        return [
            submission.uuid,
            submission.name,
            submission.created,
            submission.locale ?? '',
            submission.origin ?? '',
            submission.referer ?? '',
            submission.timeZone ?? '',
            formatFilesValue(submission),
            ...fieldNames.map(name => fieldValues.get(name) ?? '')
        ];
    });

    return [
        headerRow.map(escapeCsvValue).join(','),
        ...rows.map(row => row.map(v => escapeCsvValue(String(v))).join(','))
    ].join('\n');
};

export const csvFormat: ExportFormat = {
    id: 'csv',
    label: 'CSV',
    extension: 'csv',
    mimeType: 'text/csv;charset=utf-8;',
    buildContent: buildCsvContent
};
