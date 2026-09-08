import {useApolloClient} from '@apollo/client';
import {Add, Button, Close, Dropdown, Input, Link, Loader, Reload, Typography} from '@jahia/moonstone';
import React, {useEffect, useMemo, useRef, useState} from 'react';
import {useTranslation} from 'react-i18next';
import {CURRENT_NODE_BY_PATH, FORM_FIELDS_BY_PATH, OPPORTUNITY_FIELDS} from './graphql';
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
    unknownEfficyFields,
    resolveRowField,
    serializeMapping,
    sortEfficyFields
} from './OpportunityMapping.utils';
import type {
    FormFieldOption,
    GraphNode,
    MappingRow,
    MappingSource,
    EfficyField,
    SelectorProps
} from './OpportunityMapping.types';
import './opportunityMapping.css';

/** Moonstone Dropdown option; the package does not re-export DropdownDataOption. */
type DropdownOption = {label: string; value?: string; description?: string; isDisabled?: boolean};

const generateRowId = (): string => Math.random().toString(36).substring(2, 10);

// Efficy property types whose constant is best typed through a dedicated input.
const NUMERIC_EFF_TYPES = new Set(['number']);

/** A stored value the option list does not know still needs a labelled entry. */
const withStoredOption = (options: DropdownOption[], stored: string | undefined): DropdownOption[] => {
    if (!stored || options.some(option => option.value === stored)) {
        return options;
    }

    return [...options, {label: stored, value: stored}];
};

/**
 * The constant of a row: a picklist dropdown when Efficy lists the values, a
 * yes/no dropdown for booleans, a typed input otherwise.
 */
