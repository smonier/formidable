import {describe, expect, it} from 'vitest';
import {remainingFeedbackPause} from './useFormSubmission';

// The spinner is shown for half a second at least, measured from its appearance (#327): a fast
// answer completes the floor, an answer that took longer pays nothing more.
describe('remainingFeedbackPause', () => {
	it('completes the floor after a fast answer', () => {
		expect(remainingFeedbackPause(40)).toBe(460);
	});

	it('waits the whole floor when the answer was immediate', () => {
		expect(remainingFeedbackPause(0)).toBe(500);
	});

	it('waits nothing once the floor is reached', () => {
		expect(remainingFeedbackPause(500)).toBe(0);
	});

	it('waits nothing after a slow answer', () => {
		expect(remainingFeedbackPause(1200)).toBe(0);
	});
});
