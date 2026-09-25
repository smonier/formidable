import gql from 'graphql-tag';
import {enableModule} from '@jahia/cypress';
import {
	createPublishedLiveFormPage,
	FORMIDABLE_TEST_SITE,
	getInputTextNode,
	getSelectNode,
	visitLiveForm
} from '../../support/fixtures';
import type {JahiaNode} from '../../support/fixtures';
import {useFormidableSite} from './support';

/** The sample module extending the built-in fields with a help text position. */
const SAMPLE_MODULE = 'formidable-test-module-samples-tsx';
const POSITION_MIXIN = 'fmdbsamplemix:helpTextPosition';
/** Set by the sample's rendering of the text input, which takes the place of Formidable's. */
const POSITION_ATTRIBUTE = 'data-fmdbsample-help-position';

type HelpTextPosition = 'up' | 'down' | 'both';

const HELP = 'Between 3 and 20 characters.';

const FIELDS = {
	up: {name: 'helpUp', title: 'Help above', helpText: HELP},
	down: {name: 'helpDown', title: 'Help below', helpText: HELP},
	both: {name: 'helpBoth', title: 'Help above and below', helpText: HELP},
	// A text input without the mixin's setting: the built-in placement.
	unset: {name: 'helpUnset', title: 'Help unset', helpText: HELP},
	// A placement without any help text: nothing to place.
	none: {name: 'helpNone', title: 'No help'},
	// A masked field: the rendering keeps everything the mask stands for — pattern, formatted default,
	// formatting while typing.
	masked: {name: 'helpMasked', title: 'Masked code', helpText: HELP, mask: 'AA-9999', defaultValue: 'ab1234'}
};
const MASK_PATTERN = '^[A-Za-z][A-Za-z]-[0-9][0-9][0-9][0-9]$';

// The mixin extends every built-in field type with a help text, not the text input alone.
const SELECT_FIELD = {
	name: 'helpSelect',
	title: 'Help on a select',
	helpText: HELP,
	options: [{value: 'one', label: 'One', selected: false}]
};

/** A field carrying the sample setting — nothing else to pick: the sample rendering is the default. */
const withHelpTextPosition = (node: JahiaNode, position: HelpTextPosition): JahiaNode => {
	node.mixins = [...(node.mixins ?? []), POSITION_MIXIN];
	node.properties.push({name: 'helpTextPosition', value: position});

	return node;
};

const EDIT_FORM = gql`
	query editFormOfField($path: String!) {
		forms {
			editForm(uiLocale: "en", locale: "en", uuidOrPath: $path) {
				sections {
					name
					fieldSets {
						name
						fields {
							name
							valueConstraints {
								displayValue
								value {
									string
								}
							}
						}
					}
				}
			}
		}
	}
`;

interface EditFormResponse {
	errors?: unknown;
	data?: {
		forms?: {
			editForm?: {
				sections: {
					name: string;
					fieldSets: {
						name: string;
						fields: {
							name: string;
							valueConstraints?: {displayValue: string; value?: {string?: string}}[];
						}[];
					}[];
				}[];
			};
		};
	};
}

/**
 * A third-party module adds a setting to every built-in field with a help text — where that
 * help goes: above the field (the built-in rendering), below it, or in both places — and a
 * rendering of the text input honouring it, registered as a default view of higher priority
 * than Formidable's: on a site enabled for the module, every text input renders through it,
 * nothing to pick. The setting is a mixin the module's own form override surfaces inside each
 * field's own editor fieldset, right under Help text, and keeps in force without a switch.
 * Whatever the placement, the control keeps describing one help block for assistive
 * technology; the repeat of "both" is decorative.
 */
