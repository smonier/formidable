export interface FormResultsNode {
    uuid: string;
    path: string;
    name: string;
    displayName: string;
    submissionsContainer?: {
        nodes?: Array<{
            canRemoveNode?: boolean;
            canRemoveChildNodes?: boolean;
        }>;
    };
    parentForm: {
        refNode: {
            uuid: string;
            path: string;
            displayName: string;
        } | null;
    } | null;
    submissionCount?: number;
}

export interface SubmissionFieldValue {
    name: string;
    values: string[];
}

interface SubmissionProperty {
    name?: string;
    value?: unknown;
    values?: unknown;
}

export interface SubmissionFile {
    fieldName: string;
    fileName: string;
    fileUuid: string;
    filePath: string;
    fileUrl: string;
    mimeType: string | null;
    thumbnailUrl: string | null;
}

export interface SubmissionRow {
    uuid: string;
    path: string;
    name: string;
    created: string;
    origin: string | null;
    locale: string | null;
    referer: string | null;
    timeZone: string | null;
    fieldValues: SubmissionFieldValue[];
    files: SubmissionFile[];
}

export interface SubmissionQueryFilters {
    startDate?: string;
    endDate?: string;
}

const JCR_PROPERTY_PREFIXES = ['jcr:', 'j:', 'mix:'];

export function isUserProperty(name: string): boolean {
    return !JCR_PROPERTY_PREFIXES.some(prefix => name.startsWith(prefix));
}

function normalizePropertyValues(property: SubmissionProperty): string[] {
    if (Array.isArray(property.values) && property.values.length > 0) {
        return property.values
            .filter(value => value != null && value !== '')
            .map(value => String(value));
    }

    if (property.value == null || property.value === '') {
        return [];
    }

    return [String(property.value)];
}

/** The field types whose stored value is a date the reader should see in their own format. */
export type FieldValueKind = 'date' | 'datetime';

/**
 * What the results screen knows about the source form: the label of each field in
 * the UI language, the order in which the fields are displayed in the form, and the
 * fields whose values are dates (stored as the ISO strings the browser inputs post).
 */
export interface FormFields {
    labels: Map<string, string>;
    order: string[];
    kinds: Map<string, FieldValueKind>;
}

export const EMPTY_FORM_FIELDS: FormFields = {labels: new Map(), order: [], kinds: new Map()};

/**
 * Sorts items by the position of their field in the form. Fields the form no longer
 * has (deleted or renamed since the submission) keep their stored order, after the
 * known ones, so nothing disappears from the results.
 */
export function sortByFormOrder<T>(items: T[], getFieldName: (item: T) => string, order: string[]): T[] {
    if (order.length === 0) {
        return items;
    }

    const position = new Map(order.map((name, index) => [name, index]));
    return items
        .map((item, index) => ({item, index, rank: position.get(getFieldName(item)) ?? order.length + index}))
        .sort((a, b) => a.rank - b.rank)
        .map(({item}) => item);
}

/** Raw GraphQL node shapes: everything is read defensively, so 'unknown' leaves stay. */
type GqlFileNode = {
    name: string;
    uuid: string;
    path: string;
    url?: string;
    thumbnailUrl?: string | null;
    content?: {nodes?: Array<{mimeType?: {value?: string}}>};
};

export type GqlSubmissionNode = {
    uuid: string;
    path: string;
    name: string;
    created?: {value?: string};
    origin?: {value?: string};
    locale?: {value?: string};
    referer?: {value?: string};
    timeZone?: {value?: string};
    data?: {nodes?: Array<{properties?: SubmissionProperty[]}>};
    files?: {nodes?: Array<{children?: {nodes?: Array<{name: string; children?: {nodes?: GqlFileNode[]}}>}}>};
} & Record<string, unknown>;

