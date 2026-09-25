import gql from 'graphql-tag';

const START_CONTENT_INTEGRITY_SCAN = gql`
	query startContentIntegrityScan(
		$id: String!
		$workspace: WorkspaceToScan!
		$startNode: String!
		$excludedPaths: [String!]
		$skipMountPoints: Boolean!
		$checksToRun: [String!]!
		$uploadResults: Boolean!
	) {
		contentIntegrity {
			integrityScan(id: $id) {
				id
				scan(
					workspace: $workspace
					startNode: $startNode
					excludedPaths: $excludedPaths
					skipMountPoints: $skipMountPoints
					checksToRun: $checksToRun
					uploadResults: $uploadResults
				)
			}
		}
	}
`;

const GET_CONTENT_INTEGRITY_CHECKS = gql`
	query getContentIntegrityChecks {
		contentIntegrity {
			integrityChecks {
				id
				enabled
			}
		}
	}
`;

const GET_CONTENT_INTEGRITY_SCAN = gql`
	query getContentIntegrityScan($id: String!) {
		contentIntegrity {
			integrityScan(id: $id) {
				id
				status
				resultsID
				logs
			}
		}
	}
`;

const GET_CONTENT_INTEGRITY_SCAN_RESULTS = gql`
	query getContentIntegrityScanResults($id: String!, $offset: Int!, $pageSize: Int!) {
		contentIntegrity {
			scanResultsDetails(id: $id) {
				errorCount
				totalErrorCount
				errors(offset: $offset, pageSize: $pageSize) {
					checkName
					errorType
					workspace
					nodePath
					nodePrimaryType
					message
					extraInfos {
						key
						label
						value
					}
				}
			}
		}
	}
`;

type IntegrityWorkspace = 'EDIT' | 'LIVE' | 'BOTH';
type IntegrityScanStatus = 'RUNNING' | 'FINISHED' | 'INTERRUPTED' | 'FAILED' | 'UNKNOWN' | 'NONE' | string;

interface IntegrityScanInfo {
	id: string;
	status?: IntegrityScanStatus | null;
	resultsID?: string | null;
	logs?: string[] | null;
}

export interface IntegrityScanError {
	checkName: string;
	errorType: string;
	workspace?: string | null;
	nodePath?: string | null;
	nodePrimaryType?: string | null;
	message?: string | null;
	extraInfos?: Array<{
		key?: string | null;
		label?: string | null;
		value?: string | null;
	}> | null;
}

export interface IntegrityScanResults {
	errorCount: number;
	totalErrorCount: number;
	errors: IntegrityScanError[];
}

interface StartScanResponse {
	errors?: Array<{
		message?: string | null;
	}> | null;
	data?: {
		contentIntegrity?: {
			integrityScan?: {
				id?: string | null;
				scan?: string | null;
			} | null;
		} | null;
	};
}

interface IntegrityCheckDefinition {
	id: string;
	enabled?: boolean | null;
}

interface IntegrityChecksResponse {
	errors?: Array<{
		message?: string | null;
	}> | null;
	data?: {
		contentIntegrity?: {
			integrityChecks?: IntegrityCheckDefinition[] | null;
		} | null;
	};
}

interface ScanStateResponse {
	errors?: Array<{
		message?: string | null;
	}> | null;
	data?: {
		contentIntegrity?: {
			integrityScan?: IntegrityScanInfo | null;
		} | null;
	};
}

interface ScanResultsResponse {
	errors?: Array<{
		message?: string | null;
	}> | null;
	data?: {
		contentIntegrity?: {
			scanResultsDetails?: IntegrityScanResults | null;
		} | null;
	};
}

export interface RunContentIntegrityScanOptions {
	startNode: string;
	workspace?: IntegrityWorkspace;
	excludedPaths?: string[];
	checksToRun?: string[];
	skipMountPoints?: boolean;
	pollIntervalMs?: number;
	maxPollAttempts?: number;
	pageSize?: number;
}

export type AssertContentIntegrityCleanOptions = RunContentIntegrityScanOptions;

const TERMINAL_FAILURE_STATUSES = new Set(['failed', 'interrupted', 'unknown']);
const CLEAN_SCAN_MARKERS = ['no error found', '0 errors found'];

