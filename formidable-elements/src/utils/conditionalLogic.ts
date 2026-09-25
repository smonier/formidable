import {getLogicProvider, type LogicSourceType, type ScalarLogicProvider} from '~/utils/logicProviders';
import {localToday} from '~/components/Input/Date/bounds';

export type ConditionalLogicSourceType = LogicSourceType;

export type ConditionalLogicValueKind = 'choice' | 'date' | 'number' | 'boolean' | 'text';

export type ConditionalLogicOperator =
	| 'in'
	| 'notIn'
	| 'isChecked'
	| 'isUnchecked'
	| 'containsAny'
	| 'containsAll'
	| 'before'
	| 'after'
	| 'on'
	| 'between'
	| 'eq'
	| 'neq'
	| 'lt'
	| 'lte'
	| 'gt'
	| 'gte'
	| 'isTrue'
	| 'isFalse'
	| 'isEmpty'
	| 'isNotEmpty'
	| 'equals'
	| 'notEquals'
	| 'contains'
	| 'exists'
	| 'notExists';

export interface ConditionalLogicRule {
	logicId?: string;
	// Absent on rules stored before jsVariable support; treated as 'field'. Wider than
	// LogicSourceType on purpose: a stored rule may name a provider this runtime does not
	// ship (authored against a newer module), and such a rule must be kept so it can fail
	// closed instead of vanishing.
	sourceType?: string;
	sourceFieldName?: string;
	sourceNodeId?: string;
	// Informative metadata; source eligibility is enforced at authoring time.
	sourceFieldType?: string;
	// Value kind of the source at authoring time; picks the comparison semantics
	// where an operator is shared across kinds ('between': number vs date).
	valueKind?: ConditionalLogicValueKind;
	// Provider config: exactly one of these, named by the provider's configKey.
	variable?: string;
	param?: string;
	cookie?: string;
	operator: ConditionalLogicOperator;
	value?: string;
	values?: string[];
}

const VALUE_KINDS: ConditionalLogicValueKind[] = ['choice', 'date', 'number', 'boolean', 'text'];

/**
 * Which operators each rule kind can actually evaluate here. Declared as a Record over
 * the operator union, so adding an operator to the union without deciding whether the
 * field evaluator implements it fails the type-check instead of silently falling through
 * to `default` — where an unevaluable rule hides its target field and skips the field's
 * required validation server-side.
 */
const FIELD_OPERATOR_IMPLEMENTED: Record<ConditionalLogicOperator, boolean> = {
	in: true,
	notIn: true,
	isChecked: true,
	isUnchecked: true,
	containsAny: true,
	containsAll: true,
	before: true,
	after: true,
	on: true,
	between: true,
	eq: true,
	neq: true,
	lt: true,
	lte: true,
	gt: true,
	gte: true,
	isTrue: true,
	isFalse: true,
	isEmpty: true,
	isNotEmpty: true,
	equals: true,
	contains: true,
	// Provider-only (jsVariable, urlParam, cookie…), by design: no field-source counterpart.
	notEquals: false,
	exists: false,
	notExists: false
};

/**
 * Operators available on every provider source: its state is one optional string, so it
 * supports presence and string comparison, and nothing else.
 */
const PROVIDER_OPERATORS = new Set<string>(['equals', 'notEquals', 'contains', 'exists', 'notExists']);

/** A rule that designates something other than a previous field, known provider or not. */
const isNonFieldRule = (rule: Partial<ConditionalLogicRule>): boolean =>
	typeof rule.sourceType === 'string' && rule.sourceType !== '' && rule.sourceType !== 'field';

/**
 * Why a rule cannot be evaluated here, or null when it can. A stored rule is authored
 * data and may come from a newer module version, so everything is checked at runtime:
 * the source type may name a provider this runtime does not ship, the provider's
 * reference may be missing, and the operator may not exist for the rule's kind.
 */
const ruleUnresolvedReason = (rule: ConditionalLogicRule): string | null => {
	if (isNonFieldRule(rule)) {
		const provider = getLogicProvider(rule.sourceType);
		if (!provider) return `source:${rule.sourceType}`;
		if (providerRuleRef(rule) === null) return `ref:${provider.id}`;
		return PROVIDER_OPERATORS.has(rule.operator) ? null : `operator:${rule.operator}`;
	}

	return FIELD_OPERATOR_IMPLEMENTED[rule.operator] === true ? null : `operator:${rule.operator}`;
};

const reportedUnresolvedReasons = new Set<string>();

