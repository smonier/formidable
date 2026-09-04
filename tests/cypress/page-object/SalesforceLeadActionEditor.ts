import {ContentEditor} from '@jahia/jcontent-cypress/dist/page-object';
import {FORMIDABLE_TEST_SITE} from '../support/fixtures/site';
import {SalesforceLeadMappingField} from './SalesforceLeadMappingField';

/** Content Editor opened on a fmdbsfdc:createLeadAction node. */
export class SalesforceLeadActionEditor {
	constructor(private readonly contentEditor: ContentEditor) {}

	static visit(actionPath: string): SalesforceLeadActionEditor {
		const contentEditor = ContentEditor.visit(actionPath, FORMIDABLE_TEST_SITE.key, 'en', 'content-folders/contents');
		return new SalesforceLeadActionEditor(contentEditor);
	}

	get mappingField(): SalesforceLeadMappingField {
		const field = new SalesforceLeadMappingField(
			cy.get(SalesforceLeadMappingField.defaultSelector, {timeout: 30000}).should('be.visible')
		);
		field.waitUntilReady();
		return field;
	}

	/**
	 * Saves and waits for the confirmation toast without asserting its text: the jcontent page
	 * object checks the English wording, which fails on an instance whose root user has another
	 * UI language.
	 */
	save(): void {
		this.contentEditor.saveUnchecked();
		cy.get('#dialog-errorBeforeSave', {timeout: 1000}).should('not.exist');
		cy.get('[role="alertdialog"]', {timeout: 15000}).should('be.visible');
	}

	cancel(): void {
		this.contentEditor.cancel();
	}

	cancelAndDiscard(): void {
		this.contentEditor.cancelAndDiscard();
	}
}
