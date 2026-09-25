import gql from 'graphql-tag';
import {publishAndWaitJobEnding} from '@jahia/cypress';
import {
	createPublishedLiveFormPage,
	expectNoLiveOwnedProperty,
	getInputDateNode,
	getSelectNode,
	SELECT_SINGLE,
	visitLiveForm
} from '../../support/fixtures';
import {CONTENT_PATH} from '../../support/constants';
import {localDay, useFormidableSite} from './support';

const FORM_NAME = 'prefixed-properties-form';
const FORM_PATH = `${CONTENT_PATH}/${FORM_NAME}`;
const DATE_PATH = `${FORM_PATH}/fields/bookingDate`;
const SELECT_PATH = `${FORM_PATH}/fields/${SELECT_SINGLE.name}`;

// Both spellings are read: the migration must leave the unprefixed ones and only them.
const GET_FIELD = gql`
	query getField($path: String!, $workspace: Workspace!) {
		jcr(workspace: $workspace) {
			nodeByPath(path: $path) {
				mixinTypes {
					name
				}
				minBoundMode: property(name: "minBoundMode") {
					value
				}
				minRelativeAmount: property(name: "minRelativeAmount") {
					value
				}
				minRelativeUnit: property(name: "minRelativeUnit") {
					value
				}
				maxBoundMode: property(name: "maxBoundMode") {
					value
				}
				max: property(name: "max") {
					value
				}
				optionsMode: property(name: "optionsMode") {
					value
				}
				options: property(name: "options", language: "en") {
					values
				}
				optionsFr: property(name: "options", language: "fr") {
					values
				}
				optionsEmptyLabel: property(name: "optionsEmptyLabel", language: "en") {
					value
				}
				oldMinBoundMode: property(name: "fmdb:minBoundMode") {
					value
				}
				oldOptionsMode: property(name: "fmdb:optionsMode") {
					value
				}
				oldOptions: property(name: "fmdb:options", language: "en") {
					values
				}
				oldOptionsFr: property(name: "fmdb:options", language: "fr") {
					values
				}
				oldOptionsEmptyLabel: property(name: "fmdb:optionsEmptyLabel", language: "en") {
					value
				}
			}
		}
	}
`;

type Field = {
	mixinTypes?: Array<{name: string}>;
	minBoundMode?: {value?: string} | null;
	minRelativeAmount?: {value?: string} | null;
	minRelativeUnit?: {value?: string} | null;
	maxBoundMode?: {value?: string} | null;
	max?: {value?: string} | null;
	optionsMode?: {value?: string} | null;
	options?: {values?: string[]} | null;
	optionsFr?: {values?: string[]} | null;
	optionsEmptyLabel?: {value?: string} | null;
	oldMinBoundMode?: {value?: string} | null;
	oldOptionsMode?: {value?: string} | null;
	oldOptions?: {values?: string[]} | null;
	oldOptionsFr?: {values?: string[]} | null;
	oldOptionsEmptyLabel?: {value?: string} | null;
};

const EMPTY_LABEL = 'Choose a department';
// 0.4 stored the translated option list per language: the select gets a French list too.
const FR_OPTIONS = SELECT_SINGLE.options.map(option =>
	JSON.stringify({value: option.value, label: `${option.label} (fr)`, selected: option.selected}));

/**
 * Puts a field into its 0.4.0 shape, and fails — rather than logs — when the fixture could not:
 * cy.executeGroovy yields the provisioning status ('.installed', or '.failed' on a script error),
 * never the script's own report. The pre-migration guards below then assert the shape itself.
 */
const prefixProperties = (path: string) =>
	cy.executeGroovy('groovy/prefixMixinProperties.groovy', {__FIELD_PATH__: path}).then(result => {
		expect(String(result), `0.4 shape of ${path}`).not.to.contain('.failed');
	});

type FieldResponse = {data?: {jcr?: {nodeByPath?: Field | null}}};