/**
 * Reports a rule this runtime cannot evaluate. Once per reason per page: the visibility
 * pass runs on every input event, and a silent unevaluable rule looks exactly like a
 * legitimately hidden field.
 */
const reportUnresolvedRule = (reason: string) => {
	if (reportedUnresolvedReasons.has(reason)) return;
	reportedUnresolvedReasons.add(reason);
	console.warn(
		`[formidable] Conditional logic rule cannot be evaluated (${reason}): its target `
		+ 'field stays hidden. Check the stored rule or the module version.'
	);
};

/**
 * A provider rule is usable when its source type names a known provider and it carries a
 * non-empty reference under that provider's config key. Returns the trimmed reference, or
 * null when the rule is not a usable provider rule.
 */
const providerRuleRef = (parsed: Partial<ConditionalLogicRule>): string | null => {
	const provider = getLogicProvider(parsed.sourceType);
	if (!provider) return null;

	const ref = parsed[provider.configKey];
	return typeof ref === 'string' && ref.trim() !== '' ? ref.trim() : null;
};

export const parseConditionalLogicRule = (rawValue: string): ConditionalLogicRule | null => {
	try {
		const parsed = JSON.parse(rawValue) as Partial<ConditionalLogicRule>;
		if (!parsed || typeof parsed.operator !== 'string') {
			return null;
		}

		const provider = getLogicProvider(parsed.sourceType);
		const ref = providerRuleRef(parsed);
		if (provider && ref) {
			return {
				logicId: typeof parsed.logicId === 'string' ? parsed.logicId : undefined,
				sourceType: provider.id,
				[provider.configKey]: ref,
				operator: parsed.operator as ConditionalLogicOperator,
				value: typeof parsed.value === 'string' ? parsed.value : undefined
			};
		}

		// A non-field rule that is not usable here — unknown provider, missing reference —
		// is kept rather than dropped: the evaluator fails it closed and reports it, so the
		// field stays hidden everywhere instead of client-visible but server-hidden.
		if (isNonFieldRule(parsed)) {
			return {
				logicId: typeof parsed.logicId === 'string' ? parsed.logicId : undefined,
				sourceType: parsed.sourceType,
				operator: parsed.operator as ConditionalLogicOperator,
				value: typeof parsed.value === 'string' ? parsed.value : undefined
			};
		}

		if (typeof parsed.sourceFieldName !== 'string') {
			return null;
		}

		return {
			logicId: typeof parsed.logicId === 'string' ? parsed.logicId : undefined,
			sourceFieldName: parsed.sourceFieldName,
			sourceNodeId: typeof parsed.sourceNodeId === 'string' ? parsed.sourceNodeId : undefined,
			sourceFieldType: typeof parsed.sourceFieldType === 'string' ? parsed.sourceFieldType : undefined,
			valueKind: VALUE_KINDS.includes(parsed.valueKind as ConditionalLogicValueKind) ? parsed.valueKind : undefined,
			operator: parsed.operator as ConditionalLogicOperator,
			value: typeof parsed.value === 'string' ? parsed.value : undefined,
			values: Array.isArray(parsed.values) ? parsed.values.filter(value => typeof value === 'string') : undefined
		};
	} catch {
		return null;
	}
};

export const parseConditionalLogicRules = (rawValues: string[] = []): ConditionalLogicRule[] => {
	return rawValues
		.map(parseConditionalLogicRule)
		.filter((rule): rule is ConditionalLogicRule => rule !== null);
};

const isConditionalLogicRule = (value: unknown): value is ConditionalLogicRule => {
	if (!value || typeof value !== 'object') return false;
	const candidate = value as Partial<ConditionalLogicRule>;
	if (typeof candidate.operator !== 'string') return false;
	// Non-field rules are always kept, usable or not (unknown provider, missing
	// reference): they must fail closed in the evaluator, not silently vanish here.
	if (isNonFieldRule(candidate)) return true;
	return typeof candidate.sourceFieldName === 'string';
};

const deserializeConditionalLogicRules = (rawValue: string): ConditionalLogicRule[] => {
	try {
		const parsed = JSON.parse(rawValue) as unknown;
		if (!Array.isArray(parsed)) return [];

		if (parsed.every(entry => typeof entry === 'string')) {
			return parseConditionalLogicRules(parsed as string[]);
		}

		return parsed.filter(isConditionalLogicRule);
	} catch {
		return [];
	}
};

interface SourceFieldState {
	values: string[];
	checked: boolean;
}

