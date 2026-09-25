import {addNode, publishAndWaitJobEnding, uploadFile} from '@jahia/cypress';
import {
	createPublishedLiveFormPage,
	FORMIDABLE_TEST_SITE,
	getCheckboxNode,
	getInputTextNode,
	getRadioNode,
	getSelectNode,
	visitLiveForm,
	visitPreviewForm,
	withValidationMessages
} from '../../support/fixtures';
import {SITE_HOME_PATH} from '../../support/constants';
import {useFormidableSite} from './support';
import type {Form} from '../../page-object';

const TEXT_WITH_HELP = {
	name: 'nickname',
	title: 'Nickname',
	helpText: 'Between 3 and 20 characters, visible to other members.'
};

const TEXT_WITHOUT_HELP = {
	name: 'company',
	title: 'Company'
};

// helpText is a rich text property: basic formatting must be rendered as HTML
const TEXT_WITH_HTML_HELP = {
	name: 'workEmailAlias',
	title: 'Work email alias',
	helpText: '<p>Use your <strong>professional</strong> address, not a personal one.</p>'
};

const SELECT_WITH_HELP = {
	name: 'country',
	title: 'Country',
	helpText: 'Pick the country of your billing address.',
	options: [
		{value: '', label: 'Please select', selected: false},
		{value: 'fr', label: 'France', selected: false},
		{value: 'ca', label: 'Canada', selected: false}
	]
};

const RADIO_WITH_HELP = {
	name: 'contactChannel',
	title: 'Preferred contact channel',
	helpText: 'We will only use it for updates about your request.',
	choices: [
		{value: 'email', label: 'Email', selected: false},
		{value: 'phone', label: 'Phone', selected: false}
	]
};

const CHECKBOX_GROUP_WITH_HELP = {
	name: 'interests',
	title: 'Interests',
	helpText: 'Select all the topics you want to hear about.',
	choices: [
		{value: 'news', label: 'News', selected: false},
		{value: 'events', label: 'Events', selected: false}
	]
};

const REQUIRED_TEXT_WITH_HELP = {
	name: 'projectCode',
	title: 'Project code',
	required: true,
	helpText: 'The 6-character code from your confirmation email.'
};

const REQUIRED_MESSAGE = 'Please provide your project code.';

// Internal link and image authored the way the Content Editor pickers insert
// them: /cms/{mode}/{lang}/<path>.html and /files/{workspace}/<path> — stored
// with Jahia URL placeholders, which the rendering must resolve before
// reaching the visitor. Rich text values passed through island props (checkbox
// group help) escape the HTML-level URL rewriting, so they are resolved
// server-side before serialization; fully server-rendered helps (input text)
// are resolved by the platform itself.
const HELP_PAGE_NAME = 'help-center';
const SITE_FILES_PATH = `/sites/${FORMIDABLE_TEST_SITE.key}/files`;
const helpPageLink = (label: string) =>
	`<a href="/cms/{mode}/{lang}/sites/${FORMIDABLE_TEST_SITE.key}/home/${HELP_PAGE_NAME}.html">${label}</a>`;
const HELP_IMAGE = `<img src="/files/{workspace}/sites/${FORMIDABLE_TEST_SITE.key}/files/cats.jpg" alt="cats"/>`;

const CHECKBOX_WITH_RICH_HELP = {
	name: 'newsletterTopics',
	title: 'Newsletter topics',
	helpText: `<p>Not sure what to pick? ${helpPageLink('Open the help page')} ${HELP_IMAGE}</p>`,
	choices: [
		{value: 'news', label: 'News', selected: false},
		{value: 'events', label: 'Events', selected: false}
	]
};
const CHECKBOX_HELP_FR = `<p>Pas sûr de votre choix ? ${helpPageLink('Ouvrir la page d’aide')} ${HELP_IMAGE}</p>`;

const TEXT_WITH_RICH_HELP = {
	name: 'projectReference',
	title: 'Project reference',
	helpText: `<p>Where to find it? ${helpPageLink('Open the help page')} ${HELP_IMAGE}</p>`
};
const TEXT_HELP_FR = `<p>Où la trouver ? ${helpPageLink('Ouvrir la page d’aide')} ${HELP_IMAGE}</p>`;

