import {type FormEvent, type RefObject, useRef, useState} from 'react';
import {fieldKindFromForm, interpolateMessage} from '~/utils/messageUtils';
import {applyConditionalLogicVisibility, buildLogicStateHeader} from '~/utils/conditionalLogic';
import {FORM_LOGIC_STATE_HEADER} from '~/utils/logicProviders';
import {submitterTimeZone, TIME_ZONE_HEADER} from '~/utils/timeZone';
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
/** Dispatched on the form element after a 2xx, before the form is reset. Detail: {formId, response}. */
export const SUBMITTED_EVENT = 'formidable:submitted';

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

/** The server's JSON answer, or null for a body that is not JSON: the event never fails on it. */
function parseJsonBody(text: string): unknown {
	try {
		return JSON.parse(text);
	} catch {
		return null;
	}
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
}

interface UseFormSubmissionReturn {
	message: string | null;
	messageType: 'success' | 'error' | 'maintenance' | null;
	isLoading: boolean;
	isCaptchaValid: boolean;
	setIsCaptchaValid: (valid: boolean) => void;
	captchaRef: RefObject<CaptchaHandle | null>;
	handleSubmit: (event: FormEvent<HTMLFormElement>, preValidate?: () => boolean) => void;
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
}: UseFormSubmissionOptions): UseFormSubmissionReturn {
	const [message, setMessage] = useState<string | null>(null);
	const [messageType, setMessageType] = useState<'success' | 'error' | 'maintenance' | null>(null);
	const [isLoading, setIsLoading] = useState(false);
	const [isCaptchaValid, setIsCaptchaValid] = useState(false);
	const captchaRef = useRef<CaptchaHandle>(null);

	const handleSubmit = async (event: FormEvent<HTMLFormElement>, preValidate?: () => boolean) => {
		event.preventDefault();

		if (isMultiStep && !isLastStep) return;

		if (preValidate && !preValidate()) return;

		setIsLoading(true);
		const spinnerShownAt = Date.now();

		if (captcha && !captchaRef.current?.getToken()) {
			setMessage(labels.captchaRequired);
			setMessageType('error');
			setIsLoading(false);
			return;
		}

		const form = event.currentTarget;
		let serverErrorCode: string | undefined;
		let serverActionsProgress: {completed: number; total: number} | undefined;

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

			const rawCaptchaToken = captcha ? captchaRef.current?.getToken() : undefined;
			const captchaToken = rawCaptchaToken?.trim() || undefined;
			// Read once, at the moment of submit: one declared state backs every provider
			// rule, which is what lets the server evaluate them coherently.
			const logicStateHeader = buildLogicStateHeader(form);
			// getAttribute, not form.action: a control named "action" shadows the property (see formId above)
			const targetUrl = submitActionUrl ?? form.getAttribute('action') ?? globalThis.location.href;

			// XHR is kept here because Jahia's CSRFGuard integrates with XMLHttpRequest rather than fetch.
			// Direct authenticated submissions to this servlet path are still protected server-side and
			// are not made valid purely by switching from fetch to XHR.
			const response = await new Promise<XMLHttpRequest>((resolve, reject) => {
				const xhr = new XMLHttpRequest();
				xhr.open('POST', targetUrl, true);
				if (captchaToken) {
					xhr.setRequestHeader('X-Formidable-Captcha-Token', captchaToken);
				}

				if (logicStateHeader) {
					xhr.setRequestHeader(FORM_LOGIC_STATE_HEADER, logicStateHeader);
				}

				const timeZone = submitterTimeZone();
				if (timeZone) {
					xhr.setRequestHeader(TIME_ZONE_HEADER, timeZone);
				}
				xhr.withCredentials = true;
				xhr.onload = () => resolve(xhr);
				xhr.onerror = () => reject(new Error('Submission failed'));
				xhr.send(formData);
			});

			if (response.status < 200 || response.status >= 300) {
				try {
					const body = JSON.parse(response.responseText);
					if (typeof body.errorCode === 'string') serverErrorCode = body.errorCode;
					if (typeof body.actionsCompleted === 'number' && typeof body.actionsTotal === 'number') {
						serverActionsProgress = {completed: body.actionsCompleted, total: body.actionsTotal};
					}
				} catch { /* ignore non-JSON bodies */ }
				throw new Error('Submission failed');
			}

			// The accepted submission, announced to the page before the island touches the form:
			// a bubbling DOM event carrying the form's UUID and the server's answer, for scripts
			// that know nothing of this island (the jExperience module listens for it). The
			// event says "accepted, here is what the server answered" and nothing else; a listener
			// that throws does not reach this code (dispatchEvent reports it to the window).
			form.dispatchEvent(new CustomEvent(SUBMITTED_EVENT, {
				bubbles: true,
				detail: {formId, response: parseJsonBody(response.responseText)},
			}));

			// The spinner has been visible since before the request: only the rest of the floor is waited for
			await completeFeedbackPause(spinnerShownAt);

			setMessage(interpolatedSubmissionMessage || 'Form submitted successfully!');
			setMessageType('success');
			// called off the prototype: a control named "reset" shadows the method, and this runs after the
			// 200 and after every action, so throwing here would tell the visitor the submission failed
			HTMLFormElement.prototype.reset.call(form);
			if (isMultiStep) setCurrentStep(0);
		} catch (error) {
			if (serverErrorCode === MAINTENANCE_ERROR_CODE) {
				// Its own message type, not 'error': an error keeps the form on screen
				// so the visitor can retry, but this rejection means the platform is
				// read-only — there is nothing to retry, so the form hides, mirroring
				// the server render, which emits the message alone.
				setMessage(labels.maintenanceUnavailable);
				setMessageType('maintenance');
				captchaRef.current?.reset();
				return;
			}
			const formData = new FormData(form);
			const interpolatedErrorMessage = interpolateMessage(errorMessage, formData, locale, fieldKindFromForm(form));
			const base = interpolatedErrorMessage || 'An error occurred while submitting the form.';
			const details: string[] = [];
			if (serverErrorCode) {
				details.push(`${labels.errorCode}: ${serverErrorCode}`);
			}
			if (serverActionsProgress) {
				details.push(labels.actionsProgress(serverActionsProgress.completed, serverActionsProgress.total));
			}
			const full = details.length > 0
				? `${base}<br><small class="fmdb-message-details fmdb-message-error-details">${details.join(' — ')}</small>`
				: base;
			setMessage(full);
			setMessageType('error');
			captchaRef.current?.reset();
			console.error("formidable submit error:", error);
		} finally {
			setIsLoading(false);
		}
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
