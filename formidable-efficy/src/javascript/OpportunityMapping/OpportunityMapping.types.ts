/**
 * Where a mapped Efficy property takes its value from: a form field of the
 * enclosing form, or a constant typed by the contributor.
 */
export type MappingSource = 'field' | 'constant' | 'today' | 'personByEmail';

/**
 * One line of the mapping table, as stored. This shape is the ONLY contract with the
 * Java side (fieldMapping property of fmdbeff:createOpportunityAction) — keep it exact.
 * Unknown properties are kept verbatim on re-serialization so a document authored
 * against a newer module version round-trips unchanged.
 */
export interface MappingRow {
    effField: string;
    effType?: string;
    effFieldType?: string;
    source: MappingSource;
    // 'field' rows: the three identifiers of the form field. fieldKey may be empty for
    // a field that has never been saved through the engine listeners; fieldName and
    // nodeId are always known.
    fieldKey?: string;
    fieldName?: string;
    nodeId?: string;
    // 'constant' rows
    value?: string;
    [extra: string]: unknown;
}

export interface MappingDocument {
    version: number;
    rows: MappingRow[];
    [extra: string]: unknown;
}

/** A Opportunity field as described by the formidableEfficy.objectFields GraphQL query. */
export interface EfficyField {
    name: string;
    label: string;
    /** Efficy type: string, number, date, datetime, enumeration, bool. */
    type: string;
    /** Efficy field type: text, textarea, select, radio, checkbox (multi-value), booleancheckbox, number, date... */
    fieldType?: string | null;
    length?: number | null;
    required: boolean;
    createable: boolean;
    picklistValues?: Array<{value: string; label: string}> | null;
}

/**
 * Shape of the values a form field produces, from the engine's semantic mixins
 * (fmdbmix:choiceField, dateField, numberField, booleanField, textField, emailField).
 */
export type FormValueKind = 'choice' | 'date' | 'number' | 'boolean' | 'text' | 'email';

export interface FormFieldOption {
    id: string;
    fieldKey?: string;
    name: string;
    path: string;
    label: string;
    type: string;
    valueKind?: FormValueKind;
}

export interface SelectorField {
    name?: string;
    readOnly?: boolean;
    node?: {path?: string; uuid?: string};
    path?: string;
    nodePath?: string;
}

export interface EditorContextLike {
    path?: string;
    uuid?: string;
    lang?: string;
    language?: string;
    uilang?: string;
    locale?: string;
    workspace?: string;
    siteInfo?: {
        defaultLanguage?: string;
    };
    nodeData?: {
        path?: string;
        uuid?: string;
        lang?: string;
        language?: string;
        workspace?: string;
    };
}

/** Props the Content Editor hands to a selectorType component. */
export interface SelectorProps {
    field: SelectorField;
    id: string;
    value?: string;
    readOnly?: boolean;
    onChange: (value: string) => void;
    editorContext?: EditorContextLike;
    context?: EditorContextLike;
    form?: {
        values?: Record<string, unknown>;
    };
}

export interface PropertyValue {
    name: string;
    value?: string | null;
    values?: string[] | null;
}

export interface GraphAncestorNode {
    uuid: string;
    name: string;
    path: string;
    primaryNodeType?: {name?: string | null} | null;
}

export interface GraphNode {
    uuid: string;
    name: string;
    path: string;
    displayName?: string | null;
    primaryNodeType?: {name?: string | null} | null;
    isChoiceField?: boolean;
    isDateField?: boolean;
    isNumberField?: boolean;
    isBooleanField?: boolean;
    isTextField?: boolean;
    isEmailField?: boolean;
    isFileField?: boolean;
    isNonSubmittable?: boolean;
    properties?: PropertyValue[] | null;
    ancestors?: GraphAncestorNode[] | null;
    descendants?: {nodes?: GraphNode[] | null} | null;
}
