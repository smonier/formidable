import gql from 'graphql-tag';
import {publishAndWaitJobEnding} from '@jahia/cypress';
import {
	createFormNode,
	createPublishedLiveFormPage,
	FORMIDABLE_TEST_SITE,
	getSelectNode,
	visitLiveForm
} from '../../support/fixtures';
import {CONTENT_PATH} from '../../support/constants';
import {useFormidableSite} from './support';

const option = (value: string, label: string, selected = false): string =>
	JSON.stringify({value, label, selected});

const SET_FR_OPTIONS = gql`
	mutation setFrOptions($path: String!, $values: [String!]!) {
		jcr {
			mutateNode(pathOrId: $path) {
				mutateProperty(name: "options") {
					setValues(language: "fr", values: $values)
				}
			}
		}
	}
`;

const GET_FR_OPTIONS = gql`
	query getFrOptions($path: String!) {
		jcr {
			nodeByPath(path: $path) {
				uuid
				workspace
				property(name: "options", language: "fr") {
					values
				}
			}
		}
	}
`;

const GET_EN_OPTIONS = gql`
	query getEnOptions($path: String!) {
		jcr {
			nodeByPath(path: $path) {
				uuid
				workspace
				property(name: "options", language: "en") {
					values
				}
			}
		}
	}
`;

// "Replace untranslated content with the default language content", the site
// setting that governs what an untranslated option renders as. The live
// rendering resolves the LIVE site node. The site node is jmix:autoPublish, so
// an EDIT save alone carries the flag to live; never write it in LIVE directly —
// a direct live write marks the property as live-owned (jmix:liveProperties) and
// every later publication, automatic or not, then refuses to overwrite it.
const SET_MIX_LANGUAGE = gql`
	mutation setMixLanguage($path: String!, $active: String!) {
		jcr {
			mutateNode(pathOrId: $path) {
				mutateProperty(name: "j:mixLanguage") {
					setValue(value: $active)
				}
			}
		}
	}
`;

const GET_MIX_LANGUAGE_LIVE = gql`
	query getMixLanguageLive($path: String!) {
		jcr(workspace: LIVE) {
			nodeByPath(path: $path) {
				uuid
				workspace
				property(name: "j:mixLanguage") {
					value
				}
			}
		}
	}
`;

const replaceUntranslatedContent = (active: boolean): void => {
	const path = `/sites/${FORMIDABLE_TEST_SITE.key}`;
	cy.apollo({mutation: SET_MIX_LANGUAGE, variables: {path, active: String(active)}}).then(result => {
		expect(result.errors, 'site setting written in edit').to.equal(undefined);
	});
	cy.waitUntil(
		() => cy.apollo({query: GET_MIX_LANGUAGE_LIVE, variables: {path}, fetchPolicy: 'no-cache'})
			.then(result => result?.data?.jcr?.nodeByPath?.property?.value === String(active)),
		{timeout: 15000, interval: 500, errorMsg: 'the site setting was never auto-published to live'}
	);
};

const frOptionsOf = (result: {data?: {jcr?: {nodeByPath?: {property?: {values?: string[]}}}}}): string[] | undefined =>
	result?.data?.jcr?.nodeByPath?.property?.values;

const readFrOptions = (fieldPath: string) =>
	cy.apollo({query: GET_FR_OPTIONS, variables: {path: fieldPath}, fetchPolicy: 'no-cache'});

/**
 * The option VALUES of a manual choice field are its identity — submissions
 * store them, conditional logic matches them, the forged-value validation
 * checks them — so every language must share one set. The site's default
 * language is the authority: saving options feeds EVERY site language the
 * master's values, order and count, creating the translation subnode of a
 * language that has none. Each language keeps its own label for a value it
 * already carries, and an entry nobody translated is stored with an EMPTY
 * label — the master's words are never copied into a translation.
 *
 * What such an entry RENDERS is the site's call, not this module's: the
 * "Replace untranslated content with the default language content" setting
 * decides, per entry, between the default language's label and not rendering
 * the entry at all.
 */
