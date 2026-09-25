/**
 * Manual-testing playground provisioning — NOT a test.
 *
 * Rebuilds the Formidable test site with a ready-to-use set of published live
 * forms, so the whole UI and submission process can be exercised by hand on a
 * stable model. Run it with: yarn playground (from the tests/ folder).
 *
 * The forms live under /sites/<site>/contents/forms/playground, each on a page of its own
 * under /sites/<site>/home (<form name>-page), all with a save-to-JCR action so the results
 * screens can be exercised. Every form comes in TWO looks, told apart by the prefix of its
 * name and of its title:
 *   - css-*    "CSS - …"    the sample theme (registration-yellow-theme.css) in the form's
 *                           "Custom CSS" property: a business stylesheet on top of the modules'
 *                           markup, the case the authoring UI (Page Builder zones, boxes) must
 *                           stay readable in
 *   - plain-*  "Plain - …"  no css property: the modules' own look, the markup and the class
 *                           hooks as the styling contract (docs/styling/) describes them
 * The forms:
 *   - simple      minimal contact form, the visitor named by a first and a last name so that
 *                 each maps to its own visitor-profile property; a select drives a conditional
 *                 phone field
 *   - newsletter  a second small form, shown only on the two-forms page below
 *   - steps       three-step form with navigation (step 2 holds a fieldset, the deepest
 *                 authoring level, and the delivery method drives a field and that fieldset)
 *   - complete    every built-in field type (same set as spec 20), the visitor profile fields
 *                 first (mapped, prefilled, then the sensitive one), and the whole options matrix
 *                 — manual, options source, category and content, each single and multiple, every
 *                 field with words of its own (the content ones point at texts under
 *                 contents/agencies and contents/services; the agencies hold an unpublished draft
 *                 to showcase that only published contents reach live)
 *   - languages   a choice field whose French labels are half translated, to try the site's
 *                 "replace untranslated content" setting both ways
 * And one page per look holding two forms, <look>-two-forms-page: the simple form (referenced)
 * next to the newsletter one — the case a page with one form never shows (two results sets,
 * two mappings, one tracking script).
 *
 * jContent's trees (pages, content folders) list nodes in creation order, so the set is created
 * in the order a reader expects to find it: the looks by label, and within a look the forms by
 * title — CSS before Plain, Complete form first, Two forms on one page last.
 *
 * When formidable-jexperience-engine is on the instance, the simple and the complete forms map their
 * fields to jCustomer's default visitor profile properties (firstName, lastName, email, phoneNumber,
 * birthDate, gender, kids, countryName), one field per form is marked sensitive, and jExperience is
 * enabled on the site — so a publication writes the mapping rules and a live submission feeds the
 * profile. The two shapes jCustomer's default schema has no property for — a multi-valued string for the
 * checkbox group, a boolean for the newsletter switch — get one in a "Formidable playground" card, created
 * once through jExperience's admin proxy. Without the module the same forms are provisioned, mappings left
 * out: nothing here depends on it. The yarn script runs the browser under a plain Chrome user agent: the tracker
 * carries the crawler-user-agents list, which names HeadlessChrome, and would otherwise start in its fallback mode
 * and send nothing for the sample submissions.
 *
 * All editorial values are provided in both site languages (en and fr), so the
 * localized rendering and editing can be exercised too. The sourced country
 * select carries an empty-option label in both languages to showcase the
 * native required validation on the site.
 *
 * It also declares the options sources in the OSGi config (countries + two
 * static lists of the fmdbSampleStaticList initializer of
 * formidable-test-module-samples-java, the screen types and how you watch),
 * creates the sample category trees product/tv and product/audio the
 * category-mode fields point at and the contents/agencies and
 * contents/services folders the content-mode ones read, and
 * provisions the results reader user john-doe (password John#1234, kept on
 * the server across runs, site member as editor) with fmdb-results-reader
 * granted on the two simple forms only — to test the results access rights.
 */
import gql from 'graphql-tag';
import {addNode, createSite, createUser, deleteSite, enableModule, getNodeByPath, grantRoles, publishAndWaitJobEnding} from '@jahia/cypress';
import {
	CHECKBOX_GROUP_COMPLETE,
	CHECKBOX_SINGLE_COMPLETE,
	FORMIDABLE_TEST_SITE,
	getCategoryChoiceFieldNode,
	getCategoryNode,
	getCheckboxNode,
	getContentChoiceFieldNode,
	getInputColorNode,
	getFieldsetNode,
	getInputDateNode,
	getInputDatetimeLocalNode,
	getInputEmailNode,
	getInputFileNode,
	getInputNumberNode,
	getInputTextNode,
	getRadioNode,
	getSelectNode,
	getSourcedChoiceFieldNode,
	getStepNode,
	getSwitchNode,
	getTextareaNode,
	getTitledTextNode,
	INPUT_COLOR_COMPLETE,
	INPUT_DATE_COMPLETE,
	INPUT_DATETIME_LOCAL_COMPLETE,
	INPUT_EMAIL_COMPLETE,
	INPUT_FILE_MULTIPLE,
	INPUT_TEXT_COMPLETE,
	RADIO_GROUP,
	SELECT_SINGLE,
	setOptionsSourcesConfig,
	TEXTAREA_COMPLETE
} from '../support/fixtures';
import {createFormNode, createPublishedLiveFormPage, visitLiveForm} from '../support/fixtures/forms';
import {CONTENT_PATH, FORMIDABLE_MODULE_IDS, PLAYGROUND_FORMS_PATH, SITE_HOME_PATH} from '../support/constants';
import type {JahiaNode, NodeProperty} from '../support/fixtures/types';

// Sample theme written into the CSS forms' css property; lives with the
// sample code so module developers can pick it up as a starting point.
const SAMPLE_THEME_PATH = '../jahia-test-module/sample-form-css/registration-yellow-theme.css';
// Read once, before the first form is built (see the before hook).
let themeCss = '';

// The folder holding the forms (contents/forms/playground) and its parent, created and published with the site.
const FORMS_FOLDER_PATH = `${CONTENT_PATH}/forms`;

const AGENCIES_ROOT_PATH = `${CONTENT_PATH}/agencies`;
const SERVICES_ROOT_PATH = `${CONTENT_PATH}/services`;
const CATEGORY_ROOT = '/sites/systemsite/categories';

const RESULTS_READER = {name: 'john-doe', password: 'John#1234'};

/**
 * One of the two looks every form is provisioned in. The key prefixes the system names
 * (`css-simple`, `plain-simple`), the labels prefix the titles ("CSS - Simple contact form").
 */
interface Look {
	key: 'css' | 'plain';
	label: string;
	frLabel: string;
	/** Whether the sample theme goes into the form's "Custom CSS" property; the plain look shows the modules' own styles. */
	css: boolean;
}

// In label order: the order the looks are created in, hence the order the trees show them in.
const LOOKS: Look[] = [
	{key: 'css', label: 'CSS', frLabel: 'CSS', css: true},
	{key: 'plain', label: 'Plain', frLabel: 'Brut', css: false}
];

const nameOf = (look: Look, base: string): string => `${look.key}-${base}`;
const titleOf = (look: Look, title: string): string => `${look.label} - ${title}`;
const frTitleOf = (look: Look, frTitle: string): string => `${look.frLabel} - ${frTitle}`;
// Read at provisioning time: the theme is loaded in the before hook.
const cssOf = (look: Look): NodeProperty[] => (look.css ? [{name: 'css', value: themeCss}] : []);
const livePageOf = (look: Look, base: string): string => `home/${nameOf(look, base)}-page.html`;

