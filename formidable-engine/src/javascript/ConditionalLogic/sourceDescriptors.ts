import type {LogicOperator, SourceFieldOption, SourceValueKind} from './ConditionalLogic.types';

/**
 * Everything the rules editor needs to know about a source field to offer it as a
 * conditional logic source: operators and choice storage are read from the
 * descriptor — nothing else in the editor branches on concrete type names.
 *
 * A field type declares its value kind by carrying one of the engine's semantic
 * mixins (fmdbmix:choiceField, dateField, numberField, booleanField, textField),
 * which maps to a kind-default descriptor below. Types needing more than the default
 * (a different choice property, specialized operators) get an explicit per-type
 * override entry; third-party types only need the mixin in their CND.
 */
export interface LogicSourceDescriptor {
    valueKind: SourceValueKind;
    // JCR property holding the JSON-encoded choice values, for 'choice' sources.
    choiceProperty?: string;
    getOperators: (source: SourceFieldOption) => LogicOperator[];
}

const KIND_DEFAULTS: Record<SourceValueKind, LogicSourceDescriptor> = {
    choice: {
        valueKind: 'choice',
        choiceProperty: 'options',
        getOperators: () => ['in', 'notIn']
    },
    date: {
        valueKind: 'date',
        getOperators: () => ['before', 'after', 'on', 'between']
    },
    number: {
        valueKind: 'number',
        getOperators: () => ['eq', 'neq', 'lt', 'lte', 'gt', 'gte', 'between']
    },
    boolean: {
        valueKind: 'boolean',
        getOperators: () => ['isTrue', 'isFalse']
    },
    text: {
        valueKind: 'text',
        getOperators: () => ['isNotEmpty', 'isEmpty', 'equals', 'contains']
    }
};

const TYPE_OVERRIDES = new Map<string, LogicSourceDescriptor>([
    ['fmdb:checkbox', {
        valueKind: 'choice',
        choiceProperty: 'options',
        getOperators: source => source.choiceValues.length <= 1
            ? ['isChecked', 'isUnchecked']
            : ['containsAny', 'containsAll']
    }]
]);

/**
 * Resolves the descriptor of a source field: an explicit per-type override wins,
 * then the default descriptor of the field's declared value kind. Returns
 * undefined for fields that are not logic sources (no override, no semantic mixin).
 */
export const getSourceDescriptor = (type?: string, valueKind?: SourceValueKind): LogicSourceDescriptor | undefined => {
    const override = type ? TYPE_OVERRIDES.get(type) : undefined;
    if (override) {
        return override;
    }

    return valueKind ? KIND_DEFAULTS[valueKind] : undefined;
};

/**
 * Operators that compare against contributor-provided value(s); the others
 * (checked/true/defined states) need no value widget at all.
 */
export const operatorNeedsValue = (operator: LogicOperator): boolean =>
    !['isChecked', 'isUnchecked', 'isTrue', 'isFalse', 'isEmpty', 'isNotEmpty', 'exists', 'notExists'].includes(operator);

// Provider operators (jsVariable, urlParam, cookie) live in providers.ts, next to the
// providers themselves: they depend on the source's shape, not on a field's value kind.
