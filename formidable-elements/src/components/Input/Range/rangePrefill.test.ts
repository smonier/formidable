import {describe, expect, it} from 'vitest';
import {prefillValue} from './rangePrefill';

const unanswered = {answeredByVisitor: false, minValue: 0, maxValue: 10, step: 1};

describe('prefillValue', () => {
	it('takes a number within the bounds for an unanswered slider', () => {
		expect(prefillValue({value: 3}, unanswered)).toBe('3');
		expect(prefillValue({value: '7'}, unanswered)).toBe('7');
	});

	it('leaves an answer the visitor gave alone', () => {
		expect(prefillValue({value: 3}, {...unanswered, answeredByVisitor: true})).toBeNull();
	});

	it('ignores what is not a number within the bounds', () => {
		expect(prefillValue({value: 'many'}, unanswered)).toBeNull();
		expect(prefillValue({value: 11}, unanswered)).toBeNull();
		expect(prefillValue({value: -1}, unanswered)).toBeNull();
		expect(prefillValue({}, unanswered)).toBeNull();
		expect(prefillValue({value: ''}, unanswered)).toBeNull();
	});

	it('snaps to the step grid, like the displayed position, and never past the maximum', () => {
		expect(prefillValue({value: 0.31}, {...unanswered, maxValue: 1, step: 0.1})).toBe('0.3');
		expect(prefillValue({value: 9.7}, {...unanswered, step: 2})).toBe('10');
		expect(prefillValue({value: 2.6}, {...unanswered, minValue: 1, step: 0.5})).toBe('2.5');
	});
});
