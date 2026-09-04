import {useApolloClient} from '@apollo/client';
import {Add, Button, Close, Dropdown, Input, Link, Loader, Reload, Typography} from '@jahia/moonstone';
import React, {useEffect, useMemo, useRef, useState} from 'react';
import {useTranslation} from 'react-i18next';
import {CURRENT_NODE_BY_PATH, FORM_FIELDS_BY_PATH, LEAD_FIELDS} from './graphql';
import {
    autoMapRows,
    buildFormFieldOptions,
    extractConnectionId,
    extractCurrentNodePath,
    extractLanguage,
    extractWorkspace,
    findFormPath,
    isTypeMismatch,
    missingRequiredFields,
    parseMapping,
    unknownSalesforceFields,
    resolveRowField,
    serializeMapping,
    sortSalesforceFields
} from './LeadMapping.utils';
import type {
    FormFieldOption,
    GraphNode,
    MappingRow,
    MappingSource,
    SalesforceField,
    SelectorProps
} from './LeadMapping.types';
import './leadMapping.css';

/** Moonstone Dropdown option; the package does not re-export DropdownDataOption. */
type DropdownOption = {label: string; value?: string; description?: string; isDisabled?: boolean};

const generateRowId = (): string => Math.random().toString(36).substring(2, 10);

// Salesforce field types whose constant is best typed through a dedicated input.
const NUMERIC_SF_TYPES = new Set(['int', 'double', 'currency', 'percent']);

/** A stored value the option list does not know still needs a labelled entry. */
const withStoredOption = (options: DropdownOption[], stored: string | undefined): DropdownOption[] => {
    if (!stored || options.some(option => option.value === stored)) {
        return options;
    }

    return [...options, {label: stored, value: stored}];
};

/**
 * The constant of a row: a picklist dropdown when Salesforce lists the values, a
 * yes/no dropdown for booleans, a typed input otherwise.
 */
const ConstantValueEditor = ({
    inputId,
    sfField,
    sfType,
    value,
    readOnly,
    onValueChange
}: {
    inputId: string;
    sfField?: SalesforceField;
    sfType?: string;
    value: string;
    readOnly: boolean;
    onValueChange: (value: string) => void;
}) => {
    const {t} = useTranslation('formidable-salesforce');
    const type = sfField?.type ?? sfType ?? '';
    const picklist = sfField?.picklistValues ?? [];

    if ((type === 'picklist' || type === 'multipicklist') && picklist.length > 0) {
        const options = picklist.map(choice => ({label: choice.label || choice.value, value: choice.value}));
        if (type === 'multipicklist') {
            // Salesforce stores a multipicklist as its values joined with semicolons.
            const selected = value.split(';').filter(entry => entry !== '');
            return (
                <Dropdown
                    variant="outlined"
                    data={withStoredOption(options, undefined)}
                    hasSearch={options.length >= 5}
                    values={selected}
                    placeholder={t('leadMapping.selectPicklistValue')}
                    isDisabled={readOnly}
                    onChange={(_event, item) => {
                        if (!item.value) {
                            return;
                        }

                        const next = selected.includes(item.value)
                            ? selected.filter(entry => entry !== item.value)
                            : [...selected, item.value];
                        onValueChange(next.join(';'));
                    }}
                />
            );
        }

        return (
            <Dropdown
                variant="outlined"
                data={withStoredOption(options, value)}
                hasSearch={options.length >= 5}
                value={value || undefined}
                placeholder={t('leadMapping.selectPicklistValue')}
                isDisabled={readOnly}
                onChange={(_event, item) => onValueChange(item.value ?? '')}
            />
        );
    }

    if (type === 'boolean') {
        const options = [
            {label: t('leadMapping.booleanTrue'), value: 'true'},
            {label: t('leadMapping.booleanFalse'), value: 'false'}
        ];
        return (
            <Dropdown
                variant="outlined"
                data={withStoredOption(options, value)}
                value={value || undefined}
                placeholder={t('leadMapping.selectPicklistValue')}
                isDisabled={readOnly}
                onChange={(_event, item) => onValueChange(item.value ?? '')}
            />
        );
    }

    // yyyy-MM-dd is exactly the Salesforce date format; datetime stays free text (an
    // ISO 8601 instant with its zone, which the native local picker cannot produce).
    const inputType = type === 'date' ? 'date' : (NUMERIC_SF_TYPES.has(type) ? 'number' : 'text');

    return (
        <Input
            id={inputId}
            type={inputType}
            isReadOnly={readOnly}
            placeholder={t('leadMapping.constantPlaceholder')}
            title={value || undefined}
            aria-label={t('leadMapping.columns.value')}
            value={value}
            size="big"
            onChange={event => onValueChange(event.target.value)}
        />
    );
};

