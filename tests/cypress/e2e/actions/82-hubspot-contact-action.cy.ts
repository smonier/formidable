import {createPublishedLiveFormPage, visitLiveForm} from '../../support/fixtures/forms';
import {
	getCheckboxNode,
	getCreateContactActionNode,
	getInputEmailNode,
	getInputNumberNode,
	getInputTextNode,
	getSelectNode,
	getTextareaNode,
	isHubspotMockAvailable,
	publishAndWaitLive,
	readMockContacts,
	resetMockContacts
} from '../../support/fixtures';
import {JahiaNode} from '../../support/fixtures/types';
import {useFormidableSite} from '../support/useFormidableSite';

/**
 * The "Create HubSpot Contact" action end to end, against the mock HubSpot server
 * (tests/hubspot-mock). Skips itself when the mock or the `mock` connection is not
 * available on the instance, so the suite stays green on a bare CI instance.
 */
const CONTACT_FORM_ELEMENTS: JahiaNode[] = [
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
			{value: 'BANKING', label: 'Banking', selected: false},
			{value: 'TECHNOLOGY', label: 'Technology', selected: false}
		]
	})
];

const FULL_MAPPING = [
	{hsProperty: 'lastname', hsType: 'string', hsFieldType: 'text', fieldName: 'fullName'},
	{hsProperty: 'company', hsType: 'string', hsFieldType: 'text', source: 'constant' as const, value: 'Cypress Corp'},
	{hsProperty: 'email', hsType: 'string', hsFieldType: 'text', fieldName: 'email'},
	{hsProperty: 'message', hsType: 'string', hsFieldType: 'textarea', fieldName: 'message'},
	{hsProperty: 'numemployees', hsType: 'number', hsFieldType: 'number', fieldName: 'employees'},
	{hsProperty: 'newsletter_optin', hsType: 'bool', hsFieldType: 'booleancheckbox', fieldName: 'doNotCall'},
	{hsProperty: 'industry', hsType: 'enumeration', hsFieldType: 'select', fieldName: 'industry'},
	{hsProperty: 'hs_contact_status', hsType: 'enumeration', hsFieldType: 'select', source: 'constant' as const, value: 'NEW'},
	{hsProperty: 'phone', hsType: 'string', hsFieldType: 'phonenumber', fieldName: 'removedField'}
];

describe('Actions - 82 Create HubSpot Contact', () => {
	useFormidableSite();

	let mockAvailable = false;

	before(() => {
		cy.login();
		isHubspotMockAvailable().then(available => {
			mockAvailable = available;
			if (!available) {
				cy.log('HubSpot mock or "mock" connection unavailable: the HubSpot action specs are skipped');
			}
		});
		cy.logout();
	});

	beforeEach(function () {
		if (!mockAvailable) {
			this.skip();
		}

		resetMockContacts();
	});

	it('creates a contact from the mapped fields, constants and coerced types', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`hs-create-${suffix}`, 'HubSpot create', CONTACT_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateContactActionNode({mapping: FULL_MAPPING})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Ada Lovelace');
			form.getEmailInput('email').type(`ada-${suffix}@example.com`);
			form.getTextarea('message').type('Please call me back about Formidable');
			form.getNumberInput('employees').type('250');
			form.getCheckbox('doNotCall').check();
			form.getSelectInput('industry').select('Technology');
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			readMockContacts().should(contacts => {
				expect(contacts).to.have.length(1);
				const props = contacts[0].properties;
				expect(props.lastname).to.equal('Ada Lovelace');
				expect(props.company).to.equal('Cypress Corp');
				expect(props.email).to.equal(`ada-${suffix}@example.com`);
				expect(props.message).to.equal('Please call me back about Formidable');
				// HubSpot takes every value as a string; numbers and booleans are normalised strings.
				expect(props.numemployees).to.equal('250');
				expect(props.newsletter_optin).to.equal('true');
				expect(props.industry).to.equal('TECHNOLOGY');
				expect(props.hs_contact_status).to.equal('NEW');
				// The row pointing at a field that does not exist is skipped, not sent as empty.
				expect(props).not.to.have.property('phone');
			});
		});
	});

	it('sends "false" for an unchecked boolean and omits blank values', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`hs-blank-${suffix}`, 'HubSpot blanks', CONTACT_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateContactActionNode({mapping: FULL_MAPPING})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Blank Fields');
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			readMockContacts().should(contacts => {
				expect(contacts).to.have.length(1);
				expect(contacts[0].properties.newsletter_optin).to.equal('false');
				expect(contacts[0].properties).not.to.have.property('email');
				expect(contacts[0].properties).not.to.have.property('numemployees');
				expect(contacts[0].properties).not.to.have.property('industry');
			});
		});
	});

	it('updates the existing contact with the same email when the strategy is upsertByEmail', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`hs-upsert-${suffix}`, 'HubSpot upsert', CONTACT_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateContactActionNode({mapping: FULL_MAPPING, duplicateStrategy: 'upsertByEmail'})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const email = `upsert-${suffix}@example.com`;
			let form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('First Name');
			form.getEmailInput('email').type(email);
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Second Name');
			form.getEmailInput('email').type(email);
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			readMockContacts().should(contacts => {
				expect(contacts).to.have.length(1);
				expect(contacts[0].properties.lastname).to.equal('Second Name');
				expect(contacts[0]._updated).to.equal(true);
			});
		});
	});

	it('fails the submission when HubSpot rejects the contact and the action is set to fail', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`hs-reject-${suffix}`, 'HubSpot reject', CONTACT_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateContactActionNode({mapping: FULL_MAPPING, failSubmissionOnError: true})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Rejected Contact');
			// The mock refuses this address with VALIDATION_ERROR.
			form.getEmailInput('email').type('reject@example.com');
			form.submit();
			form.waitForSubmit();
			form.getErrorMessage().should('be.visible');

			readMockContacts().should(contacts => expect(contacts).to.have.length(0));
		});
	});

	it('accepts the submission and only logs when the action is set not to fail', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`hs-lenient-${suffix}`, 'HubSpot lenient', CONTACT_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateContactActionNode({mapping: FULL_MAPPING, failSubmissionOnError: false})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Lenient Contact');
			form.getEmailInput('email').type('reject@example.com');
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			readMockContacts().should(contacts => expect(contacts).to.have.length(0));
		});
	});

	it('fails on a duplicate email when the strategy is create (HubSpot 409 CONFLICT)', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`hs-duplicate-${suffix}`, 'HubSpot duplicate', CONTACT_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateContactActionNode({mapping: FULL_MAPPING, duplicateStrategy: 'create', failSubmissionOnError: true})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const email = `dup-${suffix}@example.com`;
			let form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('First');
			form.getEmailInput('email').type(email);
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Second');
			form.getEmailInput('email').type(email);
			form.submit();
			form.waitForSubmit();
			form.getErrorMessage().should('be.visible');

			readMockContacts().should(contacts => {
				expect(contacts).to.have.length(1);
				expect(contacts[0].properties.lastname).to.equal('First');
			});
		});
	});
});
