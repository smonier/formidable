import type {
    EditorContextLike,
    FormFieldOption,
    FormValueKind,
    GraphNode,
    MappingDocument,
    MappingRow,
    MappingSource,
    SalesforceField,
    SelectorProps
} from './LeadMapping.types';

export const MAPPING_VERSION = 1;

/** Formik key of the sibling connection property on fmdbsfdc:createLeadAction. */
export const CONNECTION_FORM_KEY = 'fmdbsfdc:createLeadAction_connectionId';

export const emptyMapping = (): MappingDocument => ({version: MAPPING_VERSION, rows: []});

const isRecord = (candidate: unknown): candidate is Record<string, unknown> =>
    typeof candidate === 'object' && candidate !== null && !Array.isArray(candidate);

const asString = (candidate: unknown): string | undefined =>
    typeof candidate === 'string' ? candidate : undefined;

const normalizeRow = (raw: unknown): MappingRow | null => {
    if (!isRecord(raw)) {
        return null;
    }

    const sfField = asString(raw.sfField) ?? '';
    const declaredSource = asString(raw.source);
    // A row stored without a source is read from what it carries: a node reference
    // makes it a field row, anything else a constant.
    const source: MappingSource = declaredSource === 'field' || declaredSource === 'constant'
        ? declaredSource
        : (asString(raw.nodeId) || asString(raw.fieldKey) || asString(raw.fieldName) ? 'field' : 'constant');

    const row: MappingRow = {...raw, sfField, source};
    if (typeof raw.sfType !== 'string') {
        delete row.sfType;
    }

    if (source === 'field') {
        row.fieldKey = asString(raw.fieldKey) ?? '';
        row.fieldName = asString(raw.fieldName) ?? '';
        row.nodeId = asString(raw.nodeId) ?? '';
    } else {
        row.value = asString(raw.value) ?? '';
    }

    return row;
};

/**
 * Parses the stored property value. Accepts an empty/absent value, a bare array of rows
 * or the {version, rows} object; anything unreadable yields the empty document.
 * Unknown properties (document-level and row-level) are kept for re-serialization.
 */
export const parseMapping = (value?: string): MappingDocument => {
    if (!value || value.trim() === '') {
        return emptyMapping();
    }

    try {
        const parsed: unknown = JSON.parse(value);
        if (Array.isArray(parsed)) {
            return {
                version: MAPPING_VERSION,
                rows: parsed.map(normalizeRow).filter((row): row is MappingRow => row !== null)
            };
        }

        if (isRecord(parsed)) {
            const rows = Array.isArray(parsed.rows) ? parsed.rows : [];
            return {
                ...parsed,
                version: typeof parsed.version === 'number' ? parsed.version : MAPPING_VERSION,
                rows: rows.map(normalizeRow).filter((row): row is MappingRow => row !== null)
            };
        }

        return emptyMapping();
    } catch {
        return emptyMapping();
    }
};

/** Serializes the document in the stored shape; drops the keys the other source owns. */
export const serializeMapping = (doc: MappingDocument): string => {
    const rows = doc.rows.map(row => {
        const stored: MappingRow = {...row, sfField: row.sfField, source: row.source};
        if (row.source === 'field') {
            delete stored.value;
            stored.fieldKey = row.fieldKey ?? '';
            stored.fieldName = row.fieldName ?? '';
            stored.nodeId = row.nodeId ?? '';
        } else {
            delete stored.fieldKey;
            delete stored.fieldName;
            delete stored.nodeId;
            stored.value = row.value ?? '';
        }

        return stored;
    });

    return JSON.stringify({...doc, version: MAPPING_VERSION, rows});
};

export const extractEditorContext = (props: SelectorProps): EditorContextLike | undefined =>
    props.editorContext ?? props.context;

export const extractCurrentNodePath = (props: SelectorProps): string | undefined => {
    const editorContext = extractEditorContext(props);

    return props.field.node?.path
        ?? props.field.nodePath
        ?? props.field.path
        ?? editorContext?.nodeData?.path
        ?? editorContext?.path
        ?? undefined;
};

export const extractLanguage = (props: SelectorProps): string => {
    const editorContext = extractEditorContext(props);
    const fromWindow = (window as unknown as {contextJsParameters?: {uilang?: string}}).contextJsParameters?.uilang;

    return editorContext?.nodeData?.language
        ?? editorContext?.nodeData?.lang
        ?? editorContext?.language
        ?? editorContext?.lang
        ?? editorContext?.uilang
        ?? editorContext?.locale
        ?? fromWindow
        ?? 'en';
};

export const extractWorkspace = (props: SelectorProps): string => {
    const editorContext = extractEditorContext(props);
    return editorContext?.nodeData?.workspace ?? editorContext?.workspace ?? 'EDIT';
};

