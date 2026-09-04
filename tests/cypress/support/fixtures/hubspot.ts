import gql from 'graphql-tag';
import {JahiaNode, NodeProperty} from './types';

/**
 * The development HubSpot connection the specs rely on: a factory configuration
 * org.jahia.modules.formidable.hubspot-mock.cfg pointing (allowInsecureDevUrl) at the
 * mock HubSpot server of tests/hubspot-mock/mock_hubspot.py, reachable from the
 * Jahia container as http://host.docker.internal:8091 and from the Cypress runner as
 * HUBSPOT_MOCK_URL (default http://localhost:8091).
 */
export const HUBSPOT_MOCK = {
	connectionId: 'mock',
	baseUrl: (Cypress.env('HUBSPOT_MOCK_URL') as string | undefined) || 'http://localhost:8091'
};

export type ContactMappingRowInput =
	| {hsProperty: string; hsType?: string; hsFieldType?: string; source?: 'field'; fieldName: string}
	| {hsProperty: string; hsType?: string; hsFieldType?: string; source: 'constant'; value: string};

/**
 * The fieldMapping JSON exactly as the HubspotContactMapping selector stores it. Field rows
 * carry only the node name here: the action resolves fieldKey, then uuid, then name, so this
 * exercises the name fallback a hand-written or imported mapping relies on.
 */
export const buildContactMapping = (rows: ContactMappingRowInput[]): string => JSON.stringify({
	version: 1,
	rows: rows.map(row => (row.source === 'constant'
		? {hsProperty: row.hsProperty, hsType: row.hsType ?? 'string', hsFieldType: row.hsFieldType ?? 'text', source: 'constant', value: row.value}
		: {hsProperty: row.hsProperty, hsType: row.hsType ?? 'string', hsFieldType: row.hsFieldType ?? 'text', source: 'field', fieldKey: '', fieldName: row.fieldName, nodeId: ''}))
});

export interface CreateContactActionData {
	name?: string;
	connectionId?: string;
	mapping?: ContactMappingRowInput[];
	duplicateStrategy?: 'create' | 'upsertByEmail';
	failSubmissionOnError?: boolean;
}

export function getCreateContactActionNode(data: CreateContactActionData = {}): JahiaNode {
	const properties: NodeProperty[] = [
		{name: 'jcr:title', value: 'Create HubSpot Contact', language: 'en'},
		{name: 'connectionId', value: data.connectionId ?? HUBSPOT_MOCK.connectionId},
		{name: 'duplicateStrategy', value: data.duplicateStrategy ?? 'create'},
		{name: 'failSubmissionOnError', value: String(data.failSubmissionOnError ?? true), type: 'BOOLEAN'}
	];
	if (data.mapping) {
		properties.push({name: 'fieldMapping', value: buildContactMapping(data.mapping)});
	}

	return {
		name: data.name ?? 'hubspotContact',
		primaryNodeType: 'fmdbhs:createContactAction',
		properties
	};
}

export interface MockContact {
	id: string;
	properties: Record<string, string>;
	_updated?: boolean;
	[field: string]: unknown;
}

export const readMockContacts = (): Cypress.Chainable<MockContact[]> =>
	cy.request<MockContact[]>(`${HUBSPOT_MOCK.baseUrl}/__mock/contacts`).its('body');

export const resetMockContacts = (): Cypress.Chainable =>
	cy.request('DELETE', `${HUBSPOT_MOCK.baseUrl}/__mock/contacts`);

/** Whether the mock server answers and the instance declares the mock connection as ready. */
export const isHubspotMockAvailable = (): Cypress.Chainable<boolean> =>
	cy.request({url: `${HUBSPOT_MOCK.baseUrl}/__mock/contacts`, failOnStatusCode: false, timeout: 5000})
		.then(response => {
			if (response.status !== 200) {
				return cy.wrap(false, {log: false});
			}

			return cy.apollo({
				query: gql`query { formidableHubspot { connections { id ready } } }`
			}).then((result: {data?: {formidableHubspot?: {connections?: Array<{id: string; ready: boolean}>}}}) => {
				const connections = result.data?.formidableHubspot?.connections ?? [];
				return cy.wrap(connections.some(c => c.id === HUBSPOT_MOCK.connectionId && c.ready), {log: false});
			});
		});
