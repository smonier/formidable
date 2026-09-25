import {
	createPublishedLiveFormPage,
	getEmailNotificationActionNode,
	getForwardActionNode,
	getInputTextNode,
	getLogSubmissionActionNode,
	getSaveToJcrActionNode,
	visitEditForm,
	visitLiveForm
} from '../../support/fixtures';
import {useFormidableSite} from '../support/useFormidableSite';

/** A forward target of the module configuration, the choicelist the forward action's targetId is fed from. */
const FORWARD_TARGET = {id: 'crm01', label: 'Salesforce Marketing', url: 'https://crm.example.com/hook'};

const setForwardTargets = (lines: string): Cypress.Chainable => cy.runProvisioningScript({
	script: {
		fileContent: JSON.stringify([{editConfiguration: 'org.jahia.modules.formidable', properties: {forwardTargets: lines}}]),
		type: 'application/json'
	}
});

/**
 * A form placed on a page shows its actions while authoring: the Page Builder renders the
 * form's action list as a zone under the buttons — one card per action (title, telling
 * parameter, type description), in execution order, and the list's own create button, whose
 * module declares the accepted type to jContent. A choice parameter shows the label its
 * choicelist gives it (the forward target's), a third-party action (the test module's) gets
 * its card the same way, from what its own module declares for the Content Editor. A form
 * without any action is called out, since its submissions go nowhere. Nothing of the zone
 * exists in live or in preview, not even when its views are requested directly, as fragments.
 */
describe('Actions - 71 Form actions zone in the Page Builder', () => {
	useFormidableSite();

	before(() => {
		setForwardTargets(`${FORWARD_TARGET.id}|${FORWARD_TARGET.label}|${FORWARD_TARGET.url}`);
	});

	after(() => {
		// The configuration is instance-global: back to the shipped default.
		setForwardTargets('');
	});

	it('lists the actions of the form with their create button, in edit mode only', () => {
		createPublishedLiveFormPage(
			'actions-zone-form',
			'Actions Zone Form',
			[getInputTextNode({name: 'zoneField', title: 'Zone field'})],
			undefined,
			undefined,
			{
				actions: [
					getEmailNotificationActionNode({name: 'notifySales', title: 'Notify sales', to: 'sales@example.com'}),
					getSaveToJcrActionNode(),
					getLogSubmissionActionNode(),
					getForwardActionNode({name: 'forwardToCrm', title: 'Forward to CRM', targetId: FORWARD_TARGET.id})
				]
			}
		).then(({pagePath, livePath, formPath}) => {
			visitEditForm(pagePath);

			cy.get('.fmdb-authoring-actions').should('have.length', 1).within(() => {
				cy.get('.fmdb-authoring-action').should('have.length', 4);
				// Execution order: the email first, the save second.
				cy.get('.fmdb-authoring-action').eq(0).within(() => {
					cy.get('.fmdb-authoring-action-title').should('have.text', 'Notify sales');
					cy.get('.fmdb-authoring-action-detail').should('have.text', 'sales@example.com');
					// The type description is the tooltip the engine declares for the Content Editor.
					cy.get('.fmdb-authoring-action-description').should('contain.text', 'Sends an email notification');
				});
				cy.get('.fmdb-authoring-action').eq(1).within(() => {
					// No title given: the type label stands in, and this type has no telling parameter.
					cy.get('.fmdb-authoring-action-title').should('have.text', 'Save to JCR');
					cy.get('.fmdb-authoring-action-detail').should('not.exist');
				});
				// Third-party contract: the test module's type is dispatched to the mixin view, and
				// its label, tooltip and icon are read from THAT module (its resource bundle, its icons).
				cy.get('.fmdb-authoring-action').eq(2)
					.should('have.attr', 'data-fmdb-action-type', 'fmdbsample:logSubmissionAction')
					.within(() => {
						cy.get('.fmdb-authoring-action-title').should('have.text', 'Log submission action');
						cy.get('.fmdb-authoring-action-description').should('contain.text', 'Writes each submission to the server log');
						cy.get('.fmdb-authoring-action-icon').should('have.attr', 'src')
							.and('include', '/modules/formidable-test-module-samples-java/icons/fmdbsample_logSubmissionAction.png');
					});
				// A choice parameter: the stored target id is shown as the label its choicelist
				// (fed by the module configuration) gives it, resolved through the engine's initializer.
				cy.get('.fmdb-authoring-action').eq(3).within(() => {
					cy.get('.fmdb-authoring-action-title').should('have.text', 'Forward to CRM');
					cy.get('.fmdb-authoring-action-detail').should('have.text', FORWARD_TARGET.label);
				});
				cy.get('.fmdb-authoring-actions-empty').should('not.exist');
			});

			// The authoring views guard themselves: asked for directly in live or in preview (two
			// controllers, one guard), they render — successfully — nothing of the zone. Asked for as
			// fragments (.ajax): a plain render URL names a page template after the view and is a 404
			// before the view ever runs. The status is checked too: an error page would lack the markup
			// as well.
			[
				{mode: 'live', base: '/cms/render/live/en'},
				{mode: 'preview', base: '/cms/preview/default/en'}
			].forEach(({mode, base}) => {
				[`${formPath}/actions`, `${formPath}/actions/notifySales`].forEach(path => {
					cy.request(`${base}${path}.hidden.authoring.html.ajax`).then(response => {
						expect(response.status, `${mode} render of ${path}`).to.equal(200);
						expect(response.body, `${mode} body of ${path}`).not.to.contain('fmdb-authoring-action');
					});
				});
			});

			// The list's own module declares the accepted type — the action mixin, which jContent
			// turns into one "New Form Action" button, then the type chooser — and holds the create
			// placeholder (the placeholder itself carries no type: the core puts the constraint on
			// the list's module). Suffix selector: the path is reference-scoped (ref@/form/actions).
			cy.get('[jahiatype="module"][path$="/actions"]')
				.should('have.length', 1)
				.should('have.attr', 'nodetypes', 'fmdbmix:formAction')
				.find('[jahiatype="module"][type="placeholder"]')
				.should('have.length', 1);

			// Live: the visitor's form only.
			visitLiveForm(livePath);
			cy.get('.fmdb-authoring-actions').should('not.exist');
		});
	});

	it('calls out a form without any action', () => {
		createPublishedLiveFormPage('no-action-form', 'No Action Form', [
			getInputTextNode({name: 'lonelyField', title: 'Lonely field'})
		]).then(({pagePath}) => {
			visitEditForm(pagePath);

			cy.get('.fmdb-authoring-actions').should('have.length', 1).within(() => {
				cy.get('.fmdb-authoring-action').should('not.exist');
				cy.get('.fmdb-authoring-actions-empty').should('be.visible');
			});
		});
	});
});
