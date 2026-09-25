// @vitest-environment jsdom
import {describe, expect, it} from 'vitest';
import {applyAfterPrefill, clearPrefillMarks, restoreMirror} from './rangePrefill';

/**
 * What the island does with the author's choice after a prefill it accepted, on the markup the view
 * produces: the named mirror inside the element wrapper. The script leaves this shape to the island
 * because the mirror says nothing of the slider's verdict, so the island's own hand is what is tested.
 */
const slider = () => {
	document.body.innerHTML = '<div data-fmdb-node-name="satisfaction"><input type="range"><input type="hidden" name="satisfaction" value="7"></div>';
	return {
		wrapper: document.querySelector<HTMLElement>('[data-fmdb-node-name]')!,
		mirror: document.querySelector<HTMLInputElement>('input[type="hidden"]')!
	};
};

describe('applyAfterPrefill', () => {
	it('read-only: marks the wrapper and asks for the slider to be locked, the mirror untouched', () => {
		const {wrapper, mirror} = slider();
		expect(applyAfterPrefill('readOnly', mirror)).toBe(true);
		expect(wrapper.dataset.fmdbPrefilled).toBe('readonly');
		expect(wrapper.style.display).toBe('');
		expect(mirror.value).toBe('7');
	});

	it('hidden: takes the wrapper out of sight, the mirror still there to be submitted', () => {
		const {wrapper, mirror} = slider();
		expect(applyAfterPrefill('hidden', mirror)).toBe(false);
		expect(wrapper.dataset.fmdbPrefilled).toBe('hidden');
		expect(wrapper.style.display).toBe('none');
		expect(wrapper.getAttribute('aria-hidden')).toBe('true');
		expect(mirror.disabled).toBe(false);
	});

	it('editable, or nothing asked: changes nothing', () => {
		const {wrapper, mirror} = slider();
		expect(applyAfterPrefill(undefined, mirror)).toBe(false);
		expect(applyAfterPrefill('editable', mirror)).toBe(false);
		expect('fmdbPrefilled' in wrapper.dataset).toBe(false);
	});

	it('survives a mirror outside any element wrapper', () => {
		document.body.innerHTML = '<input type="hidden" name="loose">';
		const mirror = document.querySelector<HTMLElement>('input')!;
		expect(applyAfterPrefill('hidden', mirror)).toBe(false);
		expect(applyAfterPrefill('readOnly', null)).toBe(true);
	});
});

describe('clearPrefillMarks', () => {
	it('gives a hidden field back its place, so a reset does not leave it unanswered out of sight', () => {
		const {wrapper, mirror} = slider();
		applyAfterPrefill('hidden', mirror);
		clearPrefillMarks(mirror);
		expect('fmdbPrefilled' in wrapper.dataset).toBe(false);
		expect(wrapper.style.display).toBe('');
		expect(wrapper.hasAttribute('aria-hidden')).toBe(false);
	});

	it('takes the read-only mark off, and leaves a field that was never prefilled alone', () => {
		const {wrapper, mirror} = slider();
		applyAfterPrefill('readOnly', mirror);
		clearPrefillMarks(mirror);
		expect('fmdbPrefilled' in wrapper.dataset).toBe(false);

		clearPrefillMarks(mirror);
		expect('fmdbPrefilled' in wrapper.dataset).toBe(false);
		expect(() => clearPrefillMarks(null)).not.toThrow();
	});
});

describe('restoreMirror', () => {
	it('takes a refused value back out of the named control: the form posts what the slider shows', () => {
		const {mirror} = slider();
		mirror.value = '87'; // what the script wrote before telling the island
		restoreMirror(mirror, '5');
		expect(mirror.value).toBe('5');
	});

	it('says the change, so that the rules re-read the value the script had already announced', () => {
		const {mirror} = slider();
		const heard: string[] = [];
		['input', 'change'].forEach(name => mirror.addEventListener(name, event => heard.push(`${name}:${event.bubbles}`)));

		mirror.value = '87';
		restoreMirror(mirror, '5');
		expect(heard).toEqual(['input:true', 'change:true']);

		// nothing to correct, nothing said
		restoreMirror(mirror, '5');
		expect(heard).toEqual(['input:true', 'change:true']);
	});

	it('empties it for a slider still unanswered, and survives no mirror at all', () => {
		const {mirror} = slider();
		mirror.value = '87';
		restoreMirror(mirror, '');
		expect(mirror.value).toBe('');
		expect(() => restoreMirror(null, '5')).not.toThrow();
	});
});
