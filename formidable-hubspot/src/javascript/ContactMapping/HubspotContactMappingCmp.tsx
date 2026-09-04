import {useApolloClient} from '@apollo/client';
import {Add, Button, Close, Dropdown, Input, Link, Loader, Reload, Typography} from '@jahia/moonstone';
import React, {useEffect, useMemo, useRef, useState} from 'react';
import {useTranslation} from 'react-i18next';
import {CURRENT_NODE_BY_PATH, FORM_FIELDS_BY_PATH, CONTACT_PROPERTIES} from './graphql';
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
    unknownHubspotFields,
    resolveRowField,
    serializeMapping,
    sortHubspotFields
} from './ContactMapping.utils';
import type {
    FormFieldOption,
    GraphNode,
    MappingRow,
    MappingSource,
    HubspotField,
    SelectorProps
} from './ContactMapping.types';
import './contactMapping.css';

/** Moonstone Dropdown option; the package does not re-export DropdownDataOption. */
type DropdownOption = {label: string; value?: string; description?: string; isDisabled?: boolean};

const generateRowId = (): string => Math.random().toString(36).substring(2, 10);

// HubSpot property types whose constant is best typed through a dedicated input.
const NUMERIC_HS_TYPES = new Set(['number']);

/** A stored value the option list does not know still needs a labelled entry. */
const withStoredOption = (options: DropdownOption[], stored: string | undefined): DropdownOption[] => {
    if (!stored || options.some(option => option.value === stored)) {
        return options;
    }

    return [...options, {label: stored, value: stored}];
};

/**
 * The constant of a row: a picklist dropdown when Hubspot lists the values, a
 * yes/no dropdown for booleans, a typed input otherwise.
 */