/** The sibling connection id, read from the Content Editor form values (string id). */
export const extractConnectionId = (props: SelectorProps): string => {
    const raw = props.form?.values?.[CONNECTION_FORM_KEY];
    if (typeof raw === 'string') {
        return raw.trim();
    }

    if (Array.isArray(raw) && typeof raw[0] === 'string') {
        return raw[0].trim();
    }

    return '';
};

/**
 * The enclosing form: the fmdb:form ancestor of the edited node — or the node itself,
 * should the editor ever hand the form path directly.
 */
export const findFormPath = (node?: GraphNode | null): string | undefined => {
    if (node?.primaryNodeType?.name === 'fmdb:form') {
        return node.path;
    }

    return node?.ancestors?.find(ancestor => ancestor.primaryNodeType?.name === 'fmdb:form')?.path;
};

// The declared value kind of a field node, from its semantic mixin. A well-formed type
// carries at most one; the order below just makes conflicts deterministic. Email is
// checked before text: an email field also declares the text mixin in some element sets.
const getDeclaredValueKind = (node: GraphNode): FormValueKind | undefined => {
    if (node.isChoiceField) return 'choice';
    if (node.isDateField) return 'date';
    if (node.isNumberField) return 'number';
    if (node.isBooleanField) return 'boolean';
    if (node.isEmailField) return 'email';
    if (node.isTextField) return 'text';
    return undefined;
};

/**
 * The submittable form fields a row may read from: every form element except the
 * non-submittable ones (layout, captions…) and file fields (not supported in v1).
 * Duplicate display names get :1, :2… suffixes so the dropdown stays unambiguous.
 */
export const buildFormFieldOptions = (nodes: GraphNode[] = []): FormFieldOption[] => {
    const options: FormFieldOption[] = nodes
        .filter(node => !node.isNonSubmittable && !node.isFileField && node.primaryNodeType?.name)
        .map(node => ({
            id: node.uuid,
            fieldKey: node.properties?.find(property => property.name === 'fieldKey')?.value ?? undefined,
            name: node.name,
            path: node.path,
            label: node.displayName ?? node.name,
            type: node.primaryNodeType?.name ?? '',
            valueKind: getDeclaredValueKind(node)
        }));

    const labelCounts = new Map<string, number>();
    for (const option of options) {
        labelCounts.set(option.label, (labelCounts.get(option.label) ?? 0) + 1);
    }

    const labelCounters = new Map<string, number>();
    for (const option of options) {
        if ((labelCounts.get(option.label) ?? 0) > 1) {
            const counter = (labelCounters.get(option.label) ?? 0) + 1;
            labelCounters.set(option.label, counter);
            option.label = `${option.label}:${counter}`;
        }
    }

    return options;
};

/**
 * The form field a stored row designates: the node id is the primary identity, then the
 * business key, then the node name (a field re-created under the same name).
 */
export const resolveRowField = (row: MappingRow, fields: FormFieldOption[]): FormFieldOption | undefined => {
    if (row.source !== 'field') {
        return undefined;
    }

    if (row.nodeId) {
        const byId = fields.find(field => field.id === row.nodeId);
        if (byId) {
            return byId;
        }
    }

    if (row.fieldKey) {
        const byKey = fields.find(field => field.fieldKey === row.fieldKey);
        if (byKey) {
            return byKey;
        }
    }

    if (row.fieldName) {
        return fields.find(field => field.name === row.fieldName);
    }

    return undefined;
};

/** Required fields first, then alphabetical by label, stable for equal keys. */
export const sortSalesforceFields = (fields: SalesforceField[]): SalesforceField[] =>
    [...fields].sort((a, b) => {
        if (a.required !== b.required) {
            return a.required ? -1 : 1;
        }

        return a.label.localeCompare(b.label);
    });

/** Lowercase, accent-free, alphanumeric only: "Prénom " and "prenom" compare equal. */
export const normalizeName = (name: string | undefined): string =>
    (name ?? '')
        .normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '')
        .toLowerCase()
        .replace(/[^a-z0-9]/g, '');

/**
 * Common form-field names per Salesforce Lead field (already normalized). The SF field
 * name and label themselves always match, so they are not repeated here.
 */
