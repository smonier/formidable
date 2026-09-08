import {createUser, deleteUser} from '@jahia/cypress';
import gql from 'graphql-tag';
import {createFormNode} from '../../support/fixtures/forms';
import {
	getCreateContactActionNode,
	getCreateLeadActionNode,
	getCreateOpportunityActionNode
} from '../../support/fixtures';
import {CONTENT_PATH} from '../../support/constants';
import {useFormidableSite} from '../support/useFormidableSite';
import {JahiaNode} from '../../support/fixtures/types';

/**
 * The CRM connector GraphQL roots (formidableSalesforce, formidableHubspot, formidableEfficy) are
 * gated on the node named by contextPath: the caller must hold jcr:modifyProperties on it AND it
 * must be the action node, the form's actions list or the form. The second half matters: every
 * Jahia account owns its own user node, so a permission check alone opens the CRM metadata and the
 * connections to any authenticated visitor. Each case below goes red if either half is deleted.
 */
const PROBE = {name: 'crm-gate-probe', password: 'Probe1234!'};
const FORM_NAME = 'crm-gate-form';
const FORM_PATH = `${CONTENT_PATH}/${FORM_NAME}`;

interface Crm {
	root: string;
	action: () => JahiaNode;
	connectionId: string;
}

const CRMS: Crm[] = [
	{root: 'formidableSalesforce', action: getCreateLeadActionNode, connectionId: 'mock'},
	{root: 'formidableHubspot', action: getCreateContactActionNode, connectionId: 'mock'},
	{root: 'formidableEfficy', action: getCreateOpportunityActionNode, connectionId: 'mock'}
];

type GqlResult = {data?: Record<string, Record<string, unknown> | null> | null; errors?: Array<{message: string}>};

const query = (crm: Crm, field: string, contextPath: string, extra = ''): Cypress.Chainable<GqlResult> =>
	cy.apollo({
		// 'all' yields {data, errors}; the default policy yields the caught ApolloError instead
		errorPolicy: 'all',
		variables: {contextPath},
		query: gql`query CrmGate($contextPath: String!) { ${crm.root} { ${field}(${extra}contextPath: $contextPath) { __typename } } }`
	}) as unknown as Cypress.Chainable<GqlResult>;

const expectDenied = (result: GqlResult, crm: Crm, field: string) => {
	expect((result.errors ?? []).map(e => e.message), `${crm.root}.${field} must be refused, got ${JSON.stringify(result).slice(0, 400)}`).to.include('Permission denied');
	expect(result.data?.[crm.root]?.[field], `${crm.root}.${field} must return no data`).to.be.oneOf([null, undefined]);
};

const expectAllowed = (result: GqlResult, crm: Crm, field: string) => {
	expect(result.errors, `${crm.root}.${field} errors`).to.be.undefined;
	expect(result.data?.[crm.root]?.[field], `${crm.root}.${field} data`).to.not.be.oneOf([null, undefined]);
};

describe('Actions - 86 CRM connector GraphQL gate', () => {
	useFormidableSite();

	// The three connector modules are part of FORMIDABLE_MODULE_IDS, which useFormidableSite()
	// enables on the test site, so the suite already assumes they are installed.
	const installed: Crm[] = CRMS;
	let probeUserPath = '';

	before(() => {
		cy.login();
		createFormNode(FORM_NAME, 'CRM gate form', [], {actions: installed.map(crm => crm.action())});
		createUser(PROBE.name, PROBE.password);
		cy.logout();
		// cy.apollo authenticates as root whatever cy.login did: the probe needs its own client.
		cy.apolloClient({username: PROBE.name, password: PROBE.password});
		cy.apollo({query: gql`query { currentUser { node { path } } }`}).then((result: {data?: {currentUser?: {node?: {path: string}}}}) => {
			probeUserPath = result.data?.currentUser?.node?.path ?? '';
			expect(probeUserPath, 'the probe user has a user node').to.match(/^\/users\//);
			expect(probeUserPath, 'the probe client is not root').to.not.equal('/users/root');
		});
		cy.apolloClient();
	});

	after(() => {
		cy.login();
		deleteUser(PROBE.name);
		cy.logout();
	});

	it('lets an editor of the form reach the metadata through the actions list and the form', () => {
		cy.apolloClient();
		installed.forEach(crm => {
			query(crm, 'objectFields', `${FORM_PATH}/actions`, `connectionId: "${crm.connectionId}", `).then(r => expectAllowed(r, crm, 'objectFields'));
			query(crm, 'connections', FORM_PATH).then(r => expectAllowed(r, crm, 'connections'));
		});
	});

	it('refuses a writable node that is not an authoring context, even for root', () => {
		cy.apolloClient();
		installed.forEach(crm => {
			query(crm, 'objectFields', CONTENT_PATH, `connectionId: "${crm.connectionId}", `).then(r => expectDenied(r, crm, 'objectFields'));
			query(crm, 'connections', `/sites`).then(r => expectDenied(r, crm, 'connections'));
			query(crm, 'testConnection', CONTENT_PATH, `connectionId: "${crm.connectionId}", `).then(r => expectDenied(r, crm, 'testConnection'));
		});
	});

	it('refuses an authenticated user without rights on the form, including through its own user node', () => {
		cy.apolloClient({username: PROBE.name, password: PROBE.password});
		installed.forEach(crm => {
			query(crm, 'objectFields', probeUserPath, `connectionId: "${crm.connectionId}", `).then(r => expectDenied(r, crm, 'objectFields'));
			query(crm, 'connections', probeUserPath).then(r => expectDenied(r, crm, 'connections'));
			query(crm, 'testConnection', probeUserPath, `connectionId: "${crm.connectionId}", `).then(r => expectDenied(r, crm, 'testConnection'));
			query(crm, 'objectFields', `${FORM_PATH}/actions`, `connectionId: "${crm.connectionId}", `).then(r => expectDenied(r, crm, 'objectFields'));
		});
	});
});
