import type {FormFields, SubmissionRow} from '../../FormResults.utils';
import type {ExportFormat} from './ExportFormat';

const toAbsoluteUrl = (url: string): string => {
    if (url.startsWith('http://') || url.startsWith('https://')) {
        return url;
    }

    return new URL(url, window.location.origin).toString();
};

const buildJsonContent = (
    submissions: SubmissionRow[],
    _t: (key: string) => string,
    formFields: FormFields
): string => {
    const formFieldLabels = formFields.labels;
    const data = submissions.map(submission => ({
        id: submission.uuid,
        name: submission.name,
        created: submission.created,
        origin: submission.origin,
        locale: submission.locale,
        referer: submission.referer,
        timeZone: submission.timeZone,
        fields: Object.fromEntries(
            submission.fieldValues.map(field => {
                const label = formFieldLabels.get(field.name);
                return [
                    field.name,
                    {
                        name: field.name,
                        ...(label ? {label} : {}),
                        value: field.values.length === 1 ? field.values[0] : field.values
                    }
                ];
            })
        ),
        files: Object.fromEntries(
            Array.from(
                submission.files.reduce((grouped, file) => {
                    const entry = grouped.get(file.fieldName) ?? [];
                    entry.push({
                        fileName: file.fileName,
                        mimeType: file.mimeType,
                        url: toAbsoluteUrl(file.fileUrl)
                    });
                    grouped.set(file.fieldName, entry);
                    return grouped;
                }, new Map<string, {fileName: string; mimeType: string | null; url: string}[]>())
            )
        )
    }));

    return JSON.stringify(data, null, 2);
};

export const jsonFormat: ExportFormat = {
    id: 'json',
    label: 'JSON',
    extension: 'json',
    mimeType: 'application/json;charset=utf-8;',
    buildContent: buildJsonContent
};
