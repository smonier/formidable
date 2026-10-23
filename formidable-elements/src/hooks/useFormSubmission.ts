import {type FormEvent, type RefObject, useRef, useState} from 'react';
import {fieldKindFromForm, interpolateMessage} from '~/utils/messageUtils';
import {applyConditionalLogicVisibility, buildLogicStateHeader} from '~/utils/conditionalLogic';
import {FORM_LOGIC_STATE_HEADER} from '~/utils/logicProviders';
import {submitterTimeZone, TIME_ZONE_HEADER} from '~/utils/timeZone';
import {anchorFieldMessages, type FieldMessage, parseFieldMessages} from '~/utils/fieldActionMessages';
import {type CaptchaHandle} from '~/components/Form/Captcha.client';

interface SubmissionLabels {
	captchaRequired: string;
	errorCode: string;
	actionsProgress: (completed: number, total: number) => string;
	maintenanceUnavailable: string;
}

// Server rejection while the platform is in read-only maintenance (see docs/administration/error-codes.md).
// Covers the window where the mode is switched between render and submit: the visitor gets
// the same maintenance message as the render-time state, not a technical error.
const MAINTENANCE_ERROR_CODE = 'FMDB-014';
// A field action refused a value at submission (pipeline step 11b): a validation failure, read
// under the field like one — the contributor's message anchored there, the focus moved, no global
// error box, no code on screen. The pre-check is a courtesy; this is the authority saying the same.
const FIELD_ACTION_REFUSED_CODE = 'FMDB-015';
// An action refused the submission with a sentence for the visitor (`userMessage`, already translated and escaped by
// the server): "wrong e-mail or password", "this e-mail cannot be used". Shown as the form's error message, the code
// kept off the screen: the sentence says what the visitor can do, the code says nothing to them.
const ACTION_REFUSED_CODE = 'FMDB-018';
/** Dispatched on the form element after a 2xx, before the form is reset. Detail: {formId, response}. */
export const SUBMITTED_EVENT = 'formidable:submitted';
/**
 * How long a redirect waits, at most, for the work the listeners of the submitted event handed to `waitUntil` (the
 * jExperience module's form event): long enough for a tracker to land its event, short enough that a tracker that never
 * answers does not keep the visitor on the form.
 */
export const REDIRECT_HOLD_MS = 3000;

/**
 * What a listener of the submitted event receives in `detail`. A listener whose work must land before the visitor leaves
 * for a redirect (an event the next page is rendered from) passes its promise to `waitUntil`, synchronously: the redirect
 * waits for every such promise, settled either way, or REDIRECT_HOLD_MS. Without a redirect nothing waits.
 */
export interface SubmittedDetail {
	formId: string;
	response: unknown;
	waitUntil: (work: PromiseLike<unknown>) => void;
}

/**
 * How long the spinner stays on screen at least. A submission answered faster than the eye would
 * swap the form for its result without the spinner ever being seen, and the visitor would read the
 * change as if nothing had happened in between. The floor is measured from the moment the spinner
 * appears, so an answer that already took longer waits nothing more (#327).
 *
 * This is not what protects a click before hydration: Form.client.tsx sets `noValidate` on mount,
 * and a pre-hydration submit leaves the page as a native POST without reaching this code.
 */
const MINIMUM_FEEDBACK_MS = 500;

/** What is left of the minimum feedback pause once the spinner has been shown for `shownForMs`: nothing past the floor. */
export function remainingFeedbackPause(shownForMs: number): number {
	return Math.max(0, MINIMUM_FEEDBACK_MS - shownForMs);
}

/** Holds the success message until the spinner, shown since `spinnerShownAt`, has had its floor; a slow answer waits nothing. */
async function completeFeedbackPause(spinnerShownAt: number): Promise<void> {
	const pause = remainingFeedbackPause(Date.now() - spinnerShownAt);
	if (pause > 0) {
		await new Promise(resolve => setTimeout(resolve, pause));
	}
}

/**
 * A path of this site: one leading slash, no scheme, no host, no backslash, no control character, no whitespace. The
 * server only sends such a path in `redirect`; the browser checks it again before it navigates, so a body that was
 * tampered with on the way, or written by a module of the page, cannot send the visitor to another site.
 */
export function isSameOriginPath(target: unknown): target is string {
	if (typeof target !== 'string' || target.length === 0 || target.length > 2048 || !target.startsWith('/')) return false;
	if (target.startsWith('//') || target.startsWith('/\\')) return false;
	// eslint-disable-next-line no-control-regex
	return !/[\\\s\u0000-\u001f\u007f]/.test(target);
}