const ConstantValueEditor = ({
    inputId,
    hsProperty,
    hsType,
    hsFieldType,
    value,
    readOnly,
    onValueChange
}: {
    inputId: string;
    hsProperty?: HubspotField;
    hsType?: string;
    hsFieldType?: string;
    value: string;
    readOnly: boolean;
    onValueChange: (value: string) => void;
}) => {
    const {t} = useTranslation('formidable-hubspot');
    const type = hsProperty?.type ?? hsType ?? '';
    const fieldType = hsProperty?.fieldType ?? hsFieldType ?? '';
    const picklist = hsProperty?.picklistValues ?? [];

    if (type === 'enumeration' && fieldType !== 'booleancheckbox' && picklist.length > 0) {
        const options = picklist.map(choice => ({label: choice.label || choice.value, value: choice.value}));
        if (fieldType === 'checkbox') {
            // HubSpot stores a multi-value enumeration as its values joined with semicolons.
            const selected = value.split(';').filter(entry => entry !== '');
            return (
                <Dropdown
                    variant="outlined"
                    data={withStoredOption(options, undefined)}
                    hasSearch={options.length >= 5}
                    values={selected}
                    placeholder={t('contactMapping.selectPicklistValue')}
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
                placeholder={t('contactMapping.selectPicklistValue')}
                isDisabled={readOnly}
                onChange={(_event, item) => onValueChange(item.value ?? '')}
            />
        );
    }

    if (type === 'bool' || fieldType === 'booleancheckbox') {
        const options = [
            {label: t('contactMapping.booleanTrue'), value: 'true'},
            {label: t('contactMapping.booleanFalse'), value: 'false'}
        ];
        return (
            <Dropdown
                variant="outlined"
                data={withStoredOption(options, value)}
                value={value || undefined}
                placeholder={t('contactMapping.selectPicklistValue')}
                isDisabled={readOnly}
                onChange={(_event, item) => onValueChange(item.value ?? '')}
            />
        );
    }

    // yyyy-MM-dd is exactly the HubSpot date format; datetime stays free text (an
    // ISO 8601 instant with its zone, which the native local picker cannot produce).
    const inputType = type === 'date' ? 'date' : (NUMERIC_HS_TYPES.has(type) ? 'number' : 'text');

    return (
        <Input
            id={inputId}
            type={inputType}
            isReadOnly={readOnly}
            placeholder={t('contactMapping.constantPlaceholder')}
            title={value || undefined}
            aria-label={t('contactMapping.columns.value')}
            value={value}
            size="big"
            onChange={event => onValueChange(event.target.value)}
        />
    );
};

export const HubspotContactMappingCmp = (props: SelectorProps) => {
    const {field, id, value, onChange} = props;
    const {t} = useTranslation('formidable-hubspot');
    const client = useApolloClient();
    const readOnly = Boolean(props.readOnly || field.readOnly);

    const [formFields, setFormFields] = useState<FormFieldOption[]>([]);
    const [formLoading, setFormLoading] = useState(true);
    const [formError, setFormError] = useState<string | null>(null);
    const [hsProperties, setHsProperties] = useState<HubspotField[]>([]);
    const [hsLoading, setHsLoading] = useState(false);
    const [hsError, setHsError] = useState<string | null>(null);
    // Incremented by the refresh button: re-runs the Hubspot query with the cache bypassed.
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
                setFormError(t('contactMapping.unresolvedContext'));
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
                    console.error('[HubspotContactMappingCmp] failed to load form fields', error);
                    setFormFields([]);
                    setFormError(t(stage === 'form' ? 'contactMapping.formNotFound' : 'contactMapping.formFieldsLoadError'));
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

    // (b) The createable Contact properties of the selected connection; re-queried whenever
    // the sibling connection value changes.
    useEffect(() => {
        let cancelled = false;

        const loadHubspotFields = async () => {
            if (!connectionId || !currentNodePath) {
                setHsProperties([]);
                setHsError(null);
                setHsLoading(false);
                return;
            }

            setHsLoading(true);
            setHsError(null);

            try {
                const result = await client.query<{
                    formidableHubspot?: {objectFields?: HubspotField[] | null} | null;
                }>({
                    query: CONTACT_PROPERTIES,
                    variables: {connectionId, contextPath: currentNodePath, refresh: refreshCount > 0},
                    fetchPolicy: 'network-only'
                });

                if (!cancelled) {
                    const fields = result.data?.formidableHubspot?.objectFields ?? [];
                    setHsProperties(fields.filter(hsProperty => hsProperty.createable !== false));
                }
            } catch (error) {
                if (!cancelled) {
                    console.error('[HubspotContactMappingCmp] failed to load Hubspot Contact properties', error);
                    setHsProperties([]);
                    const message = error instanceof Error ? error.message : String(error);
                    setHsError(`${t('contactMapping.hubspotLoadError')}: ${message}`);
                }
            } finally {
                if (!cancelled) {
                    setHsLoading(false);
                }
            }
        };

        void loadHubspotFields();

        return () => {
            cancelled = true;
        };
    }, [client, connectionId, currentNodePath, refreshCount, t]);

    const sortedHsProperties = useMemo(() => sortHubspotFields(hsProperties), [hsProperties]);
    const hsPropertiesByName = useMemo(() => new Map(hsProperties.map(hsProperty => [hsProperty.name, hsProperty])), [hsProperties]);
    const hsOptions = useMemo<DropdownOption[]>(
        () => sortedHsProperties.map(hsProperty => ({
            label: hsProperty.required ? `${hsProperty.label} *` : hsProperty.label,
            description: `${hsProperty.name} · ${hsProperty.fieldType ?? hsProperty.type}`,
            value: hsProperty.name
        })),
        [sortedHsProperties]
    );
    const formFieldOptions = useMemo<DropdownOption[]>(
        () => formFields.map(formField => ({label: formField.label, value: formField.id})),
        [formFields]
    );
    const sourceOptions: DropdownOption[] = [
        {label: t('contactMapping.sources.field'), value: 'field'},
        {label: t('contactMapping.sources.constant'), value: 'constant'}
    ];

    // The form field each row designates, resolved once per render (rows are few).
    const resolvedFields = useMemo(
        () => rows.map(row => resolveRowField(row, formFields)),
        [rows, formFields]
    );

    const missingRequired = useMemo(() => missingRequiredFields(hsProperties, rows), [hsProperties, rows]);
    const unknownFields = useMemo(() => unknownHubspotFields(hsProperties, rows), [hsProperties, rows]);
    const typeHints = rows.flatMap((row, index) => {
        const formField = resolvedFields[index];
        const hsType = hsPropertiesByName.get(row.hsProperty)?.type ?? row.hsType;
        if (!formField || !isTypeMismatch(hsType, formField.valueKind)) {
            return [];
        }

        return [t('contactMapping.typeHint', {
            hsProperty: hsPropertiesByName.get(row.hsProperty)?.label ?? row.hsProperty,
            hsType,
            formField: formField.label,
            kind: t(`contactMapping.kinds.${formField.valueKind}`)
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
        const hsProperty = item.value ? hsPropertiesByName.get(item.value) : undefined;
        updateRow(index, {hsProperty: item.value ?? '', hsType: hsProperty?.type ?? rows[index].hsType, hsFieldType: hsProperty?.fieldType ?? rows[index].hsFieldType});
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
        commitRows([...rows, {hsProperty: '', source: 'field', fieldKey: '', fieldName: '', nodeId: ''}]);
    };

    const handleRemoveRow = (index: number) => {
        rowIds.splice(index, 1);
        commitRows(rows.filter((_row, rowIndex) => rowIndex !== index));
    };

    const handleAutoMap = () => {
        const added = autoMapRows(hsProperties, formFields, rows);
        if (added.length > 0) {
            commitRows([...rows, ...added]);
        }
    };

    if (formLoading) {
        return <Loader size="small"/>;
    }

    if (formError) {
        return (
            <Typography variant="body" className="fmdbhs-contactMappingError">{formError}</Typography>
        );
    }

    const renderStatus = () => {
        if (!connectionId) {
            return <Typography variant="caption" className="fmdbhs-contactMappingMuted">{t('contactMapping.noConnection')}</Typography>;
        }

        if (hsLoading) {
            return (
                <>
                    <Loader size="small"/>
                    <Typography variant="caption" className="fmdbhs-contactMappingMuted">{t('contactMapping.loadingHubspot')}</Typography>
                </>
            );
        }

        if (hsError) {
            return <Typography variant="caption" className="fmdbhs-contactMappingError">{hsError}</Typography>;
        }

        if (hsProperties.length === 0) {
            return <Typography variant="caption" className="fmdbhs-contactMappingMuted">{t('contactMapping.noHubspotFields')}</Typography>;
        }

        return null;
    };

    const status = renderStatus();
    const canEditRows = !readOnly;
    const canAutoMap = canEditRows && hsProperties.length > 0 && formFields.length > 0;

    return (
        <div className="fmdbhs-contactMapping flexFluid" data-sel-role="contact-mapping">
            {formFields.length === 0 && (
                <div className="fmdbhs-contactMappingStatus">
                    <Typography variant="caption" className="fmdbhs-contactMappingMuted">{t('contactMapping.noFormFields')}</Typography>
                </div>
            )}
            {status && <div className="fmdbhs-contactMappingStatus">{status}</div>}

            {rows.length > 0 && (
                <div className="fmdbhs-contactMappingGrid" role="table">
                    <Typography variant="caption" className="fmdbhs-contactMappingHeader">{t('contactMapping.columns.hubspotField')}</Typography>
                    <Typography variant="caption" className="fmdbhs-contactMappingHeader">{t('contactMapping.columns.source')}</Typography>
                    <Typography variant="caption" className="fmdbhs-contactMappingHeader">{t('contactMapping.columns.value')}</Typography>
                    <div className="fmdbhs-contactMappingHeader"/>

                    {rows.map((row, index) => {
                        const rowId = rowIds[index];
                        const usedSfFields = new Set(rows.filter((_r, i) => i !== index).map(other => other.hsProperty));
                        const rowSfOptions = withStoredOption(
                            hsOptions.filter(option => option.value === row.hsProperty || !usedSfFields.has(option.value ?? '')),
                            row.hsProperty
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
                            <div key={rowId} className="fmdbhs-contactMappingRow" data-sel-role="contact-mapping-row" role="row">
                                <div>
                                    <Dropdown
                                        variant="outlined"
                                        data={rowSfOptions}
                                        hasSearch={rowSfOptions.length >= 5}
                                        value={row.hsProperty || undefined}
                                        placeholder={t('contactMapping.selectHubspotField')}
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
                                            placeholder={t('contactMapping.selectFormField')}
                                            isDisabled={readOnly}
                                            onChange={(_event, item) => handleFormFieldChange(index, item)}
                                        />
                                    ) : (
                                        <ConstantValueEditor
                                            inputId={`${id}-constant-${rowId}`}
                                            hsProperty={hsPropertiesByName.get(row.hsProperty)}
                                            hsType={row.hsType}
                                            hsFieldType={row.hsFieldType}
                                            value={row.value ?? ''}
                                            readOnly={readOnly}
                                            onValueChange={nextValue => updateRow(index, {value: nextValue})}
                                        />
                                    )}
                                </div>
                                <div className="fmdbhs-contactMappingRemove">
                                    <Button
                                        variant="ghost"
                                        icon={<Close/>}
                                        title={t('contactMapping.remove')}
                                        aria-label={t('contactMapping.remove')}
                                        isDisabled={readOnly}
                                        data-sel-role="contact-mapping-remove"
                                        onClick={() => handleRemoveRow(index)}
                                    />
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            {rows.length === 0 && (
                <Typography variant="caption" className="fmdbhs-contactMappingMuted">{t('contactMapping.noRows')}</Typography>
            )}

            <div className="fmdbhs-contactMappingActions">
                <Button
                    variant="outlined"
                    icon={<Add/>}
                    label={t('contactMapping.add')}
                    isDisabled={!canEditRows}
                    data-sel-role="contact-mapping-add"
                    onClick={handleAddRow}
                />
                <Button
                    variant="ghost"
                    icon={<Link/>}
                    label={t('contactMapping.autoMap')}
                    isDisabled={!canAutoMap}
                    data-sel-role="contact-mapping-automap"
                    onClick={handleAutoMap}
                />
                <Button
                    variant="ghost"
                    icon={<Reload/>}
                    label={t('contactMapping.refreshFields')}
                    title={t('contactMapping.refreshFieldsHint')}
                    isDisabled={!connectionId || hsLoading}
                    data-sel-role="contact-mapping-refresh"
                    onClick={() => setRefreshCount(count => count + 1)}
                />
            </div>

            {(missingRequired.length > 0 || unknownFields.length > 0 || typeHints.length > 0) && (
                <div className="flexCol" data-sel-role="contact-mapping-warnings">
                    {missingRequired.length > 0 && (
                        <Typography variant="caption" className="fmdbhs-contactMappingWarning">
                            {t('contactMapping.missingRequired', {fields: missingRequired.map(hsProperty => hsProperty.label).join(', ')})}
                        </Typography>
                    )}
                    {unknownFields.length > 0 && (
                        <Typography variant="caption" className="fmdbhs-contactMappingError" data-sel-role="contact-mapping-unknown">
                            {t('contactMapping.unknownFields', {fields: unknownFields.join(', ')})}
                        </Typography>
                    )}
                    {typeHints.map(hint => (
                        <Typography key={hint} variant="caption" className="fmdbhs-contactMappingWarning">{hint}</Typography>
                    ))}
                </div>
            )}
        </div>
    );
};

HubspotContactMappingCmp.displayName = 'HubspotContactMappingCmp';
