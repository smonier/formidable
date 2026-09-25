import {createPublishedLiveFormPage, FORMIDABLE_TEST_SITE, getInputTextNode} from '../../support/fixtures';
import {expectErrorResponse, postDirectMultipartSubmission, useFormidableSite, withSameOriginHeaders} from './support';

/**
 * The engine's SubmissionResponseEnricher SPI, through the sample enricher of
 * formidable-test-module-samples-java: what a module of its own adds to the JSON body of an
 * accepted submission, and — as important — that it adds nothing to every other form's.
 * See docs/extension/how-to-enrich-the-submission-response.md.
 */
describe('Security - 47 the response enricher SPI', () => {
	useFormidableSite();

	let enrichedFormId: string;
	let plainFormId: string;

	before(() => {
		cy.login();

		createPublishedLiveFormPage(
			'enricher-opted-in-form',
			'Enricher opted-in form',
			[getInputTextNode({name: 'fullName', title: 'Full name', required: true})],
			undefined,
			undefined,
			{mixins: ['fmdbsamplemix:enrichedResponse']}
		).then(({formId}) => {
			enrichedFormId = formId;
		});

		createPublishedLiveFormPage(
			'enricher-plain-form',
			'Enricher plain form',
			[getInputTextNode({name: 'fullName', title: 'Full name'})]
		).then(({formId}) => {
			plainFormId = formId;
		});
	});

	it('adds the enricher block to the form that opted in, next to success', () => {
		postDirectMultipartSubmission({
			formId: enrichedFormId,
			fields: {fullName: 'Ada Lovelace'},
			headers: withSameOriginHeaders()
		}).then(response => {
			expect(response.status).to.eq(200);
			const body = response.body as {success: boolean; sample?: Record<string, unknown>};
			expect(Object.keys(body).sort()).to.deep.equal(['sample', 'success']);
			expect(body.success).to.eq(true);
			// what the sample enricher was handed: the form it ran for, its site, the submission's
			// locale and how many fields the pipeline accepted
			expect(body.sample).to.deep.equal({
				formName: 'enricher-opted-in-form',
				siteKey: FORMIDABLE_TEST_SITE.key,
				locale: 'en',
				fieldCount: 1
			});
		});
	});

	it('leaves the body of a form that did not opt in exactly as it was', () => {
		postDirectMultipartSubmission({
			formId: plainFormId,
			fields: {fullName: 'Ada Lovelace'},
			headers: withSameOriginHeaders()
		}).then(response => {
			expect(response.status).to.eq(200);
			// an enricher is asked for every accepted submission of the platform: one that answers
			// unconditionally would put its key here
			expect(response.body).to.deep.equal({success: true});
		});
	});

	it('never enriches a rejected submission', () => {
		// the opted-in form's only field is required, so an empty submission is rejected before any
		// enricher is asked — the body carries the error alone
		postDirectMultipartSubmission({
			formId: enrichedFormId,
			fields: {},
			headers: withSameOriginHeaders()
		}).then(response => expectErrorResponse(response, 400, 'FMDB-010'));
	});
});