describe('Form fields - 222 Help text position (third-party sample)', () => {
	useFormidableSite();

	before(() => {
		// The site created by useFormidableSite knows the core modules only: the sample module's
		// views, mixin and form override are offered on a site once the module is enabled there.
		enableModule(SAMPLE_MODULE, FORMIDABLE_TEST_SITE.key);
	});

	it('places the help text above, below or on both sides of the field, described once', () => {
		createPublishedLiveFormPage('help-text-position-form', 'Help Text Position Form', [
			withHelpTextPosition(getInputTextNode(FIELDS.up), 'up'),
			withHelpTextPosition(getInputTextNode(FIELDS.down), 'down'),
			withHelpTextPosition(getInputTextNode(FIELDS.both), 'both'),
			getInputTextNode(FIELDS.unset),
			withHelpTextPosition(getInputTextNode(FIELDS.none), 'down'),
			withHelpTextPosition(getInputTextNode(FIELDS.masked), 'down')
		]).then(({livePath}) => {
			const form = visitLiveForm(livePath);

			// Above: the single help block precedes the control, as the built-in view renders it —
			// through the sample rendering, the attribute says, the setting left unset included.
			[FIELDS.up, FIELDS.unset].forEach(field => {
				const input = form.getTextInput(field.name);
				input.getContainer().should('have.attr', POSITION_ATTRIBUTE, 'up');
				input.shouldHaveHelpText(HELP);
				input.getContainer().find('.fmdb-form-help').should('have.length', 1);
				input.getInput().prev().should('have.class', 'fmdb-form-help');
				// The built-in contract of a text input is kept by the third-party view.
				input.getInput().should('have.class', 'fmdb-form-control').and('have.attr', 'name', field.name);
				input.getInput().invoke('attr', 'id').should('match', /^input-/);
			});

			// Below: the single help block follows the control, still the one it is described by.
			const below = form.getTextInput(FIELDS.down.name);
			below.getContainer().should('have.attr', POSITION_ATTRIBUTE, 'down');
			below.shouldHaveHelpText(HELP);
			below.getContainer().find('.fmdb-form-help').should('have.length', 1);
			below.getInput().next().should('have.class', 'fmdb-form-help');
			below.getInput().prev().should('have.class', 'fmdb-form-label');

			// Both: two blocks. The first carries the id the control references; the second is a
			// visual repeat, without id and hidden from assistive technology.
			const both = form.getTextInput(FIELDS.both.name);
			both.getContainer().should('have.attr', POSITION_ATTRIBUTE, 'both');
			both.getContainer().find('.fmdb-form-help').should('have.length', 2).each($help => {
				expect($help.text(), 'help text of each block').to.equal(HELP);
			});
			// (Cypress: `have.attr` with the name alone yields the attribute's value, so the id is read
			// on a fresh query rather than chained after the aria-hidden assertion.)
			both.getInput().prev().should('have.class', 'fmdb-form-help').and('not.have.attr', 'aria-hidden');
			both.getInput().prev().invoke('attr', 'id').then(helpId => {
				expect(helpId, 'id of the described block').to.match(/^help-/);
				both.getInput().should('have.attr', 'aria-describedby', helpId);
			});
			both.getInput().next().should('have.class', 'fmdb-form-help')
				.and('have.attr', 'aria-hidden', 'true')
				.and('not.have.attr', 'id');

			// No help text: nothing to place, and nothing referenced — the setting still read.
			const none = form.getTextInput(FIELDS.none.name);
			none.getContainer().should('have.attr', POSITION_ATTRIBUTE, 'down');
			none.shouldNotHaveHelpText();
			none.getInput().should('not.have.attr', 'aria-describedby');

			// A masked field keeps everything the mask stands for: the mask on the input, the pattern
			// derived from it, the default formatted by it, and the formatting while typing — the sample's
			// own island, built on the hook of @jahia/formidable-library (#308).
			const masked = form.getTextInput(FIELDS.masked.name);
			masked.getContainer().should('have.attr', POSITION_ATTRIBUTE, 'down');
			masked.shouldHaveMask(FIELDS.masked.mask).shouldHavePattern(MASK_PATTERN).shouldHaveValue('AB-1234');
			// The island wraps the control (one extra DOM level, `display: contents`, as in Formidable's
			// own rendering): the help block follows the wrapper, not the input itself.
			masked.getInput().closest('.fmdb-form-group > *').next().should('have.class', 'fmdb-form-help');
			masked.type('cd5678');
			masked.shouldHaveValue('CD-5678');
		});
	});

	it('offers the position right under the help text in the editor of every field, without a switch', () => {
		createPublishedLiveFormPage('help-text-position-editor-form', 'Help Text Position Editor Form', [
			withHelpTextPosition(getInputTextNode(FIELDS.up), 'up'),
			withHelpTextPosition(getSelectNode(SELECT_FIELD), 'down')
		]).then(({formPath}) => {
			// One form override, on the mixin, serves every type it extends: its <main> fieldset
			// resolves to the edited type's own fieldset.
			[
				{name: FIELDS.up.name, type: 'fmdb:inputText'},
				{name: SELECT_FIELD.name, type: 'fmdb:select'}
			].forEach(({name, type}) => {
				cy.apollo({
					query: EDIT_FORM,
					variables: {path: `${formPath}/fields/${name}`}
				}).then((response: EditFormResponse) => {
					expect(response.errors, `GraphQL errors for the edit form of ${type}`).to.be.undefined;

					const sections = response.data?.forms?.editForm?.sections ?? [];
					const content = sections.find(section => section.name === 'content');
					const main = content?.fieldSets.find(fieldSet => fieldSet.name === type);
					const names = main?.fields.map(field => field.name) ?? [];

					// Surfaced in the field's own fieldset, right after Help text.
					expect(names, `fields of the ${type} fieldset`).to.include('helpText');
					expect(names[names.indexOf('helpText') + 1], `field under Help text of ${type}`).to.equal('helpTextPosition');

					// The mixin's own fieldset holds nothing any more: no switch to flip before the setting shows.
					const own = content?.fieldSets.find(fieldSet => fieldSet.name === POSITION_MIXIN);
					expect(own?.fields ?? [], `fields left in the mixin fieldset of ${type}`).to.be.empty;

					// The choices, labelled from the sample module's bundle.
					const position = main?.fields.find(field => field.name === 'helpTextPosition');
					expect(position?.valueConstraints?.map(constraint => constraint.value?.string), `values for ${type}`)
						.to.deep.equal(['up', 'down', 'both']);
					expect(position?.valueConstraints?.map(constraint => constraint.displayValue), `labels for ${type}`)
						.to.deep.equal(['Above the field', 'Below the field', 'Above and below the field']);
				});
			});
		});
	});
});