const ConstantValueEditor = ({
    inputId,
    effField,
    effType,
    value,
    readOnly,
    onValueChange
}: {
    inputId: string;
    effField?: EfficyField;
    effType?: string;
    value: string;
    readOnly: boolean;
    onValueChange: (value: string) => void;
}) => {
    const {t} = useTranslation('formidable-efficy');
    const type = effField?.type ?? effType ?? '';
    const picklist = effField?.picklistValues ?? [];

    if ((type === 'referential' || type === 'referential-multi') && picklist.length > 0) {
        const options = picklist.map(choice => ({label: choice.label || choice.value, value: choice.value}));
        if (type === 'referential-multi') {
            // Efficy stores a multi-value enumeration as its values joined with semicolons.
            const selected = value.split(';').filter(entry => entry !== '');
            return (
                <Dropdown
                    variant="outlined"
                    data={withStoredOption(options, undefined)}
                    hasSearch={options.length >= 5}
                    values={selected}
                    placeholder={t('opportunityMapping.selectPicklistValue')}
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
                placeholder={t('opportunityMapping.selectPicklistValue')}
                isDisabled={readOnly}
                onChange={(_event, item) => onValueChange(item.value ?? '')}
            />
        );
    }

    if (type === 'boolean') {
        const options = [
            {label: t('opportunityMapping.booleanTrue'), value: 'true'},
            {label: t('opportunityMapping.booleanFalse'), value: 'false'}
        ];
        return (
            <Dropdown
                variant="outlined"
                data={withStoredOption(options, value)}
                value={value || undefined}
                placeholder={t('opportunityMapping.selectPicklistValue')}
                isDisabled={readOnly}
                onChange={(_event, item) => onValueChange(item.value ?? '')}
            />
        );
    }

    // yyyy-MM-dd is exactly the Efficy date format; datetime stays free text (an
    // ISO 8601 instant with its zone, which the native local picker cannot produce).
    const inputType = type === 'date' ? 'date' : (NUMERIC_EFF_TYPES.has(type) ? 'number' : 'text');

    return (
        <Input
            id={inputId}
            type={inputType}
            isReadOnly={readOnly}
            placeholder={t('opportunityMapping.constantPlaceholder')}
            title={value || undefined}
            aria-label={t('opportunityMapping.columns.value')}
            value={value}
            size="big"
            onChange={event => onValueChange(event.target.value)}
        />
    );
};

export const EfficyOpportunityMappingCmp = (props: SelectorProps) => {
    const {field, id, value, onChange} = props;
    const {t} = useTranslation('formidable-efficy');
    const client = useApolloClient();
    const readOnly = Boolean(props.readOnly || field.readOnly);

    const [formFields, setFormFields] = useState<FormFieldOption[]>([]);
    const [formLoading, setFormLoading] = useState(true);
    const [formError, setFormError] = useState<string | null>(null);
    const [effFields, setEffFields] = useState<EfficyField[]>([]);
    const [effLoading, setEffLoading] = useState(false);
    const [effError, setEffError] = useState<string | null>(null);
    // Incremented by the refresh button: re-runs the Efficy query with the cache bypassed.
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
                setFormError(t('opportunityMapping.unresolvedContext'));
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
                    console.error('[EfficyOpportunityMappingCmp] failed to load form fields', error);
                    setFormFields([]);
                    setFormError(t(stage === 'form' ? 'opportunityMapping.formNotFound' : 'opportunityMapping.formFieldsLoadError'));
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

    // (b) The createable Opportunity fields of the selected connection; re-queried whenever
    // the sibling connection value changes.
    useEffect(() => {
        let cancelled = false;

        const loadEfficyFields = async () => {
            if (!connectionId || !currentNodePath) {
                setEffFields([]);
                setEffError(null);
                setEffLoading(false);
                return;
            }

            setEffLoading(true);
            setEffError(null);

            try {
                const result = await client.query<{
                    formidableEfficy?: {objectFields?: EfficyField[] | null} | null;
                }>({
                    query: OPPORTUNITY_FIELDS,
                    variables: {connectionId, contextPath: currentNodePath, refresh: refreshCount > 0},
                    fetchPolicy: 'network-only'
                });

                if (!cancelled) {
                    const fields = result.data?.formidableEfficy?.objectFields ?? [];
                    setEffFields(fields.filter(effField => effField.createable !== false));
                }
            } catch (error) {
                if (!cancelled) {
                    console.error('[EfficyOpportunityMappingCmp] failed to load Efficy Opportunity fields', error);
                    setEffFields([]);
                    const message = error instanceof Error ? error.message : String(error);
                    setEffError(`${t('opportunityMapping.efficyLoadError')}: ${message}`);
                }
            } finally {
                if (!cancelled) {
                    setEffLoading(false);
                }
            }
        };

        void loadEfficyFields();

        return () => {
            cancelled = true;
        };
    }, [client, connectionId, currentNodePath, refreshCount, t]);

    const sortedEffFields = useMemo(() => sortEfficyFields(effFields), [effFields]);
    const effFieldsByName = useMemo(() => new Map(effFields.map(effField => [effField.name, effField])), [effFields]);
    const effOptions = useMemo<DropdownOption[]>(
        () => sortedEffFields.map(effField => ({
            label: effField.required ? `${effField.label} *` : effField.label,
            description: `${effField.name} · ${effField.fieldType ?? effField.type}`,
            value: effField.name
        })),
        [sortedEffFields]
    );
    const formFieldOptions = useMemo<DropdownOption[]>(
        () => formFields.map(formField => ({label: formField.label, value: formField.id})),
        [formFields]
    );
    const sourceOptions: DropdownOption[] = [
        {label: t('opportunityMapping.sources.field'), value: 'field'},
        {label: t('opportunityMapping.sources.constant'), value: 'constant'},
        {label: t('opportunityMapping.sources.today'), value: 'today'},
        {label: t('opportunityMapping.sources.personByEmail'), value: 'personByEmail'}
    ];

    // The form field each row designates, resolved once per render (rows are few).
    const resolvedFields = useMemo(
        () => rows.map(row => resolveRowField(row, formFields)),
        [rows, formFields]
    );

    const missingRequired = useMemo(() => missingRequiredFields(effFields, rows), [effFields, rows]);
    const unknownFields = useMemo(() => unknownEfficyFields(effFields, rows), [effFields, rows]);
    const typeHints = rows.flatMap((row, index) => {
        const formField = resolvedFields[index];
        const effType = effFieldsByName.get(row.effField)?.type ?? row.effType;
        if (!formField || !isTypeMismatch(effType, formField.valueKind)) {
            return [];
        }

        return [t('opportunityMapping.typeHint', {
            effField: effFieldsByName.get(row.effField)?.label ?? row.effField,
            effType,
            formField: formField.label,
            kind: t(`opportunityMapping.kinds.${formField.valueKind}`)
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
        const effField = item.value ? effFieldsByName.get(item.value) : undefined;
        updateRow(index, {effField: item.value ?? '', effType: effField?.type ?? rows[index].effType, effFieldType: effField?.fieldType ?? rows[index].effFieldType});
    };

    const handleSourceChange = (index: number, item: DropdownOption) => {
        const source = item.value as MappingSource | undefined;
        if (!source || source === rows[index].source) {
            return;
        }

        const row = {...rows[index], source};
        if (source === 'field' || source === 'personByEmail') {
            delete row.value;
            row.fieldKey = row.fieldKey ?? '';
            row.fieldName = row.fieldName ?? '';
            row.nodeId = row.nodeId ?? '';
        } else if (source === 'constant') {
            delete row.fieldKey;
            delete row.fieldName;
            delete row.nodeId;
            row.value = '';
        } else {
            delete row.value;
            delete row.fieldKey;
            delete row.fieldName;
            delete row.nodeId;
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
        commitRows([...rows, {effField: '', source: 'field', fieldKey: '', fieldName: '', nodeId: ''}]);
    };

    const handleRemoveRow = (index: number) => {
        rowIds.splice(index, 1);
        commitRows(rows.filter((_row, rowIndex) => rowIndex !== index));
    };

    const handleAutoMap = () => {
        const added = autoMapRows(effFields, formFields, rows);
        if (added.length > 0) {
            commitRows([...rows, ...added]);
        }
    };

    if (formLoading) {
        return <Loader size="small"/>;
    }

    if (formError) {
        return (
            <Typography variant="body" className="fmdbeff-opportunityMappingError">{formError}</Typography>
        );
    }

    const renderStatus = () => {
        if (!connectionId) {
            return <Typography variant="caption" className="fmdbeff-opportunityMappingMuted">{t('opportunityMapping.noConnection')}</Typography>;
        }

        if (effLoading) {
            return (
                <>
                    <Loader size="small"/>
                    <Typography variant="caption" className="fmdbeff-opportunityMappingMuted">{t('opportunityMapping.loadingEfficy')}</Typography>
                </>
            );
        }

        if (effError) {
            return <Typography variant="caption" className="fmdbeff-opportunityMappingError">{effError}</Typography>;
        }

        if (effFields.length === 0) {
            return <Typography variant="caption" className="fmdbeff-opportunityMappingMuted">{t('opportunityMapping.noEfficyFields')}</Typography>;
        }

        return null;
    };

    const status = renderStatus();
    const canEditRows = !readOnly;
    const canAutoMap = canEditRows && effFields.length > 0 && formFields.length > 0;

    return (
        <div className="fmdbeff-opportunityMapping flexFluid" data-sel-role="opportunity-mapping">
            {formFields.length === 0 && (
                <div className="fmdbeff-opportunityMappingStatus">
                    <Typography variant="caption" className="fmdbeff-opportunityMappingMuted">{t('opportunityMapping.noFormFields')}</Typography>
                </div>
            )}
            {status && <div className="fmdbeff-opportunityMappingStatus">{status}</div>}

            {rows.length > 0 && (
                <div className="fmdbeff-opportunityMappingGrid" role="table">
                    <Typography variant="caption" className="fmdbeff-opportunityMappingHeader">{t('opportunityMapping.columns.efficyField')}</Typography>
                    <Typography variant="caption" className="fmdbeff-opportunityMappingHeader">{t('opportunityMapping.columns.source')}</Typography>
                    <Typography variant="caption" className="fmdbeff-opportunityMappingHeader">{t('opportunityMapping.columns.value')}</Typography>
                    <div className="fmdbeff-opportunityMappingHeader"/>

                    {rows.map((row, index) => {
                        const rowId = rowIds[index];
                        const usedSfFields = new Set(rows.filter((_r, i) => i !== index).map(other => other.effField));
                        const rowSfOptions = withStoredOption(
                            effOptions.filter(option => option.value === row.effField || !usedSfFields.has(option.value ?? '')),
                            row.effField
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
                            <div key={rowId} className="fmdbeff-opportunityMappingRow" data-sel-role="opportunity-mapping-row" role="row">
                                <div>
                                    <Dropdown
                                        variant="outlined"
                                        data={rowSfOptions}
                                        hasSearch={rowSfOptions.length >= 5}
                                        value={row.effField || undefined}
                                        placeholder={t('opportunityMapping.selectEfficyField')}
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
                                    {row.source === 'today' ? (
                                        <Typography variant="caption" className="fmdbeff-opportunityMappingMuted">{t('opportunityMapping.todayValue')}</Typography>
                                    ) : (row.source === 'field' || row.source === 'personByEmail') ? (
                                        <Dropdown
                                            variant="outlined"
                                            data={rowFormOptions}
                                            hasSearch={rowFormOptions.length >= 5}
                                            value={resolvedField?.id ?? (row.fieldName || row.fieldKey || undefined)}
                                            placeholder={t('opportunityMapping.selectFormField')}
                                            isDisabled={readOnly}
                                            onChange={(_event, item) => handleFormFieldChange(index, item)}
                                        />
                                    ) : (
                                        <ConstantValueEditor
                                            inputId={`${id}-constant-${rowId}`}
                                            effField={effFieldsByName.get(row.effField)}
                                            effType={row.effType}
                                            value={row.value ?? ''}
                                            readOnly={readOnly}
                                            onValueChange={nextValue => updateRow(index, {value: nextValue})}
                                        />
                                    )}
                                </div>
                                <div className="fmdbeff-opportunityMappingRemove">
                                    <Button
                                        variant="ghost"
                                        icon={<Close/>}
                                        title={t('opportunityMapping.remove')}
                                        aria-label={t('opportunityMapping.remove')}
                                        isDisabled={readOnly}
                                        data-sel-role="opportunity-mapping-remove"
                                        onClick={() => handleRemoveRow(index)}
                                    />
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            {rows.length === 0 && (
                <Typography variant="caption" className="fmdbeff-opportunityMappingMuted">{t('opportunityMapping.noRows')}</Typography>
            )}

            <div className="fmdbeff-opportunityMappingActions">
                <Button
                    variant="outlined"
                    icon={<Add/>}
                    label={t('opportunityMapping.add')}
                    isDisabled={!canEditRows}
                    data-sel-role="opportunity-mapping-add"
                    onClick={handleAddRow}
                />
                <Button
                    variant="ghost"
                    icon={<Link/>}
                    label={t('opportunityMapping.autoMap')}
                    isDisabled={!canAutoMap}
                    data-sel-role="opportunity-mapping-automap"
                    onClick={handleAutoMap}
                />
                <Button
                    variant="ghost"
                    icon={<Reload/>}
                    label={t('opportunityMapping.refreshFields')}
                    title={t('opportunityMapping.refreshFieldsHint')}
                    isDisabled={!connectionId || effLoading}
                    data-sel-role="opportunity-mapping-refresh"
                    onClick={() => setRefreshCount(count => count + 1)}
                />
            </div>

            {(missingRequired.length > 0 || unknownFields.length > 0 || typeHints.length > 0) && (
                <div className="flexCol" data-sel-role="opportunity-mapping-warnings">
                    {missingRequired.length > 0 && (
                        <Typography variant="caption" className="fmdbeff-opportunityMappingWarning">
                            {t('opportunityMapping.missingRequired', {fields: missingRequired.map(effField => effField.label).join(', ')})}
                        </Typography>
                    )}
                    {unknownFields.length > 0 && (
                        <Typography variant="caption" className="fmdbeff-opportunityMappingError" data-sel-role="opportunity-mapping-unknown">
                            {t('opportunityMapping.unknownFields', {fields: unknownFields.join(', ')})}
                        </Typography>
                    )}
                    {typeHints.map(hint => (
                        <Typography key={hint} variant="caption" className="fmdbeff-opportunityMappingWarning">{hint}</Typography>
                    ))}
                </div>
            )}
        </div>
    );
};

EfficyOpportunityMappingCmp.displayName = 'EfficyOpportunityMappingCmp';
