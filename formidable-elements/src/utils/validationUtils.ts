type ValidityFlag =
	| 'valueMissing'
	| 'typeMismatch'
	| 'patternMismatch'
	| 'tooShort'
	| 'tooLong'
	| 'rangeUnderflow'
	| 'rangeOverflow'
	| 'stepMismatch'
	| 'badInput';

const VALIDITY_FLAGS: ValidityFlag[] = [
	'valueMissing',
	'typeMismatch',
	'patternMismatch',
	'tooShort',
	'tooLong',
	'rangeUnderflow',
	'rangeOverflow',
	'stepMismatch',
	'badInput',
];

const FLAG_TO_DATA_ATTR: Record<ValidityFlag, string> = {
	valueMissing: 'data-fmdb-msg-value-missing',
	typeMismatch: 'data-fmdb-msg-type-mismatch',
	patternMismatch: 'data-fmdb-msg-pattern-mismatch',
	tooShort: 'data-fmdb-msg-too-short',
	tooLong: 'data-fmdb-msg-too-long',
	rangeUnderflow: 'data-fmdb-msg-range-underflow',
	rangeOverflow: 'data-fmdb-msg-range-overflow',
	stepMismatch: 'data-fmdb-msg-step-mismatch',
	badInput: 'data-fmdb-msg-bad-input',
};

const ERROR_CLASS = 'fmdb-validation-error';
const INVALID_CLASS = 'fmdb-invalid';

const getGroupedInputs = (
	input: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement
): Array<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement> => {
	if (!(input instanceof HTMLInputElement)) return [input];
	if ((input.type !== 'radio' && input.type !== 'checkbox') || !input.name || !input.form) return [input];

	return Array.from(
		input.form.querySelectorAll<HTMLInputElement>(`input[type="${input.type}"][name="${CSS.escape(input.name)}"]`)
	);
};

/**
 * A form's own id, never the control that shadows it. `HTMLFormElement` is `[LegacyOverrideBuiltIns]`:
 * a control whose name matches an IDL attribute takes that property over, and a field's name is the
 * contributor's system name — so a field named `id` turns `form.id` into an `HTMLInputElement`, which
 * is truthy and has no `replace`. A control named `id` cannot shadow the attribute read;
 * `getAttribute` itself could, which is accepted — no label leads a contributor to that name.
 */
export const formIdOf = (form: HTMLFormElement | null | undefined): string =>
	form?.getAttribute('id') ?? '';

const sanitizeIdPart = (value: string): string => {
	const sanitized = value.replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/^-+|-+$/g, '');
	return sanitized || 'field';
};

const buildErrorId = (input: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement): string => {
	if (input instanceof HTMLInputElement && (input.type === 'radio' || input.type === 'checkbox') && input.name) {
		const formId = formIdOf(input.form);
		const formPrefix = formId ? `${sanitizeIdPart(formId)}-` : '';
		return `fmdb-validation-error-${formPrefix}${sanitizeIdPart(input.type)}-${sanitizeIdPart(input.name)}`;
	}

	const base = input.id || input.name || 'field';
	return `fmdb-validation-error-${sanitizeIdPart(base)}`;
};

const updateDescribedBy = (
	input: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement,
	errorId: string,
	add: boolean
): void => {
	const current = (input.getAttribute('aria-describedby') ?? '')
		.split(/\s+/)
		.filter(Boolean);
	const next = add
		? Array.from(new Set([...current, errorId]))
		: current.filter(token => token !== errorId);

	if (next.length > 0) {
		input.setAttribute('aria-describedby', next.join(' '));
	} else {
		input.removeAttribute('aria-describedby');
	}
};

const clearFieldAria = (input: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement): void => {
	input.removeAttribute('aria-invalid');
	updateDescribedBy(input, buildErrorId(input), false);
};

export const resolveValidationMessage = (
	input: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement,
): string => {
	const v = input.validity;

	for (const flag of VALIDITY_FLAGS) {
		if (!v[flag]) continue;

		const customMsg = input.getAttribute(FLAG_TO_DATA_ATTR[flag]);
		if (customMsg) return customMsg;

		return input.validationMessage;
	}

	return input.validationMessage;
};

export const showFieldError = (
	input: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement,
	message: string,
): void => {
	clearFieldError(input);
	const groupedInputs = getGroupedInputs(input);
	groupedInputs.forEach(groupedInput => {
		groupedInput.classList.add(INVALID_CLASS);
		groupedInput.setAttribute('aria-invalid', 'true');
	});

	const errorEl = document.createElement('div');
	errorEl.id = buildErrorId(input);
	errorEl.className = ERROR_CLASS;
	errorEl.setAttribute('role', 'status');
	errorEl.textContent = message;
	groupedInputs.forEach(groupedInput => updateDescribedBy(groupedInput, errorEl.id, true));

	const formGroup = input.closest('.fmdb-form-group');
	if (formGroup) {
		formGroup.appendChild(errorEl);
	} else {
		input.insertAdjacentElement('afterend', errorEl);
	}
};

export const clearFieldError = (
	input: HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement,
): void => {
	const groupedInputs = getGroupedInputs(input);
	groupedInputs.forEach(groupedInput => {
		groupedInput.classList.remove(INVALID_CLASS);
		clearFieldAria(groupedInput);
	});

	const errorId = buildErrorId(input);
	input.ownerDocument.getElementById(errorId)?.remove();
};

export const clearAllFieldErrors = (form: HTMLFormElement): void => {
	form.querySelectorAll(`.${ERROR_CLASS}`).forEach(el => el.remove());
	form.querySelectorAll(`.${INVALID_CLASS}`).forEach(el => {
		el.classList.remove(INVALID_CLASS);
	});
	form.querySelectorAll<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>('input, select, textarea')
		.forEach(input => clearFieldAria(input));
};
