import {
	createPublishedLiveFormPage,
	getInputDateNode,
	getInputDatetimeLocalNode,
	visitLiveForm
} from '../../support/fixtures';
import {localDay} from '../../support/constants';
import {useFormidableSite} from './support';
import {
	expectErrorResponse,
	expectSuccessResponse,
	postDirectMultipartSubmission,
	withSameOriginHeaders
} from '../security/support';

// The hydration test freezes the browser clock on a leap day at noon: the
// island resolves the offsets from that instant, so the expected attributes are
// plain literals — no re-implementation of the shifting arithmetic in the spec,
// and no race across local midnight. 2024-02-29 minus 18 years lands on a
// non-leap year, so the assertion also proves the month-end clamping
// (java.time semantics, mirrored by the island): February 29 → February 28.
const FROZEN_NOW = new Date(2024, 1, 29, 12, 0, 0);

describe('Validation - 37 Date bounds at an offset from the submission day', () => {
	useFormidableSite();

	let formId: string;
	let livePath: string;

	before(() => {
		cy.login();
		createPublishedLiveFormPage(
			'relative-offset-bounds-form',
			'Relative offset bounds form',
			[
				// Age-limit shape: no birth date less than 18 years in the past.
				getInputDateNode({name: 'birthDate', title: 'Birth date', maxRelative: {amount: -18, unit: 'years'}}),
				// Booking-window shape: no appointment beyond 30 days ahead, none in the past.
				getInputDatetimeLocalNode({
					name: 'appointment',
					title: 'Appointment',
					minBoundMode: 'today',
					maxRelative: {amount: 30, unit: 'days'}
				})
			]
		).then(created => {
			formId = created.formId;
			livePath = created.livePath;
		});
		cy.logout();
	});

	it('hydrates the inputs with offsets resolved on the visitor day, month-end clamped', () => {
		cy.clock(FROZEN_NOW.getTime(), ['Date']);
		const form = visitLiveForm(livePath);

		// The bound is set at hydration (an SSR attribute would be frozen by the
		// fragment cache), so the assertions retry until the island has run.
		form.get().find('input[name="birthDate"]').should('have.attr', 'max', '2006-02-28');
		form.get().find('input[name="appointment"]').should('have.attr', 'min', '2024-02-29T00:00');
		form.get().find('input[name="appointment"]').should('have.attr', 'max', '2024-03-30T23:59');
	});

	it('accepts values inside the offset window', () => {
		cy.logout();

		postDirectMultipartSubmission({
			formId,
			fields: {
				// Far beyond any -18y maximum for decades to come.
				birthDate: '1990-06-15',
				appointment: `${localDay(10)}T12:00`
			},
			headers: withSameOriginHeaders()
		}).then(expectSuccessResponse);
	});

	it('rejects values beyond the offsets regardless of the rendered page age', () => {
		cy.logout();

		// A birth date on the submission day is 18 years past the -18y maximum:
		// beyond any timezone widening, whatever clock the runner sits on.
		postDirectMultipartSubmission({
			formId,
			fields: {birthDate: localDay(0)},
			headers: withSameOriginHeaders()
		}).then(response => expectErrorResponse(response, 400, 'FMDB-010'));

		// Three days beyond the +30d window: the widening spans at most two
		// calendar days around any clock, so 33 is provably out.
		postDirectMultipartSubmission({
			formId,
			fields: {appointment: `${localDay(33)}T12:00`},
			headers: withSameOriginHeaders()
		}).then(response => expectErrorResponse(response, 400, 'FMDB-010'));
	});
});