const getField = (path: string, workspace: 'EDIT' | 'LIVE') =>
	cy.apollo({query: GET_FIELD, variables: {path, workspace}});

// What the editor does when the minimum switches from an offset to the submission day:
// the relative fieldset goes (with its offset), the mode changes.
const SWITCH_MIN_TO_TODAY = gql`
	mutation switchMinBoundToToday($path: String!) {
		jcr {
			mutateNode(pathOrId: $path) {
				removeMixins(mixins: ["fmdbmix:relativeMinDate"])
				mutateProperty(name: "minBoundMode") {
					setValue(value: "today")
				}
			}
		}
	}
`;

/**
 * Startup migration of the fmdb:-prefixed mixin properties written by 0.4.0 (#310): a
 * field whose options-source or date-bounds properties carry the prefix gets them
 * rewritten under their unprefixed names, in both workspaces, values and translations
 * kept. The rename rule itself is unit-tested (MixinPropertyNamesMigrationTest); this
 * spec proves the reachable end-to-end path: the 0.4 shape is produced through the
 * deprecated definitions the CND keeps for one release, the engine restart migrates it,
 * nothing is left live-owned, and the migrated field keeps living — edited and
 * republished, live follows.
 */
describe('Validation - 48 Mixin property names migration', () => {
	useFormidableSite();

	it('renames the prefixed properties of fields stored by 0.4.0 and keeps their values', () => {
		const select = getSelectNode({...SELECT_SINGLE, emptyLabel: EMPTY_LABEL});
		select.properties.push({name: 'options', values: FR_OPTIONS, language: 'fr'});
		createPublishedLiveFormPage(
			FORM_NAME,
			'Prefixed Properties Form',
			[
				getInputDateNode({
					name: 'bookingDate',
					title: 'Booking date',
					minRelative: {amount: -18, unit: 'years'},
					max: '2030-12-31T00:00:00.000'
				}),
				select
			],
			`${FORM_NAME}-page`,
			'Prefixed Properties Form',
			// Both languages published: the French list must reach live for the migration to rename it there too
			{publishLanguages: ['en', 'fr']}
		).then(({livePath}) => {
			prefixProperties(DATE_PATH);
			prefixProperties(SELECT_PATH);

			// The simulated 0.4 shape, before the migration: prefixed names only, on both fields.
			getField(DATE_PATH, 'EDIT').then((response: FieldResponse) => {
				const node = response.data?.jcr?.nodeByPath;
				expect(node?.oldMinBoundMode?.value, 'simulated 0.4 minBoundMode').to.equal('relative');
				expect(node?.minBoundMode, 'unprefixed minBoundMode before the migration').to.be.null;
				expect(node?.minRelativeAmount, 'unprefixed offset before the migration').to.be.null;
			});
			getField(SELECT_PATH, 'EDIT').then((response: FieldResponse) => {
				const node = response.data?.jcr?.nodeByPath;
				expect(node?.oldOptionsMode?.value, 'simulated 0.4 optionsMode').to.equal('manual');
				expect(node?.oldOptions?.values, 'simulated 0.4 options').to.have.length(SELECT_SINGLE.options.length);
				expect(node?.oldOptionsFr?.values, 'simulated 0.4 French options').to.have.length(FR_OPTIONS.length);
				expect(node?.oldOptionsEmptyLabel?.value, 'simulated 0.4 empty-option label').to.equal(EMPTY_LABEL);
				expect(node?.optionsMode, 'unprefixed optionsMode before the migration').to.be.null;
				expect(node?.optionsEmptyLabel, 'unprefixed empty-option label before the migration').to.be.null;
			});

			// The migration is keyed on content state and runs at module activation:
			// restarting the engine is the upgrade trigger.
			cy.executeGroovy('groovy/restartFormidableEngine.groovy', {})
				.then(result => cy.log(String(result)));

			// Module activation is asynchronous, and the migration processes the default
			// workspace before live, the options-source carriers before the date-bounds ones:
			// gate on the LAST renamed state (the date field in LIVE) so no assertion races it.
			cy.waitUntil(
				() => getField(DATE_PATH, 'LIVE').then((response: FieldResponse) => {
					const node = response.data?.jcr?.nodeByPath;
					return node?.minBoundMode?.value === 'relative' && node?.oldMinBoundMode === null;
				}),
				{timeout: 60000, interval: 2000, errorMsg: 'the migration never renamed the date bounds in live'}
			);

			(['EDIT', 'LIVE'] as const).forEach(workspace => {
				getField(DATE_PATH, workspace).then((response: FieldResponse) => {
					const node = response.data?.jcr?.nodeByPath;
					const mixins = node?.mixinTypes?.map(mixin => mixin.name) ?? [];
					const scope = `${DATE_PATH} (${workspace})`;

					expect(node?.minBoundMode?.value, scope).to.equal('relative');
					expect(node?.minRelativeAmount?.value, scope).to.equal('-18');
					expect(node?.minRelativeUnit?.value, scope).to.equal('years');
					expect(node?.maxBoundMode?.value, scope).to.equal('date');
					expect(node?.max?.value, scope).to.contain('2030-12-31');
					expect(mixins, scope).to.include('fmdbmix:relativeMinDate');
					expect(mixins, scope).to.include('fmdbmix:fixedMaxDate');
					expect(node?.oldMinBoundMode, `${scope}: the prefixed property is gone`).to.be.null;
				});

				getField(SELECT_PATH, workspace).then((response: FieldResponse) => {
					const node = response.data?.jcr?.nodeByPath;
					const scope = `${SELECT_PATH} (${workspace})`;
					const options = (node?.options?.values ?? []).map(raw => JSON.parse(raw) as {value: string});

					expect(node?.optionsMode?.value, scope).to.equal('manual');
					expect(options.map(option => option.value), scope)
						.to.deep.equal(SELECT_SINGLE.options.map(option => option.value));
					// Each language keeps its own list, verbatim
					expect(node?.optionsFr?.values, `${scope}: French options`).to.deep.equal(FR_OPTIONS);
					expect(node?.optionsEmptyLabel?.value, `${scope}: empty-option label`).to.equal(EMPTY_LABEL);
					expect(node?.oldOptionsMode, `${scope}: the prefixed mode is gone`).to.be.null;
					expect(node?.oldOptions, `${scope}: the prefixed options are gone`).to.be.null;
					expect(node?.oldOptionsFr, `${scope}: the prefixed French options are gone`).to.be.null;
					expect(node?.oldOptionsEmptyLabel, `${scope}: the prefixed empty-option label is gone`).to.be.null;
				});
			});

			// The live pass is a system rewrite, not user-generated content: nothing may
			// be left live-owned, or every later publication would skip it (#281).
			expectNoLiveOwnedProperty(DATE_PATH);
			expectNoLiveOwnedProperty(SELECT_PATH);

			// The migrated field keeps living: its minimum switched to the submission
			// day in edit mode, then published, must reach the live rendering.
			cy.apollo({mutation: SWITCH_MIN_TO_TODAY, variables: {path: DATE_PATH}})
				.then(response => expect(response.errors, 'switch the min bound to today').to.be.undefined);
			publishAndWaitJobEnding(FORM_PATH);

			getField(DATE_PATH, 'LIVE').then((response: FieldResponse) => {
				const node = response.data?.jcr?.nodeByPath;
				expect(node?.minBoundMode?.value, 'live min bound mode after the republish').to.equal('today');
				expect(node?.minRelativeAmount, 'live offset after the republish').to.be.null;
				expect(node?.max?.value, 'live max after the republish').to.contain('2030-12-31');
			});

			const form = visitLiveForm(livePath);
			form.get().find('input[name="bookingDate"]')
				.should('have.attr', 'min', localDay())
				.and('have.attr', 'max', '2030-12-31');
		});
	});
});