const NON_VALUE_INPUT_TYPES = new Set(['submit', 'reset', 'button', 'file', 'image']);

/**
 * Escape hatch for widgets not rendered as native named controls: an element
 * inside the source wrapper (or the wrapper itself) carrying a
 * `data-fmdb-logic-value` attribute is read INSTEAD of probing form controls.
 * The attribute holds the widget's current value — a plain string, or a JSON
 * array of strings for multi-value widgets. The widget is responsible for
 * keeping the attribute up to date (logic re-evaluates on form input/change
 * events, so the widget should also dispatch one after updating it).
 */
const readLogicValueOverride = (wrapper: HTMLElement): string[] | null => {
	const host = wrapper.hasAttribute('data-fmdb-logic-value')
		? wrapper
		: wrapper.querySelector<HTMLElement>('[data-fmdb-logic-value]');
	if (!host) {
		return null;
	}

	const raw = host.getAttribute('data-fmdb-logic-value') ?? '';
	if (raw.startsWith('[')) {
		try {
			const parsed = JSON.parse(raw) as unknown;
			if (Array.isArray(parsed)) {
				return parsed.filter((entry): entry is string => typeof entry === 'string');
			}
		} catch {
			// Fall through: treat the raw attribute as a single scalar value.
		}
	}

	return raw === '' ? [] : [raw];
};

/**
 * Reads the current value(s) of a source field from its wrapper, whatever its widget:
 * every named form control inside the wrapper contributes its value — selected options
 * for selects, checked values for checkables (radio/checkbox), the raw value otherwise.
 * Any field rendered with native named controls is therefore readable without
 * field-type-specific code; others can expose `data-fmdb-logic-value`.
 */
const getSourceFieldState = (wrapper: HTMLElement): SourceFieldState => {
	const override = readLogicValueOverride(wrapper);
	if (override !== null) {
		return {
			values: override,
			checked: override.length === 1 && override[0] === 'true'
		};
	}

	const controls = Array.from(wrapper.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>(
		'input[name], select[name], textarea[name]'
	));

	const values: string[] = [];
	const checkables: HTMLInputElement[] = [];

	for (const control of controls) {
		if (control instanceof HTMLSelectElement) {
			values.push(...Array.from(control.selectedOptions).map(option => option.value).filter(Boolean));
		} else if (control instanceof HTMLInputElement && (control.type === 'checkbox' || control.type === 'radio')) {
			checkables.push(control);
			if (control.checked && control.value) {
				values.push(control.value);
			}
		} else if (!(control instanceof HTMLInputElement) || !NON_VALUE_INPUT_TYPES.has(control.type)) {
			if (control.value) {
				values.push(control.value);
			}
		}
	}

	return {
		values,
		// isChecked/isUnchecked are only offered on fields rendered as a single
		// checkable control (e.g. a lone checkbox); groups always compare values.
		checked: checkables.length === 1 && checkables[0].checked
	};
};

const compareDate = (left: string, right: string): number => {
	if (left === right) return 0;
	return left < right ? -1 : 1;
};

/**
 * Sentinel a date rule may carry instead of a fixed date: the submission day.
 * Unambiguous by construction — a date input can never produce this literal.
 */
export const LOGIC_TODAY_SENTINEL = 'today';

/**
 * Whether a rule compares against the submission day. Gated on the value kind, not
 * on an operator list: only the date kind gives the sentinel its meaning (a text
 * rule keeps comparing the literal string), and the editor stamps the kind on
 * every rule that can carry the sentinel. Mirrors the server's referencesToday.
 */
const ruleReferencesToday = (rule: ConditionalLogicRule): boolean =>
	rule.valueKind === 'date'
	&& (rule.value === LOGIC_TODAY_SENTINEL || (rule.values ?? []).includes(LOGIC_TODAY_SENTINEL));

/**
 * A copy of the rule with the today sentinel substituted by the visitor's local
 * day, so the ordinary operator evaluation applies unchanged — the same one-shot
 * rewrite the server does, keeping every operator branch sentinel-free.
 */
const withTodayResolved = (rule: ConditionalLogicRule): ConditionalLogicRule => {
	const today = localToday();
	return {
		...rule,
		value: rule.value === LOGIC_TODAY_SENTINEL ? today : rule.value,
		values: rule.values?.map(value => (value === LOGIC_TODAY_SENTINEL ? today : value))
	};
};

/**
 * Strict numeric parsing: the whole trimmed string must be a number, mirroring
 * the server-side Double.parseDouble ("9abc" is not a number, unlike parseFloat).
 */
