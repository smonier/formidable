import {useEffect, useRef, useState, type KeyboardEvent} from 'react';
import {useTranslation} from 'react-i18next';
import './range.css';
import {applyAfterPrefill, clearPrefillMarks, type PrefillDetail, prefillValue, restoreMirror} from './rangePrefill';

// Keys that operate a range slider: releasing a focus-navigation key (e.g. Tab
// landing on the slider) must not count as an answer.
const SLIDER_KEYS = new Set(['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End', 'PageUp', 'PageDown']);

// Dispatched on the hidden mirror by a script that prefills the form (the jExperience integration): the
// mirror is controlled, so a value written into it would be reset at the next render — the island takes
// the value from the event instead, under the rules of rangePrefill.ts.
const PREFILL_EVENT = 'formidable:prefill';

interface RangeInputProps {
	name: string;
	inputId: string;
	helpId?: string;
	datalistId?: string;
	minValue: number;
	maxValue: number;
	step?: number;
	defaultValue?: number;
	minLabel?: string;
	maxLabel?: string;
	required?: boolean;
	title?: string;
	autofocus?: boolean;
	disabled?: boolean;
	form?: string;
	validationAttributes: Record<string, string | undefined>;
}

/**
 * Range slider that submits nothing until the visitor interacts: the visible
 * slider is unnamed and mirrors its value into a hidden named input, empty while
 * unanswered. This keeps an untouched slider distinguishable from an answered one
 * (no pre-answered bias) and gives 'required' a real meaning for this control.
 */
export default function RangeInput({
	name,
	inputId,
	helpId,
	datalistId,
	minValue,
	maxValue,
	step,
	defaultValue,
	minLabel,
	maxLabel,
	required = false,
	title,
	autofocus,
	disabled,
	form,
	validationAttributes
}: RangeInputProps) {
	const {t} = useTranslation('formidable-elements', {keyPrefix: 'fmdb_inputRange'});
	const initialValue = defaultValue !== undefined ? String(defaultValue) : '';
	const [value, setValue] = useState<string>(initialValue);
	const answered = value !== '';
	const rangeRef = useRef<HTMLInputElement>(null);
	const mirrorRef = useRef<HTMLInputElement>(null);
	const answeredByVisitorRef = useRef(false);
	const mountedRef = useRef(false);
	// Set when a prefill wrote the slider and the author asked for read-only: the visible slider is disabled,
	// the named mirror keeps the value for the submission. The island applies what follows the write itself
	// (rangePrefill.ts): the script knows what it wrote into the mirror, not what the slider accepted.
	const [lockedByPrefill, setLockedByPrefill] = useState(false);

	// The thumb needs a position even while unanswered; the midpoint mirrors the
	// browser default for a valueless range, snapped to the step grid so the
	// browser's own value sanitization cannot desync the controlled value
	// (toFixed absorbs float noise like 0.1 * 3).
	const stepValue = step && step > 0 ? step : 1;
	const snappedMidpoint = minValue + (Math.round(((maxValue - minValue) / 2) / stepValue) * stepValue);
	const displayValue = answered ? value : String(Math.min(maxValue, Number(snappedMidpoint.toFixed(6))));

	// Selecting exactly the displayed position fires no change event, so a
	// completed interaction (pointer release, slider-operating key release) also
	// counts as an answer.
	const confirmCurrentPosition = () => {
		answeredByVisitorRef.current = true;
		if (!answered && rangeRef.current) {
			setValue(rangeRef.current.value);
		}
	};

	const confirmOnSliderKey = (event: KeyboardEvent<HTMLInputElement>) => {
		if (SLIDER_KEYS.has(event.key)) {
			confirmCurrentPosition();
		}
	};

	// An untouched slider holds no submitted value: surface that through the
	// constraint-validation API so the shared form validation (validateInputs /
	// invalid events) shows an inline error, exactly like the checkbox group does.
	useEffect(() => {
		const range = rangeRef.current;
		if (!range) {
			return;
		}

		if (required && !answered) {
			const message = range.getAttribute('data-fmdb-msg-value-missing') || t('required');
			range.setCustomValidity(message);
		} else {
			range.setCustomValidity('');
		}
	}, [required, answered, t]);

	// Conditional logic evaluates on the bubbling input event, BEFORE React commits
	// the new value to the hidden mirror — re-dispatch a change event after the
	// commit so rules see the fresh value. Dispatched from the visible slider so the
	// shared form validation also clears its inline error, which is attached to the
	// slider (the customValidity reset above runs first, in the same effects flush).
	useEffect(() => {
		if (!mountedRef.current) {
			mountedRef.current = true;
			return;
		}

		rangeRef.current?.dispatchEvent(new Event('change', {bubbles: true}));
	}, [value]);

	// A controlled slider does not follow native form reset: restore the initial state.
	useEffect(() => {
		const formElement = rangeRef.current?.form;
		if (!formElement) {
			return;
		}

		const handleReset = () => {
			answeredByVisitorRef.current = false;
			setValue(initialValue);
			// Nothing is prefilled after a reset: give the slider back its hand and its wrapper back its
			// place, or a required slider would stay unanswered behind a hidden wrapper, or locked on a
			// value the visitor no longer has.
			setLockedByPrefill(false);
			clearPrefillMarks(mirrorRef.current);
		};
		formElement.addEventListener('reset', handleReset);
		return () => formElement.removeEventListener('reset', handleReset);
	}, [initialValue]);

	useEffect(() => {
		const mirror = mirrorRef.current;
		if (!mirror) return;
		const handlePrefill = (event: Event) => {
			const detail = (event as CustomEvent<PrefillDetail>).detail ?? {};
			const accepted = prefillValue(detail, {
				answeredByVisitor: answeredByVisitorRef.current,
				minValue,
				maxValue,
				step: stepValue
			});
			if (accepted !== null) {
				setValue(accepted);
				if (applyAfterPrefill(detail.then, mirror)) {
					setLockedByPrefill(true);
				}
			}

			// The script writes the profile's value into the mirror before telling the island, and the mirror
			// is what the form posts and what the conditional logic reads: whatever the slider settled on has
			// to be what it says — the value it took, since React skips the render when that already equals
			// the state, or its own when it refused.
			restoreMirror(mirror, accepted ?? value);
		};
		mirror.addEventListener(PREFILL_EVENT, handlePrefill);
		return () => mirror.removeEventListener(PREFILL_EVENT, handlePrefill);
	}, [minValue, maxValue, stepValue, value]);

	return (
		<>
			<div className="fmdb-range-row">
				{minLabel && <span className="fmdb-range-end-label">{minLabel}</span>}
				<input
					ref={rangeRef}
					type="range"
					id={inputId}
					className="fmdb-form-control fmdb-range"
					aria-describedby={helpId}
					aria-valuetext={answered ? value : t('unanswered')}
					min={minValue}
					max={maxValue}
					step={step}
					list={datalistId}
					value={displayValue}
					title={title}
					autoFocus={autofocus}
					disabled={disabled || lockedByPrefill}
					form={form}
					onChange={event => {
						answeredByVisitorRef.current = true;
						setValue(event.target.value);
					}}
					onPointerUp={confirmCurrentPosition}
					onKeyUp={confirmOnSliderKey}
					{...validationAttributes}
				/>
				{maxLabel && <span className="fmdb-range-end-label">{maxLabel}</span>}
				<output htmlFor={inputId} className="fmdb-range-output" aria-hidden="true">
					{answered ? value : '–'}
				</output>
			</div>
			<input ref={mirrorRef} type="hidden" name={name} value={value} form={form}/>
		</>
	);
}
