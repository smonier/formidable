import {EfficyOpportunityActionEditor} from '../../page-object';
import {createFormNode} from '../../support/fixtures/forms';
import {
	getCreateOpportunityActionNode,
	getInputEmailNode,
	getInputTextNode,
	getTextareaNode,
	isEfficyMockAvailable
} from '../../support/fixtures';
import {CONTENT_PATH} from '../../support/constants';
import {useFormidableSite} from '../support/useFormidableSite';

/**
 * The EfficyOpportunityMapping selector in Content Editor: lists the opportunity properties of the
 * selected connection and the form's own fields, auto-maps by name, flags the properties the connection does not expose, and persists the mapping. Needs the mock Efficy (see spec 80).
 */
const FORM_ELEMENTS = [
	getInputTextNode({name: 'fullName', title: 'Full name', placeholder: 'Full name'}),
	getInputEmailNode({name: 'email', title: 'Email'}),
	getTextareaNode({name: 'message', title: 'Message'})
];

describe('Actions - 85 Efficy opportunity mapping selector', () => {
	useFormidableSite();

	let mockAvailable = false;

	before(() => {
		cy.login();
		isEfficyMockAvailable().then(available => {
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
		const formName = `eff-selector-${suffix}`;
		return createFormNode(formName, formName, FORM_ELEMENTS, {
			actions: [getCreateOpportunityActionNode({name: 'efficyOpportunity'})]
		}).then(() => `${CONTENT_PATH}/${formName}/actions/efficyOpportunity`);
	};

	it('lists the catalog fields with their required marks and the form fields', () => {
		createActionForm(`${Date.now()}-list`).then(actionPath => {
			const editor = EfficyOpportunityActionEditor.visit(actionPath);
			const mapping = editor.mappingField;

			mapping.shouldHaveRowCount(0);
			mapping.addRow();
			mapping.shouldHaveRowCount(1);

			mapping.openDropdown(0, 0);
			// The connection catalog, required fields first and starred.
			mapping.menuShouldHaveItems(['Title *', 'Amount *', 'State *', 'Details', 'Reference number']);
			mapping.menuShouldNotHaveItems(['OppID']);
			mapping.selectMenuItem('Details');

			mapping.openDropdown(0, 2);
			mapping.menuShouldHaveItems(['Full name', 'Email', 'Message']);
			mapping.selectMenuItem('Email');
			mapping.closeMenu();

			mapping.warningsShouldContain('Title');
			mapping.warningsShouldContain('Amount');

			// Refetching the catalog and referentials keeps the authored rows and the field list.
			mapping.refreshEfficyFields();
			mapping.shouldHaveRowCount(1);
			mapping.openDropdown(0, 0);
			mapping.menuShouldHaveItems(['Title *', 'Amount *']);
			mapping.closeMenu();

			editor.cancelAndDiscard();
		});
	});

	it('auto-maps by name, accepts constants and persists the mapping', () => {
		createActionForm(`${Date.now()}-automap`).then(actionPath => {
			const editor = EfficyOpportunityActionEditor.visit(actionPath);
			const mapping = editor.mappingField;

			mapping.autoMap();
			// email -> Person (id) by email lookup, Date -> submission date, message -> Details
			mapping.shouldHaveRowCount(3);
			// Catalog labels are language independent (source labels follow the UI language).
			mapping.rowShouldContainText(0, 'Date');
			mapping.rowShouldContainText(1, 'Person (id)');
			mapping.rowShouldContainText(1, 'Email');
			mapping.rowShouldContainText(2, 'Details');
			mapping.rowShouldContainText(2, 'Message');

			mapping.addRow();
			mapping.selectEfficyField(3, 'Title');
			mapping.selectSource(3, 'Constant');
			mapping.typeConstant(3, 'ACME');

			editor.save();
			editor.cancel();

			const reopened = EfficyOpportunityActionEditor.visit(actionPath);
			reopened.mappingField.shouldHaveRowCount(4);
			reopened.mappingField.rowShouldContainText(3, 'Title');
			reopened.mappingField.getRow(3).get().find('input').first().should('have.value', 'ACME');
			reopened.cancel();
		});
	});
});
