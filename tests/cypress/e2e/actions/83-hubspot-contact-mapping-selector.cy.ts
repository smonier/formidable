import {HubspotContactActionEditor} from '../../page-object';
import {createFormNode} from '../../support/fixtures/forms';
import {
	getCreateContactActionNode,
	getInputEmailNode,
	getInputTextNode,
	getTextareaNode,
	isHubspotMockAvailable
} from '../../support/fixtures';
import {CONTENT_PATH} from '../../support/constants';
import {useFormidableSite} from '../support/useFormidableSite';

/**
 * The HubspotContactMapping selector in Content Editor: lists the contact properties of the
 * selected connection and the form's own fields, auto-maps by name, flags the properties the connection does not expose, and persists the mapping. Needs the mock HubSpot (see spec 80).
 */
const FORM_ELEMENTS = [
	getInputTextNode({name: 'fullName', title: 'Full name', placeholder: 'Full name'}),
	getInputEmailNode({name: 'email', title: 'Email'}),
	getTextareaNode({name: 'message', title: 'Message'})
];

describe('Actions - 83 HubSpot contact mapping selector', () => {
	useFormidableSite();

	let mockAvailable = false;

	before(() => {
		cy.login();
		isHubspotMockAvailable().then(available => {
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
		const formName = `hs-selector-${suffix}`;
		return createFormNode(formName, formName, FORM_ELEMENTS, {
			actions: [getCreateContactActionNode({name: 'hubspotContact'})]
		}).then(() => `${CONTENT_PATH}/${formName}/actions/hubspotContact`);
	};

	it('lists writable HubSpot contact properties and the form fields', () => {
		createActionForm(`${Date.now()}-list`).then(actionPath => {
			const editor = HubspotContactActionEditor.visit(actionPath);
			const mapping = editor.mappingField;

			mapping.shouldHaveRowCount(0);
			mapping.addRow();
			mapping.shouldHaveRowCount(1);

			mapping.openDropdown(0, 0);
			// Writable properties only: read-only, calculated and hidden ones are never offered.
			mapping.menuShouldHaveItems(['Company Name', 'Last Name', 'Email', 'Lead Status']);
			mapping.menuShouldNotHaveItems(['Record ID', 'Score', 'Hidden']);
			mapping.selectMenuItem('Email');

			mapping.openDropdown(0, 2);
			mapping.menuShouldHaveItems(['Full name', 'Email', 'Message']);
			mapping.selectMenuItem('Email');
			mapping.closeMenu();

			// HubSpot has no required contact property, so no required warning appears.
			mapping.warningsShouldNotContain('Required');

			// Refetching the HubSpot properties keeps the authored rows and the property list.
			mapping.refreshHubspotFields();
			mapping.shouldHaveRowCount(1);
			mapping.openDropdown(0, 0);
			mapping.menuShouldHaveItems(['Company Name', 'Last Name']);
			mapping.closeMenu();

			editor.cancelAndDiscard();
		});
	});

	it('auto-maps by name, accepts constants and persists the mapping', () => {
		createActionForm(`${Date.now()}-automap`).then(actionPath => {
			const editor = HubspotContactActionEditor.visit(actionPath);
			const mapping = editor.mappingField;

			mapping.autoMap();
			// fullName -> lastname, email -> email, message -> message (label order: Email, Last Name, Message)
			mapping.shouldHaveRowCount(3);
			mapping.rowShouldContainText(1, 'Last Name');
			mapping.rowShouldContainText(1, 'Full name');

			mapping.addRow();
			mapping.selectHubspotField(3, 'Company Name');
			mapping.selectSource(3, 'Constant');
			mapping.typeConstant(3, 'ACME');

			editor.save();
			editor.cancel();

			const reopened = HubspotContactActionEditor.visit(actionPath);
			reopened.mappingField.shouldHaveRowCount(4);
			reopened.mappingField.rowShouldContainText(3, 'Company Name');
			reopened.mappingField.getRow(3).get().find('input').first().should('have.value', 'ACME');
			reopened.cancel();
		});
	});
});