export const SalesforceLeadMappingCmp = (props: SelectorProps) => {
    const {field, id, value, onChange} = props;
    const {t} = useTranslation('formidable-salesforce');
    const client = useApolloClient();
    const readOnly = Boolean(props.readOnly || field.readOnly);

    const [formFields, setFormFields] = useState<FormFieldOption[]>([]);
    const [formLoading, setFormLoading] = useState(true);
    const [formError, setFormError] = useState<string | null>(null);
    const [sfFields, setSfFields] = useState<SalesforceField[]>([]);
    const [sfLoading, setSfLoading] = useState(false);
    const [sfError, setSfError] = useState<string | null>(null);
    // Incremented by the refresh button: re-runs the Salesforce query with the cache bypassed.
    const [refreshCount, setRefreshCount] = useState(0);

    const currentNodePath = extractCurrentNodePath(props);
    const language = extractLanguage(props);
    const workspace = extractWorkspace(props);
    const connectionId = extractConnectionId(props);
    const doc = useMemo(() => parseMapping(value), [value]);
    const rows = doc.rows;

    // Rows carry no identity of their own, and their position is not one either (a
    // removal shifts every row below). React keys come from this list, kept aligned
    // with the rows: appended for rows added, spliced on removal.
    const rowIdsRef = useRef<string[]>([]);
    const rowIds = rowIdsRef.current;
    while (rowIds.length < rows.length) {
        rowIds.push(generateRowId());
    }

    if (rowIds.length > rows.length) {
        rowIds.length = rows.length;
    }

    // (a) The enclosing form and its submittable fields.
    useEffect(() => {
        let cancelled = false;

        const loadFormFields = async () => {
            if (!currentNodePath) {
                setFormFields([]);
                setFormError(t('leadMapping.unresolvedContext'));
                setFormLoading(false);
                return;
            }

            setFormLoading(true);
            setFormError(null);
            let stage: 'form' | 'fields' = 'form';

            try {
                const currentNodeResult = await client.query<{
                    jcr?: {nodeByPath?: GraphNode | null} | null;
                }>({
                    query: CURRENT_NODE_BY_PATH,
                    variables: {path: currentNodePath, workspace},
                    fetchPolicy: 'network-only'
                });

                const formPath = findFormPath(currentNodeResult.data?.jcr?.nodeByPath);
                if (!formPath) {
                    throw new Error(`No fmdb:form ancestor for ${currentNodePath}`);
                }

                stage = 'fields';
                const formFieldsResult = await client.query<{
                    jcr?: {nodeByPath?: GraphNode | null} | null;
                }>({
                    query: FORM_FIELDS_BY_PATH,
                    variables: {path: formPath, workspace, language},
                    fetchPolicy: 'network-only'
                });

                if (!cancelled) {
                    setFormFields(buildFormFieldOptions(formFieldsResult.data?.jcr?.nodeByPath?.descendants?.nodes ?? []));
                }
            } catch (error) {
                if (!cancelled) {
                    console.error('[SalesforceLeadMappingCmp] failed to load form fields', error);
                    setFormFields([]);
                    setFormError(t(stage === 'form' ? 'leadMapping.formNotFound' : 'leadMapping.formFieldsLoadError'));
                }
            } finally {
                if (!cancelled) {
                    setFormLoading(false);
                }
            }
        };

        void loadFormFields();

        return () => {
            cancelled = true;
        };
    }, [client, currentNodePath, language, t, workspace]);

    // (b) The createable Lead fields of the selected connection; re-queried whenever
    // the sibling connection value changes.
    useEffect(() => {
        let cancelled = false;

        const loadSalesforceFields = async () => {
            if (!connectionId || !currentNodePath) {
                setSfFields([]);
                setSfError(null);
                setSfLoading(false);
                return;
            }

            setSfLoading(true);
            setSfError(null);

            try {
                const result = await client.query<{
                    formidableSalesforce?: {objectFields?: SalesforceField[] | null} | null;
                }>({
                    query: LEAD_FIELDS,
                    variables: {connectionId, contextPath: currentNodePath, refresh: refreshCount > 0},
                    fetchPolicy: 'network-only'
                });

                if (!cancelled) {
                    const fields = result.data?.formidableSalesforce?.objectFields ?? [];
                    setSfFields(fields.filter(sfField => sfField.createable !== false));
                }
            } catch (error) {
                if (!cancelled) {
                    console.error('[SalesforceLeadMappingCmp] failed to load Salesforce Lead fields', error);
                    setSfFields([]);
                    const message = error instanceof Error ? error.message : String(error);
                    setSfError(`${t('leadMapping.salesforceLoadError')}: ${message}`);
                }
            } finally {
                if (!cancelled) {
                    setSfLoading(false);
                }
            }
        };

        void loadSalesforceFields();

        return () => {
            cancelled = true;
        };
    }, [client, connectionId, currentNodePath, refreshCount, t]);

    const sortedSfFields = useMemo(() => sortSalesforceFields(sfFields), [sfFields]);
    const sfFieldsByName = useMemo(() => new Map(sfFields.map(sfField => [sfField.name, sfField])), [sfFields]);
    const sfOptions = useMemo<DropdownOption[]>(
        () => sortedSfFields.map(sfField => ({
            label: sfField.required ? `${sfField.label} *` : sfField.label,
            description: `${sfField.name} · ${sfField.type}`,
            value: sfField.name
        })),
        [sortedSfFields]
    );
    const formFieldOptions = useMemo<DropdownOption[]>(
        () => formFields.map(formField => ({label: formField.label, value: formField.id})),
        [formFields]
    );
    const sourceOptions: DropdownOption[] = [
        {label: t('leadMapping.sources.field'), value: 'field'},
        {label: t('leadMapping.sources.constant'), value: 'constant'}
    ];

    // The form field each row designates, resolved once per render (rows are few).
    const resolvedFields = useMemo(
        () => rows.map(row => resolveRowField(row, formFields)),
        [rows, formFields]
    );

    const missingRequired = useMemo(() => missingRequiredFields(sfFields, rows), [sfFields, rows]);
    const unknownFields = useMemo(() => unknownSalesforceFields(sfFields, rows), [sfFields, rows]);
    const typeHints = rows.flatMap((row, index) => {
        const formField = resolvedFields[index];
        const sfType = sfFieldsByName.get(row.sfField)?.type ?? row.sfType;
        if (!formField || !isTypeMismatch(sfType, formField.valueKind)) {
            return [];
        }

        return [t('leadMapping.typeHint', {
            sfField: sfFieldsByName.get(row.sfField)?.label ?? row.sfField,
            sfType,
            formField: formField.label,
            kind: t(`leadMapping.kinds.${formField.valueKind}`)
        })];
    });

    // Every write goes through here, and only from a user edit: the Content Editor
    // derives its dirtiness from the form values, so nothing may call onChange on load.
    const commitRows = (nextRows: MappingRow[]) => {
        onChange(serializeMapping({...doc, rows: nextRows}));
    };

    const updateRow = (index: number, patch: Partial<MappingRow>) => {
        commitRows(rows.map((row, rowIndex) => (rowIndex === index ? {...row, ...patch} : row)));
    };

    const handleSfFieldChange = (index: number, item: DropdownOption) => {
        const sfField = item.value ? sfFieldsByName.get(item.value) : undefined;
        updateRow(index, {sfField: item.value ?? '', sfType: sfField?.type ?? rows[index].sfType});
    };

    const handleSourceChange = (index: number, item: DropdownOption) => {
        const source = item.value as MappingSource | undefined;
        if (!source || source === rows[index].source) {
            return;
        }

        const row = {...rows[index], source};
        if (source === 'field') {
            delete row.value;
            row.fieldKey = '';
            row.fieldName = '';
            row.nodeId = '';
        } else {
            delete row.fieldKey;
            delete row.fieldName;
            delete row.nodeId;
            row.value = '';
        }

        commitRows(rows.map((current, rowIndex) => (rowIndex === index ? row : current)));
    };

    const handleFormFieldChange = (index: number, item: DropdownOption) => {
        const formField = formFields.find(candidate => candidate.id === item.value);
        if (!formField) {
            return;
        }

        updateRow(index, {fieldKey: formField.fieldKey ?? '', fieldName: formField.name, nodeId: formField.id});
    };

    const handleAddRow = () => {
        commitRows([...rows, {sfField: '', source: 'field', fieldKey: '', fieldName: '', nodeId: ''}]);
    };

    const handleRemoveRow = (index: number) => {
        rowIds.splice(index, 1);
        commitRows(rows.filter((_row, rowIndex) => rowIndex !== index));
    };

    const handleAutoMap = () => {
        const added = autoMapRows(sfFields, formFields, rows);
        if (added.length > 0) {
            commitRows([...rows, ...added]);
        }
    };

    if (formLoading) {
        return <Loader size="small"/>;
    }

    if (formError) {
        return (
            <Typography variant="body" className="fmdbsfdc-leadMappingError">{formError}</Typography>
        );
    }

    const renderStatus = () => {
        if (!connectionId) {
            return <Typography variant="caption" className="fmdbsfdc-leadMappingMuted">{t('leadMapping.noConnection')}</Typography>;
        }

        if (sfLoading) {
            return (
                <>
                    <Loader size="small"/>
                    <Typography variant="caption" className="fmdbsfdc-leadMappingMuted">{t('leadMapping.loadingSalesforce')}</Typography>
                </>
            );
        }

        if (sfError) {
            return <Typography variant="caption" className="fmdbsfdc-leadMappingError">{sfError}</Typography>;
        }

        if (sfFields.length === 0) {
            return <Typography variant="caption" className="fmdbsfdc-leadMappingMuted">{t('leadMapping.noSalesforceFields')}</Typography>;
        }

        return null;
    };

    const status = renderStatus();
    const canEditRows = !readOnly;
    const canAutoMap = canEditRows && sfFields.length > 0 && formFields.length > 0;

    return (
        <div className="fmdbsfdc-leadMapping flexFluid" data-sel-role="lead-mapping">
            {formFields.length === 0 && (
                <div className="fmdbsfdc-leadMappingStatus">
                    <Typography variant="caption" className="fmdbsfdc-leadMappingMuted">{t('leadMapping.noFormFields')}</Typography>
                </div>
            )}
            {status && <div className="fmdbsfdc-leadMappingStatus">{status}</div>}

            {rows.length > 0 && (
                <div className="fmdbsfdc-leadMappingGrid" role="table">
                    <Typography variant="caption" className="fmdbsfdc-leadMappingHeader">{t('leadMapping.columns.salesforceField')}</Typography>
                    <Typography variant="caption" className="fmdbsfdc-leadMappingHeader">{t('leadMapping.columns.source')}</Typography>
                    <Typography variant="caption" className="fmdbsfdc-leadMappingHeader">{t('leadMapping.columns.value')}</Typography>
                    <div className="fmdbsfdc-leadMappingHeader"/>

                    {rows.map((row, index) => {
                        const rowId = rowIds[index];
                        const usedSfFields = new Set(rows.filter((_r, i) => i !== index).map(other => other.sfField));
                        const rowSfOptions = withStoredOption(
                            sfOptions.filter(option => option.value === row.sfField || !usedSfFields.has(option.value ?? '')),
                            row.sfField
                        );
                        const usedFieldIds = new Set(
                            resolvedFields
                                .filter((_resolved, i) => i !== index)
                                .map(resolved => resolved?.id ?? '')
                                .filter(fieldId => fieldId !== '')
                        );
                        const resolvedField = resolvedFields[index];
                        const rowFormOptions = withStoredOption(
                            formFieldOptions.filter(option => option.value === resolvedField?.id || !usedFieldIds.has(option.value ?? '')),
                            // An unresolvable stored reference (the field was deleted) shows its name
                            // rather than an empty chip that reads as data loss.
                            resolvedField ? undefined : (row.fieldName || row.fieldKey || undefined)
                        );

                        return (
                            <div key={rowId} className="fmdbsfdc-leadMappingRow" data-sel-role="lead-mapping-row" role="row">
                                <div>
                                    <Dropdown
                                        variant="outlined"
                                        data={rowSfOptions}
                                        hasSearch={rowSfOptions.length >= 5}
                                        value={row.sfField || undefined}
                                        placeholder={t('leadMapping.selectSalesforceField')}
                                        isDisabled={readOnly}
                                        onChange={(_event, item) => handleSfFieldChange(index, item)}
                                    />
                                </div>
                                <div>
                                    <Dropdown
                                        variant="outlined"
                                        data={sourceOptions}
                                        value={row.source}
                                        isDisabled={readOnly}
                                        onChange={(_event, item) => handleSourceChange(index, item)}
                                    />
                                </div>
                                <div>
                                    {row.source === 'field' ? (
                                        <Dropdown
                                            variant="outlined"
                                            data={rowFormOptions}
                                            hasSearch={rowFormOptions.length >= 5}
                                            value={resolvedField?.id ?? (row.fieldName || row.fieldKey || undefined)}
                                            placeholder={t('leadMapping.selectFormField')}
                                            isDisabled={readOnly}
                                            onChange={(_event, item) => handleFormFieldChange(index, item)}
                                        />
                                    ) : (
                                        <ConstantValueEditor
                                            inputId={`${id}-constant-${rowId}`}
                                            sfField={sfFieldsByName.get(row.sfField)}
                                            sfType={row.sfType}
                                            value={row.value ?? ''}
                                            readOnly={readOnly}
                                            onValueChange={nextValue => updateRow(index, {value: nextValue})}
                                        />
                                    )}
                                </div>
                                <div className="fmdbsfdc-leadMappingRemove">
                                    <Button
                                        variant="ghost"
                                        icon={<Close/>}
                                        title={t('leadMapping.remove')}
                                        aria-label={t('leadMapping.remove')}
                                        isDisabled={readOnly}
                                        data-sel-role="lead-mapping-remove"
                                        onClick={() => handleRemoveRow(index)}
                                    />
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            {rows.length === 0 && (
                <Typography variant="caption" className="fmdbsfdc-leadMappingMuted">{t('leadMapping.noRows')}</Typography>
            )}

            <div className="fmdbsfdc-leadMappingActions">
                <Button
                    variant="outlined"
                    icon={<Add/>}
                    label={t('leadMapping.add')}
                    isDisabled={!canEditRows}
                    data-sel-role="lead-mapping-add"
                    onClick={handleAddRow}
                />
                <Button
                    variant="ghost"
                    icon={<Link/>}
                    label={t('leadMapping.autoMap')}
                    isDisabled={!canAutoMap}
                    data-sel-role="lead-mapping-automap"
                    onClick={handleAutoMap}
                />
                <Button
                    variant="ghost"
                    icon={<Reload/>}
                    label={t('leadMapping.refreshFields')}
                    title={t('leadMapping.refreshFieldsHint')}
                    isDisabled={!connectionId || sfLoading}
                    data-sel-role="lead-mapping-refresh"
                    onClick={() => setRefreshCount(count => count + 1)}
                />
            </div>

            {(missingRequired.length > 0 || unknownFields.length > 0 || typeHints.length > 0) && (
                <div className="flexCol" data-sel-role="lead-mapping-warnings">
                    {missingRequired.length > 0 && (
                        <Typography variant="caption" className="fmdbsfdc-leadMappingWarning">
                            {t('leadMapping.missingRequired', {fields: missingRequired.map(sfField => sfField.label).join(', ')})}
                        </Typography>
                    )}
                    {unknownFields.length > 0 && (
                        <Typography variant="caption" className="fmdbsfdc-leadMappingError" data-sel-role="lead-mapping-unknown">
                            {t('leadMapping.unknownFields', {fields: unknownFields.join(', ')})}
                        </Typography>
                    )}
                    {typeHints.map(hint => (
                        <Typography key={hint} variant="caption" className="fmdbsfdc-leadMappingWarning">{hint}</Typography>
                    ))}
                </div>
            )}
        </div>
    );
};

SalesforceLeadMappingCmp.displayName = 'SalesforceLeadMappingCmp';