const parseNumber = (value?: string): number => {
	const trimmed = value?.trim() ?? '';
	return trimmed === '' ? Number.NaN : Number(trimmed);
};

/**
 * Numeric comparison of a source value against an expected value. Returns null
 * when either side is not a finite number, so the calling operator fails safe.
 */
const compareNumber = (left?: string, right?: string): number | null => {
	const leftNumber = parseNumber(left);
	const rightNumber = parseNumber(right);
	if (!Number.isFinite(leftNumber) || !Number.isFinite(rightNumber)) {
		return null;
	}

	return leftNumber === rightNumber ? 0 : (leftNumber < rightNumber ? -1 : 1);
};

/**
 * Boolean state of a source field: a single checkable control reports its
 * checked state; value-based widgets (radio pairs, hidden input,
 * data-fmdb-logic-value) report a truthy current value — anything non-empty
 * except the explicit "false", mirroring the server-side evaluator.
 */
const getBooleanState = (state: SourceFieldState): boolean =>
	state.checked || (Boolean(state.values[0]) && state.values[0].trim().toLowerCase() !== 'false');

/**
 * A text source counts as filled when any of its values holds non-whitespace
 * content, mirroring the server-side String::isBlank check.
 */
const hasTextContent = (values: string[]): boolean =>
	values.some(value => value.trim() !== '');

/**
 * Evaluates a provider rule (a JS variable, a URL parameter, a cookie…). The provider
 * reports one optional string, so the comparison is the same whichever provider it is.
 */
const evaluateProviderRule = (rule: ConditionalLogicRule, provider: ScalarLogicProvider): boolean => {
	const ref = rule[provider.configKey];
	const actual = ref ? provider.read(ref) : undefined;
	const defined = actual !== undefined;
	const expected = rule.value ?? '';

	switch (rule.operator) {
		case 'equals':
			return defined && actual === expected;
		case 'notEquals':
			return defined && actual !== expected;
		case 'contains':
			return defined && expected !== '' && actual!.includes(expected);
		case 'exists':
			return defined;
		case 'notExists':
			return !defined;
		default:
			return false;
	}
};

/**
 * One scan of the form's stored rules for everything the logic state needs: the
 * references used by each provider, and whether any rule compares against the
 * submission day. A single pass keeps the two answers coherent by construction —
 * they must come from the same rule set to build one truthful declaration.
 */
const collectLogicStateNeeds = (
	form: HTMLFormElement
): {refsByProvider: Map<string, string[]>; hasTodayRule: boolean} => {
	const refsByProvider = new Map<string, Set<string>>();
	let hasTodayRule = false;

	for (const wrapper of Array.from(form.querySelectorAll<HTMLElement>('[data-fmdb-logics]'))) {
		for (const rule of deserializeConditionalLogicRules(wrapper.dataset.fmdbLogics ?? '')) {
			hasTodayRule = hasTodayRule || ruleReferencesToday(rule);

			const provider = getLogicProvider(rule.sourceType);
			const ref = provider ? rule[provider.configKey] : undefined;
			if (!provider || !ref) continue;

			const refs = refsByProvider.get(provider.id) ?? new Set<string>();
			refs.add(ref);
			refsByProvider.set(provider.id, refs);
		}
	}

	return {
		refsByProvider: new Map(Array.from(refsByProvider, ([id, refs]) => [id, Array.from(refs)])),
		hasTodayRule
	};
};

/**
 * Groups the references used by each provider across the form, so a provider that needs to
 * watch its state is subscribed once with everything it must watch.
 */
export const collectProviderRefs = (form: HTMLFormElement): Map<string, string[]> =>
	collectLogicStateNeeds(form).refsByProvider;

/** Unicode-safe base64: header values must stay ASCII, provider values may not be. */
const toBase64 = (value: string): string => {
	let binary = '';
	for (const byte of new TextEncoder().encode(value)) {
		binary += String.fromCharCode(byte);
	}

	return btoa(binary);
};

/**
 * Builds the submit-time state declaration for the FORM_LOGIC_STATE_HEADER, or null when
 * no rule of the form needs one. One `read` per referenced provider state, at the moment
 * of submit: a single declared state backs every rule that reads it, which is what lets
 * the server evaluate provider rules coherently instead of counting every provider-gated
 * field as hidden. `null` encodes "absent", as distinct from empty string. When a rule
 * compares against the submission day, the visitor's local day is declared too, so both
 * evaluators resolve "today" to the same calendar day whatever the timezone offset.
 */