/** Titles are in ascending order, as the trees will show the nodes created in that order. */
const inTitleOrder = (titles: string[]): boolean => titles.every((title, index) => index === 0 || titles[index - 1].localeCompare(title, 'en') < 0);

const saveToJcrAction = (): JahiaNode => ({
	name: 'storeSubmission',
	primaryNodeType: 'fmdb:save2jcrAction',
	properties: []
});

// Titles of the two lists of every form, in both site languages: the words of the
// module's default titles, set explicitly. The Page Builder shows a list's title on
// its box, and a default left unresolved at provisioning time (a bundle not registered
// yet) would leave the bare resource key there for the whole life of the playground.
const LIST_TITLES = {
	fieldListProperties: [
		{name: 'jcr:title', value: 'Form fields', language: 'en'},
		{name: 'jcr:title', value: 'Champs du formulaire', language: 'fr'}
	],
	actionListProperties: [
		{name: 'jcr:title', value: 'Form actions', language: 'en'},
		{name: 'jcr:title', value: 'Actions du formulaire', language: 'fr'}
	]
};

/**
 * Creates and publishes one form in the playground folder, in the given look, with its page under
 * home — both titled in both site languages (the actions get localized default titles at creation,
 * so an en-only publication would leave their fr translation unpublished). formProperties adds what
 * a given form wants on top (the buttons it shows, for one).
 */
const provisionForm = (look: Look, base: string, title: string, frTitle: string, fields: JahiaNode[], formProperties: NodeProperty[] = []) =>
	createPublishedLiveFormPage(
		nameOf(look, base),
		titleOf(look, title),
		fields,
		undefined,
		undefined,
		{
			parentPath: PLAYGROUND_FORMS_PATH,
			actions: [saveToJcrAction()],
			...LIST_TITLES,
			properties: [{name: 'jcr:title', value: frTitleOf(look, frTitle), language: 'fr'}, ...cssOf(look), ...formProperties],
			pageProperties: [{name: 'jcr:title', value: frTitleOf(look, frTitle), language: 'fr'}],
			publishLanguages: ['en', 'fr']
		}
	);

// Adds French values on top of the fixture's English ones. Touching the French
// locale of a choice field makes its French options mandatory (i18n property):
// choice fields must always pair a French title with frOptions.
const withFrench = (node: JahiaNode, frProperties: Array<{name: string; value?: string; values?: string[]}>): JahiaNode => {
	node.properties.push(...frProperties.map(property => ({...property, language: 'fr'})));
	return node;
};

// Adds English values the fixture does not set (same shape as withFrench).
const withEnglish = (node: JahiaNode, enProperties: Array<{name: string; value?: string; values?: string[]}>): JahiaNode => {
	node.properties.push(...enProperties.map(property => ({...property, language: 'en'})));
	return node;
};

// --- jExperience: the visitor profile mapping of a field, applied only when the module is on the instance.
// The mixins below are declared by formidable-jexperience-engine; the flag is read in the first test, before
// any form is built, and the two helpers hand the node back untouched when the module is absent.
const JXP_MAPPING_MIXIN = 'fmdbmix:jExperienceProfileMapping';
const JXP_SENSITIVE_MIXIN = 'fmdbmix:jExperienceSensitiveField';
// The tracker loads its context through jCustomer: a cold instance takes longer than the default command
// timeout, so the context is polled up to this long — and never waited for, since a jCustomer that does
// not answer is a case this script goes on through.
const JXP_CONTEXT_TIMEOUT_MS = 30000;
const CONTEXT_ATTEMPT_PAUSE_MS = 2000;
let jExperienceAvailable = false;

/**
 * Maps the field to a visitor profile property: the mapping mixin, the property and the write strategy; with
 * `prefill`, the switch inside that same mapping, and `then` its option — what the page does with the field
 * once the profile's value is in it (editable when left out).
 */
const mappedTo = (node: JahiaNode, profileProperty: string, options: {strategy?: 'alwaysSet' | 'setIfMissing'; prefill?: boolean; then?: 'readOnly' | 'hidden'} = {}): JahiaNode => {
	if (!jExperienceAvailable) return node;
	node.mixins = [...(node.mixins ?? []), JXP_MAPPING_MIXIN];
	node.properties.push(
		{name: 'jExperienceProfileProperty', value: profileProperty},
		{name: 'jExperienceSetStrategy', value: options.strategy ?? 'alwaysSet'}
	);
	if (options.prefill) {
		node.properties.push({name: 'jExperiencePrefill', value: 'true', type: 'BOOLEAN'});
	}
	if (options.prefill && options.then) {
		node.properties.push({name: 'jExperiencePrefillThen', value: options.then});
	}
	return node;
};

/** Marks the field sensitive: its value never reaches the visitor profile, and the dropdown offers it no mapping. */
const sensitive = (node: JahiaNode): JahiaNode => {
	if (!jExperienceAvailable) return node;
	node.mixins = [...(node.mixins ?? []), JXP_SENSITIVE_MIXIN];
	node.properties.push({name: 'jExperienceSensitive', value: 'true', type: 'BOOLEAN'});
	return node;
};

const GENDER_RADIO = {
	name: 'gender',
	title: 'Gender',
	required: false,
	choices: [
		{value: 'female', label: 'Female', selected: false},
		{value: 'male', label: 'Male', selected: false},
		{value: 'other', label: 'Other', selected: false}
	]
};

// jCustomer's default schema offers no multi-valued and no boolean property a form could feed, so the
// playground adds the two it needs, in a card of their own — through jExperience's admin proxy, which
// carries the logged-in session; created once, found again on the next run. A proxy that does not
// answer (no jCustomer connected) is logged, not fatal: the mappings are then skipped at publication.
// jExperience groups the profile's properties into cards through this system tag: cardDataTag/<card id>/<card
// position>/<card title>. The id is free (jExperience's own are an underscore and nine random characters), the
// position orders the cards on the profile screen (jExperience's five default cards take 0 to 5), the title
// is what the screen shows. Copied from a property created by hand in the jExperience UI.
const PLAYGROUND_CARD_TAG = 'cardDataTag/_fmdbplaygd/6/Formidable playground';
const CUSTOM_PROFILE_PROPERTIES = [
	{id: 'formidableInterests', name: 'Interests (Formidable playground)', type: 'string', multivalued: true},
	{id: 'formidableOptIn', name: 'Newsletter opt-in (Formidable playground)', type: 'boolean', multivalued: false}
];
const ensureCustomProfileProperties = (): void => {
	const endpoint = `/modules/jexperience/proxy/${FORMIDABLE_TEST_SITE.key}/cxs/profiles/properties`;
	cy.request({url: `${endpoint}/targets/profiles`, failOnStatusCode: false}).then(response => {
		if (response.status !== 200 || !Array.isArray(response.body)) {
			cy.log(`jCustomer not reachable through jExperience (HTTP ${response.status}): the playground's profile properties are not created`);
			return;
		}
		const existing = new Set((response.body as Array<{itemId: string}>).map(property => property.itemId));
		CUSTOM_PROFILE_PROPERTIES.forEach((property, position) => {
			if (existing.has(property.id)) return;
			cy.request({
				method: 'POST',
				url: endpoint,
				failOnStatusCode: false,
				body: {
					itemId: property.id,
					itemType: 'propertyType',
					target: 'profiles',
					type: property.type,
					multivalued: property.multivalued,
					metadata: {id: property.id, name: property.name, tags: [], systemTags: [PLAYGROUND_CARD_TAG, `positionInCard.${position}`, 'hasCardDataTag'], enabled: true, hidden: false, readOnly: false}
				}
			}).then(created => cy.log(`visitor profile property ${property.id}: HTTP ${created.status}`));
		});
	});
};