describe('Form fields - 210 Help text', () => {
	useFormidableSite();

	it('renders the help text linked to its control, and skips it when not set', () => {
		createPublishedLiveFormPage(
			'help-text-form',
			'Help Text Form',
			[
				getInputTextNode(TEXT_WITH_HELP),
				getInputTextNode(TEXT_WITHOUT_HELP),
				getInputTextNode(TEXT_WITH_HTML_HELP),
				getSelectNode(SELECT_WITH_HELP),
				getRadioNode(RADIO_WITH_HELP),
				getCheckboxNode(CHECKBOX_GROUP_WITH_HELP)
			]
		).then(({livePath}) => {
			const form = visitLiveForm(livePath);

			form.getTextInput(TEXT_WITH_HELP.name).shouldHaveHelpText(TEXT_WITH_HELP.helpText);
			form.getTextInput(TEXT_WITHOUT_HELP.name).shouldNotHaveHelpText();

			// Rich text help: markup is rendered as HTML, not escaped
			form.getTextInput(TEXT_WITH_HTML_HELP.name)
				.shouldHaveHelpText('Use your professional address, not a personal one.')
				.getHelpText()
				.find('strong')
				.should('have.text', 'professional');

			form.getSelectInput(SELECT_WITH_HELP.name).shouldHaveHelpText(SELECT_WITH_HELP.helpText);

			// Group variants: the help block sits inside the fieldset and describes the fieldset
			form.getRadioGroup(RADIO_WITH_HELP.name).shouldHaveHelpText(RADIO_WITH_HELP.helpText);
			form.getCheckboxGroup(CHECKBOX_GROUP_WITH_HELP.name).shouldHaveHelpText(CHECKBOX_GROUP_WITH_HELP.helpText);
		});
	});

	it('resolves internal page links and images in help texts, in all languages', () => {
		// The published media and page the help texts reference
		uploadFile('files/cats.jpg', SITE_FILES_PATH, 'cats.jpg', 'image/jpeg').then(() => {
			publishAndWaitJobEnding(`${SITE_FILES_PATH}/cats.jpg`);

			addNode({
				parentPathOrId: SITE_HOME_PATH,
				name: HELP_PAGE_NAME,
				primaryNodeType: 'jnt:page',
				properties: [
					{name: 'jcr:title', value: 'Help center', language: 'en'},
					{name: 'jcr:title', value: 'Centre d’aide', language: 'fr'},
					{name: 'j:templateName', value: 'simple'}
				]
			}).then(() => {
				publishAndWaitJobEnding(`${SITE_HOME_PATH}/${HELP_PAGE_NAME}`, ['en', 'fr']);

				// Island props path: checkbox group help, translated in both languages
				const checkboxNode = getCheckboxNode(CHECKBOX_WITH_RICH_HELP);
				const checkboxChoices = checkboxNode.properties.find(p => p.name === 'options');
				checkboxNode.properties.push(
					{name: 'jcr:title', value: 'Sujets de la newsletter', language: 'fr'},
					{name: 'helpText', value: CHECKBOX_HELP_FR, language: 'fr'},
					{name: 'options', values: checkboxChoices?.values, language: 'fr'}
				);

				// SSR path: text input help, translated in both languages
				const textNode = getInputTextNode(TEXT_WITH_RICH_HELP);
				textNode.properties.push(
					{name: 'jcr:title', value: 'Référence du projet', language: 'fr'},
					{name: 'helpText', value: TEXT_HELP_FR, language: 'fr'}
				);

				createPublishedLiveFormPage(
					'help-text-link-form',
					'Help Text Link Form',
					[checkboxNode, textNode],
					undefined,
					undefined,
					{
						pageProperties: [{name: 'jcr:title', value: 'Formulaire avec aide', language: 'fr'}],
						publishLanguages: ['en', 'fr']
					}
				).then(({livePath}) => {
					const assertRichHelp = (
						label: string,
						pageUrl: string,
						openForm: () => Form,
						texts: {checkbox: string; text: string; linkLabel: string}
					) => {
						// The raw page source must not leak unresolved placeholders —
						// this covers the values serialized inside island props, which
						// the HTML-level URL rewriting cannot reach
						cy.request(pageUrl).its('body').should(body => {
							expect(body, `${label} no unresolved placeholders in page source`).to.not.contain('/cms/{mode}');
							expect(body, `${label} no unresolved placeholders in page source`).to.not.contain('{workspace}');
						});

						const form = openForm();
						const helps = [
							{getHelp: () => form.getCheckboxGroup(CHECKBOX_WITH_RICH_HELP.name).getHelpText(), text: texts.checkbox},
							{getHelp: () => form.getTextInput(TEXT_WITH_RICH_HELP.name).getHelpText(), text: texts.text}
						];

						helps.forEach(({getHelp, text}) => {
							getHelp().should('contain', text);

							getHelp().find('a')
								.should('contain', texts.linkLabel)
								.invoke('attr', 'href')
								.then(href => {
									expect(href, `${label} href is resolved`).to.not.contain('{mode}');
									expect(href, `${label} href is resolved`).to.not.contain('{lang}');
									expect(href, `${label} href is resolved`).to.not.contain('##');
									expect(href, `${label} href targets the help page`)
										.to.contain(`/sites/${FORMIDABLE_TEST_SITE.key}/home/${HELP_PAGE_NAME}`);

									// The resolved URL must actually serve the page
									cy.request({url: href, retryOnStatusCodeFailure: true})
										.its('status')
										.should('eq', 200);
								});

							getHelp().find('img')
								.invoke('attr', 'src')
								.then(src => {
									expect(src, `${label} img src is resolved`).to.not.contain('{workspace}');
									expect(src, `${label} img src is resolved`).to.not.contain('##');
									expect(src, `${label} img targets the media`).to.contain('cats.jpg');

									// The resolved URL must actually serve the media
									cy.request({url: src, retryOnStatusCodeFailure: true})
										.its('status')
										.should('eq', 200);
								});
						});
					};

					const liveUrl = (lang: string) => `/${lang}/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`;
					const previewUrl = (lang: string) => `/cms/render/default/${lang}/sites/${FORMIDABLE_TEST_SITE.key}/${livePath}`;
					const EN_TEXTS = {
						checkbox: 'Not sure what to pick?',
						text: 'Where to find it?',
						linkLabel: 'Open the help page'
					};
					const FR_TEXTS = {
						checkbox: 'Pas sûr de votre choix ?',
						text: 'Où la trouver ?',
						linkLabel: 'Ouvrir la page d’aide'
					};

					assertRichHelp('[en live]', liveUrl('en'), () => visitLiveForm(livePath, 'en'), EN_TEXTS);
					assertRichHelp('[fr live]', liveUrl('fr'), () => visitLiveForm(livePath, 'fr'), FR_TEXTS);

					// Preview mode rewrites the same stored URLs differently (render/default servlet)
					assertRichHelp('[en preview]', previewUrl('en'), () => visitPreviewForm(livePath, 'en'), EN_TEXTS);
					assertRichHelp('[fr preview]', previewUrl('fr'), () => visitPreviewForm(livePath, 'fr'), FR_TEXTS);
				});
			});
		});
	});

	it('keeps the help text referenced while a validation error is shown', () => {
		createPublishedLiveFormPage(
			'help-text-validation-form',
			'Help Text Validation Form',
			[
				withValidationMessages(
					getInputTextNode(REQUIRED_TEXT_WITH_HELP),
					{msgValueMissing: REQUIRED_MESSAGE}
				)
			]
		).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			const input = form.getTextInput(REQUIRED_TEXT_WITH_HELP.name);

			input.shouldHaveHelpText(REQUIRED_TEXT_WITH_HELP.helpText);

			form.submit();

			// The control now references both the help block and the error message
			input
				.shouldHaveValidationError(REQUIRED_MESSAGE)
				.shouldHaveHelpText(REQUIRED_TEXT_WITH_HELP.helpText);
			input.getInput().invoke('attr', 'aria-describedby').should('contain', 'fmdb-validation-error-');

			input
				.type('ABC123')
				.shouldNotHaveValidationError()
				.shouldHaveHelpText(REQUIRED_TEXT_WITH_HELP.helpText);
			input.getInput().invoke('attr', 'aria-describedby').should('not.contain', 'fmdb-validation-error-');
		});
	});
});
