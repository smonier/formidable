/** What a `formidable:prefill` event carries: the profile's value, and what the author asked to follow the write. */
export interface PrefillDetail {
	value?: unknown;
	then?: string;
}

/** What the slider knows of itself when the event arrives. */
export interface RangeState {
	/** Whether the visitor already moved or confirmed the slider. */
	answeredByVisitor: boolean;
	minValue: number;
	maxValue: number;
	step: number;
}

/**
 * The value the slider takes from a prefill, or null to ignore it. The rules are the ones the prefill
 * applies to every other control, spelt here because the slider's named control is a hidden mirror of
 * React state, which a script cannot write into: an answer the visitor gave stays, a default the author
 * gave gives way to the profile's value, and only a number within the bounds is an answer — anything
 * else, a value the profile does not have included, leaves the slider as it is. The number is snapped
 * to the step grid, as the displayed position is, so the browser's own sanitizing of the range input
 * cannot desync the controlled value.
 */
export function prefillValue(detail: PrefillDetail, state: RangeState): string | null {
	if (state.answeredByVisitor) {
		return null;
	}
	if (detail.value === undefined || detail.value === null || detail.value === '') {
		return null;
	}
	const number = Number(detail.value);
	if (!Number.isFinite(number) || number < state.minValue || number > state.maxValue) {
		return null;
	}
	const step = state.step > 0 ? state.step : 1;
	const snapped = state.minValue + (Math.round((number - state.minValue) / step) * step);
	return String(Math.min(state.maxValue, Number(snapped.toFixed(6))));
}

/**
 * What the author asked once the profile's value is in the slider, applied by the island itself: the
 * mirror is the only named control of the field and it is barred from constraint validation, so the
 * prefill script — which knows what it wrote, not what the island accepted — leaves this shape to the
 * island, which calls this only for a value it accepted (within the bounds, the visitor's own answer
 * kept). `readOnly` marks the field's wrapper and says to disable the visible slider, the mirror keeping
 * the value; `hidden` takes the wrapper out of sight, its value still submitted. Returns whether the
 * slider is to be locked.
 */
export function applyAfterPrefill(then: string | undefined, mirror: HTMLElement | null): boolean {
	const wrapper = mirror?.closest<HTMLElement>('[data-fmdb-node-name]') ?? null;
	if (then === 'readOnly') {
		if (wrapper) {
			wrapper.dataset.fmdbPrefilled = 'readonly';
		}

		return true;
	}
	if (then === 'hidden' && wrapper) {
		wrapper.dataset.fmdbPrefilled = 'hidden';
		wrapper.style.display = 'none';
		wrapper.setAttribute('aria-hidden', 'true');
	}
	return false;
}

/**
 * A form reset empties the slider, so nothing is prefilled any more and what followed the prefill goes
 * with it: the wrapper comes back in sight and loses the marker the styling and the conditional logic
 * read. Without this a reset would leave a required slider unanswered behind a `display: none` wrapper —
 * its error rendered out of sight, its value impossible to give.
 */
export function clearPrefillMarks(mirror: HTMLElement | null): void {
	const wrapper = mirror?.closest<HTMLElement>('[data-fmdb-node-name]');
	if (!wrapper) {
		return;
	}
	delete wrapper.dataset.fmdbPrefilled;
	wrapper.style.removeProperty('display');
	wrapper.removeAttribute('aria-hidden');
}

/**
 * What the mirror must say once the island has settled a prefill: the value the slider shows. The script
 * writes the mirror before telling the island, and the mirror is the field's only named control — what the
 * form posts and what the conditional logic reads. Two ways the script's raw write would otherwise stay:
 * the slider refused it, which changes no state, and the accepted value snapped to the one the slider
 * already held, which React renders no second time. Both leave the mirror saying something the slider
 * never showed, so this puts it back and says the change, since the script announced its own write to the
 * rules before the island had a verdict.
 */
export function restoreMirror(mirror: HTMLInputElement | null, value: string): void {
	if (!mirror || mirror.value === value) {
		return;
	}
	mirror.value = value;
	mirror.dispatchEvent(new Event('input', {bubbles: true}));
	mirror.dispatchEvent(new Event('change', {bubbles: true}));
}