const normalizeScanStatus = (status?: string | null) => status?.trim().toLowerCase() ?? 'unknown';

const buildScanId = (startNode: string) => {
	const sanitizedPath = startNode.replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/^-+|-+$/g, '');
	return `fmdb-integrity-${Date.now()}-${sanitizedPath || 'root'}`;
};

const formatGraphQLErrors = (errors?: Array<{message?: string | null}> | null) => {
	if (!errors || errors.length === 0) {
		return null;
	}

	return errors.map(error => error.message).filter(Boolean).join('\n');
};

const formatScanLogs = (logs?: string[] | null) => {
	if (!logs || logs.length === 0) {
		return 'No logs returned by content-integrity.';
	}

	return logs.join('\n');
};

const hasCleanScanLogs = (logs?: string[] | null) => {
	if (!logs || logs.length === 0) {
		return false;
	}

	const normalizedLogs = logs.join('\n').toLowerCase();
	return CLEAN_SCAN_MARKERS.some(marker => normalizedLogs.includes(marker));
};

const formatIntegrityErrors = (errors: IntegrityScanError[]) => errors.map(error => {
	const details = [
		error.checkName,
		error.errorType,
		error.workspace ? `workspace=${error.workspace}` : null,
		error.nodePath ? `path=${error.nodePath}` : null,
		error.nodePrimaryType ? `type=${error.nodePrimaryType}` : null,
		error.message ? `message=${error.message}` : null,
		error.extraInfos && error.extraInfos.length > 0
			? `extra=${error.extraInfos.map(info => `${info.key ?? info.label ?? 'info'}=${info.value ?? ''}`).join(', ')}`
			: null
	].filter(Boolean);

	return `- ${details.join(' | ')}`;
}).join('\n');

const getChecksToRun = (
	checksToRun?: string[],
	pollIntervalMs = 500,
	maxPollAttempts = 40,
	attempt = 0
): Cypress.Chainable<string[]> => {
	if (checksToRun && checksToRun.length > 0) {
		return cy.wrap(checksToRun, {log: false});
	}

	return cy.apollo({
		query: GET_CONTENT_INTEGRITY_CHECKS
	}).then(result => {
		// cy.apollo yields the raw Apollo result: read as this query's shape.
		const response = result as unknown as IntegrityChecksResponse;
		const graphqlErrors = formatGraphQLErrors(response.errors);
		if (graphqlErrors) {
			throw new Error(`GraphQL error while loading content-integrity checks:\n${graphqlErrors}`);
		}

		const checks = response.data?.contentIntegrity?.integrityChecks ?? [];
		const availableChecks = checks
			.map(check => check.id)
			.filter(Boolean);
		const enabledChecks = checks
			.filter(check => check.enabled !== false)
			.map(check => check.id)
			.filter(Boolean);

		if (enabledChecks.length > 0) {
			return cy.wrap(enabledChecks, {log: false});
		}

		if (availableChecks.length > 0) {
			return cy.wrap(availableChecks, {log: false});
		}

		if (attempt >= maxPollAttempts) {
			throw new Error(`No content-integrity checks are available for execution after ${maxPollAttempts} polling attempts.`);
		}

		return cy.wait(pollIntervalMs, {log: false}).then(() => getChecksToRun(
			checksToRun,
			pollIntervalMs,
			maxPollAttempts,
			attempt + 1
		));
	});
};

const getScanState = (id: string): Cypress.Chainable<IntegrityScanInfo> => cy.apollo({
	query: GET_CONTENT_INTEGRITY_SCAN,
	variables: {id}
}).then(result => {
	const response = result as unknown as ScanStateResponse;
	const graphqlErrors = formatGraphQLErrors(response.errors);
	if (graphqlErrors) {
		throw new Error(`GraphQL error while loading content-integrity scan '${id}':\n${graphqlErrors}`);
	}

	const scan = response.data?.contentIntegrity?.integrityScan;
	if (!scan?.id) {
		throw new Error(`Unable to retrieve content-integrity scan '${id}'.`);
	}

	return scan;
});