// Deleting the site removes its forms without telling their mapping rules apart (a removed node's types cannot
// be resolved), so each run would leave the previous run's rules behind in jCustomer — and every one of them
// would still put a stale form id in the tracker's watch list. The rules of the site are deleted before the
// site is, by the id prefix the synchroniser writes.
const MAPPING_RULE_PREFIX = `formidable-form-mapping_${FORMIDABLE_TEST_SITE.key}_`;
const deleteMappingRulesOfTheSite = (): void => {
	const endpoint = `/modules/jexperience/proxy/${FORMIDABLE_TEST_SITE.key}/cxs/rules`;
	cy.request({url: endpoint, failOnStatusCode: false}).then(response => {
		if (response.status !== 200 || !Array.isArray(response.body)) {
			cy.log(`jCustomer not reachable through jExperience (HTTP ${response.status}): the previous run's mapping rules are left as they are`);
			return;
		}
		const stale = (response.body as Array<{id: string}>).map(rule => rule.id).filter(id => id.startsWith(MAPPING_RULE_PREFIX));
		cy.log(`${stale.length} mapping rule(s) of a previous run to delete`);
		stale.forEach(id => cy.request({method: 'DELETE', url: `${endpoint}/${id}`, failOnStatusCode: false}));
	});
};

// The mapping mixin is registered only when formidable-jexperience-engine is deployed (and it resolves only
// with jExperience present). A missing type answers a GraphQL error, which cy.apollo reports rather than
// throws — but so does a GraphQL schema being rebuilt, which is what a module deployed a minute earlier
// leaves behind; one such run silently provisioned everything without mappings. So the question is asked
// a few times before the module is declared absent, and the last error is logged.
const DETECTION_ATTEMPTS = 5;
const detectJExperience = (attempt = 1): void => {
	cy.apollo({query: gql`query jExperienceMappingMixin { jcr { nodeTypeByName(name: "${JXP_MAPPING_MIXIN}") { name } } }`})
		.then((response: {errors?: unknown; data?: {jcr?: {nodeTypeByName?: {name?: string} | null}}}) => {
			if (!response.errors && response.data?.jcr?.nodeTypeByName?.name) {
				jExperienceAvailable = true;
				cy.log('formidable-jexperience-engine present: the simple and complete forms map their fields to visitor profile properties');
			} else if (attempt < DETECTION_ATTEMPTS) {
				cy.log(`the mapping mixin did not answer (attempt ${attempt}/${DETECTION_ATTEMPTS}), asking again in 2 s`);
				// eslint-disable-next-line cypress/no-unnecessary-waiting -- a pause between two tries, not a wait for an element
				cy.wait(2000);
				detectJExperience(attempt + 1);
			} else {
				jExperienceAvailable = false;
				cy.log(`formidable-jexperience-engine absent: forms provisioned without visitor profile mappings (last answer: ${JSON.stringify(response.errors ?? response.data)})`);
			}
		});
};

// French option list in the manual-options storage format.
const frOptions = (options: Array<{value: string; label: string; selected?: boolean}>): {name: string; values: string[]} => ({
	name: 'options',
	values: options.map(option => JSON.stringify({
		value: option.value,
		label: option.label,
		selected: option.selected ?? false
	}))
});

const FR_DEPARTMENT_OPTIONS = frOptions([
	{value: 'engineering', label: 'Ingénierie'},
	{value: 'sales', label: 'Ventes'},
	{value: 'support', label: 'Support'}
]);

// Department select in both languages. The empty first entry of the shared
// fixture is replaced by the empty-option label property: the field still
// starts empty, through the supported configuration.
// Conditional logic driven by the delivery method, so rules can be tried in live,
// preview and edit mode: the pickup location shows only for "pickup", and (on the
// multi-step forms) the delivery address fieldset shows for every other method.
const PICKUP_LOCATION_RULE = JSON.stringify({
	logicId: 'pg-pickup-location',
	sourceFieldName: 'deliveryMethod',
	sourceFieldType: 'fmdb:radio',
	valueKind: 'choice',
	operator: 'in',
	values: ['pickup']
});

const DELIVERY_ADDRESS_RULE = JSON.stringify({
	logicId: 'pg-delivery-address',
	sourceFieldName: 'deliveryMethod',
	sourceFieldType: 'fmdb:radio',
	valueKind: 'choice',
	operator: 'notIn',
	values: ['pickup']
});

// Attaches conditional-logic rules (authoring format) to a field, fieldset or step.
const withLogics = (node: JahiaNode, ...rules: string[]): JahiaNode => ({
	...node,
	properties: [...node.properties, {name: 'logics', values: rules}]
});

// Second conditional case, on the simple form this time: in its plain look that
// form shows how the core renders a conditional field by default.
const PHONE_NUMBER_RULE = JSON.stringify({
	logicId: 'pg-phone-number',
	sourceFieldName: 'contactChannel',
	sourceFieldType: 'fmdb:select',
	valueKind: 'choice',
	operator: 'in',
	values: ['phone']
});

const contactChannelSelect = (): JahiaNode => withFrench(
	getSelectNode({
		name: 'contactChannel',
		title: 'How should we get back to you?',
		options: [
			{value: 'email', label: 'By email', selected: true},
			{value: 'phone', label: 'By phone', selected: false}
		]
	}),
	[
		{name: 'jcr:title', value: 'Comment vous recontacter ?'},
		frOptions([{value: 'email', label: 'Par e-mail', selected: true}, {value: 'phone', label: 'Par téléphone'}])
	]
);

const phoneNumberField = (): JahiaNode => {
	const field = getInputTextNode({
		name: 'phoneNumber',
		title: 'Phone number (shown when you ask for a call)',
		placeholder: '+33 6 12 34 56 78',
		// Digits only are typed, the literals come by themselves: what reaches the profile is the formatted number.
		mask: '+99 9 99 99 99 99'
	});
	return withFrench(
		withLogics(field, PHONE_NUMBER_RULE),
		[
			{name: 'jcr:title', value: 'Numéro de téléphone (affiché si vous demandez un appel)'},
			{name: 'placeholder', value: '+33 6 12 34 56 78'}
		]
	);
};

const pickupLocationField = (): JahiaNode => {
	const field = getInputTextNode({
		name: 'pickupLocation',
		title: 'Pickup location (shown when delivery method is Pickup)',
		placeholder: 'Store name or city'
	});
	return withFrench(
		withLogics(field, PICKUP_LOCATION_RULE),
		[
			{name: 'jcr:title', value: 'Point de retrait (affiché si le mode de livraison est Retrait)'},
			{name: 'placeholder', value: 'Nom du magasin ou ville'}
		]
	);
};

const departmentSelect = (): JahiaNode => withFrench(
	withEnglish(
		getSelectNode({...SELECT_SINGLE, options: SELECT_SINGLE.options.filter(option => option.value !== '')}),
		[{name: 'optionsEmptyLabel', value: 'Please select'}]
	),
	[
		{name: 'jcr:title', value: 'Service'},
		{name: 'optionsEmptyLabel', value: 'Veuillez sélectionner'},
		FR_DEPARTMENT_OPTIONS
	]
);

// A HALF-translated option list: French carries a label for the first entry
// only, which is what the save-time feeding leaves behind for the others (an
// entry nobody translated is stored with an empty label, never with the
// default language's words). Whether those two render as "Chocolate" and
// "Pistachio" or vanish from the French form is the site's call — see the log
// line at the end of this run.
const FR_FLAVOR_OPTIONS = frOptions([
	{value: 'vanilla', label: 'Vanille'},
	{value: 'chocolate', label: ''},
	{value: 'pistachio', label: ''}
]);