export function parseSubmissionNode(node: GqlSubmissionNode, fieldOrder: string[] = []): SubmissionRow {
    const fieldValues: SubmissionFieldValue[] = [];
    const dataNode = node.data?.nodes?.[0];
    const properties = dataNode?.properties as SubmissionProperty[] | undefined;

    if (Array.isArray(properties)) {
        for (const prop of properties) {
            const name = typeof prop.name === 'string' ? prop.name : '';
            if (name && isUserProperty(name)) {
                fieldValues.push({
                    name,
                    values: normalizePropertyValues(prop)
                });
            }
        }
    }

    const files: SubmissionFile[] = [];
    const filesNode = node.files?.nodes?.[0];
    if (filesNode?.children?.nodes) {
        for (const fieldFolder of filesNode.children.nodes) {
            for (const fileNode of fieldFolder.children?.nodes ?? []) {
                files.push({
                    fieldName: fieldFolder.name,
                    fileName: fileNode.name,
                    fileUuid: fileNode.uuid,
                    filePath: fileNode.path,
                    fileUrl: fileNode.url ?? '',
                    mimeType: fileNode.content?.nodes?.[0]?.mimeType?.value ?? null,
                    thumbnailUrl: fileNode.thumbnailUrl ?? null
                });
            }
        }
    }

    return {
        uuid: node.uuid,
        path: node.path,
        name: node.name,
        created: node.created?.value ?? '',
        origin: node.origin?.value ?? null,
        locale: node.locale?.value ?? null,
        referer: node.referer?.value ?? null,
        timeZone: node.timeZone?.value ?? null,
        fieldValues: sortByFormOrder(fieldValues, field => field.name, fieldOrder),
        files: sortByFormOrder(files, file => file.fieldName, fieldOrder)
    };
}

const toJcrDateStart = (dateValue: string): string => {
    const [year, month, day] = dateValue.split('-').map(Number);
    return new Date(year, month - 1, day, 0, 0, 0, 0).toISOString();
};

const toJcrDateEndExclusive = (dateValue: string): string => {
    const [year, month, day] = dateValue.split('-').map(Number);
    return new Date(year, month - 1, day + 1, 0, 0, 0, 0).toISOString();
};

function buildSubmissionWhereClauses(formResultsPath: string, filters: SubmissionQueryFilters = {}): string[] {
    const whereClauses = [`ISDESCENDANTNODE(s, '${formResultsPath}/submissions')`];

    if (filters.startDate) {
        whereClauses.push(`s.[jcr:created] >= CAST('${toJcrDateStart(filters.startDate)}' AS DATE)`);
    }

    if (filters.endDate) {
        whereClauses.push(`s.[jcr:created] < CAST('${toJcrDateEndExclusive(filters.endDate)}' AS DATE)`);
    }

    return whereClauses;
}

export function buildSubmissionsQuery(
    formResultsPath: string,
    sortBy: string,
    sortDirection: string,
    filters: SubmissionQueryFilters = {}
): string {
    const orderDirection = sortDirection === 'ascending' ? 'ASC' : 'DESC';
    const orderColumn = sortBy === 'created' ? 's.[jcr:created]' : `s.[${sortBy}]`;
    const whereClauses = buildSubmissionWhereClauses(formResultsPath, filters);

    return `SELECT * FROM [fmdb:formSubmission] AS s WHERE ${whereClauses.join(' AND ')} ORDER BY ${orderColumn} ${orderDirection}`;
}

export function buildCountQuery(formResultsPath: string, filters: SubmissionQueryFilters = {}): string {
    const whereClauses = buildSubmissionWhereClauses(formResultsPath, filters);
    return `SELECT * FROM [fmdb:formSubmission] AS s WHERE ${whereClauses.join(' AND ')}`;
}

export function formatDate(isoDate: string): string {
    if (!isoDate) {
        return '';
    }

    try {
        const date = new Date(isoDate);
        return date.toLocaleString();
    } catch {
        return isoDate;
    }
}

const DATE_VALUE = /^(\d{4})-(\d{2})-(\d{2})$/;
// Seconds and a fraction are optional, as in the backend's format (HH:mm[:ss[.SSS]]); the
// fraction is matched but not read: the short time style shows none of it.
const DATETIME_VALUE = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d{1,3})?)?$/;

/**
 * Formats a stored field value for the reader when the field is a date or a datetime
 * field, in the browser's locale like the submission metadata. The inputs post wall-clock
 * strings without a zone ("2026-09-09", "2026-09-05T12:53"), and the results show them as
 * typed: the parts are carried in UTC and formatted in UTC, so the reader's own zone never
 * shifts them (`new Date("2026-09-09")` would read UTC midnight and show the previous day
 * west of Greenwich) nor rejects them (02:30 on a spring-forward day does not exist in the
 * reader's zone but did where the submitter typed it). Anything that does not parse, and
 * every other field kind, is shown as stored.
 *
 * A datetime is followed by the submitter's time zone when the submission recorded one
 * ("05/09/2026 12:53 (Europe/Paris)"): the value stays as typed, the zone says where 12:53
 * applies. A date has no zone, and a submission without one (posted outside a browser, or
 * stored before the zone was recorded) shows the value alone.
 *
 * The zone is the submitter's — where the text was typed — not a property of the value, which
 * the datetime-local input stores without one: a datetime meant for a fixed remote place (an
 * appointment at a venue in another zone) still shows the submitter's zone. The exports keep
 * the two apart, the raw value in its column and the zone in its own.
 */