const waitForScanCompletion = (
	id: string,
	pollIntervalMs: number,
	maxPollAttempts: number,
	attempt = 0
): Cypress.Chainable<IntegrityScanInfo> => getScanState(id).then(scan => {
	const normalizedStatus = normalizeScanStatus(scan.status);

	if (normalizedStatus === 'finished') {
		return cy.wrap(scan, {log: false});
	}

	if (TERMINAL_FAILURE_STATUSES.has(normalizedStatus)) {
		throw new Error(
			`content-integrity scan '${id}' ended with status '${scan.status}'.\n${formatScanLogs(scan.logs)}`
		);
	}

	if (attempt >= maxPollAttempts) {
		throw new Error(
			`content-integrity scan '${id}' did not finish after ${maxPollAttempts} polling attempts. Last status: '${scan.status ?? 'UNKNOWN'}'.\n${formatScanLogs(scan.logs)}`
		);
	}

	return cy.wait(pollIntervalMs, {log: false}).then(() => waitForScanCompletion(
		id,
		pollIntervalMs,
		maxPollAttempts,
		attempt + 1
	));
});

const getScanResults = (id: string, pageSize: number): Cypress.Chainable<IntegrityScanResults> => cy.apollo({
	query: GET_CONTENT_INTEGRITY_SCAN_RESULTS,
	variables: {
		id,
		offset: 0,
		pageSize
	}
}).then(result => {
	const response = result as unknown as ScanResultsResponse;
	const graphqlErrors = formatGraphQLErrors(response.errors);
	if (graphqlErrors) {
		throw new Error(`GraphQL error while loading content-integrity results '${id}':\n${graphqlErrors}`);
	}

	const results = response.data?.contentIntegrity?.scanResultsDetails;
	if (!results) {
		throw new Error(`Unable to retrieve content-integrity results '${id}'.`);
	}

	return results;
});

export const runContentIntegrityScan = ({
	startNode,
	workspace = 'EDIT',
	excludedPaths = [],
	checksToRun,
	skipMountPoints = false,
	pollIntervalMs = 500,
	maxPollAttempts = 40,
	pageSize = 200
}: RunContentIntegrityScanOptions): Cypress.Chainable<IntegrityScanResults> => {
	const id = buildScanId(startNode);
	return getChecksToRun(checksToRun, pollIntervalMs, maxPollAttempts).then(resolvedChecksToRun => cy.apollo({
		query: START_CONTENT_INTEGRITY_SCAN,
		variables: {
			id,
			workspace,
			startNode,
			excludedPaths,
			skipMountPoints,
			checksToRun: resolvedChecksToRun,
			uploadResults: true
		}
	})).then(result => {
		const response = result as unknown as StartScanResponse;
		const graphqlErrors = formatGraphQLErrors(response.errors);
		if (graphqlErrors) {
			throw new Error(`GraphQL error while starting content-integrity scan for '${startNode}':\n${graphqlErrors}`);
		}

		const scanId = response.data?.contentIntegrity?.integrityScan?.scan;
		if (!scanId) {
			throw new Error(`Unable to start a content-integrity scan for '${startNode}'.`);
		}

		return waitForScanCompletion(scanId, pollIntervalMs, maxPollAttempts);
	}).then(scan => {
		if (!scan.resultsID) {
			if (hasCleanScanLogs(scan.logs)) {
				// Wrapped: the other branch yields a chain, and one .then() must yield one kind of subject.
				return cy.wrap({
					errorCount: 0,
					totalErrorCount: 0,
					errors: []
				} satisfies IntegrityScanResults, {log: false});
			}

			throw new Error(
				`content-integrity scan '${scan.id}' finished without a results identifier.\n${formatScanLogs(scan.logs)}`
			);
		}

		return getScanResults(scan.resultsID, pageSize);
	});
};

export const assertContentIntegrityClean = (options: AssertContentIntegrityCleanOptions): Cypress.Chainable<IntegrityScanResults> =>
	runContentIntegrityScan(options).then(results => {
		if (results.totalErrorCount > 0) {
			throw new Error(
				`content-integrity reported ${results.totalErrorCount} violation(s) under '${options.startNode}'.\n${formatIntegrityErrors(results.errors)}`
			);
		}

		return results;
	});

export const formatIntegrityScanResults = (results: IntegrityScanResults) =>
	`content-integrity reported ${results.totalErrorCount} violation(s).\n${formatIntegrityErrors(results.errors)}`;
