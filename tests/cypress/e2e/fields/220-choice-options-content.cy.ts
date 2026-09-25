import gql from 'graphql-tag';
import {addNode, deleteNode, getNodeByPath, publishAndWaitJobEnding} from '@jahia/cypress';
import {
	createFormNode,
	createPublishedLiveFormPage,
	flushSiteCache,
	FORMIDABLE_TEST_SITE,
	getContentChoiceFieldNode,
	getInputTextNode,
	getTitledTextNode,
	OPTIONS_QUERY_MAX_RESULTS_DEFAULT,
	setOptionsQueryMaxResults,
	visitLiveForm
} from '../../support/fixtures';
import {CONTENT_PATH} from '../../support/constants';
import {useFormidableSite} from './support';

const AGENCIES_ROOT_PATH = `${CONTENT_PATH}/agencies`;

// The dependent type list, exactly as the editor re-resolves it when the root
// changes (jcontent dependent-properties mechanism on optionsNodeType).
const GET_CONTENT_TYPES = gql`
	query getContentTypes($fieldPath: String, $parentPath: String!, $context: [InputContextEntryInput]!, $locale: String!) {
		forms {
			fieldConstraints(
				nodeUuidOrPath: $fieldPath,
				parentNodeUuidOrPath: $parentPath,
				primaryNodeType: "fmdb:select",
				fieldNodeType: "fmdbmix:contentOptions",
				fieldName: "optionsNodeType",
				context: $context,
				uiLocale: $locale,
				locale: $locale
			) {
				displayValue
				value {
					string
				}
			}
		}
	}
`;

type FieldConstraintsResponse = {
	errors?: Array<{message: string}>;
	data?: {
		forms?: {
			fieldConstraints?: Array<{
				displayValue?: string;
				value?: {string?: string};
			}>;
		};
	};
};

const constraintsOf = (response: FieldConstraintsResponse) =>
	response.data?.forms?.fieldConstraints ?? [];

