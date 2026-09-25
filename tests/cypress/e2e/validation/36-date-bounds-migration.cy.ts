import gql from 'graphql-tag';
import {publishAndWaitJobEnding} from '@jahia/cypress';
import {
	createPublishedLiveFormPage,
	expectNoLiveOwnedProperty,
	getInputDateNode,
	getInputDatetimeLocalNode,
	visitLiveForm
} from '../../support/fixtures';
import {CONTENT_PATH} from '../../support/constants';
import {localDay, useFormidableSite} from './support';

const FORM_NAME = 'legacy-bounds-form';
const FORM_PATH = `${CONTENT_PATH}/${FORM_NAME}`;
const DATE_PATH = `${CONTENT_PATH}/${FORM_NAME}/fields/legacyDate`;
const DATETIME_PATH = `${CONTENT_PATH}/${FORM_NAME}/fields/legacyDatetime`;

const GET_MIGRATED_FIELD = gql`
	query getMigratedDateField($path: String!, $workspace: Workspace!) {
		jcr(workspace: $workspace) {
			nodeByPath(path: $path) {
				mixinTypes {
					name
				}
				minBoundMode: property(name: "minBoundMode") {
					value
				}
				maxBoundMode: property(name: "maxBoundMode") {
					value
				}
				min: property(name: "min") {
					value
				}
				max: property(name: "max") {
					value
				}
			}
		}
	}
`;

type MigratedFieldResponse = {
	data?: {
		jcr?: {
			nodeByPath?: {
				mixinTypes?: Array<{name: string}>;
				minBoundMode?: {value?: string} | null;
				maxBoundMode?: {value?: string} | null;
				min?: {value?: string} | null;
				max?: {value?: string} | null;
			};
		};
	};
};

const getMigratedField = (path: string, workspace: 'EDIT' | 'LIVE') =>
	cy.apollo({query: GET_MIGRATED_FIELD, variables: {path, workspace}});

// What the editor does when the minimum is switched from a fixed date to the
// submission day: the fixed-bound fieldset goes (with its value), the mode changes.
const SWITCH_MIN_TO_TODAY = gql`
	mutation switchMinBoundToToday($path: String!) {
		jcr {
			mutateNode(pathOrId: $path) {
				removeMixins(mixins: ["fmdbmix:fixedMinDate"])
				mutateProperty(name: "minBoundMode") {
					setValue(value: "today")
				}
			}
		}
	}
`;

/**
 * Startup migration of pre-mode date bounds: a field carrying fixed min/max values
 * but no bound modes gets stamped with mode 'date' plus the fixed-bound mixins, in
 * both workspaces, and the values stay in place.
 *
 * The trigger state is simulated by removing the mode properties from
 * normally-created fields. That is the closest state the JCR API can produce: a
 * genuine 0.3 node also lacks the fixed-bound mixins, and that shape cannot be
 * reproduced here (a raw write without an applicable definition is rejected, and
 * removing a mixin drops its properties) — the definition-less branch is covered
 * by DateBoundsContentMigrationTest with mocks. This spec proves the end-to-end
 * reachable path: stamping, value survival, both workspaces, and that the migrated
 * field keeps living afterwards — edited and republished, live follows.
 */