const FR_DELIVERY_OPTIONS = frOptions([
	{value: 'standard', label: 'Standard'},
	{value: 'express', label: 'Express', selected: true},
	{value: 'pickup', label: 'Retrait sur place'}
]);
const FR_GENDER_OPTIONS = frOptions([
	{value: 'female', label: 'Femme'},
	{value: 'male', label: 'Homme'},
	{value: 'other', label: 'Autre'}
]);

// datetime-local value one week ahead, so it always satisfies the "today" lower bound.
const nextWeekAtTen = (): string => {
	const date = new Date();
	date.setDate(date.getDate() + 7);
	return `${date.toISOString().slice(0, 10)}T10:00`;
};

// The first field of each form carries a help text in both languages, in the shape
// the editor stores (a paragraph), so the help-text look can be judged on every form.
// Full name field with a custom required message in both site languages.
const fullNameField = (): JahiaNode => {
	const node = getInputTextNode({name: 'fullName', title: 'Full name', required: true});
	node.properties.push(
		{name: 'helpText', value: '<p>As written on your identity document.</p>', language: 'en'},
		{name: 'helpText', value: '<p>Tel qu\'il figure sur votre pièce d\'identité.</p>', language: 'fr'},
		{name: 'msgValueMissing', value: 'Please fill in your full name', language: 'en'},
		{name: 'msgValueMissing', value: 'Merci de renseigner votre nom complet', language: 'fr'}
	);
	return node;
};

const firstNameField = (): JahiaNode => {
	const node = getInputTextNode({name: 'firstName', title: 'First name', required: true});
	node.properties.push(
		{name: 'helpText', value: '<p>As written on your identity document.</p>', language: 'en'},
		{name: 'helpText', value: '<p>Tel qu\'il figure sur votre pièce d\'identité.</p>', language: 'fr'},
		{name: 'msgValueMissing', value: 'Please fill in your first name', language: 'en'},
		{name: 'msgValueMissing', value: 'Merci de renseigner votre prénom', language: 'fr'}
	);
	return node;
};

// Given and family name rather than one "full name" field: a visitor profile holds them apart, so
// this is the form that exercises a mapping field by field.
const lastNameField = (): JahiaNode => {
	const node = getInputTextNode({name: 'lastName', title: 'Last name', required: true});
	node.properties.push(
		{name: 'msgValueMissing', value: 'Please fill in your last name', language: 'en'},
		{name: 'msgValueMissing', value: 'Merci de renseigner votre nom', language: 'fr'}
	);
	return node;
};

// --- The fields of every form, built afresh for each look (the mapping helpers write into the nodes).

const simpleFormNodes = (): JahiaNode[] => [
	// The visitor profile mapping, on the fields jCustomer knows by default. A required field always sets
	// its property, an optional one only completes a missing value; the free-text message stays out of the profile.
	mappedTo(withFrench(firstNameField(), [{name: 'jcr:title', value: 'Prénom'}]), 'firstName', {prefill: true}),
	mappedTo(withFrench(lastNameField(), [{name: 'jcr:title', value: 'Nom'}]), 'lastName', {prefill: true}),
	mappedTo(withFrench(getInputEmailNode({name: 'email', title: 'Email', required: true}), [{name: 'jcr:title', value: 'Email'}]), 'email', {strategy: 'setIfMissing', prefill: true}),
	sensitive(withFrench(getTextareaNode({name: 'message', title: 'Message'}), [{name: 'jcr:title', value: 'Message'}])),
	contactChannelSelect(),
	mappedTo(phoneNumberField(), 'phoneNumber', {strategy: 'setIfMissing', prefill: true}),
	// An optional single file, on the one form that offers Reset: the field a reset has to empty,
	// and the only shape whose value a browser will not let a script put back.
	withFrench(getInputFileNode({name: 'supportingDocument', title: 'Supporting document'}), [{name: 'jcr:title', value: 'Pièce jointe'}])
];

const newsletterNodes = (): JahiaNode[] => [
	withFrench(getInputEmailNode({name: 'email', title: 'Email', required: true}), [{name: 'jcr:title', value: 'Email'}]),
	withFrench(getInputTextNode({name: 'firstName', title: 'First name'}), [{name: 'jcr:title', value: 'Prénom'}])
];

// The three steps of the multi-step form.
const multiStepNodes = (): JahiaNode[] => [
	withFrench(getStepNode({
		name: 'identity',
		title: 'Identity',
		label: 'Identity',
		children: [
			withFrench(fullNameField(), [{name: 'jcr:title', value: 'Nom complet'}]),
			withFrench(getInputEmailNode({name: 'email', title: 'Email', required: true}), [{name: 'jcr:title', value: 'Email'}])
		]
	}), [{name: 'jcr:title', value: 'Identité'}, {name: 'label', value: 'Identité'}]),
	withFrench(getStepNode({
		name: 'preferences',
		title: 'Preferences',
		label: 'Preferences',
		children: [
			departmentSelect(),
			withFrench(getRadioNode(RADIO_GROUP), [{name: 'jcr:title', value: 'Mode de livraison'}, FR_DELIVERY_OPTIONS]),
			// The delivery method drives what follows: the pickup location for "pickup",
			// the address fieldset for the other methods — a rule on a field and a rule
			// on a container, in the same step as their source.
			pickupLocationField(),
			// A fieldset inside a step: the deepest authoring level (list > step > fieldset > field).
			withLogics(withFrench(getFieldsetNode({
				name: 'deliveryAddress',
				title: 'Delivery address',
				children: [
					withFrench(getInputTextNode({name: 'street', title: 'Street'}), [{name: 'jcr:title', value: 'Rue'}]),
					withFrench(getInputTextNode({name: 'postalCode', title: 'Postal code'}), [{name: 'jcr:title', value: 'Code postal'}]),
					withFrench(getInputTextNode({name: 'city', title: 'City'}), [{name: 'jcr:title', value: 'Ville'}])
				]
			}), [{name: 'jcr:title', value: 'Adresse de livraison'}]), DELIVERY_ADDRESS_RULE)
		]
	}), [{name: 'jcr:title', value: 'Préférences'}, {name: 'label', value: 'Préférences'}]),
	withFrench(getStepNode({
		name: 'confirmation',
		title: 'Confirmation',
		label: 'Confirmation',
		children: [
			withFrench(getCheckboxNode(CHECKBOX_SINGLE_COMPLETE), [
				{name: 'jcr:title', value: 'J\'accepte les conditions'},
				frOptions([{value: 'agreed', label: 'J\'accepte les conditions', selected: true}])
			]),
			withFrench(getTextareaNode({name: 'comment', title: 'Comment'}), [{name: 'jcr:title', value: 'Commentaire'}])
		]
	}), [{name: 'jcr:title', value: 'Confirmation'}, {name: 'label', value: 'Confirmation'}])
];

/** The roots the complete form's category-mode and content-mode fields point at. */
interface ChoiceRoots {
	tvCategoryUuid: string;
	audioCategoryUuid: string;
	agenciesRootUuid: string;
	servicesRootUuid: string;
}

