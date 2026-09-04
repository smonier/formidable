import {createPublishedLiveFormPage, visitLiveForm} from '../../support/fixtures/forms';
import {
	getCheckboxNode,
	getCreateLeadActionNode,
	getInputEmailNode,
	getInputNumberNode,
	getInputTextNode,
	getSelectNode,
	getTextareaNode,
	isSalesforceMockAvailable,
	publishAndWaitLive,
	readMockLeads,
	resetMockLeads
} from '../../support/fixtures';
import {JahiaNode} from '../../support/fixtures/types';
import {useFormidableSite} from '../support/useFormidableSite';

/**
 * The "Create Salesforce Lead" action end to end, against the mock Salesforce server
 * (tests/salesforce-mock). Skips itself when the mock or the `mock` connection is not
 * available on the instance, so the suite stays green on a bare CI instance.
 */
const LEAD_FORM_ELEMENTS: JahiaNode[] = [
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
			{value: 'Banking', label: 'Banking', selected: false},
			{value: 'Technology', label: 'Technology', selected: false}
		]
	})
];

const FULL_MAPPING = [
	{sfField: 'LastName', sfType: 'string', fieldName: 'fullName'},
	{sfField: 'Company', sfType: 'string', source: 'constant' as const, value: 'Cypress Corp'},
	{sfField: 'Email', sfType: 'email', fieldName: 'email'},
	{sfField: 'Description', sfType: 'textarea', fieldName: 'message'},
	{sfField: 'NumberOfEmployees', sfType: 'int', fieldName: 'employees'},
	{sfField: 'DoNotCall', sfType: 'boolean', fieldName: 'doNotCall'},
	{sfField: 'Industry', sfType: 'picklist', fieldName: 'industry'},
	{sfField: 'LeadSource', sfType: 'picklist', source: 'constant' as const, value: 'Web'},
	{sfField: 'Phone', sfType: 'phone', fieldName: 'removedField'}
];

describe('Actions - 80 Create Salesforce Lead', () => {
	useFormidableSite();

	let mockAvailable = false;

	before(() => {
		cy.login();
		isSalesforceMockAvailable().then(available => {
			mockAvailable = available;
			if (!available) {
				cy.log('Salesforce mock or "mock" connection unavailable: the Salesforce action specs are skipped');
			}
		});
		cy.logout();
	});

	beforeEach(function () {
		if (!mockAvailable) {
			this.skip();
		}

		resetMockLeads();
	});

	it('creates a lead from the mapped fields, constants and coerced types', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`sfdc-create-${suffix}`, 'Salesforce create', LEAD_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateLeadActionNode({mapping: FULL_MAPPING})],
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

			readMockLeads().should(leads => {
				expect(leads).to.have.length(1);
				const lead = leads[0];
				expect(lead.LastName).to.equal('Ada Lovelace');
				expect(lead.Company).to.equal('Cypress Corp');
				expect(lead.Email).to.equal(`ada-${suffix}@example.com`);
				expect(lead.Description).to.equal('Please call me back about Formidable');
				expect(lead.NumberOfEmployees).to.equal(250);
				expect(lead.DoNotCall).to.equal(true);
				expect(lead.Industry).to.equal('Technology');
				expect(lead.LeadSource).to.equal('Web');
				// The row pointing at a field that does not exist is skipped, not sent as empty.
				expect(lead).not.to.have.property('Phone');
			});
		});
	});

	it('sends false for an unchecked boolean and omits blank values', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`sfdc-blank-${suffix}`, 'Salesforce blanks', LEAD_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateLeadActionNode({mapping: FULL_MAPPING})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Blank Fields');
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			readMockLeads().should(leads => {
				expect(leads).to.have.length(1);
				expect(leads[0].DoNotCall).to.equal(false);
				expect(leads[0]).not.to.have.property('Email');
				expect(leads[0]).not.to.have.property('NumberOfEmployees');
				expect(leads[0]).not.to.have.property('Industry');
			});
		});
	});

	it('updates the existing lead with the same email when the strategy is upsertByEmail', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`sfdc-upsert-${suffix}`, 'Salesforce upsert', LEAD_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateLeadActionNode({mapping: FULL_MAPPING, duplicateStrategy: 'upsertByEmail'})],
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

			readMockLeads().should(leads => {
				expect(leads).to.have.length(1);
				expect(leads[0].LastName).to.equal('Second Name');
				expect(leads[0]._updated).to.equal(true);
			});
		});
	});

	it('fails the submission when Salesforce rejects the lead and the action is set to fail', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`sfdc-reject-${suffix}`, 'Salesforce reject', LEAD_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateLeadActionNode({mapping: FULL_MAPPING, failSubmissionOnError: true})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Rejected Lead');
			// The mock refuses this address with FIELD_CUSTOM_VALIDATION_EXCEPTION.
			form.getEmailInput('email').type('reject@example.com');
			form.submit();
			form.waitForSubmit();
			form.getErrorMessage().should('be.visible');

			readMockLeads().should(leads => expect(leads).to.have.length(0));
		});
	});

	it('accepts the submission and only logs when the action is set not to fail', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`sfdc-lenient-${suffix}`, 'Salesforce lenient', LEAD_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateLeadActionNode({mapping: FULL_MAPPING, failSubmissionOnError: false})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getTextInput('fullName').type('Lenient Lead');
			form.getEmailInput('email').type('reject@example.com');
			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			readMockLeads().should(leads => expect(leads).to.have.length(0));
		});
	});

	it('refuses to send a lead missing the required LastName or Company', () => {
		const suffix = Date.now();
		createPublishedLiveFormPage(`sfdc-required-${suffix}`, 'Salesforce required', LEAD_FORM_ELEMENTS, undefined, undefined, {
			actions: [getCreateLeadActionNode({
				mapping: [{sfField: 'Email', sfType: 'email', fieldName: 'email'}],
				failSubmissionOnError: true
			})],
			publish: publishAndWaitLive
		}).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			form.getEmailInput('email').type(`no-name-${suffix}@example.com`);
			form.submit();
			form.waitForSubmit();
			form.getErrorMessage().should('be.visible');

			readMockLeads().should(leads => expect(leads).to.have.length(0));
		});
	});
});