export const buildLogicStateHeader = (form: HTMLFormElement): string | null => {
	const {refsByProvider, hasTodayRule} = collectLogicStateNeeds(form);
	const providers: Record<string, Record<string, string | null>> = {};
	const declaration: {v: number; providers: typeof providers; today?: string} = {v: 1, providers};
	let hasAny = false;

	for (const [providerId, refs] of refsByProvider) {
		const provider = getLogicProvider(providerId);
		if (!provider) continue;

		const state: Record<string, string | null> = {};
		for (const ref of refs) {
			state[ref] = provider.read(ref) ?? null;
		}

		providers[providerId] = state;
		hasAny = true;
	}

	if (hasTodayRule) {
		declaration.today = localToday();
		hasAny = true;
	}

	return hasAny ? toBase64(JSON.stringify(declaration)) : null;
};

const evaluateRule = (rule: ConditionalLogicRule, sourceWrapper: HTMLElement): boolean => {
	if (ruleReferencesToday(rule)) {
		rule = withTodayResolved(rule);

		// A 'between' interval emptied by the submission day (its fixed bound now
		// past "today", or not yet reached) matches nothing by construction: the
		// rule is ignored — counts as satisfied — rather than hiding its field
		// forever. The rule editor warns about it. Mirrors the server evaluator.
		const [from, to] = rule.values ?? [];
		if (rule.operator === 'between' && from && to && compareDate(from, to) > 0) {
			return true;
		}
	}

	const state = getSourceFieldState(sourceWrapper);
	const values = state.values;
	const expectedValues = rule.values ?? [];

	switch (rule.operator) {
		case 'in':
			return expectedValues.some(value => values.includes(value));
		case 'notIn':
			return values.length > 0 && expectedValues.every(value => !values.includes(value));
		case 'isChecked':
			return state.checked;
		case 'isUnchecked':
			return !state.checked;
		case 'containsAny':
			return expectedValues.some(value => values.includes(value));
		case 'containsAll':
			return expectedValues.every(value => values.includes(value));
		case 'before':
			return values.length > 0 && !!rule.value && compareDate(values[0], rule.value) < 0;
		case 'after':
			return values.length > 0 && !!rule.value && compareDate(values[0], rule.value) > 0;
		case 'on':
			return values.length > 0 && !!rule.value && compareDate(values[0], rule.value) === 0;
		case 'between': {
			if (values.length === 0 || expectedValues.length < 2 || expectedValues[0] === '' || expectedValues[1] === '') {
				return false;
			}

			// 'between' is shared by the date and number kinds: dates compare as
			// ISO strings, numbers numerically ("9" < "10").
			if (rule.valueKind === 'number') {
				const fromCompare = compareNumber(values[0], expectedValues[0]);
				const toCompare = compareNumber(values[0], expectedValues[1]);
				return fromCompare !== null && toCompare !== null && fromCompare >= 0 && toCompare <= 0;
			}

			return compareDate(values[0], expectedValues[0]) >= 0
				&& compareDate(values[0], expectedValues[1]) <= 0;
		}

		case 'eq':
			return compareNumber(values[0], rule.value) === 0;
		case 'neq': {
			const comparison = compareNumber(values[0], rule.value);
			return comparison !== null && comparison !== 0;
		}

		case 'lt':
			return (compareNumber(values[0], rule.value) ?? 1) < 0;
		case 'lte':
			return (compareNumber(values[0], rule.value) ?? 1) <= 0;
		case 'gt':
			return (compareNumber(values[0], rule.value) ?? -1) > 0;
		case 'gte':
			return (compareNumber(values[0], rule.value) ?? -1) >= 0;
		case 'isTrue':
			return getBooleanState(state);
		case 'isFalse':
			return !getBooleanState(state);
		case 'isEmpty':
			return !hasTextContent(values);
		case 'isNotEmpty':
			return hasTextContent(values);
		// Text comparisons require a non-empty expected value on BOTH evaluators:
		// an empty text input submits "" server-side but yields no value here, so
		// allowing an empty expected value would make the two sides disagree
		// (emptiness is what isEmpty/isNotEmpty are for).
		case 'equals':
			return values.length > 0 && !!rule.value && values[0] === rule.value;
		case 'contains':
			return values.length > 0 && !!rule.value && values[0].includes(rule.value);
		default:
			return false;
	}
};