// Every built-in field type, in three blocks. The visitor profile first — the mapped fields, all prefilled,
// then the sensitive one — so that what the jExperience integration touches is read in one place; the other
// field types next; and last the choice fields that complete the options matrix — four sources (manual,
// options source, category, content) each in a single and a multiple shape:
//   manual         single: gender, deliveryMethod (radio), department (select)   multiple: interests (checkbox group)
//   options source single: country (select)                                      multiple: viewing (checkbox group)
//   category       single: tvCategory (radio, product/tv)                        multiple: audioCategories (checkbox group, product/audio)
//   content        single: agency (select, agencies)                             multiple: services (multiple select, services)
// Each field has an option set of its own — the same three words under three sources read as one field
// repeated (HDU, 2026-09-18).
const completeFormNodes = ({tvCategoryUuid, audioCategoryUuid, agenciesRootUuid, servicesRootUuid}: ChoiceRoots): JahiaNode[] => [
	mappedTo(withFrench(getInputEmailNode({...INPUT_EMAIL_COMPLETE, defaultValue: undefined}), [
		{name: 'jcr:title', value: 'Email de contact'},
		{name: 'placeholder', value: 'Saisissez votre adresse e-mail'}
	]), 'email', {strategy: 'setIfMissing', prefill: true}),
	// A birth date cannot be after the submission day (the relative bound mode, showcased live). Read-only once
	// the profile's value is in: the visitor sees the date the profile knows and cannot change it — a date input,
	// whose native read-only holds. Not the email: the simple form states it first, so the complete form's own
	// visitors would never type theirs.
	mappedTo(withFrench(getInputDateNode({...INPUT_DATE_COMPLETE, defaultValue: undefined, max: undefined, maxBoundMode: 'today'}), [{name: 'jcr:title', value: 'Date de naissance'}]), 'birthDate', {prefill: true, then: 'readOnly'}),
	// The two shapes the profile mapping had no field for: a single choice to a string property,
	// a number to an integer one.
	// Read-only too, on a radio group: no native attribute for it, the page puts the profile's choice back on every change.
	mappedTo(withFrench(getRadioNode(GENDER_RADIO), [{name: 'jcr:title', value: 'Genre'}, FR_GENDER_OPTIONS]), 'gender', {strategy: 'setIfMissing', prefill: true, then: 'readOnly'}),
	// The one field with an author's default: the profile's value replaces the 1, and a visitor whose profile
	// has no kids value keeps it.
	mappedTo(withFrench(getInputNumberNode({name: 'kids', title: 'Number of children', minValue: 0, maxValue: 20, step: 1, defaultValue: 1}), [{name: 'jcr:title', value: 'Nombre d\'enfants'}]), 'kids', {strategy: 'setIfMissing', prefill: true}),
	// The sourced select showcases the empty-option label: the field starts
	// empty and its native required validation is exercisable on the site.
	// The countries source holds ISO codes, which is what jCustomer's countryName expects.
	mappedTo(withFrench(
		withEnglish(
			getSourcedChoiceFieldNode({primaryNodeType: 'fmdb:select', name: 'country', title: 'Country (sourced: countries)', sourceKey: 'countries'}),
			[{name: 'optionsEmptyLabel', value: 'Select a country…'}]
		),
		[
			{name: 'jcr:title', value: 'Pays (source : countries)'},
			{name: 'optionsEmptyLabel', value: 'Sélectionnez un pays…'}
		]
	// Prefilled too: a select is the shape whose first option the browser selects by itself, the one a
	// "did the visitor choose" guard reading the live state mistakes for a choice. And hidden once the profile
	// knows the country: the field disappears, its value is still submitted.
	), 'countryName', {strategy: 'setIfMissing', prefill: true, then: 'hidden'}),
	// The group and the switch feed the two properties the playground adds to jCustomer (see above), and are
	// prefilled from them: the multi-valued and the boolean shapes of the prefill.
	mappedTo(withFrench(getCheckboxNode(CHECKBOX_GROUP_COMPLETE), [
		{name: 'jcr:title', value: 'Centres d\'intérêt requis'},
		frOptions([
			{value: 'reading', label: 'Lecture'},
			{value: 'sports', label: 'Sport', selected: true},
			{value: 'music', label: 'Musique'}
		])
	]), 'formidableInterests', {prefill: true}),
	mappedTo(withFrench(getSwitchNode({name: 'newsletter', title: 'Newsletter opt-in', onLabel: 'Yes', offLabel: 'No'}), [
		{name: 'jcr:title', value: 'Lettre d\'information'},
		{name: 'onLabel', value: 'Oui'},
		{name: 'offLabel', value: 'Non'}
	]), 'formidableOptIn', {strategy: 'setIfMissing', prefill: true}),
	// An employee code is the kind of value that must never reach a visitor profile: the sensitive flag.
	// placeholder and list are i18n as well: without a French value the field loses its example and its
	// suggestion list in that language.
	sensitive(withFrench(getInputTextNode({...INPUT_TEXT_COMPLETE, defaultValue: undefined, helpText: '<p>Two capital letters, a dash, four digits: <strong>AB-1234</strong>.</p>'}), [
		{name: 'jcr:title', value: 'Code employé'},
		{name: 'helpText', value: '<p>Deux lettres majuscules, un tiret, quatre chiffres : <strong>AB-1234</strong>.</p>'},
		{name: 'placeholder', value: 'AB-1234'},
		{name: 'list', values: ['AB-1234', 'CD-5678']}
	])),
	// --- The rest of the field types, nothing of the visitor profile in them.
	// The appointment cannot be before the submission day (the other relative bound mode).
	withFrench(getInputDatetimeLocalNode({...INPUT_DATETIME_LOCAL_COMPLETE, defaultValue: undefined, min: undefined, minBoundMode: 'today'}), [{name: 'jcr:title', value: 'Rendez-vous'}]),
	withFrench(getInputColorNode(INPUT_COLOR_COMPLETE), [{name: 'jcr:title', value: 'Choisissez votre couleur préférée'}]),
	withFrench(getRadioNode(RADIO_GROUP), [{name: 'jcr:title', value: 'Mode de livraison'}, FR_DELIVERY_OPTIONS]),
	pickupLocationField(),
	departmentSelect(),
	withFrench(getTextareaNode({...TEXTAREA_COMPLETE, defaultValue: undefined}), [
		{name: 'jcr:title', value: 'Résumé du projet'},
		{name: 'placeholder', value: 'Décrivez le projet'}
	]),
	withFrench(getInputFileNode(INPUT_FILE_MULTIPLE), [{name: 'jcr:title', value: 'Pièces jointes'}]),
	// --- The rest of the options matrix (manual single and multiple, and the sourced single select, are above).
	withFrench(getSourcedChoiceFieldNode({primaryNodeType: 'fmdb:checkbox', name: 'viewing', title: 'How you watch TV (options source: static list, multiple)', sourceKey: 'viewing'}), [{name: 'jcr:title', value: 'Comment vous regardez la TV (source d\'options : liste statique, multiple)'}]),
	withFrench(getCategoryChoiceFieldNode({primaryNodeType: 'fmdb:radio', name: 'tvCategory', title: 'Your TV technology (category product/tv, single)', rootCategoryUuid: tvCategoryUuid}), [{name: 'jcr:title', value: 'Votre technologie TV (catégorie product/tv, choix unique)'}]),
	withFrench(getCategoryChoiceFieldNode({primaryNodeType: 'fmdb:checkbox', name: 'audioCategories', title: 'Audio products you are interested in (category product/audio, multiple)', rootCategoryUuid: audioCategoryUuid}), [{name: 'jcr:title', value: 'Produits audio qui vous intéressent (catégorie product/audio, choix multiple)'}]),
	withFrench(getContentChoiceFieldNode({primaryNodeType: 'fmdb:select', name: 'agency', title: 'Your agency (content: texts under contents/agencies, single)', rootNodeUuid: agenciesRootUuid, nodeType: 'jnt:text'}), [{name: 'jcr:title', value: 'Votre agence (contenu : textes sous contents/agencies, choix unique)'}]),
	withFrench(getContentChoiceFieldNode({primaryNodeType: 'fmdb:select', name: 'services', title: 'Services you need (content: texts under contents/services, multiple select)', rootNodeUuid: servicesRootUuid, nodeType: 'jnt:text', multiple: true}), [{name: 'jcr:title', value: 'Services souhaités (contenu : textes sous contents/services, sélection multiple)'}])
];