describe('Validation - 36 Date bounds migration', () => {
	useFormidableSite();

	it('stamps the fixed-date mode on bounds stored before the modes existed', () => {
		createPublishedLiveFormPage(
			FORM_NAME,
			'Legacy Bounds Form',
			[
				getInputDateNode({
					name: 'legacyDate',
					title: 'Legacy date',
					min: '2020-01-01T00:00:00.000',
					max: '2030-12-31T00:00:00.000'
				}),
				getInputDatetimeLocalNode({
					name: 'legacyDatetime',
					title: 'Legacy datetime',
					max: '2030-12-31T18:30:00.000'
				})
			]
		).then(({livePath}) => {
			cy.executeGroovy('groovy/removeDateBoundModes.groovy', {__FIELD_PATH__: DATE_PATH})
				.then(result => cy.log(String(result)));
			cy.executeGroovy('groovy/removeDateBoundModes.groovy', {__FIELD_PATH__: DATETIME_PATH})
				.then(result => cy.log(String(result)));

			// The migration is keyed on content state and runs at module activation:
			// restarting the engine is the upgrade trigger.
			cy.executeGroovy('groovy/restartFormidableEngine.groovy', {})
				.then(result => cy.log(String(result)));

			// Module activation is asynchronous, and the migration processes the
			// default workspace before live: gate on the LAST stamped state (the
			// datetime field in LIVE) so no assertion races the migration.
			cy.waitUntil(
				() => getMigratedField(DATETIME_PATH, 'LIVE').then(
					(response: MigratedFieldResponse) =>
						response.data?.jcr?.nodeByPath?.maxBoundMode?.value === 'date'
				),
				{timeout: 60000, interval: 2000, errorMsg: 'the migration never stamped maxBoundMode in live'}
			);

			(['EDIT', 'LIVE'] as const).forEach(workspace => {
				getMigratedField(DATE_PATH, workspace).then((response: MigratedFieldResponse) => {
					const node = response.data?.jcr?.nodeByPath;
					const mixins = node?.mixinTypes?.map(mixin => mixin.name) ?? [];
					const scope = `${DATE_PATH} (${workspace})`;

					expect(node?.minBoundMode?.value, scope).to.equal('date');
					expect(node?.maxBoundMode?.value, scope).to.equal('date');
					expect(mixins, scope).to.include('fmdbmix:fixedMinDate');
					expect(mixins, scope).to.include('fmdbmix:fixedMaxDate');
					expect(node?.min?.value, scope).to.contain('2020-01-01');
					expect(node?.max?.value, scope).to.contain('2030-12-31');
				});

				getMigratedField(DATETIME_PATH, workspace).then((response: MigratedFieldResponse) => {
					const node = response.data?.jcr?.nodeByPath;
					const mixins = node?.mixinTypes?.map(mixin => mixin.name) ?? [];
					const scope = `${DATETIME_PATH} (${workspace})`;

					// Only the configured side is stamped: no minimum means no min mode.
					expect(node?.minBoundMode?.value, scope).to.equal(undefined);
					expect(node?.maxBoundMode?.value, scope).to.equal('date');
					expect(mixins, scope).to.include('fmdbmix:fixedMaxDatetime');
					expect(mixins, scope).not.to.include('fmdbmix:fixedMinDatetime');
					expect(node?.max?.value, scope).to.contain('2030-12-31');
				});
			});

			// The live pass is a system rewrite, not user-generated content: nothing may
			// be left live-owned, or every later publication would skip it (#281).
			expectNoLiveOwnedProperty(DATE_PATH);
			expectNoLiveOwnedProperty(DATETIME_PATH);

			// The migrated field keeps living: its minimum switched to the submission
			// day in edit mode, then published, must reach the live rendering.
			cy.apollo({mutation: SWITCH_MIN_TO_TODAY, variables: {path: DATE_PATH}})
				.then(response => expect(response.errors, 'switch the min bound to today').to.be.undefined);
			publishAndWaitJobEnding(FORM_PATH);

			getMigratedField(DATE_PATH, 'LIVE').then((response: MigratedFieldResponse) => {
				const node = response.data?.jcr?.nodeByPath;
				expect(node?.minBoundMode?.value, 'live min bound mode after the republish').to.equal('today');
				expect(node?.min, 'live fixed min after the republish').to.be.null;
				expect(node?.max?.value, 'live max after the republish').to.contain('2030-12-31');
			});

			const form = visitLiveForm(livePath);
			form.get().find('input[name="legacyDate"]')
				.should('have.attr', 'min', localDay())
				.and('have.attr', 'max', '2030-12-31');
		});
	});
});