export function formatFieldValue(value: string, kind: FieldValueKind | undefined, timeZone?: string | null): string {
    if (kind === 'date') {
        const date = wallClockDate(DATE_VALUE.exec(value));
        return date ? date.toLocaleDateString(undefined, {timeZone: 'UTC'}) : value;
    }

    if (kind === 'datetime') {
        const date = wallClockDate(DATETIME_VALUE.exec(value));
        if (!date) {
            return value;
        }

        const formatted = date.toLocaleString(undefined, {dateStyle: 'short', timeStyle: 'short', timeZone: 'UTC'});
        return timeZone ? `${formatted} (${timeZone})` : formatted;
    }

    return value;
}

/**
 * Builds the Date carrying the matched parts as UTC fields, or null when they do not
 * describe a date: Date silently rolls an out-of-range part over ("2026-13-45" becomes
 * February 2027), so every part is read back and compared. UTC has no daylight-saving
 * gap, so a valid wall-clock value always round-trips.
 */
function wallClockDate(match: RegExpExecArray | null): Date | null {
    if (!match) {
        return null;
    }

    const [year, month, day, hours = 0, minutes = 0, seconds = 0] = match.slice(1).map(part => Number(part ?? 0));
    // Not Date.UTC(): it maps the years 0-99 to 1900-1999, and "0099-01-01" is a valid date value.
    const date = new Date(0);
    date.setUTCFullYear(year, month - 1, day);
    date.setUTCHours(hours, minutes, seconds, 0);
    const roundTrips = date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
        && date.getUTCHours() === hours && date.getUTCMinutes() === minutes && date.getUTCSeconds() === seconds;
    return roundTrips ? date : null;
}

export function formatFileSize(bytes: number | null): string {
    if (bytes == null) {
        return '';
    }

    if (bytes < 1024) {
        return `${bytes} B`;
    }

    if (bytes < 1024 * 1024) {
        return `${(bytes / 1024).toFixed(1)} KB`;
    }

    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/**
 * Reads GET_FORM_FIELD_LABELS. The descendants come back in tree order, which is
 * the order the form displays its fields in (steps, then blocks, then fields).
 */
type GqlFormFieldNode = {name: string; displayName?: string; isDate?: boolean; isDatetime?: boolean} & Record<string, unknown>;
type GqlFormFieldsResponse = {
    jcr?: {nodeById?: {fields?: {nodes?: Array<{descendants?: {nodes?: Array<GqlFormFieldNode>}}>}}};
};

export function parseFormFields(data: GqlFormFieldsResponse | undefined): FormFields {
    const labels = new Map<string, string>();
    const order: string[] = [];
    const kinds = new Map<string, FieldValueKind>();
    const fieldListNodes = data?.jcr?.nodeById?.fields?.nodes;
    if (!Array.isArray(fieldListNodes) || fieldListNodes.length === 0) {
        return EMPTY_FORM_FIELDS;
    }

    const nodes = fieldListNodes[0]?.descendants?.nodes;
    if (!Array.isArray(nodes)) {
        return EMPTY_FORM_FIELDS;
    }

    for (const node of nodes) {
        if (typeof node.name !== 'string' || !node.name) {
            continue;
        }

        order.push(node.name);
        if (node.displayName && node.displayName !== node.name) {
            labels.set(node.name, node.displayName);
        }

        if (node.isDate === true) {
            kinds.set(node.name, 'date');
        } else if (node.isDatetime === true) {
            kinds.set(node.name, 'datetime');
        }
    }

    return {labels, order, kinds};
}

/** Typed access to Jahia's global UI context (window.contextJsParameters). */
export function uiContext(): {siteKey?: string; uilang?: string} {
    return (window as Window & {contextJsParameters?: {siteKey?: string; uilang?: string}}).contextJsParameters ?? {};
}
