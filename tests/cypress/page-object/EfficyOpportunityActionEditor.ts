import {ContentEditor} from '@jahia/jcontent-cypress/dist/page-object';
import {FORMIDABLE_TEST_SITE} from '../support/fixtures/site';
import {EfficyOpportunityMappingField} from './EfficyOpportunityMappingField';

/** Content Editor opened on a fmdbeff:createOpportunityAction node. */
export class EfficyOpportunityActionEditor {
	constructor(private readonly contentEditor: ContentEditor) {}

	static visit(actionPath: string): EfficyOpportunityActionEditor {
		const contentEditor = ContentEditor.visit(actionPath, FORMIDABLE_TEST_SITE.key, 'en', 'content-folders/contents');
		return new EfficyOpportunityActionEditor(contentEditor);
	}

	get mappingField(): EfficyOpportunityMappingField {
		const field = new EfficyOpportunityMappingField(
			cy.get(EfficyOpportunityMappingField.defaultSelector, {timeout: 30000}).should('be.visible')
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
