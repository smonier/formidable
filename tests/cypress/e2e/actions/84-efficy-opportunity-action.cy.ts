import {createPublishedLiveFormPage, visitLiveForm} from '../../support/fixtures/forms';
import {
	getCheckboxNode,
	getCreateOpportunityActionNode,
	getInputEmailNode,
	getInputNumberNode,
	getInputTextNode,
	getSelectNode,
	getTextareaNode,
	isEfficyMockAvailable,
	publishAndWaitLive,
	readMockOpportunities,
	resetMockOpportunities
} from '../../support/fixtures';
import {JahiaNode} from '../../support/fixtures/types';
import {useFormidableSite} from '../support/useFormidableSite';

/**
 * The "Create Efficy Opportunity" action end to end, against the mock Efficy server
 * (tests/efficy-mock). Skips itself when the mock or the `mock` connection is not
 * available on the instance, so the suite stays green on a bare CI instance.
 */
const OPPORTUNITY_FORM_ELEMENTS: JahiaNode[] = [
	getInputTextNode({name: 'fullName', title: 'Full name', placeholder: 'Full name'}),
	getInputEmailNode({name: 'email', title: 'Email'}),
	getTextareaNode({name: 'message', title: 'Message'}),
	getInputNumberNode({name: 'employees', title: 'Employees'}),
	getCheckboxNode({name: 'doNotCall', title: 'Do not call me', choices: [{value: 'yes', label: 'Do not call me', selected: false}]}),
	getSelectNode({
		name: 'industry',
		title: 'Industry',
		options: [
			{value: '', label: 'Choose', selected: false},
			{value: '000000000086cdda', label: 'Santé', selected: false},
			{value: '000000000086cde1', label: 'Prévoyance', selected: false}
		]
	})
];

const FULL_MAPPING = [
	{effField: 'OppTitle', effType: 'string', fieldName: 'fullName'},
	{effField: 'OppDetail', effType: 'text', fieldName: 'message'},
	{effField: 'OppPerID', effType: 'reference', source: 'personByEmail' as const, fieldName: 'email'},
	{effField: 'OppStoID', effType: 'referential', source: 'constant' as const, value: '000000000000074f'},
	{effField: 'OppOpbID', effType: 'referential', source: 'constant' as const, value: '0000000000000b54'},
	{effField: 'OppDate', effType: 'date', source: 'today' as const},
	{effField: 'OppStake', effType: 'number', fieldName: 'employees'},
	{effField: 'OppGammeShouhaitee_', effType: 'referential-multi', fieldName: 'industry'},
	{effField: 'OppNumRef', effType: 'string', fieldName: 'removedField'}
];

describe('Actions - 84 Create Efficy Opportunity', () => {
	useFormidableSite();

	let mockAvailable = false;

	before(() => {
		cy.login();
		isEfficyMockAvailable().then(available => {
			mockAvailable = available;
			if (!available) {
				cy.log('Efficy mock or "mock" connection unavailable: the Efficy action specs are skipped');
			}
		});
		cy.logout();
	});

	beforeEach(function () {
		if (!mockAvailable) {
			this.skip();
		}

		resetMockOpportunities();
	});

	it('creates an opportunity from the mapped fields, constants, submission date and person lookup', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`eff-create-${suffix}`, 'Efficy create', OPPORTUNITY_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateOpportunityActionNode({mapping: FULL_MAPPING})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Ada Lovelace - Assurance Vie');
			// The mock knows this person: PerID 0000000000005f14, enterprise 0000000000004fb3.
			form.getEmailInput('email').type('ada@example.com');
			form.getTextarea('message').type('Please call me back about a life insurance');
			form.getNumberInput('employees').type('8000');
			form.getSelectInput('industry').select('Prévoyance');
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			readMockOpportunities().should(opps => {
				expect(opps).to.have.length(1);
				const opp = opps[0];
				expect(opp.OppTitle).to.equal('Ada Lovelace - Assurance Vie');
				expect(opp.OppDetail).to.equal('Please call me back about a life insurance');
				expect(opp.OppPerID).to.equal('0000000000005f14');
				// The enterprise comes from the person found by email, since OppEntID is not mapped.
				expect(opp.OppEntID).to.equal('0000000000004fb3');
				expect(opp.OppStoID).to.equal('000000000000074f');
				expect(opp.OppOpbID).to.equal('0000000000000b54');
				expect(opp.OppDate).to.match(/^\d{4}-\d{2}-\d{2}$/);
				expect(opp.OppStake).to.equal(8000);
				expect(opp.OppGammeShouhaitee_).to.deep.equal(['000000000086cde1']);
				// The row pointing at a field that does not exist is skipped, not sent as empty.
				expect(opp).not.to.have.property('OppNumRef');
			});
		});
	});

	it('falls back to the connection defaults when the email matches no Efficy person', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`eff-defaults-${suffix}`, 'Efficy defaults', OPPORTUNITY_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateOpportunityActionNode({mapping: FULL_MAPPING})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Unknown Person');
			form.getEmailInput('email').type(`nobody-${suffix}@example.com`);
			form.getNumberInput('employees').type('100');
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			readMockOpportunities().should(opps => {
				expect(opps).to.have.length(1);
				expect(opps[0].OppPerID).to.equal('0000000000000001');
				expect(opps[0].OppEntID).to.equal('0000000000000001');
			});
		});
	});

	it('fails the submission when Efficy rejects the opportunity and the action is set to fail', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`eff-reject-${suffix}`, 'Efficy reject', OPPORTUNITY_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateOpportunityActionNode({mapping: FULL_MAPPING, failSubmissionOnError: true})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			// The mock refuses this title with BEAN_VALIDATION_ERROR.
			form.getTextInput('fullName').type('REJECT');
			form.getEmailInput('email').type('ada@example.com');
			form.getNumberInput('employees').type('1');
			form.submit();
			form.waitForSubmit();
			form.getErrorMessage().should('be.visible');

			readMockOpportunities().should(opps => expect(opps).to.have.length(0));
		});
	});

	it('accepts the submission and only logs when the action is set not to fail', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`eff-lenient-${suffix}`, 'Efficy lenient', OPPORTUNITY_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateOpportunityActionNode({mapping: FULL_MAPPING, failSubmissionOnError: false})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('REJECT');
			form.getEmailInput('email').type('ada@example.com');
			form.getNumberInput('employees').type('1');
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			readMockOpportunities().should(opps => expect(opps).to.have.length(0));
		});
	});

	it('refuses to send an opportunity missing a required catalog field', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`eff-required-${suffix}`, 'Efficy required', OPPORTUNITY_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateOpportunityActionNode({
				// No OppStake: required by the catalog.
				mapping: FULL_MAPPING.filter(row => row.effField !== 'OppStake'),
				failSubmissionOnError: true
			})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('No amount');
			form.getEmailInput('email').type('ada@example.com');
			form.submit();
			form.waitForSubmit();
			form.getErrorMessage().should('be.visible');

			readMockOpportunities().should(opps => expect(opps).to.have.length(0));
		});
	});
});
