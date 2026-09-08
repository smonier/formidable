import gql from 'graphql-tag';
import {JahiaNode, NodeProperty} from './types';
import {CONTENT_PATH} from '../constants';
import {createFormNode} from './forms';

const PROBE_FORM = 'salesforce-connections-probe';

/**
 * The development Salesforce connection the specs rely on: a factory configuration
 * org.jahia.modules.formidable.salesforce-mock.cfg pointing (allowInsecureDevUrl) at the
 * mock Salesforce server of tests/salesforce-mock/mock_salesforce.py, reachable from the
 * Jahia container as http://host.docker.internal:8090 and from the Cypress runner as
 * SALESFORCE_MOCK_URL (default http://localhost:8090).
 */
export const SALESFORCE_MOCK = {
	connectionId: 'mock',
	baseUrl: (Cypress.env('SALESFORCE_MOCK_URL') as string | undefined) || 'http://localhost:8090'
};

export type LeadMappingRowInput =
	| {sfField: string; sfType?: string; source?: 'field'; fieldName: string}
	| {sfField: string; sfType?: string; source: 'constant'; value: string};

/**
 * The fieldMapping JSON exactly as the SalesforceLeadMapping selector stores it. Field rows
 * carry only the node name here: the action resolves fieldKey, then uuid, then name, so this
 * exercises the name fallback a hand-written or imported mapping relies on.
 */
export const buildLeadMapping = (rows: LeadMappingRowInput[]): string => JSON.stringify({
	version: 1,
	rows: rows.map(row => (row.source === 'constant'
		? {sfField: row.sfField, sfType: row.sfType ?? 'string', source: 'constant', value: row.value}
		: {sfField: row.sfField, sfType: row.sfType ?? 'string', source: 'field', fieldKey: '', fieldName: row.fieldName, nodeId: ''}))
});

export interface CreateLeadActionData {
	name?: string;
	connectionId?: string;
	mapping?: LeadMappingRowInput[];
	duplicateStrategy?: 'create' | 'upsertByEmail';
	failSubmissionOnError?: boolean;
}

export function getCreateLeadActionNode(data: CreateLeadActionData = {}): JahiaNode {
	const properties: NodeProperty[] = [
		{name: 'jcr:title', value: 'Create Salesforce Lead', language: 'en'},
		{name: 'connectionId', value: data.connectionId ?? SALESFORCE_MOCK.connectionId},
		{name: 'duplicateStrategy', value: data.duplicateStrategy ?? 'create'},
		{name: 'failSubmissionOnError', value: String(data.failSubmissionOnError ?? true), type: 'BOOLEAN'}
	];
	if (data.mapping) {
		properties.push({name: 'fieldMapping', value: buildLeadMapping(data.mapping)});
	}

	return {
		name: data.name ?? 'salesforceLead',
		primaryNodeType: 'fmdbsfdc:createLeadAction',
		properties
	};
}

export interface MockLead {
	Id: string;
	_updated?: boolean;
	[field: string]: unknown;
}

export const readMockLeads = (): Cypress.Chainable<MockLead[]> =>
	cy.request<MockLead[]>(`${SALESFORCE_MOCK.baseUrl}/__mock/leads`).its('body');

export const resetMockLeads = (): Cypress.Chainable =>
	cy.request('DELETE', `${SALESFORCE_MOCK.baseUrl}/__mock/leads`);

/** Whether the mock server answers and the instance declares the mock connection as ready. */
export const isSalesforceMockAvailable = (): Cypress.Chainable<boolean> =>
	cy.request({url: `${SALESFORCE_MOCK.baseUrl}/__mock/leads`, failOnStatusCode: false, timeout: 5000})
		.then(response => {
			if (response.status !== 200) {
				return cy.wrap(false, {log: false});
			}

			// connections is gated like the other fields: it needs a form (or action) node the
			// caller can edit, so probe through a throwaway form in the test site.
			createFormNode(PROBE_FORM, 'CRM connections probe');

			return cy.apollo({
				variables: {contextPath: `${CONTENT_PATH}/${PROBE_FORM}`},
				query: gql`query CrmConnectionsProbe($contextPath: String!) { formidableSalesforce { connections(contextPath: $contextPath) { id ready } } }`
			}).then((result: {data?: {formidableSalesforce?: {connections?: Array<{id: string; ready: boolean}>}}}) => {
				const connections = result.data?.formidableSalesforce?.connections ?? [];
				return cy.wrap(connections.some(c => c.id === SALESFORCE_MOCK.connectionId && c.ready), {log: false});
			});
		});

/**
 * Publishes a node and waits until it exists in the live workspace. Unlike
 * publishAndWaitJobEnding this does not depend on the job scheduler reporting no EXECUTING
 * publication job, which a stale job on a long-lived development instance can block forever.
 */
export const publishAndWaitLive = (path: string, languages: string[] = ['en']): void => {
	cy.apollo({
		mutation: gql`mutation PublishLeadFormNode($pathOrId: String!, $languages: [String!]) {
			jcr { mutateNode(pathOrId: $pathOrId) { publish(languages: $languages, publishSubNodes: true, includeSubTree: true) } }
		}`,
		variables: {pathOrId: path, languages}
	});
	cy.waitUntil(
		() => cy.apollo({
			fetchPolicy: 'no-cache',
			query: gql`query LiveNodeExists($path: String!) { jcr(workspace: LIVE) { nodeByPath(path: $path) { uuid } } }`,
			variables: {path},
			errorPolicy: 'all'
		}).then((result: {data?: {jcr?: {nodeByPath?: {uuid?: string} | null} | null}}) => Boolean(result.data?.jcr?.nodeByPath?.uuid)),
		{errorMsg: `Node ${path} never appeared in live`, timeout: 60000, interval: 1000}
	);
	// eslint-disable-next-line cypress/no-unnecessary-waiting
	cy.wait(500);
};