const languagesFormNodes = (): JahiaNode[] => [
	withFrench(
		getSelectNode({
			name: 'flavor',
			title: 'Flavor',
			helpText: '<p>Two of the three flavours are left untranslated on purpose.</p>',
			options: [
				{value: 'vanilla', label: 'Vanilla'},
				{value: 'chocolate', label: 'Chocolate'},
				{value: 'pistachio', label: 'Pistachio'}
			]
		}),
		[
			{name: 'jcr:title', value: 'Parfum'},
			{name: 'helpText', value: '<p>Deux des trois parfums sont volontairement laissés sans traduction.</p>'},
			FR_FLAVOR_OPTIONS
		]
	)
];

const OPTIONS_SOURCES_CONFIG = [
	// Literal label
	'countries|Countries|country',
	// Localized label: resolved against the module's resource bundle in the editor UI language (offered in the
	// editor's dropdown, used by no field of the set: its values are the TV categories' words, see below)
	'tv|formidable-test-module-samples-java:sample.optionsSource.tv|fmdbSampleStaticList|plasma,oled,led',
	// A static list whose values have no label in the sample bundle: the raw value is the label, which is why
	// they are capitalised here
	'viewing|How you watch|fmdbSampleStaticList|Streaming,Cable,Satellite,Antenna'
];

