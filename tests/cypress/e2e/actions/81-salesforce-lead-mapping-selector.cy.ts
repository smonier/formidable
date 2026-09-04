import {SalesforceLeadActionEditor} from '../../page-object';
import {createFormNode} from '../../support/fixtures/forms';
import {
	getCreateLeadActionNode,
	getInputEmailNode,
	getInputTextNode,
	getTextareaNode,
	isSalesforceMockAvailable
} from '../../support/fixtures';
import {CONTENT_PATH} from '../../support/constants';
import {useFormidableSite} from '../support/useFormidableSite';

/**
 * The SalesforceLeadMapping selector in Content Editor: lists the Lead fields of the
 * selected connection and the form's own fields, auto-maps by name, warns about required
 * Salesforce fields, and persists the mapping. Needs the mock Salesforce (see spec 80).
 */
const FORM_ELEMENTS = [
	getInputTextNode({name: 'fullName', title: 'Full name', placeholder: 'Full name'}),
	getInputEmailNode({name: 'email', title: 'Email'}),
	getTextareaNode({name: 'message', title: 'Message'})
];

describe('Actions - 81 Salesforce lead mapping selector', () => {
	useFormidableSite();

	let mockAvailable = false;

	before(() => {
		cy.login();
		isSalesforceMockAvailable().then(available => {
			mockAvailable = available;
		});
		cy.logout();
	});

	beforeEach(function () {
		if (!mockAvailable) {
			this.skip();
		}
	});

	const createActionForm = (suffix: string) => {
		const formName = `sfdc-selector-${suffix}`;
		return createFormNode(formName, formName, FORM_ELEMENTS, {
			actions: [getCreateLeadActionNode({name: 'salesforceLead'})]
		}).then(() => `${CONTENT_PATH}/${formName}/actions/salesforceLead`);
	};

	it('lists Salesforce Lead fields and form fields, and warns about required fields', () => {
		createActionForm(`${Date.now()}-list`).then(actionPath => {
			const editor = SalesforceLeadActionEditor.visit(actionPath);
			const mapping = editor.mappingField;

			mapping.shouldHaveRowCount(0);
			mapping.addRow();
			mapping.shouldHaveRowCount(1);

			mapping.openDropdown(0, 0);
			// Required fields first, flagged with an asterisk; system fields never offered.
			mapping.menuShouldHaveItems(['Company *', 'Last Name *', 'Email', 'Lead Source']);
			mapping.menuShouldNotHaveItems(['Lead ID', 'Converted']);
			mapping.selectMenuItem('Email');

			mapping.openDropdown(0, 2);
			mapping.menuShouldHaveItems(['Full name', 'Email', 'Message']);
			mapping.selectMenuItem('Email');
			mapping.closeMenu();

			mapping.warningsShouldContain('Company');
			mapping.warningsShouldContain('Last Name');

			// Refetching the Salesforce fields keeps the authored rows and the field list.
			mapping.refreshSalesforceFields();
			mapping.shouldHaveRowCount(1);
			mapping.openDropdown(0, 0);
			mapping.menuShouldHaveItems(['Company *', 'Last Name *']);
			mapping.closeMenu();

			editor.cancelAndDiscard();
		});
	});

	it('auto-maps by name, accepts constants and persists the mapping', () => {
		createActionForm(`${Date.now()}-automap`).then(actionPath => {
			const editor = SalesforceLeadActionEditor.visit(actionPath);
			const mapping = editor.mappingField;

			mapping.autoMap();
			// fullName -> LastName, email -> Email, message -> Description
			mapping.shouldHaveRowCount(3);
			mapping.rowShouldContainText(0, 'Last Name');
			mapping.rowShouldContainText(0, 'Full name');
			mapping.warningsShouldContain('Company');
			mapping.warningsShouldNotContain('Last Name');

			mapping.addRow();
			mapping.selectSalesforceField(3, 'Company');
			mapping.selectSource(3, 'Constant');
			mapping.typeConstant(3, 'ACME');
			mapping.warningsShouldNotContain('Company');

			editor.save();
			editor.cancel();

			const reopened = SalesforceLeadActionEditor.visit(actionPath);
			reopened.mappingField.shouldHaveRowCount(4);
			reopened.mappingField.rowShouldContainText(3, 'Company');
			reopened.mappingField.getRow(3).get().find('input').first().should('have.value', 'ACME');
			reopened.cancel();
		});
	});
});
