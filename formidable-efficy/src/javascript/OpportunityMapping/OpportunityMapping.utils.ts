import type {
    EditorContextLike,
    FormFieldOption,
    FormValueKind,
    GraphNode,
    MappingDocument,
    MappingRow,
    MappingSource,
    EfficyField,
    SelectorProps
} from './OpportunityMapping.types';

export const MAPPING_VERSION = 1;

/** Formik key of the sibling connection property on fmdbeff:createOpportunityAction. */
export const CONNECTION_FORM_KEY = 'fmdbeff:createOpportunityAction_connectionId';

export const emptyMapping = (): MappingDocument => ({version: MAPPING_VERSION, rows: []});

const isRecord = (candidate: unknown): candidate is Record<string, unknown> =>
    typeof candidate === 'object' && candidate !== null && !Array.isArray(candidate);

const asString = (candidate: unknown): string | undefined =>
    typeof candidate === 'string' ? candidate : undefined;

const normalizeRow = (raw: unknown): MappingRow | null => {
    if (!isRecord(raw)) {
        return null;
    }

    const effField = asString(raw.effField) ?? '';
    const declaredSource = asString(raw.source);
    // A row stored without a source is read from what it carries: a node reference
    // makes it a field row, anything else a constant.
    const source: MappingSource = declaredSource === 'field' || declaredSource === 'constant' || declaredSource === 'today' || declaredSource === 'personByEmail'
        ? declaredSource
        : (asString(raw.nodeId) || asString(raw.fieldKey) || asString(raw.fieldName) ? 'field' : 'constant');

    const row: MappingRow = {...raw, effField, source};
    if (typeof raw.effType !== 'string') {
        delete row.effType;
    }

    if (source === 'field' || source === 'personByEmail') {
        row.fieldKey = asString(raw.fieldKey) ?? '';
        row.fieldName = asString(raw.fieldName) ?? '';
        row.nodeId = asString(raw.nodeId) ?? '';
    } else if (source === 'constant') {
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
        const stored: MappingRow = {...row, effField: row.effField, source: row.source};
        if (row.source === 'field' || row.source === 'personByEmail') {
            delete stored.value;
            stored.fieldKey = row.fieldKey ?? '';
            stored.fieldName = row.fieldName ?? '';
            stored.nodeId = row.nodeId ?? '';
        } else if (row.source === 'constant') {
            delete stored.fieldKey;
            delete stored.fieldName;
            delete stored.nodeId;
            stored.value = row.value ?? '';
        } else {
            delete stored.value;
            delete stored.fieldKey;
            delete stored.fieldName;
            delete stored.nodeId;
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
    if (row.source !== 'field' && row.source !== 'personByEmail') {
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

/** Alphabetical by label (Efficy has no required opportunity field). */
export const sortEfficyFields = (fields: EfficyField[]): EfficyField[] =>
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
 * Common form-field names per Efficy opportunity field (already normalized). The property
 * name and label themselves always match, so they are not repeated here.
 */
export const OPPORTUNITY_FIELD_SYNONYMS: Record<string, string[]> = {
    OppTitle: ['title', 'titre', 'subject', 'sujet', 'objet', 'opportunity', 'opportunite', 'company', 'societe', 'entreprise'],
    OppDetail: ['detail', 'details', 'description', 'message', 'comment', 'comments', 'commentaire', 'commentaires', 'besoin', 'need'],
    OppStake: ['amount', 'montant', 'budget', 'stake', 'enjeu', 'price', 'prix'],
    OppDate: ['date', 'signdate', 'datesignature', 'echeance'],
    OppNumRef: ['reference', 'ref', 'numref', 'numero', 'number']
};

const formFieldMatches = (field: FormFieldOption, effField: EfficyField): boolean => {
    const candidates = new Set([normalizeName(field.name), normalizeName(field.label), normalizeName(field.fieldKey)]);
    candidates.delete('');
    if (candidates.size === 0) {
        return false;
    }

    const targets = new Set([normalizeName(effField.name), normalizeName(effField.label), ...(OPPORTUNITY_FIELD_SYNONYMS[effField.name] ?? [])]);
    for (const candidate of candidates) {
        if (targets.has(candidate)) {
            return true;
        }
    }

    // Any email-typed form field feeds Email when no name matched.
    return false;
};

/**
 * Rows to ADD for every unmapped Efficy property with a same-named (or synonym) form
 * field not used by another row. Existing rows are never overwritten; required
 * properties are matched in label order, so an ambiguous "name" lands on lastname.
 */
export const autoMapRows = (
    effFields: EfficyField[],
    formFields: FormFieldOption[],
    existingRows: MappingRow[]
): MappingRow[] => {
    const mappedSfFields = new Set(existingRows.map(row => row.effField));
    const usedNodeIds = new Set(
        existingRows.map(row => resolveRowField(row, formFields)?.id ?? row.nodeId ?? '').filter(id => id !== '')
    );
    const added: MappingRow[] = [];

    for (const effField of sortEfficyFields(effFields)) {
        if (mappedSfFields.has(effField.name)) {
            continue;
        }

        // The person of the opportunity is resolved by e-deal from the submitted email.
        if (effField.name.endsWith('PerID') && effField.type === 'reference') {
            const emailField = formFields.find(field => !usedNodeIds.has(field.id) && field.valueKind === 'email');
            if (emailField) {
                usedNodeIds.add(emailField.id);
                mappedSfFields.add(effField.name);
                added.push({...fieldRow(effField, emailField), source: 'personByEmail'});
            }

            continue;
        }

        // Dates default to the submission day when no date field matches by name.
        if (effField.type === 'date' && !formFields.some(field => field.valueKind === 'date' && !usedNodeIds.has(field.id) && formFieldMatches(field, effField))) {
            mappedSfFields.add(effField.name);
            added.push({effField: effField.name, effType: effField.type, source: 'today'});
            continue;
        }

        const match = formFields.find(field => !usedNodeIds.has(field.id) && formFieldMatches(field, effField));
        if (!match) {
            continue;
        }

        usedNodeIds.add(match.id);
        mappedSfFields.add(effField.name);
        added.push(fieldRow(effField, match));
    }

    return added;
};

export const fieldRow = (effField: EfficyField, formField: FormFieldOption): MappingRow => ({
    effField: effField.name,
    effType: effField.type,
    effFieldType: effField.fieldType ?? undefined,
    source: 'field',
    fieldKey: formField.fieldKey ?? '',
    fieldName: formField.name,
    nodeId: formField.id
});

// Efficy types whose values a form field must produce in a specific kind. String and
// enumeration types accept anything, so they carry no entry.
const EXPECTED_KINDS: Record<string, FormValueKind[]> = {
    boolean: ['boolean'],
    number: ['number'],
    date: ['date'],
    datetime: ['date']
};

/**
 * Whether the form field's value kind is at odds with the Efficy property type — a
 * hint only (Efficy accepts "42" for a number; it rejects "hello"). Unknown kinds
 * (a field type without a semantic mixin) are never flagged.
 */
export const isTypeMismatch = (effType: string | undefined, valueKind: FormValueKind | undefined): boolean => {
    if (!effType || !valueKind) {
        return false;
    }

    const expected = EXPECTED_KINDS[effType];
    return expected !== undefined && !expected.includes(valueKind);
};

/** Labels of the required Efficy properties no row maps (none for opportunities today). */
export const missingRequiredFields = (effFields: EfficyField[], rows: MappingRow[]): EfficyField[] => {
    const mapped = new Set(rows.map(row => row.effField));
    return effFields.filter(field => field.required && !mapped.has(field.name));
};

/**
 * Rows whose Efficy property the selected connection does not expose (typo, property removed,
 * no field-level security for the integration user, or a mapping authored against another
 * portal). Efficy rejects the whole opportunity for such a property, so the editor flags them.
 */
export const unknownEfficyFields = (effFields: EfficyField[], rows: MappingRow[]): string[] => {
    if (effFields.length === 0) {
        return [];
    }

    const known = new Set(effFields.map(field => field.name));
    return rows.map(row => row.effField).filter(name => name !== '' && !known.has(name));
};
