// @vitest-environment jsdom
import {type FormEvent} from 'react';
import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest';
import {SUBMITTED_EVENT, useFormSubmission} from './useFormSubmission';

/**
 * The wiring of the minimum feedback pause (#327), which the arithmetic tests of
 * `remainingFeedbackPause` cannot see: the clock starts when the spinner appears, the success
 * message waits for what is left of the floor — nothing after a slow answer, the remainder after a
 * fast one — and the accepted submission is announced to the page before that wait, never after.
 *
 * React's state is the one seam mocked: `useState` and `useRef` become plain holders, so the hook runs
 * as a function and `handleSubmit` is driven end to end against a fake `XMLHttpRequest` under fake
 * timers. Rendering it would need react-dom, which this module does not depend on. `vi.mock` is hoisted
 * above the imports, so the static import of the hook already sees the mocked React.
 */
const react = vi.hoisted(() => ({setters: [] as Array<ReturnType<typeof vi.fn>>}));
vi.mock('react', () => ({
	useState: <T,>(initial: T) => {
		const set = vi.fn();
		react.setters.push(set);
		return [initial, set];
	},
	useRef: <T,>(initial: T) => ({current: initial}),
}));

/** The request the hook sends, answered by the test when it decides the server has taken long enough. */
class FakeXhr {
	status = 0;
	responseText = '';
	withCredentials = false;
	onload: (() => void) | null = null;
	onerror: (() => void) | null = null;
	opened: {method: string; url: string} | undefined;
	readonly headers: Record<string, string> = {};

	open(method: string, url: string) {
		this.opened = {method, url};
	}

	setRequestHeader(name: string, value: string) {
		this.headers[name] = value;
	}

	send() {
		requests.push(this);
	}

	answer(status: number, body: string) {
		this.status = status;
		this.responseText = body;
		this.onload?.();
	}
}

/** The requests sent, in order; the test answers the last one. Cleared before each test. */
const requests: FakeXhr[] = [];

const labels = {
	captchaRequired: 'captcha',
	errorCode: 'code',
	actionsProgress: (completed: number, total: number) => `${completed}/${total}`,
	maintenanceUnavailable: 'maintenance',
};

/**
 * The component the hook runs in, as it runs in Form.client.tsx — a function component in shape,
 * called directly since React is the mock above (what a hook-rendering test utility does under the hood).
 */
function SubmittingForm(options: Parameters<typeof useFormSubmission>[0]) {
	return useFormSubmission(options);
}

/**
 * Submits a fresh, empty form and hands back a way to ask whether the success message has been shown.
 * The hook's `useState` calls come in a fixed order — message, message type, loading, captcha — so
 * the second setter is the message type's.
 */
function submit() {
	const {handleSubmit} = SubmittingForm({
		formId: 'form-under-test',
		locale: 'en',
		isMultiStep: false,
		isLastStep: true,
		setCurrentStep: () => undefined,
		labels,
	});
	const form = document.createElement('form');
	document.body.append(form);
	handleSubmit({preventDefault: () => undefined, currentTarget: form} as unknown as FormEvent<HTMLFormElement>);
	const [, setMessageType] = react.setters;
	return {successShown: () => setMessageType.mock.calls.some(([type]) => type === 'success')};
}

describe('useFormSubmission: the minimum feedback pause is a floor', () => {
	/** Hears the accepted submission bubble up from the form; the body keeps its listeners across replaceChildren. */
	const submitted = vi.fn();

	beforeEach(() => {
		vi.useFakeTimers();
		vi.stubGlobal('XMLHttpRequest', FakeXhr);
		react.setters.length = 0;
		requests.length = 0;
		submitted.mockClear();
		document.body.addEventListener(SUBMITTED_EVENT, submitted);
	});

	afterEach(() => {
		document.body.removeEventListener(SUBMITTED_EVENT, submitted);
		vi.useRealTimers();
		vi.unstubAllGlobals();
		document.body.replaceChildren();
	});

	it('waits nothing more after an answer slower than the floor', async () => {
		const {successShown} = submit();
		await vi.advanceTimersByTimeAsync(1200);
		requests.at(-1)?.answer(200, '{"success":true}');

		await vi.advanceTimersByTimeAsync(0);

		expect(successShown()).toBe(true);
		expect(vi.getTimerCount()).toBe(0);
	});

	it('completes the floor after a fast answer, counted from the spinner, not from the answer', async () => {
		const {successShown} = submit();
		await vi.advanceTimersByTimeAsync(40);
		requests.at(-1)?.answer(200, '{"success":true}');

		await vi.advanceTimersByTimeAsync(459);
		expect(successShown()).toBe(false);

		await vi.advanceTimersByTimeAsync(1);
		expect(successShown()).toBe(true);
	});

	it('announces the accepted submission before the pause, not after it', async () => {
		const {successShown} = submit();
		await vi.advanceTimersByTimeAsync(40);
		requests.at(-1)?.answer(200, '{"success":true}');
		await vi.advanceTimersByTimeAsync(0);

		// the page hears of the acceptance at once: a listener (the jExperience script) never pays the floor
		expect(submitted).toHaveBeenCalledOnce();
		expect(successShown()).toBe(false);

		await vi.advanceTimersByTimeAsync(460);
		expect(successShown()).toBe(true);
	});
});