/**
 * Sends the visitor where an action asked, once the success message was shown and the form reset. Done in a task of
 * its own so the listeners of the submitted event have run, and only for a path of this site (the server validated it,
 * this is the second check); `assign`, not `replace`, so the form page stays reachable with the Back button.
 */
function navigateTo(target: unknown, holds: Promise<unknown>[] = []): void {
	if (!isSameOriginPath(target)) return;
	let gone = false;
	const go = () => {
		if (gone) return;
		gone = true;
		globalThis.location.assign(target);
	};
	setTimeout(() => {
		if (holds.length === 0) {
			go();
			return;
		}
		// the jExperience module's form event must reach jCustomer before the next page is rendered from the profile,
		// and a navigation started earlier cancels it
		void Promise.all(holds).then(go);
		setTimeout(go, REDIRECT_HOLD_MS);
	}, 0);
}

/** The server's JSON answer, or null for a body that is not JSON: the event never fails on it. */
function parseJsonBody(text: string): unknown {
	try {
		return JSON.parse(text);
	} catch {
		return null;
	}
}

/** What a rejected submission's body says: the code, the actions' progress, the messages naming fields. */
interface Rejection {
	errorCode?: string;
	actionsProgress?: {completed: number; total: number};
	messages: FieldMessage[];
	/** The sentence of an action's refusal (FMDB-018), escaped by the server. */
	userMessage?: string;
}

/** The rejection a non-2xx body carries; a body that is not JSON says nothing. */
function readRejection(responseText: string): Rejection {
	try {
		const body = JSON.parse(responseText);
		return {
			errorCode: typeof body.errorCode === 'string' ? body.errorCode : undefined,
			actionsProgress: typeof body.actionsCompleted === 'number' && typeof body.actionsTotal === 'number'
				? {completed: body.actionsCompleted, total: body.actionsTotal}
				: undefined,
			messages: parseFieldMessages(body),
			userMessage: typeof body.userMessage === 'string' && body.userMessage.trim() ? body.userMessage : undefined,
		};
	} catch {
		return {messages: []};
	}
}

/**
 * Posts the form. XHR is kept here because Jahia's CSRFGuard integrates with XMLHttpRequest rather
 * than fetch. Direct authenticated submissions to this servlet path are still protected server-side
 * and are not made valid purely by switching from fetch to XHR.
 */
function post(targetUrl: string, formData: FormData, headers: Record<string, string>): Promise<XMLHttpRequest> {
	return new Promise((resolve, reject) => {
		const xhr = new XMLHttpRequest();
		xhr.open('POST', targetUrl, true);
		for (const [name, value] of Object.entries(headers)) {
			xhr.setRequestHeader(name, value);
		}
		xhr.withCredentials = true;
		xhr.onload = () => resolve(xhr);
		xhr.onerror = () => reject(new Error('Submission failed'));
		xhr.send(formData);
	});
}

interface UseFormSubmissionOptions {
	/**
	 * The form node's UUID, from the server — never read back from the DOM. `HTMLFormElement` is
	 * `[LegacyOverrideBuiltIns]`: a control whose name matches an IDL attribute shadows it, and a field's
	 * name is the contributor's system name, so a field named `id` would turn `form.id` into that input
	 * and every listener of the event below would stop recognising the form, silently. The same rule
	 * governs the two other names a contributor plausibly types: `action` goes through `getAttribute`, and
	 * `reset` is called off the prototype. `getAttribute` itself, and the `dispatchEvent` below, are read
	 * straight off the form — a field would have to be named after them, which no label leads to.
	 */
	formId: string;
	submitActionUrl?: string;
	submissionMessage?: string;
	errorMessage?: string;
	locale: string;
	captcha?: {siteKey: string; widgetVar: string; tokenField: string};
	isMultiStep: boolean;
	isLastStep: boolean;
	setCurrentStep: (step: number) => void;
	labels: SubmissionLabels;
	/**
	 * Told the control a rejection named, so the island can bring it on screen — the step holding it, once
	 * the spinner has gone — and focus it. The spinner hides the form (display:none) while the request runs,
	 * and a hidden control cannot take the focus, so the focus is the island's, as an effect of its state.
	 */
	onRefused?: (control: HTMLElement) => void;
}

/**
 * What runs before the request is sent, and stops it on false: the constraint validation, then the
 * field actions asked about their values — the latter asynchronous, hence the promise. Nothing is
 * shown by the hook meanwhile: the constraint errors and the field messages are drawn under the
 * fields by the callers, and the form stays as it is until the answer.
 */
type PreValidate = () => boolean | Promise<boolean>;