export const LEAD_FIELD_SYNONYMS: Record<string, string[]> = {
    Email: ['email', 'mail', 'courriel', 'emailaddress', 'adresseemail'],
    FirstName: ['firstname', 'prenom', 'givenname'],
    LastName: ['lastname', 'name', 'nom', 'surname', 'familyname', 'nomdefamille', 'fullname', 'nomcomplet'],
    Company: ['company', 'societe', 'entreprise', 'organization', 'organisation'],
    Phone: ['phone', 'tel', 'telephone', 'phonenumber', 'numerodetelephone'],
    MobilePhone: ['mobile', 'mobilephone', 'portable', 'cellphone', 'cell'],
    Title: ['title', 'fonction', 'jobtitle', 'poste', 'job'],
    Website: ['website', 'site', 'siteweb', 'url', 'web'],
    City: ['city', 'ville'],
    Country: ['country', 'pays'],
    State: ['state', 'region', 'etat', 'province'],
    Street: ['street', 'address', 'adresse', 'rue'],
    PostalCode: ['postalcode', 'zip', 'zipcode', 'codepostal'],
    Description: ['description', 'message', 'comment', 'comments', 'commentaire', 'commentaires'],
    Industry: ['industry', 'secteur', 'secteurdactivite'],
    Salutation: ['salutation', 'civilite']
};

const formFieldMatches = (field: FormFieldOption, sfField: SalesforceField): boolean => {
    const candidates = new Set([normalizeName(field.name), normalizeName(field.label), normalizeName(field.fieldKey)]);
    candidates.delete('');
    if (candidates.size === 0) {
        return false;
    }

    const targets = new Set([normalizeName(sfField.name), normalizeName(sfField.label), ...(LEAD_FIELD_SYNONYMS[sfField.name] ?? [])]);
    for (const candidate of candidates) {
        if (targets.has(candidate)) {
            return true;
        }
    }

    // Any email-typed form field feeds Email when no name matched.
    return sfField.name === 'Email' && field.valueKind === 'email';
};

/**
 * Rows to ADD for every unmapped Salesforce field with a same-named (or synonym) form
 * field not used by another row. Existing rows are never overwritten; required
 * Salesforce fields are matched first so an ambiguous "name" lands on LastName.
 */
export const autoMapRows = (
    sfFields: SalesforceField[],
    formFields: FormFieldOption[],
    existingRows: MappingRow[]
): MappingRow[] => {
    const mappedSfFields = new Set(existingRows.map(row => row.sfField));
    const usedNodeIds = new Set(
        existingRows.map(row => resolveRowField(row, formFields)?.id ?? row.nodeId ?? '').filter(id => id !== '')
    );
    const added: MappingRow[] = [];

    for (const sfField of sortSalesforceFields(sfFields)) {
        if (mappedSfFields.has(sfField.name)) {
            continue;
        }

        const match = formFields.find(field => !usedNodeIds.has(field.id) && formFieldMatches(field, sfField));
        if (!match) {
            continue;
        }

        usedNodeIds.add(match.id);
        mappedSfFields.add(sfField.name);
        added.push(fieldRow(sfField, match));
    }

    return added;
};

export const fieldRow = (sfField: SalesforceField, formField: FormFieldOption): MappingRow => ({
    sfField: sfField.name,
    sfType: sfField.type,
    source: 'field',
    fieldKey: formField.fieldKey ?? '',
    fieldName: formField.name,
    nodeId: formField.id
});

// Salesforce types whose values a form field must produce in a specific kind. Text
// types accept anything (a number or a date is a fine string), so they carry no entry.
const EXPECTED_KINDS: Record<string, FormValueKind[]> = {
    boolean: ['boolean'],
    int: ['number'],
    double: ['number'],
    currency: ['number'],
    percent: ['number'],
    date: ['date'],
    datetime: ['date']
};

/**
 * Whether the form field's value kind is at odds with the Salesforce field type — a
 * hint only (Salesforce coerces "42" to an int; it rejects "hello"). Unknown kinds
 * (a field type without a semantic mixin) are never flagged.
 */
export const isTypeMismatch = (sfType: string | undefined, valueKind: FormValueKind | undefined): boolean => {
    if (!sfType || !valueKind) {
        return false;
    }

    const expected = EXPECTED_KINDS[sfType];
    return expected !== undefined && !expected.includes(valueKind);
};

/** Labels of the required Salesforce fields no row maps. */
export const missingRequiredFields = (sfFields: SalesforceField[], rows: MappingRow[]): SalesforceField[] => {
    const mapped = new Set(rows.map(row => row.sfField));
    return sfFields.filter(field => field.required && !mapped.has(field.name));
};

/**
 * Rows whose Salesforce field the selected connection does not expose (typo, field removed,
 * no field-level security for the integration user, or a mapping authored against another
 * org). Salesforce rejects the whole lead for such a field, so the editor flags them.
 */
export const unknownSalesforceFields = (sfFields: SalesforceField[], rows: MappingRow[]): string[] => {
    if (sfFields.length === 0) {
        return [];
    }

    const known = new Set(sfFields.map(field => field.name));
    return rows.map(row => row.sfField).filter(name => name !== '' && !known.has(name));
};