const toggleDescendantControls = (wrapper: HTMLElement, disabled: boolean) => {
	const controls = wrapper.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement | HTMLButtonElement>(
		'input, select, textarea, button'
	);

	for (const control of Array.from(controls)) {
		if (disabled) {
			if (!control.dataset.fmdbInitialDisabled) {
				control.dataset.fmdbInitialDisabled = control.disabled ? 'true' : 'false';
			}

			control.disabled = true;
			continue;
		}

		control.disabled = control.dataset.fmdbInitialDisabled === 'true';
		delete control.dataset.fmdbInitialDisabled;
	}
};

const setWrapperVisibility = (wrapper: HTMLElement, visible: boolean) => {
	// A wrapper's own verdict never re-enables it inside a logic-hidden ancestor:
	// wrappers are processed in document order, so the ancestor's verdict is already
	// on the DOM. Without this, a satisfied rule on a field inside a hidden step
	// re-enabled its controls, FormData posted the value, and the server — where
	// the field inherits the step's verdict — rejected the whole submission.
	const effectiveVisible = visible &&
		!wrapper.parentElement?.closest('[data-fmdb-logic-hidden="true"]');
	// A field a prefill filled and the author hid (data-fmdb-prefilled, written by the prefill script) stays
	// out of sight whatever the rule says — but it is not hidden BY the logic: its controls follow the rule
	// like any other's, so its value is still submitted, and it still counts as a source.
	const shown = effectiveVisible && wrapper.dataset.fmdbPrefilled !== 'hidden';
	wrapper.style.display = shown ? '' : 'none';
	wrapper.setAttribute('aria-hidden', shown ? 'false' : 'true');
	wrapper.dataset.fmdbLogicHidden = effectiveVisible ? 'false' : 'true';
	toggleDescendantControls(wrapper, !effectiveVisible);
};

export const applyConditionalLogicVisibility = (form: HTMLFormElement) => {
	// The server already renders the whole structure in edit mode; re-hiding it here
	// would take the field away from the contributor again, right after hydration.
	// Gated on the form itself so every caller is covered, including an integrator
	// asking for a re-evaluation through FORM_LOGIC_INVALIDATE_EVENT.
	if (form.dataset.fmdbEditMode === 'true') return;

	const wrappers = Array.from(form.querySelectorAll<HTMLElement>('[data-fmdb-node-name]'));
	const wrappersByNodeId = new Map(
		wrappers
			.filter(w => w.dataset.fmdbNodeId)
			.map(w => [w.dataset.fmdbNodeId!, w])
	);
	const wrappersByName = new Map(
		wrappers
			.filter(w => w.dataset.fmdbNodeName)
			.map(w => [w.dataset.fmdbNodeName!, w])
	);

	for (const wrapper of wrappers) {
		const rawRules = wrapper.dataset.fmdbLogics;
		if (!rawRules) {
			continue;
		}

		const rules = deserializeConditionalLogicRules(rawRules);

		if (rules.length === 0) {
			setWrapperVisibility(wrapper, true);
			continue;
		}

		// Diagnostic only — the evaluation below already fails such rules closed. Surfacing
		// it on the wrapper is what makes the difference between "hidden because the
		// condition is false" and "hidden because we could not tell" visible to a developer
		// and to a test.
		const unresolvedReasons = rules
			.map(ruleUnresolvedReason)
			.filter((reason): reason is string => reason !== null);
		if (unresolvedReasons.length > 0) {
			wrapper.dataset.fmdbLogicUnresolved = unresolvedReasons.join(',');
			unresolvedReasons.forEach(reportUnresolvedRule);
		} else {
			delete wrapper.dataset.fmdbLogicUnresolved;
		}

		const visible = rules.every(rule => {
			if (isNonFieldRule(rule)) {
				const provider = getLogicProvider(rule.sourceType);
				// Unknown provider or missing reference: unevaluable (reported above), so
				// the field stays hidden — the same verdict the server reaches.
				if (!provider || providerRuleRef(rule) === null) {
					return false;
				}

				return evaluateProviderRule(rule, provider);
			}

			const sourceWrapper = rule.sourceNodeId
				? wrappersByNodeId.get(rule.sourceNodeId)
				: wrappersByName.get(rule.sourceFieldName ?? '');

			if (!sourceWrapper) return false;
			if (sourceWrapper.closest('[data-fmdb-logic-hidden="true"]')) return false;
			return evaluateRule(rule, sourceWrapper);
		});

		setWrapperVisibility(wrapper, visible);
	}
};