interface UseFormSubmissionReturn {
	message: string | null;
	messageType: 'success' | 'error' | 'maintenance' | null;
	isLoading: boolean;
	isCaptchaValid: boolean;
	setIsCaptchaValid: (valid: boolean) => void;
	captchaRef: RefObject<CaptchaHandle | null>;
	/** Resolves once the submission is over — sent and answered, or stopped by the pre-validation. */
	handleSubmit: (event: FormEvent<HTMLFormElement>, preValidate?: PreValidate) => Promise<void>;
	showForm: () => void;
}

export function useFormSubmission({
	formId,
	submitActionUrl,
	submissionMessage,
	errorMessage,
	locale,
	captcha,
	isMultiStep,
	isLastStep,
	setCurrentStep,
	labels,
	onRefused,
}: UseFormSubmissionOptions): UseFormSubmissionReturn {
	const [message, setMessage] = useState<string | null>(null);
	const [messageType, setMessageType] = useState<'success' | 'error' | 'maintenance' | null>(null);
	const [isLoading, setIsLoading] = useState(false);
	const [isCaptchaValid, setIsCaptchaValid] = useState(false);
	const captchaRef = useRef<CaptchaHandle>(null);
	// A submission in progress, from the pre-validation to the answer: a second click meanwhile is
	// ignored. The spinner does that once it shows; the field actions settle before it does.
	const inFlightRef = useRef(false);

	const handleSubmit = async (event: FormEvent<HTMLFormElement>, preValidate?: PreValidate) => {
		event.preventDefault();

		if (isMultiStep && !isLastStep) return;
		if (inFlightRef.current) return;
		inFlightRef.current = true;

		// Read before anything awaits: React nulls the synthetic event's currentTarget once the handler returns.
		const form = event.currentTarget;

		try {
			if (preValidate && !(await preValidate())) return;

			setIsLoading(true);
			const spinnerShownAt = Date.now();

			if (captcha && !captchaRef.current?.getToken()) {
				setMessage(labels.captchaRequired);
				setMessageType('error');
				setIsLoading(false);
				return;
			}

			await send(form, spinnerShownAt);
		} finally {
			inFlightRef.current = false;
		}
	};

	/** The headers of the request: the captcha token, the declared logic state, the submitter's time zone — each when there is one. */
	const requestHeaders = (form: HTMLFormElement): Record<string, string> => {
		const headers: Record<string, string> = {};
		const captchaToken = captcha ? captchaRef.current?.getToken()?.trim() : undefined;
		if (captchaToken) {
			headers['X-Formidable-Captcha-Token'] = captchaToken;
		}
		// Read once, at the moment of submit: one declared state backs every provider
		// rule, which is what lets the server evaluate them coherently.
		const logicStateHeader = buildLogicStateHeader(form);
		if (logicStateHeader) {
			headers[FORM_LOGIC_STATE_HEADER] = logicStateHeader;
		}
		const timeZone = submitterTimeZone();
		if (timeZone) {
			headers[TIME_ZONE_HEADER] = timeZone;
		}
		return headers;
	};

	const send = async (form: HTMLFormElement, spinnerShownAt: number) => {
		let rejection: Rejection = {messages: []};
		// A message of an earlier attempt goes as the next one leaves: a refusal anchored under a field
		// must not read next to a stale "An error occurred" above the form.
		setMessage(null);
		setMessageType(null);

		try {
			// Visibility was last applied on an input event, but the visitor may have
			// crossed local midnight since: re-apply it now so the submitted controls
			// and the day declared below read the same clock, or a field legitimately
			// filled yesterday would be measured hidden against today and rejected.
			applyConditionalLogicVisibility(form);
			const formData = new FormData(form);
			if (captcha) {
				formData.delete(captcha.tokenField);
			}
			const interpolatedSubmissionMessage = interpolateMessage(submissionMessage, formData, locale, fieldKindFromForm(form));
			// getAttribute, not form.action: a control named "action" shadows the property (see formId above)
			const targetUrl = submitActionUrl ?? form.getAttribute('action') ?? globalThis.location.href;

			const response = await post(targetUrl, formData, requestHeaders(form));
			if (response.status < 200 || response.status >= 300) {
				rejection = readRejection(response.responseText);
				throw new Error('Submission failed');
			}

			// The accepted submission, announced to the page before the island touches the form:
			// a bubbling DOM event carrying the form's UUID and the server's answer, for scripts
			// that know nothing of this island (the jExperience module listens for it). The
			// event says "accepted, here is what the server answered" and nothing else; a listener
			// that throws does not reach this code (dispatchEvent reports it to the window).
			const holds: Promise<unknown>[] = [];
			const detail: SubmittedDetail = {
				formId,
				response: parseJsonBody(response.responseText),
				// settled at once either way, so a rejected promise is never left unhandled until the redirect looks at it
				waitUntil: work => {
					holds.push(Promise.resolve(work).then(() => undefined, () => undefined));
				},
			};
			form.dispatchEvent(new CustomEvent<SubmittedDetail>(SUBMITTED_EVENT, {bubbles: true, detail}));

			// The spinner has been visible since before the request: only the rest of the floor is waited for
			await completeFeedbackPause(spinnerShownAt);

			// An action's own sentence (already escaped by the server) replaces the form's message: "check your mailbox"
			const accepted = parseJsonBody(response.responseText) as {redirect?: unknown; userMessage?: unknown} | null;
			const actionMessage = typeof accepted?.userMessage === 'string' && accepted.userMessage.trim() ? accepted.userMessage : undefined;
			setMessage(actionMessage || interpolatedSubmissionMessage || 'Form submitted successfully!');
			setMessageType('success');
			// called off the prototype: a control named "reset" shadows the method, and this runs after the
			// 200 and after every action, so throwing here would tell the visitor the submission failed
			HTMLFormElement.prototype.reset.call(form);
			if (isMultiStep) setCurrentStep(0);
			navigateTo(accepted?.redirect, holds);
		} catch (error) {
			showRejection(form, rejection, error);
		} finally {
			setIsLoading(false);
		}
	};

	/** What the visitor sees of a rejection — or of a request that never got an answer. */
	const showRejection = (form: HTMLFormElement, {errorCode, actionsProgress, messages, userMessage}: Rejection, error: unknown) => {
		if (errorCode === ACTION_REFUSED_CODE && userMessage) {
			// the secret typed in a rejected attempt is not kept in the field for the next one
			for (const field of Array.from(form.querySelectorAll<HTMLInputElement>('input[type="password"]'))) {
				field.value = '';
			}
			setMessage(userMessage);
			setMessageType('error');
			captchaRef.current?.reset();
			return;
		}
		if (errorCode === MAINTENANCE_ERROR_CODE) {
			// Its own message type, not 'error': an error keeps the form on screen
			// so the visitor can retry, but this rejection means the platform is
			// read-only — there is nothing to retry, so the form hides, mirroring
			// the server render, which emits the message alone.
			setMessage(labels.maintenanceUnavailable);
			setMessageType('maintenance');
			captchaRef.current?.reset();
			return;
		}
		// A rejection naming fields (a field action's refusal, or too many answers on one field)
		// is shown where the visitor can act on it: under the field, focused. The refusal of a
		// field action is then all there is to say — no global box; the other codes keep theirs
		// under the anchored message. A message that found no field falls through to the box:
		// a refusal the visitor cannot see is worse than a generic one.
		const refused = messages.length > 0 ? anchorFieldMessages(form, messages) : null;
		if (refused) {
			onRefused?.(refused);
			if (errorCode === FIELD_ACTION_REFUSED_CODE) {
				captchaRef.current?.reset();
				return;
			}
		}
		setMessage(globalErrorMessage(form, errorCode, actionsProgress));
		setMessageType('error');
		captchaRef.current?.reset();
		console.error("formidable submit error:", error);
	};

	/** The form's own error message, interpolated, with the code and the actions' progress under it when the server said so. */
	const globalErrorMessage = (form: HTMLFormElement, errorCode?: string, actionsProgress?: Rejection['actionsProgress']): string => {
		const formData = new FormData(form);
		const interpolatedErrorMessage = interpolateMessage(errorMessage, formData, locale, fieldKindFromForm(form));
		const base = interpolatedErrorMessage || 'An error occurred while submitting the form.';
		const details: string[] = [];
		if (errorCode) {
			details.push(`${labels.errorCode}: ${errorCode}`);
		}
		if (actionsProgress) {
			details.push(labels.actionsProgress(actionsProgress.completed, actionsProgress.total));
		}
		return details.length > 0
			? `${base}<br><small class="fmdb-message-details fmdb-message-error-details">${details.join(' — ')}</small>`
			: base;
	};

	const showForm = () => {
		setIsLoading(true);
		setTimeout(() => {
			setMessage(null);
			setMessageType(null);
			setIsLoading(false);
		}, 300);
	};

	return {
		message,
		messageType,
		isLoading,
		isCaptchaValid,
		setIsCaptchaValid,
		captchaRef,
		handleSubmit,
		showForm,
	};
}
