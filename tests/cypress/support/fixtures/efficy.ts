import gql from 'graphql-tag';
import {JahiaNode, NodeProperty} from './types';
import {CONTENT_PATH} from '../constants';
import {createFormNode} from './forms';

const PROBE_FORM = 'efficy-connections-probe';

/**
 * The development Efficy connection the specs rely on: a factory configuration
 * org.jahia.modules.formidable.efficy-mock.cfg pointing (allowInsecureDevUrl) at the
 * mock Efficy e-deal server of tests/efficy-mock/mock_efficy.py, reachable from the
 * Jahia container as http://host.docker.internal:8092 and from the Cypress runner as
 * EFFICY_MOCK_URL (default http://localhost:8092).
 */
export const EFFICY_MOCK = {
	connectionId: 'mock',
	baseUrl: (Cypress.env('EFFICY_MOCK_URL') as string | undefined) || 'http://localhost:8092'
};

export type OpportunityMappingRowInput =
	| {effField: string; effType?: string; source?: 'field' | 'personByEmail'; fieldName: string}
	| {effField: string; effType?: string; source: 'constant'; value: string}
	| {effField: string; effType?: string; source: 'today'};

/**
 * The fieldMapping JSON exactly as the EfficyOpportunityMapping selector stores it. Field rows
 * carry only the node name here: the action resolves fieldKey, then uuid, then name, so this
 * exercises the name fallback a hand-written or imported mapping relies on.
 */
export const buildOpportunityMapping = (rows: OpportunityMappingRowInput[]): string => JSON.stringify({
	version: 1,
	rows: rows.map(row => {
		if (row.source === 'constant') {
			return {effField: row.effField, effType: row.effType ?? 'string', source: 'constant', value: row.value};
		}

		if (row.source === 'today') {
			return {effField: row.effField, effType: row.effType ?? 'date', source: 'today'};
		}

		return {effField: row.effField, effType: row.effType ?? 'string', source: row.source ?? 'field', fieldKey: '', fieldName: row.fieldName, nodeId: ''};
	})
});

export interface CreateOpportunityActionData {
	name?: string;
	connectionId?: string;
	mapping?: OpportunityMappingRowInput[];
	failSubmissionOnError?: boolean;
}

export function getCreateOpportunityActionNode(data: CreateOpportunityActionData = {}): JahiaNode {
	const properties: NodeProperty[] = [
		{name: 'jcr:title', value: 'Create Efficy Opportunity', language: 'en'},
		{name: 'connectionId', value: data.connectionId ?? EFFICY_MOCK.connectionId},
		{name: 'failSubmissionOnError', value: String(data.failSubmissionOnError ?? true), type: 'BOOLEAN'}
	];
	if (data.mapping) {
		properties.push({name: 'fieldMapping', value: buildOpportunityMapping(data.mapping)});
	}

	return {
		name: data.name ?? 'efficyOpportunity',
		primaryNodeType: 'fmdbeff:createOpportunityAction',
		properties
	};
}

export interface MockOpportunity {
	OppID: string;
	[field: string]: unknown;
	_updated?: boolean;
	[field: string]: unknown;
}

export const readMockOpportunities = (): Cypress.Chainable<MockOpportunity[]> =>
	cy.request<MockOpportunity[]>(`${EFFICY_MOCK.baseUrl}/__mock/opportunities`).its('body');

export const resetMockOpportunities = (): Cypress.Chainable =>
	cy.request('DELETE', `${EFFICY_MOCK.baseUrl}/__mock/opportunities`);

/** Whether the mock server answers and the instance declares the mock connection as ready. */
export const isEfficyMockAvailable = (): Cypress.Chainable<boolean> =>
	cy.request({url: `${EFFICY_MOCK.baseUrl}/__mock/opportunities`, failOnStatusCode: false, timeout: 5000})
		.then(response => {
			if (response.status !== 200) {
				return cy.wrap(false, {log: false});
			}

			// connections is gated like the other fields: it needs a form (or action) node the
			// caller can edit, so probe through a throwaway form in the test site.
			createFormNode(PROBE_FORM, 'CRM connections probe');

			return cy.apollo({
				variables: {contextPath: `${CONTENT_PATH}/${PROBE_FORM}`},
				query: gql`query CrmConnectionsProbe($contextPath: String!) { formidableEfficy { connections(contextPath: $contextPath) { id ready } } }`
			}).then((result: {data?: {formidableEfficy?: {connections?: Array<{id: string; ready: boolean}>}}}) => {
				const connections = result.data?.formidableEfficy?.connections ?? [];
				return cy.wrap(connections.some(c => c.id === EFFICY_MOCK.connectionId && c.ready), {log: false});
			});
		});