describe('Playground - provision manual-testing forms', () => {
	before(() => {
		cy.login();
		cy.readFile(SAMPLE_THEME_PATH).then((content: string) => {
			themeCss = content;
		});
	});

	it('resets the test site', () => {
		detectJExperience();
		// not gated on the module: the rules to clean up are the ones written while it *was* deployed
		cy.then(() => deleteMappingRulesOfTheSite());
		deleteSite(FORMIDABLE_TEST_SITE.key);
		createSite(FORMIDABLE_TEST_SITE.key, FORMIDABLE_TEST_SITE.config);
		FORMIDABLE_MODULE_IDS.forEach(moduleId => enableModule(moduleId, FORMIDABLE_TEST_SITE.key));
		cy.then(() => {
			if (jExperienceAvailable) {
				// Both halves of the render filter's site check: jExperience among the site's modules, and ours.
				enableModule('jexperience', FORMIDABLE_TEST_SITE.key);
				enableModule('formidable-jexperience-engine', FORMIDABLE_TEST_SITE.key);
				ensureCustomProfileProperties();
			}
		});
		// A live page renders only if the site home is published in the page
		// language (getSite().getHome() resolves to null otherwise -> 500),
		// and publishing a page does not cascade up to its home: publish the
		// home in every site language now, while it is still empty.
		publishAndWaitJobEnding(SITE_HOME_PATH, ['en', 'fr']);
		// The forms' folder, published while still empty so that every form finds its parent in live.
		addNode({parentPathOrId: CONTENT_PATH, name: 'forms', primaryNodeType: 'jnt:contentFolder', properties: []});
		addNode({parentPathOrId: FORMS_FOLDER_PATH, name: 'playground', primaryNodeType: 'jnt:contentFolder', properties: []});
		publishAndWaitJobEnding(FORMS_FOLDER_PATH, ['en', 'fr']);
	});

	it('declares the options sources in the module configuration', () => {
		setOptionsSourcesConfig(OPTIONS_SOURCES_CONFIG);
	});

	it('creates and publishes the sample category trees product/tv and product/audio (category-mode targets)', () => {
		// Categories are global; creations are idempotent (existing nodes are kept).
		addNode({parentPathOrId: CATEGORY_ROOT, ...getCategoryNode('product', 'Product', 'Produit')});
		addNode({parentPathOrId: `${CATEGORY_ROOT}/product`, ...getCategoryNode('tv', 'TV', 'Téléviseur')});
		addNode({parentPathOrId: `${CATEGORY_ROOT}/product/tv`, ...getCategoryNode('plasma', 'Plasma', 'Plasma')});
		addNode({parentPathOrId: `${CATEGORY_ROOT}/product/tv`, ...getCategoryNode('oled', 'OLED', 'OLED')});
		addNode({parentPathOrId: `${CATEGORY_ROOT}/product/tv`, ...getCategoryNode('led', 'LED', 'LED')});
		// A second tree for the multiple category field, so that the two do not show the same words.
		addNode({parentPathOrId: `${CATEGORY_ROOT}/product`, ...getCategoryNode('audio', 'Audio', 'Audio')});
		addNode({parentPathOrId: `${CATEGORY_ROOT}/product/audio`, ...getCategoryNode('headphones', 'Headphones', 'Casques')});
		addNode({parentPathOrId: `${CATEGORY_ROOT}/product/audio`, ...getCategoryNode('speakers', 'Speakers', 'Enceintes')});
		addNode({parentPathOrId: `${CATEGORY_ROOT}/product/audio`, ...getCategoryNode('soundbar', 'Soundbar', 'Barre de son')});
		publishAndWaitJobEnding(`${CATEGORY_ROOT}/product`, ['en', 'fr']);
	});

	it('creates and publishes the agency and service contents (content-mode targets)', () => {
		addNode({parentPathOrId: CONTENT_PATH, name: 'agencies', primaryNodeType: 'jnt:contentFolder', properties: []});
		addNode({parentPathOrId: AGENCIES_ROOT_PATH, ...getTitledTextNode('paris', 'Paris agency', 'Agence de Paris')});
		addNode({parentPathOrId: AGENCIES_ROOT_PATH, ...getTitledTextNode('lyon', 'Lyon agency', 'Agence de Lyon')});
		addNode({parentPathOrId: AGENCIES_ROOT_PATH, name: 'europe', primaryNodeType: 'jnt:contentFolder', properties: []});
		addNode({parentPathOrId: `${AGENCIES_ROOT_PATH}/europe`, ...getTitledTextNode('berlin', 'Berlin agency', 'Agence de Berlin')});
		publishAndWaitJobEnding(AGENCIES_ROOT_PATH, ['en', 'fr']);
		// A second root for the multiple content field, so that the two do not show the same words.
		addNode({parentPathOrId: CONTENT_PATH, name: 'services', primaryNodeType: 'jnt:contentFolder', properties: []});
		addNode({parentPathOrId: SERVICES_ROOT_PATH, ...getTitledTextNode('repair', 'Repair', 'Réparation')});
		addNode({parentPathOrId: SERVICES_ROOT_PATH, ...getTitledTextNode('installation', 'Installation', 'Installation')});
		addNode({parentPathOrId: SERVICES_ROOT_PATH, ...getTitledTextNode('training', 'Training', 'Formation')});
		publishAndWaitJobEnding(SERVICES_ROOT_PATH, ['en', 'fr']);
	});

	it('provisions the results reader user', () => {
		// Server-level user, kept across runs; site member so jContent is reachable. The results
		// themselves are granted form by form below, on the two simple forms.
		createUser(RESULTS_READER.name, RESULTS_READER.password);
		grantRoles(`/sites/${FORMIDABLE_TEST_SITE.key}`, ['editor'], RESULTS_READER.name, 'USER');
		cy.log(`Results reader: ${RESULTS_READER.name} / ${RESULTS_READER.password} (access to the two simple forms' results only)`);
	});

	/**
	 * One form (or page) of the set. Its titles are what the nodes get, in both languages, and the English
	 * one decides its place in the creation order.
	 */
	interface Entry {
		title: string;
		frTitle: string;
		/** Provisions the entry in the look, under its own titles; the roots are what the complete form's category and content fields point at. */
		provision: (look: Look, titles: Pick<Entry, 'title' | 'frTitle'>, roots: ChoiceRoots) => void;
	}

	// In TITLE order, asserted by the guard in the tests below — and the two-forms page after the simple and
	// the newsletter forms it resolves by path, the one ordering the set depends on, asserted as well.
	const ENTRIES: Entry[] = [
		{
			title: 'Complete form',
			frTitle: 'Formulaire complet',
			provision: (look, {title, frTitle}, roots) => {
				provisionForm(look, 'complete', title, frTitle, completeFormNodes(roots))
					.then(({livePath}) => cy.log(`${look.label} complete form: /en/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`));
			}
		},
		{
			title: 'Half-translated options',
			frTitle: 'Options traduites à moitié',
			provision: (look, {title, frTitle}) => {
				provisionForm(look, 'languages', title, frTitle, languagesFormNodes()).then(({livePath}) => {
					cy.log(`${look.label} half-translated options, English: /en/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`);
					cy.log(`${look.label} half-translated options, French: /fr/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`);
					cy.log('Toggle "Replace untranslated content with the default language content" in the site settings: '
						+ 'ON renders Chocolate/Pistachio with their English labels, OFF drops them from the French form.');
				});
			}
		},
		{
			title: 'Multi-step form',
			frTitle: 'Formulaire multi-étapes',
			provision: (look, {title, frTitle}) => {
				// A business stylesheet on a multi-step form (the CSS look): the authoring UI must stay readable on top of it.
				provisionForm(look, 'steps', title, frTitle, multiStepNodes())
					.then(({livePath}) => cy.log(`${look.label} multi-step form: /en/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`));
			}
		},
		{
			title: 'Newsletter',
			frTitle: 'Lettre d\'information',
			provision: (look, {title, frTitle}) => {
				// A form without a page of its own: it shows on the two-forms page only.
				createFormNode(
					nameOf(look, 'newsletter'),
					titleOf(look, title),
					newsletterNodes(),
					{
						parentPath: PLAYGROUND_FORMS_PATH,
						actions: [saveToJcrAction()],
						...LIST_TITLES,
						properties: [{name: 'jcr:title', value: frTitleOf(look, frTitle), language: 'fr'}, ...cssOf(look)]
					}
				);
				publishAndWaitJobEnding(`${PLAYGROUND_FORMS_PATH}/${nameOf(look, 'newsletter')}`, ['en', 'fr']);
			}
		},
		{
			title: 'Simple contact form',
			frTitle: 'Formulaire de contact simple',
			provision: (look, {title, frTitle}) => {
				// The form that shows the two optional buttons: Reset, which puts the form back to its
				// defaults (and undoes a prefill with them), and New form, offered once a submission
				// went through. The other forms keep Submit alone.
				const buttons: NodeProperty[] = [
					{name: 'showResetBtn', value: 'true', type: 'BOOLEAN'},
					{name: 'showNewFormBtn', value: 'true', type: 'BOOLEAN'}
				];
				provisionForm(look, 'simple', title, frTitle, simpleFormNodes(), buttons).then(({formPath, livePath}) => {
					// Results access: fmdb-results-reader on the form node, propagated to the
					// results by the ACL sync once the form is (re)published.
					grantRoles(formPath, ['fmdb-results-reader'], RESULTS_READER.name, 'USER');
					publishAndWaitJobEnding(formPath, ['en', 'fr']);
					cy.log(`${look.label} simple form: /en/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`);
				});
			}
		},
		{
			title: 'Two forms on one page',
			frTitle: 'Deux formulaires sur une page',
			provision: (look, {title, frTitle}) => {
				// The case a page with one form never shows: two forms side by side, each with its own results and
				// its own mapping. It is also what the jExperience integration has to get right — one configuration
				// block per form, one tracking script for the page. Both forms are referenced rather than copied,
				// so the page also shows a form living in two places, which is how an author uses a reference.
				const simpleName = nameOf(look, 'simple');
				const newsletterName = nameOf(look, 'newsletter');
				const pageName = `${look.key}-two-forms-page`;
				getNodeByPath(`${PLAYGROUND_FORMS_PATH}/${simpleName}`).then(simpleResponse => {
					const simpleId: string = simpleResponse.data.jcr.nodeByPath.uuid;
					getNodeByPath(`${PLAYGROUND_FORMS_PATH}/${newsletterName}`).then(newsletterResponse => {
						const newsletterId: string = newsletterResponse.data.jcr.nodeByPath.uuid;

						addNode({
							parentPathOrId: SITE_HOME_PATH,
							name: pageName,
							primaryNodeType: 'jnt:page',
							properties: [
								{name: 'jcr:title', value: titleOf(look, title), language: 'en'},
								{name: 'jcr:title', value: frTitleOf(look, frTitle), language: 'fr'},
								{name: 'j:templateName', value: 'simple'}
							],
							children: [
								{
									name: 'pagecontent',
									primaryNodeType: 'jnt:contentList',
									properties: [],
									children: [
										{name: 'main-resource-display', primaryNodeType: 'jnt:mainResourceDisplay', properties: []},
										{
											name: `${simpleName}-reference`,
											primaryNodeType: 'fmdb:formReference',
											properties: [{name: 'j:node', value: simpleId, type: 'WEAKREFERENCE'}]
										},
										{
											name: `${newsletterName}-reference`,
											primaryNodeType: 'fmdb:formReference',
											properties: [{name: 'j:node', value: newsletterId, type: 'WEAKREFERENCE'}]
										}
									]
								}
							]
						});

						publishAndWaitJobEnding(`${SITE_HOME_PATH}/${pageName}`, ['en', 'fr']);
						cy.log(`${look.label} two forms on one page: /en/sites/${FORMIDABLE_TEST_SITE.key}/home/${pageName}.html`);
					});
				});
			}
		}
	];

	// One test per look, the looks in label order and the entries in title order: what the trees will show.
	LOOKS.forEach(look => {
		it(`provisions the ${look.label.toLowerCase()} forms and their pages, in title order`, () => {
			expect(LOOKS.map(candidate => candidate.label), 'looks in label order').to.satisfy(inTitleOrder);
			expect(ENTRIES.map(entry => entry.title), 'entries in title order').to.satisfy(inTitleOrder);
			// the ordering the set actually depends on: the two-forms page resolves the two forms by path
			const at = (title: string) => ENTRIES.findIndex(entry => entry.title === title);
			expect(at('Two forms on one page'), 'the two-forms page comes after the forms it references')
				.to.be.greaterThan(Math.max(at('Simple contact form'), at('Newsletter')));

			const uuidOf = (path: string): Cypress.Chainable<string> => getNodeByPath(path).then(response => response.data.jcr.nodeByPath.uuid as string);
			uuidOf(`${CATEGORY_ROOT}/product/tv`).then(tvCategoryUuid => {
				uuidOf(`${CATEGORY_ROOT}/product/audio`).then(audioCategoryUuid => {
					uuidOf(AGENCIES_ROOT_PATH).then(agenciesRootUuid => {
						uuidOf(SERVICES_ROOT_PATH).then(servicesRootUuid => {
							ENTRIES.forEach(entry => entry.provision(look, entry, {tvCategoryUuid, audioCategoryUuid, agenciesRootUuid, servicesRootUuid}));
						});
					});
				});
			});
		});
	});

	it('submits sample entries so the results screens have something to show', () => {
		// The same entries in both looks: every results screen has something to show, and the visitor
		// profile ends the same (one Cypress visitor, the strategies decide what each submission keeps).
		LOOKS.forEach(look => {
			// Simple contact form: three visitors, one of them in French. Typed values stay
			// ASCII: realType (cypress-real-events) rejects accented characters.
			[
				{lang: 'en', firstName: 'Alice', lastName: 'Martin', email: 'alice.martin@example.com', message: 'Could you send me the brochure of your spring collection?'},
				{lang: 'en', firstName: 'Bob', lastName: 'Dupont', email: 'bob.dupont@example.com', message: 'The store in Lyon was closed on Monday, is that expected?', phone: '33612345678'},
				{lang: 'fr', firstName: 'Chloe', lastName: 'Bernard', email: 'chloe.bernard@example.com', message: 'Bonjour, je souhaite recevoir le catalogue par courrier.'}
			].forEach(({lang, firstName, lastName, email, message, phone}) => {
				const form = visitLiveForm(livePageOf(look, 'simple'), lang);
				form.getTextInput('firstName').type(firstName);
				form.getTextInput('lastName').type(lastName);
				form.getEmailInput('email').type(email);
				form.getTextarea('message').type(message);
				if (phone) {
					// Asking for a call reveals the conditional phone number field.
					form.getSelectInput('contactChannel').selectByValue('phone');
					form.getTextInput('phoneNumber').type(phone);
				}

				form.submit();
				form.waitForSubmit();
			});

			// Multi-step form: two visitors going through the three steps.
			[
				{fullName: 'Diane Roux', email: 'diane.roux@example.com', department: 'Engineering', delivery: 'Standard', comment: 'Looking forward to the next release.'},
				{fullName: 'Ethan Moreau', email: 'ethan.moreau@example.com', department: 'Sales', delivery: 'Express', comment: 'Please call me back in the afternoon.'}
			].forEach(({fullName, email, department, delivery, comment}) => {
				const form = visitLiveForm(livePageOf(look, 'steps'));
				form.getTextInput('fullName').type(fullName);
				form.getEmailInput('email').type(email);
				form.nextStep();
				form.getSelectInput('department').select(department);
				form.getRadioGroup('deliveryMethod').select(delivery);
				form.nextStep();
				form.getTextarea('comment').type(comment);
				form.submit();
				form.waitForSubmit();
			});

			// Complete form: every field type, with the PDF and CSV fixtures as attachments.
			// The third entry picks "Pickup", which reveals the conditional pickup location.
			// Three of the profile fields ask for something once the profile's value is in (birth date and
			// gender read-only, country hidden): from the second visitor on, the profile knows them, and the
			// entry leaves them to the prefill as a visitor would have to. The prefill runs once the tracker
			// has its context, and waiting for the script says nothing about that: formidable-jxp assigns
			// window.formidableJxp synchronously at load. So poll the script's own predicate — not wemLoaded,
			// which the tracker sets in its fallback mode too, where nothing is ever prefilled — bounded and
			// logged rather than asserted: a jCustomer that does not answer is the case this script degrades
			// through everywhere else, and the entries then fill every field by hand.
			const contextLoaded = (attempt = 1): void => {
				if (!jExperienceAvailable) {
					return;
				}

				cy.window({log: false}).then(win => {
					const jxp = (win as unknown as {formidableJxp?: {trackerReady: () => boolean}}).formidableJxp;
					if (jxp?.trackerReady()) {
						return;
					}

					if (attempt * CONTEXT_ATTEMPT_PAUSE_MS < JXP_CONTEXT_TIMEOUT_MS) {
						// eslint-disable-next-line cypress/no-unnecessary-waiting -- a pause between two tries, not a wait for an element
						cy.wait(CONTEXT_ATTEMPT_PAUSE_MS);
						contextLoaded(attempt + 1);
						return;
					}

					cy.log('no jExperience context (jCustomer unreachable?): the entries fill every field by hand');
				});
			};
			const unlessPrefilled = (fieldName: string, fill: () => void) => {
				cy.get(`form.fmdb-form [data-fmdb-node-name="${fieldName}"]`).then($wrapper => {
					if ($wrapper.attr('data-fmdb-prefilled')) {
						cy.log(`${fieldName}: left to the prefill (${$wrapper.attr('data-fmdb-prefilled')})`);
					} else {
						fill();
					}
				});
			};
			[
				{code: 'AB-1234', email: 'fanny.girard@example.com', birth: '1988-04-12', color: '#ff5733', interests: ['Sports', 'Music'], delivery: 'Express', pickup: null, gender: 'Female', kids: '2', country: 'FR', newsletter: true, department: 'Engineering', summary: 'A new intranet for the engineering team, with a form for incident reports.', files: ['cypress/fixtures/files/document.pdf']},
				{code: 'CD-5678', email: 'gabriel.lefevre@example.com', birth: '1975-11-30', color: '#3366cc', interests: ['Reading'], delivery: 'Standard', pickup: null, gender: 'Male', kids: '0', country: 'DE', newsletter: false, department: 'Sales', summary: 'Quarterly sales dashboard with an export of the leads collected on the site.', files: ['cypress/fixtures/files/sample.csv']},
				{code: 'EF-9012', email: 'helene.petit@example.com', birth: '1992-07-08', color: '#2e8b57', interests: ['Sports'], delivery: 'Pickup', pickup: 'Paris - Rue de Rivoli', gender: 'Other', kids: '3', country: 'CH', newsletter: true, department: 'Support', summary: 'Support knowledge base migration, including the attached inventory and specification.', files: ['cypress/fixtures/files/document.pdf', 'cypress/fixtures/files/sample.csv']}
			].forEach(({code, email, birth, color, interests, delivery, pickup, gender, kids, country, newsletter, department, summary, files}) => {
				const form = visitLiveForm(livePageOf(look, 'complete'));
				contextLoaded();
				form.getTextInput(INPUT_TEXT_COMPLETE.name!).type(code);
				// prefilled with the first visitor's address from the simple form (set if missing), typed over
				form.getEmailInput(INPUT_EMAIL_COMPLETE.name!).type(email);
				unlessPrefilled(INPUT_DATE_COMPLETE.name!, () => form.getDateInput(INPUT_DATE_COMPLETE.name!).setDate(birth));
				// The appointment cannot be before the submission day (relative "today" bound).
				form.getDateTimeLocalInput(INPUT_DATETIME_LOCAL_COMPLETE.name!).setDateTime(nextWeekAtTen());
				form.getColorInput(INPUT_COLOR_COMPLETE.name!).setColor(color);
				form.getCheckboxGroup(CHECKBOX_GROUP_COMPLETE.name!).uncheckAll().checkByLabels(interests);
				form.getRadioGroup(RADIO_GROUP.name!).select(delivery);
				if (pickup) {
					form.getTextInput('pickupLocation').type(pickup);
				}

				unlessPrefilled('gender', () => form.getRadioGroup('gender').select(gender));
				form.getNumberInput('kids').clear().type(kids);
				// the switch's track covers its input, as spec 214 knows: force the change. Said either way: from
				// the second visitor on the prefill arrives with the profile's opt-in already on.
				if (newsletter) {
					form.getCheckbox('newsletter').getInput().check({force: true});
				} else {
					form.getCheckbox('newsletter').getInput().uncheck({force: true});
				}

				unlessPrefilled('country', () => form.getSelectInput('country').selectByValue(country));
				form.getSelectInput('department').select(department);
				form.getTextarea(TEXTAREA_COMPLETE.name!).type(summary);
				form.getFileInput(INPUT_FILE_MULTIPLE.name!).attachFile(files).shouldHaveSelectedFileCount(files.length);
				form.submit();
				form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');
			});
		});
	});

	it('adds an unpublished draft agency, absent from the live options', () => {
		// Created after the complete forms are published: publishing a form
		// publishes its referenced options root with its subtree, so an earlier
		// draft would have been published along.
		addNode({parentPathOrId: AGENCIES_ROOT_PATH, ...getTitledTextNode('draft', 'Draft agency', 'Agence brouillon')});
	});
});
