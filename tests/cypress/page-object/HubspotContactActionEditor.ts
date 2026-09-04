import {ContentEditor} from '@jahia/jcontent-cypress/dist/page-object';
import {FORMIDABLE_TEST_SITE} from '../support/fixtures/site';
import {HubspotContactMappingField} from './HubspotContactMappingField';

/** Content Editor opened on a fmdbhs:createContactAction node. */
export class HubspotContactActionEditor {
	constructor(private readonly contentEditor: ContentEditor) {}

	static visit(actionPath: string): HubspotContactActionEditor {
		const contentEditor = ContentEditor.visit(actionPath, FORMIDABLE_TEST_SITE.key, 'en', 'content-folders/contents');
		return new HubspotContactActionEditor(contentEditor);
	}

	get mappingField(): HubspotContactMappingField {
		const field = new HubspotContactMappingField(
			cy.get(HubspotContactMappingField.defaultSelector, {timeout: 30000}).should('be.visible')
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
