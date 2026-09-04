import type {
    EditorContextLike,
    FormFieldOption,
    FormValueKind,
    GraphNode,
    MappingDocument,
    MappingRow,
    MappingSource,
    HubspotField,
    SelectorProps
} from './ContactMapping.types';

export const MAPPING_VERSION = 1;

/** Formik key of the sibling connection property on fmdbhs:createContactAction. */
export const CONNECTION_FORM_KEY = 'fmdbhs:createContactAction_connectionId';

export const emptyMapping = (): MappingDocument => ({version: MAPPING_VERSION, rows: []});

const isRecord = (candidate: unknown): candidate is Record<string, unknown> =>
    typeof candidate === 'object' && candidate !== null && !Array.isArray(candidate);

const asString = (candidate: unknown): string | undefined =>
    typeof candidate === 'string' ? candidate : undefined;

const normalizeRow = (raw: unknown): MappingRow | null => {
    if (!isRecord(raw)) {
        return null;
    }

    const hsProperty = asString(raw.hsProperty) ?? '';
    const declaredSource = asString(raw.source);
    // A row stored without a source is read from what it carries: a node reference
    // makes it a field row, anything else a constant.
    const source: MappingSource = declaredSource === 'field' || declaredSource === 'constant'
        ? declaredSource
        : (asString(raw.nodeId) || asString(raw.fieldKey) || asString(raw.fieldName) ? 'field' : 'constant');

    const row: MappingRow = {...raw, hsProperty, source};
    if (typeof raw.hsType !== 'string') {
        delete row.hsType;
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
        const stored: MappingRow = {...row, hsProperty: row.hsProperty, source: row.source};
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

/** Alphabetical by label (HubSpot has no required contact property). */
export const sortHubspotFields = (fields: HubspotField[]): HubspotField[] =>
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
 * Common form-field names per HubSpot contact property (already normalized). The property
 * name and label themselves always match, so they are not repeated here.
 */
export const CONTACT_FIELD_SYNONYMS: Record<string, string[]> = {
    email: ['email', 'mail', 'courriel', 'emailaddress', 'adresseemail'],
    firstname: ['firstname', 'prenom', 'givenname'],
    lastname: ['lastname', 'name', 'nom', 'surname', 'familyname', 'nomdefamille', 'fullname', 'nomcomplet'],
    company: ['company', 'societe', 'entreprise', 'organization', 'organisation'],
    phone: ['phone', 'tel', 'telephone', 'phonenumber', 'numerodetelephone'],
    mobilephone: ['mobile', 'mobilephone', 'portable', 'cellphone', 'cell'],
    jobtitle: ['title', 'jobtitle', 'fonction', 'poste', 'job'],
    website: ['website', 'site', 'siteweb', 'url', 'web'],
    city: ['city', 'ville'],
    country: ['country', 'pays'],
    state: ['state', 'region', 'etat', 'province'],
    address: ['street', 'address', 'adresse', 'rue'],
    zip: ['postalcode', 'zip', 'zipcode', 'codepostal'],
    message: ['message', 'comment', 'comments', 'commentaire', 'commentaires', 'description'],
    industry: ['industry', 'secteur', 'secteurdactivite'],
    salutation: ['salutation', 'civilite']
};

const formFieldMatches = (field: FormFieldOption, hsProperty: HubspotField): boolean => {
    const candidates = new Set([normalizeName(field.name), normalizeName(field.label), normalizeName(field.fieldKey)]);
    candidates.delete('');
    if (candidates.size === 0) {
        return false;
    }

    const targets = new Set([normalizeName(hsProperty.name), normalizeName(hsProperty.label), ...(CONTACT_FIELD_SYNONYMS[hsProperty.name] ?? [])]);
    for (const candidate of candidates) {
        if (targets.has(candidate)) {
            return true;
        }
    }

    // Any email-typed form field feeds Email when no name matched.
    return hsProperty.name === 'email' && field.valueKind === 'email';
};

/**
 * Rows to ADD for every unmapped HubSpot property with a same-named (or synonym) form
 * field not used by another row. Existing rows are never overwritten; required
 * properties are matched in label order, so an ambiguous "name" lands on lastname.
 */
export const autoMapRows = (
    hsProperties: HubspotField[],
    formFields: FormFieldOption[],
    existingRows: MappingRow[]
): MappingRow[] => {
    const mappedSfFields = new Set(existingRows.map(row => row.hsProperty));
    const usedNodeIds = new Set(
        existingRows.map(row => resolveRowField(row, formFields)?.id ?? row.nodeId ?? '').filter(id => id !== '')
    );
    const added: MappingRow[] = [];

    for (const hsProperty of sortHubspotFields(hsProperties)) {
        if (mappedSfFields.has(hsProperty.name)) {
            continue;
        }

        const match = formFields.find(field => !usedNodeIds.has(field.id) && formFieldMatches(field, hsProperty));
        if (!match) {
            continue;
        }

        usedNodeIds.add(match.id);
        mappedSfFields.add(hsProperty.name);
        added.push(fieldRow(hsProperty, match));
    }

    return added;
};

export const fieldRow = (hsProperty: HubspotField, formField: FormFieldOption): MappingRow => ({
    hsProperty: hsProperty.name,
    hsType: hsProperty.type,
    hsFieldType: hsProperty.fieldType ?? undefined,
    source: 'field',
    fieldKey: formField.fieldKey ?? '',
    fieldName: formField.name,
    nodeId: formField.id
});

// HubSpot types whose values a form field must produce in a specific kind. String and
// enumeration types accept anything, so they carry no entry.
const EXPECTED_KINDS: Record<string, FormValueKind[]> = {
    bool: ['boolean'],
    number: ['number'],
    date: ['date'],
    datetime: ['date']
};

/**
 * Whether the form field's value kind is at odds with the HubSpot property type — a
 * hint only (HubSpot accepts "42" for a number; it rejects "hello"). Unknown kinds
 * (a field type without a semantic mixin) are never flagged.
 */
export const isTypeMismatch = (hsType: string | undefined, valueKind: FormValueKind | undefined): boolean => {
    if (!hsType || !valueKind) {
        return false;
    }

    const expected = EXPECTED_KINDS[hsType];
    return expected !== undefined && !expected.includes(valueKind);
};

/** Labels of the required HubSpot properties no row maps (none for contacts today). */
export const missingRequiredFields = (hsProperties: HubspotField[], rows: MappingRow[]): HubspotField[] => {
    const mapped = new Set(rows.map(row => row.hsProperty));
    return hsProperties.filter(field => field.required && !mapped.has(field.name));
};

/**
 * Rows whose HubSpot property the selected connection does not expose (typo, property removed,
 * no field-level security for the integration user, or a mapping authored against another
 * portal). HubSpot rejects the whole contact for such a property, so the editor flags them.
 */
export const unknownHubspotFields = (hsProperties: HubspotField[], rows: MappingRow[]): string[] => {
    if (hsProperties.length === 0) {
        return [];
    }

    const known = new Set(hsProperties.map(field => field.name));
    return rows.map(row => row.hsProperty).filter(name => name !== '' && !known.has(name));
};
