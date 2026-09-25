// @vitest-environment jsdom
import {describe, expect, it} from 'vitest';
import {formIdOf} from './validationUtils';

/**
 * A form's own id, against the named getter. `HTMLFormElement` is `[LegacyOverrideBuiltIns]`: a
 * control whose name matches an IDL attribute takes that property over, and a field's name is the
 * contributor's system name — so `id`, `action` and `reset` are all names an author can type.
 *
 * jsdom does not implement that getter, so the shadowing is reproduced here the way a browser
 * presents it: the property answers the control. Without it the test would pass whatever the helper
 * reads, which is no test at all.
 */
describe('formIdOf', () => {
	it('reads the attribute, which a control named id cannot shadow', () => {
		document.body.innerHTML = `
			<form id="the-form-uuid">
				<input name="id" value="whatever"/>
			</form>`;
		const form = document.querySelector('form') as HTMLFormElement;
		const shadowing = form.querySelector('input[name="id"]') as HTMLInputElement;
		Object.defineProperty(form, 'id', {configurable: true, get: () => shadowing});

		expect(formIdOf(form)).toBe('the-form-uuid');
	});

	it('answers an empty string for no form and for a form without an id', () => {
		expect(formIdOf(null)).toBe('');
		expect(formIdOf(undefined)).toBe('');
		expect(formIdOf(document.createElement('form'))).toBe('');
	});
});
