import {createSite, deleteSite, enableModule} from '@jahia/cypress';
import {DIRECT_SUBMIT_PATH, FORMIDABLE_MODULE_IDS} from '../../support/constants';
import {FORMIDABLE_TEST_SITE} from '../../support/fixtures';

const DEFAULT_BOUNDARY = '----FormidableCypressBoundary';

interface DirectSubmissionOptions {
	body?: string;
	fields?: Record<string, string>;
	formId?: string;
	headers?: Record<string, string>;
	lang?: string;
}

export const SAME_ORIGIN = (Cypress.config('baseUrl') as string | null) ?? 'http://localhost:8080';
export const FOREIGN_ORIGIN = 'https://evil.example';

export const useFormidableSite = () => {
	before(() => {
		deleteSite(FORMIDABLE_TEST_SITE.key);
		createSite(FORMIDABLE_TEST_SITE.key, FORMIDABLE_TEST_SITE.config);
		FORMIDABLE_MODULE_IDS.forEach(moduleId => enableModule(moduleId, FORMIDABLE_TEST_SITE.key));
	});

	beforeEach(() => {
		cy.login();
	});

	afterEach(() => {
		cy.logout();
	});
};

export const withSameOriginHeaders = (headers: Record<string, string> = {}): Record<string, string> => ({
	Origin: SAME_ORIGIN,
	Referer: `${SAME_ORIGIN}/`,
	...headers
});

export const expectErrorResponse = (
		response: Cypress.Response<unknown>,
		status: number,
		errorCode: string
) => {
	expect(response.status).to.eq(status);
	// Exact, unlike the success case: a rejected submission is never enriched, so anything else in
	// the body would be a leak worth failing on.
	expect(response.body).to.deep.equal({
		success: false,
		errorCode
	});
};

export const expectSuccessResponse = (response: Cypress.Response<unknown>) => {
	expect(response.status).to.eq(200);
	// Exactly one key, as before the response-enricher SPI: none of the forms these specs submit
	// carries the sample enricher's mixin, so nothing may be added to their body — an enricher, or a
	// pipeline regression, putting a JCR path or a stack trace there must fail here. The enriched
	// shape has its own spec, which names the keys it expects.
	expect(response.body).to.deep.equal({success: true});
};

export const postDirectMultipartSubmission = ({
	body,
	fields = {},
	formId,
	headers = {},
	lang = 'en'
}: DirectSubmissionOptions): Cypress.Chainable<Cypress.Response<unknown>> => {
	const query = new URLSearchParams({lang});
	if (formId) {
		query.set('fid', formId);
	}

	const multipart = body ?? buildMultipartBody(fields);
	const boundary = body ? getMultipartBoundary(body) : DEFAULT_BOUNDARY;

	return cy.request({
		method: 'POST',
		url: `${DIRECT_SUBMIT_PATH}?${query.toString()}`,
		failOnStatusCode: false,
		headers: {
			'Content-Type': `multipart/form-data; boundary=${boundary}`,
			...headers
		},
		body: multipart
	});
};

export const postDirectNonMultipartSubmission = (
	formId: string,
	headers: Record<string, string> = {}
): Cypress.Chainable<Cypress.Response<unknown>> => {
	const query = new URLSearchParams({fid: formId, lang: 'en'});

	return cy.request({
		method: 'POST',
		url: `${DIRECT_SUBMIT_PATH}?${query.toString()}`,
		failOnStatusCode: false,
		headers: {
			'Content-Type': 'application/json',
			...headers
		},
		body: JSON.stringify({fullName: 'Mallory'})
	});
};

function buildMultipartBody(fields: Record<string, string>): string {
	return [
		...Object.entries(fields).map(([name, value]) => (
			`--${DEFAULT_BOUNDARY}\r\n`
			+ `Content-Disposition: form-data; name="${name}"\r\n\r\n`
			+ `${value}\r\n`
		)),
		`--${DEFAULT_BOUNDARY}--\r\n`
	].join('');
}

function getMultipartBoundary(body: string): string {
	const firstLine = body.match(/^--([^\r\n]+)(?:\r?\n|$)/);
	if (!firstLine || firstLine[1].endsWith('--')) {
		throw new Error('Custom multipart body must start with a boundary delimiter line');
	}

	return firstLine[1];
}
