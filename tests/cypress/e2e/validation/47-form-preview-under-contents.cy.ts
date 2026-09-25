import {createFormNode, FORMIDABLE_TEST_SITE, getInputTextNode, INPUT_TEXT_SIMPLE} from '../../support/fixtures';
import {useFormidableSite} from './support';

// The preview of a node that is not a page (a form under /contents) goes through the platform's
// content template. Until #293 the JSP test template set the test site sits on shipped a clone of
// that template naming a view it did not have: the preview came back as an empty 200 — no
// exception, one WARN in jahia.log, nothing in the suite noticed. A non-empty body is the guard.
describe('Validation - 47 Form preview under /contents', () => {
	useFormidableSite();

	it('renders the form through the platform content template', () => {
		const formName = 'preview-under-contents';
		createFormNode(formName, 'Preview under contents', [getInputTextNode(INPUT_TEXT_SIMPLE)]);

		cy.request({
			url: `/cms/render/default/en/sites/${FORMIDABLE_TEST_SITE.key}/contents/${formName}.content-template.html`,
			retryOnStatusCodeFailure: true
		}).then(response => {
			expect(response.status).to.equal(200);
			expect(response.body, 'the preview is a page, not an empty body').to.contain('<html');
			expect(response.body, 'the form is rendered').to.contain('fmdb-form');
			expect(response.body, 'the field is rendered').to.contain(`name="${INPUT_TEXT_SIMPLE.name}"`);
		});
	});
});