describe('Form fields - 221 Choice options language sync', () => {
	useFormidableSite();

	it('feeds every site language when the default language is saved', () => {
		const formName = `options-lang-feed-${Date.now()}`;
		const fieldPath = `${CONTENT_PATH}/${formName}/fields/flavor`;

		createFormNode(formName, formName, [
			getSelectNode({
				name: 'flavor',
				title: 'Flavor',
				options: [
					{value: 'vanilla', label: 'Vanilla'},
					{value: 'chocolate', label: 'Chocolate'}
				]
			})
		]).then(() => {
			// Nothing was ever authored in French, and nothing can be: the value is
			// the identity and is not typable outside the default language. So the
			// save feeds French itself — the rows a contributor needs on screen —
			// with empty labels, ready to translate in place.
			cy.waitUntil(() =>
				readFrOptions(fieldPath).then(result => {
					const values = frOptionsOf(result);
					return Boolean(values)
						&& values!.length === 2
						&& values![0] === option('vanilla', '')
						&& values![1] === option('chocolate', '');
				}), {timeout: 15000, interval: 500, errorMsg: 'French was never fed the master options'});
		});
	});

	it('re-aligns a diverging translation on the default language structure', () => {
		const formName = `options-lang-sync-${Date.now()}`;
		const fieldPath = `${CONTENT_PATH}/${formName}/fields/flavor`;

		createFormNode(formName, formName, [
			getSelectNode({
				name: 'flavor',
				title: 'Flavor',
				options: [
					{value: 'vanilla', label: 'Vanilla'},
					{value: 'chocolate', label: 'Chocolate'}
				]
			})
		]).then(() => {
			// A translation saved with its own values, order and count: 'mint' does
			// not exist in the master, 'chocolate' does (with a French label).
			cy.apollo({
				mutation: SET_FR_OPTIONS,
				variables: {
					path: fieldPath,
					values: [option('mint', 'Menthe'), option('chocolate', 'Chocolat')]
				}
			}).then(result => {
				expect(result.errors, 'divergent save accepted').to.equal(undefined);
			});

			// The observation listener re-aligns asynchronously: the French list
			// converges to the master's values in the master's order — vanilla
			// arriving unlabelled (nobody translated it), chocolate keeping its
			// French label, mint dropped as a value the identity set does not carry.
			cy.waitUntil(() =>
				readFrOptions(fieldPath).then(result => {
					const values = frOptionsOf(result);
					return Boolean(values)
						&& values!.length === 2
						&& values![0] === option('vanilla', '')
						&& values![1] === option('chocolate', 'Chocolat');
				}), {timeout: 15000, interval: 500, errorMsg: 'French options never re-aligned on the master'});
		});
	});

	it('replaces valueless rows with the master entries', () => {
		const formName = `options-junk-clean-${Date.now()}`;
		const fieldPath = `${CONTENT_PATH}/${formName}/fields/flavor`;

		createFormNode(formName, formName, [
			getSelectNode({
				name: 'flavor',
				title: 'Flavor',
				options: [
					{value: 'vanilla', label: 'Vanilla'},
					{value: 'chocolate', label: 'Chocolate'}
				]
			})
		]).then(() => {
			// What an accidental "add" clicked in another language saves: a row that
			// never received a value (no value is typable outside the default
			// language). It carries no translation anyone could keep.
			cy.apollo({
				mutation: SET_FR_OPTIONS,
				variables: {path: fieldPath, values: ['']}
			}).then(result => {
				expect(result.errors, 'valueless save accepted').to.equal(undefined);
			});

			// The master's entries take its place, unlabelled: French is left with
			// the rows to translate, never with a row that cannot exist.
			cy.waitUntil(() =>
				readFrOptions(fieldPath).then(result => {
					const values = frOptionsOf(result);
					return Boolean(values)
						&& values!.length === 2
						&& values![0] === option('vanilla', '')
						&& values![1] === option('chocolate', '');
				}), {timeout: 15000, interval: 500, errorMsg: 'the valueless French row was never replaced'});

			// The master is untouched by the alignment.
			cy.apollo({query: GET_EN_OPTIONS, variables: {path: fieldPath}, fetchPolicy: 'no-cache'})
				.then(result => {
					const values = result?.data?.jcr?.nodeByPath?.property?.values as string[];
					expect(values).to.deep.equal([
						option('vanilla', 'Vanilla'),
						option('chocolate', 'Chocolate')
					]);
				});
		});
	});

	it('renders an untranslated option as the site rules untranslated content', () => {
		const formName = `options-lang-render-${Date.now()}`;
		const formPath = `${CONTENT_PATH}/${formName}`;
		const fieldPath = `${formPath}/fields/flavor`;

		createPublishedLiveFormPage(
			formName,
			'Flavor Form',
			[
				getSelectNode({
					name: 'flavor',
					title: 'Flavor',
					options: [
						{value: 'vanilla', label: 'Vanilla'},
						{value: 'chocolate', label: 'Chocolate'}
					]
				})
			],
			undefined,
			undefined,
			{
				properties: [{name: 'jcr:title', value: 'Formulaire parfums', language: 'fr'}],
				pageProperties: [{name: 'jcr:title', value: 'Formulaire parfums', language: 'fr'}],
				publishLanguages: ['en', 'fr']
			}
		).then(({livePath}) => {
			const liveUrlFr = `/fr/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`;

			// Wait for the save-time feeding before translating: both writes target
			// the same property, and the assertions must read a settled list.
			cy.waitUntil(() =>
				readFrOptions(fieldPath).then(result => frOptionsOf(result)?.length === 2),
				{timeout: 15000, interval: 500, errorMsg: 'French was never fed the master options'});

			// A half-done translation, the ordinary state of a contribution in
			// progress: one label typed, the other left as the feeding wrote it.
			cy.apollo({
				mutation: SET_FR_OPTIONS,
				variables: {
					path: fieldPath,
					values: [option('vanilla', 'Vanille'), option('chocolate', '')]
				}
			}).then(result => {
				expect(result.errors, 'partial translation accepted').to.equal(undefined);
			});

			publishAndWaitJobEnding(formPath, ['en', 'fr']);

			// Replacing ON: the untranslated entry borrows the default language's
			// wording rather than showing a blank choice. No cache flush: the field
			// fragment depends on the site node, so the auto-published setting
			// change is what refreshes it.
			replaceUntranslatedContent(true);
			cy.waitUntil(
				() => cy.request(liveUrlFr)
					.then(response => response.body.includes('Chocolate')),
				{timeout: 30000, interval: 2000, errorMsg: 'the default-language label never reached the French rendering'}
			);

			const replacing = visitLiveForm(livePath, 'fr').getSelectInput('flavor');
			replacing.shouldBeVisible().shouldHaveOptionCount(2);
			replacing.shouldHaveOption('Vanille').shouldHaveOption('Chocolate');

			// Replacing OFF: the site asked for untranslated content to stay
			// invisible, and an untranslated choice is exactly that. Per entry —
			// the translated one is still offered.
			replaceUntranslatedContent(false);
			cy.waitUntil(
				() => cy.request(liveUrlFr)
					.then(response => !response.body.includes('Chocolate')),
				{timeout: 30000, interval: 2000, errorMsg: 'the untranslated option was never withheld'}
			);

			const withholding = visitLiveForm(livePath, 'fr').getSelectInput('flavor');
			withholding.shouldBeVisible().shouldHaveOptionCount(1);
			withholding.shouldHaveOption('Vanille');
			withholding.getOptions().should('not.contain', 'Chocolate');

			// The English page is the identity, untouched by either setting.
			visitLiveForm(livePath).getSelectInput('flavor')
				.shouldHaveOptionCount(2)
				.shouldHaveOption('Vanilla')
				.shouldHaveOption('Chocolate');
		});
	});
});