describe('Form fields - 220 Choice options from contents', () => {
	useFormidableSite();

	let agenciesRootUuid: string;

	before(() => {
		cy.login();

		// Published option targets: two direct texts plus a nested one, proving
		// values are full root-relative paths.
		addNode({parentPathOrId: CONTENT_PATH, name: 'agencies', primaryNodeType: 'jnt:contentFolder', properties: []});
		addNode({parentPathOrId: AGENCIES_ROOT_PATH, ...getTitledTextNode('paris', 'Paris agency', 'Agence de Paris')});
		addNode({parentPathOrId: AGENCIES_ROOT_PATH, ...getTitledTextNode('lyon', 'Lyon agency', 'Agence de Lyon')});
		addNode({parentPathOrId: AGENCIES_ROOT_PATH, name: 'europe', primaryNodeType: 'jnt:contentFolder', properties: []});
		addNode({parentPathOrId: `${AGENCIES_ROOT_PATH}/europe`, ...getTitledTextNode('berlin', 'Berlin agency', 'Agence de Berlin')});
		publishAndWaitJobEnding(AGENCIES_ROOT_PATH, ['en', 'fr']);

		getNodeByPath(AGENCIES_ROOT_PATH).then(response => {
			agenciesRootUuid = response.data.jcr.nodeByPath.uuid;
		});

		cy.logout();
	});

	after(() => {
		cy.login();
		// The cap configuration is instance-global: restore it whatever happened.
		setOptionsQueryMaxResults(OPTIONS_QUERY_MAX_RESULTS_DEFAULT);
		cy.logout();
	});

	it('offers the editor the content types present under the picked root', () => {
		createFormNode(
			'content-types-form',
			'Content Types Form',
			[
				getContentChoiceFieldNode({
					primaryNodeType: 'fmdb:select',
					name: 'agency',
					title: 'Agency',
					rootNodeUuid: agenciesRootUuid,
					nodeType: 'jnt:text'
				})
			]
		).then(() => {
			const fieldPath = `${CONTENT_PATH}/content-types-form/fields/agency`;
			const parentPath = `${CONTENT_PATH}/content-types-form/fields`;

			// Root changed in the editor: the new root arrives as a context entry.
			cy.apollo({
				query: GET_CONTENT_TYPES,
				variables: {
					fieldPath,
					parentPath,
					context: [
						{key: 'dependentProperties', value: ['optionsRootNode']},
						{key: 'optionsRootNode', value: [agenciesRootUuid]}
					],
					locale: 'en'
				}
			}).then((response: FieldConstraintsResponse) => {
				expect(response.errors, 'GraphQL errors for the dependent type list').to.be.undefined;

				const constraints = constraintsOf(response);
				const textType = constraints.find(constraint => constraint.value?.string === 'jnt:text');
				expect(textType, 'jnt:text offered under the agencies root').to.not.be.undefined;
				expect(textType?.displayValue, 'localized label of the jnt:text entry').to.not.be.empty;
				// Form elements never surface as option candidates.
				constraints.forEach(constraint =>
					expect(constraint.value?.string).to.not.match(/^fmdb/));
			});

			// Form build (no context entry): the root falls back to the stored property.
			cy.apollo({
				query: GET_CONTENT_TYPES,
				variables: {fieldPath, parentPath, context: [], locale: 'en'}
			}).then((response: FieldConstraintsResponse) => {
				expect(response.errors, 'GraphQL errors for the stored-root type list').to.be.undefined;
				expect(constraintsOf(response).map(constraint => constraint.value?.string), 'types resolved from the stored root')
					.to.include('jnt:text');
			});
		});
	});

	it('warns the editor in the type label when a type exceeds the configured cap', () => {
		// Three published texts against a cap of two: the type stays offered — a
		// stored value must remain selectable — but its label carries the warning.
		setOptionsQueryMaxResults(2);

		cy.apollo({
			query: GET_CONTENT_TYPES,
			variables: {
				parentPath: CONTENT_PATH,
				context: [
					{key: 'dependentProperties', value: ['optionsRootNode']},
					{key: 'optionsRootNode', value: [agenciesRootUuid]}
				],
				locale: 'en'
			}
		}).then((response: FieldConstraintsResponse) => {
			expect(response.errors, 'GraphQL errors for the capped type list').to.be.undefined;

			const textType = constraintsOf(response).find(constraint => constraint.value?.string === 'jnt:text');
			expect(textType, 'jnt:text stays offered above the cap').to.not.be.undefined;
			expect(textType?.displayValue, 'localized cap warning in the type label')
				.to.contain('more than 2 options resolve');
		});

		setOptionsQueryMaxResults(OPTIONS_QUERY_MAX_RESULTS_DEFAULT);

		// Back under the cap, the label is clean again.
		cy.apollo({
			query: GET_CONTENT_TYPES,
			variables: {
				parentPath: CONTENT_PATH,
				context: [
					{key: 'dependentProperties', value: ['optionsRootNode']},
					{key: 'optionsRootNode', value: [agenciesRootUuid]}
				],
				locale: 'en'
			}
		}).then((response: FieldConstraintsResponse) => {
			const textType = constraintsOf(response).find(constraint => constraint.value?.string === 'jnt:text');
			expect(textType?.displayValue, 'no cap warning under the limit').to.not.contain('options resolve');
		});
	});

	it('renders the published contents as options, values being root-relative paths', () => {
		createPublishedLiveFormPage(
			'content-select-form',
			'Content Select Form',
			[
				getContentChoiceFieldNode({
					primaryNodeType: 'fmdb:select',
					name: 'agency',
					title: 'Agency',
					rootNodeUuid: agenciesRootUuid,
					nodeType: 'jnt:text'
				})
			],
			undefined,
			undefined,
			{
				properties: [{name: 'jcr:title', value: 'Formulaire agences', language: 'fr'}],
				pageProperties: [{name: 'jcr:title', value: 'Formulaire agences', language: 'fr'}],
				publishLanguages: ['en', 'fr']
			}
		).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			const select = form.getSelectInput('agency');

			select.shouldBeVisible().shouldHaveOptionCount(3);
			select.get().find('option:contains("Paris agency")').should('have.attr', 'value', 'paris');
			// A nested content keeps its full path relative to the root.
			select.get().find('option:contains("Berlin agency")').should('have.attr', 'value', 'europe/berlin');

			// The same configuration resolves localized labels in the French page.
			const formFr = visitLiveForm(livePath, 'fr');
			formFr.getSelectInput('agency').shouldBeVisible().shouldHaveOption('Agence de Paris');
		});
	});

	it('blocks submission when the root of a required content field is no longer published', () => {
		addNode({parentPathOrId: CONTENT_PATH, name: 'retired-root', primaryNodeType: 'jnt:contentFolder', properties: []});
		addNode({parentPathOrId: `${CONTENT_PATH}/retired-root`, ...getTitledTextNode('only', 'Only entry', 'Seule entrée')});

		getNodeByPath(`${CONTENT_PATH}/retired-root`).then(response => {
			const retiredRootUuid: string = response.data.jcr.nodeByPath.uuid;

			createPublishedLiveFormPage(
				'content-root-down-form',
				'Content Root Down Form',
				[
					getContentChoiceFieldNode({
						primaryNodeType: 'fmdb:select',
						name: 'brokenAgency',
						title: 'Broken agency',
						rootNodeUuid: retiredRootUuid,
						nodeType: 'jnt:text',
						required: true
					})
				]
			).then(({livePath}) => {
				// Publishing the form published the referenced root along; removing
				// it from live is the real-world breakage (root unpublished later).
				deleteNode(`${CONTENT_PATH}/retired-root`, 'LIVE');

				const form = visitLiveForm(livePath);

				form.get().find('.fmdb-options-source-error')
					.should('be.visible')
					.should('have.attr', 'data-fmdb-source-error', 'blocking');
				form.getSubmitButton().get().should('be.disabled');
			});
		});
	});

	it('refreshes the rendered options when a content is published under the root, without a cache flush', () => {
		// A root of its own: the option count of the other tests must not move.
		const rootPath = `${CONTENT_PATH}/branches`;
		addNode({parentPathOrId: CONTENT_PATH, name: 'branches', primaryNodeType: 'jnt:contentFolder', properties: []});
		addNode({parentPathOrId: rootPath, ...getTitledTextNode('nice', 'Nice branch', 'Agence de Nice')});
		publishAndWaitJobEnding(rootPath, ['en', 'fr']);

		getNodeByPath(rootPath).then(response => {
			createPublishedLiveFormPage(
				'content-refresh-form',
				'Content Refresh Form',
				[
					getContentChoiceFieldNode({
						primaryNodeType: 'fmdb:select',
						name: 'branch',
						title: 'Branch',
						rootNodeUuid: response.data.jcr.nodeByPath.uuid,
						nodeType: 'jnt:text'
					})
				]
			).then(({livePath}) => {
				const liveUrl = `/en/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`;

				// First render caches the fragment with one option.
				visitLiveForm(livePath).getSelectInput('branch').shouldBeVisible().shouldHaveOptionCount(1);

				// The field fragment depends on the root's subtree: the publication
				// flushes it, nobody flushes the site cache.
				addNode({parentPathOrId: rootPath, ...getTitledTextNode('lille', 'Lille branch', 'Agence de Lille')});
				publishAndWaitJobEnding(`${rootPath}/lille`, ['en', 'fr']);
				cy.waitUntil(
					() => cy.request(liveUrl).then(response => response.body.includes('Lille branch')),
					{timeout: 30000, interval: 2000, errorMsg: 'the published content never reached the rendered options'}
				);

				visitLiveForm(livePath).getSelectInput('branch').shouldHaveOptionCount(2).shouldHaveOption('Lille branch');
			});
		});
	});

	it('fails explicitly instead of truncating when the resolved options exceed the cap', () => {
		createPublishedLiveFormPage(
			'content-cap-form',
			'Content Cap Form',
			[
				getContentChoiceFieldNode({
					primaryNodeType: 'fmdb:select',
					name: 'cappedAgency',
					title: 'Capped agency',
					rootNodeUuid: agenciesRootUuid,
					nodeType: 'jnt:text'
				}),
				getInputTextNode({name: 'fullName', title: 'Full name'})
			]
		).then(({livePath}) => {
			const liveUrl = `/en/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`;

			setOptionsQueryMaxResults(2);

			// Three published texts against a cap of two: the optional field fails
			// explicitly (never a two-option select) and the form stays usable.
			// The config reaches the services immediately, but an already-rendered
			// live page keeps serving its cached fragments: flush before polling.
			cy.waitUntil(
				() => flushSiteCache()
					.then(() => cy.request(liveUrl))
					.then(response => response.body.includes('fmdb-options-source-error')),
				{timeout: 30000, interval: 2000, errorMsg: 'the lowered cap never reached the rendering'}
			);

			const form = visitLiveForm(livePath);
			form.get().find('.fmdb-options-source-error')
				.should('be.visible')
				.should('have.attr', 'data-fmdb-source-error', 'optional');
			form.get().find('select[name="cappedAgency"]').should('not.exist');
			form.getSubmitButton().get().should('not.be.disabled');

			setOptionsQueryMaxResults(OPTIONS_QUERY_MAX_RESULTS_DEFAULT);

			// Raising the cap back restores the options on the next render.
			cy.waitUntil(
				() => flushSiteCache()
					.then(() => cy.request(liveUrl))
					.then(response => !response.body.includes('fmdb-options-source-error')),
				{timeout: 30000, interval: 2000, errorMsg: 'the restored cap never reached the rendering'}
			);

			const restoredForm = visitLiveForm(livePath);
			restoredForm.getSelectInput('cappedAgency').shouldBeVisible().shouldHaveOptionCount(3);
		});
	});
});
