import {getNodeByPath} from '@jahia/cypress';
import {getInputFileNode, type JahiaNode} from '../../support/fixtures';
import {createPublishedLiveFormPage, getLatestLiveFormSubmission, visitLiveForm} from '../../support/fixtures/forms';
import {useFormidableSite} from './support';

interface JcrPropertyResponse {
	name: string;
	value?: string | null;
}

interface JcrResourceNodeResponse {
	properties?: JcrPropertyResponse[];
}

interface JcrChildNodeResponse {
	name: string;
	children?: {
		nodes?: JcrResourceNodeResponse[];
	};
}

interface NodeByPathResponse {
	data?: {
		jcr?: {
			nodeByPath?: {
				children?: {
					nodes?: JcrChildNodeResponse[];
				};
			};
		};
	};
}

const SAVE_TO_JCR_ACTION: JahiaNode = {
	name: 'storeSubmission',
	primaryNodeType: 'fmdb:save2jcrAction',
	properties: []
};

const FIXTURE_FILES = [
	{
		fileName: 'cats.gif',
		mimeTypes: ['image/gif'],
		path: 'cypress/fixtures/files/cats.gif'
	},
	{
		fileName: 'cats.jpg',
		mimeTypes: ['image/jpeg'],
		path: 'cypress/fixtures/files/cats.jpg'
	},
	{
		fileName: 'cats.mkv',
		mimeTypes: ['video/x-matroska', 'application/x-matroska'],
		path: 'cypress/fixtures/files/cats.mkv'
	},
	{
		fileName: 'cats.png',
		mimeTypes: ['image/png'],
		path: 'cypress/fixtures/files/cats.png'
	},
	{
		fileName: 'cats.webp',
		mimeTypes: ['image/webp'],
		path: 'cypress/fixtures/files/cats.webp'
	},
	{
		fileName: 'document.pdf',
		mimeTypes: ['application/pdf'],
		path: 'cypress/fixtures/files/document.pdf'
	},
	{
		fileName: 'invalid.txt',
		mimeTypes: ['text/plain'],
		path: 'cypress/fixtures/files/invalid.txt'
	},
	{
		fileName: 'sample.csv',
		mimeTypes: ['text/csv'],
		path: 'cypress/fixtures/files/sample.csv'
	},
	{
		fileName: 'sample.odt',
		mimeTypes: ['application/vnd.oasis.opendocument.text'],
		path: 'cypress/fixtures/files/sample.odt'
	},
	{
		fileName: 'sample.ods',
		mimeTypes: ['application/vnd.oasis.opendocument.spreadsheet'],
		path: 'cypress/fixtures/files/sample.ods'
	}
] as const;

// Split the upload in two batches to verify that a second selection is merged
// with the existing FileList instead of replacing it.
const ACCEPTED_MIME_TYPES = Array.from(new Set(FIXTURE_FILES.flatMap(file => file.mimeTypes)));
const FIRST_UPLOAD_BATCH = FIXTURE_FILES.slice(0, Math.ceil(FIXTURE_FILES.length / 2));
const SECOND_UPLOAD_BATCH = FIXTURE_FILES.slice(FIRST_UPLOAD_BATCH.length);

const FILE_UPLOAD_FIELD = {
	name: 'allAllowedAttachments',
	title: 'All allowed attachments',
	accept: ACCEPTED_MIME_TYPES,
	multiple: true,
	required: true
};

const getChildNodes = (response: NodeByPathResponse): JcrChildNodeResponse[] =>
	response.data?.jcr?.nodeByPath?.children?.nodes ?? [];

const getPropertyValue = (properties: JcrPropertyResponse[] | undefined, propertyName: string): string | undefined =>
	properties?.find(property => property.name === propertyName)?.value ?? undefined;

describe('Form fields - 28 File upload saved fixtures', () => {
	useFormidableSite();

	it('uploads every file fixture and persists them with save to JCR', () => {
		const formName = 'file-save-all-fixtures-form';

		createPublishedLiveFormPage(
			formName,
			'File save all fixtures form',
			[getInputFileNode(FILE_UPLOAD_FIELD)],
			`${formName}-page`,
			'File save all fixtures form',
			{actions: [SAVE_TO_JCR_ACTION]}
		).then(({livePath}) => {
			const form = visitLiveForm(livePath);
			const fileInput = form.getFileInput(FILE_UPLOAD_FIELD.name);

			fileInput
				.shouldBeVisible()
				.shouldBeRequired()
				.shouldBeMultiple()
				// The rendered input should expose the same MIME restrictions on the front end.
				.shouldAccept(FILE_UPLOAD_FIELD.accept);

			fileInput
				.attachFile(FIRST_UPLOAD_BATCH.map(file => file.path))
				.shouldHaveSelectedFileCount(FIRST_UPLOAD_BATCH.length);

			FIRST_UPLOAD_BATCH.forEach(file => {
				fileInput.shouldHaveSelectedFile(file.fileName);
			});

			fileInput
				.attachFile(SECOND_UPLOAD_BATCH.map(file => file.path))
				// After the second selection, the first batch must still be present.
				.shouldHaveSelectedFileCount(FIXTURE_FILES.length);

			FIXTURE_FILES.forEach(file => {
				fileInput.shouldHaveSelectedFile(file.fileName);
			});

			form.submit();
			form.waitForSubmit().shouldHaveSubmissionMessage('Form submitted successfully!');

			getLatestLiveFormSubmission(formName)
				.then(({name, path, timeZone}) => {
					expect(name).to.match(/^submission-/);
					// The submission records the browser's time zone, the one the results show next to date-times.
					cy.window().then(win => {
						expect(timeZone, 'time zone of the submission').to.equal(win.Intl.DateTimeFormat().resolvedOptions().timeZone);
					});

					// Uploaded files are persisted under /files/<fieldName> as jnt:file nodes.
					return getNodeByPath(
						`${path}/files/${FILE_UPLOAD_FIELD.name}`,
						[],
						'',
						['jnt:file'],
						'LIVE'
					);
				})
				.then((response: NodeByPathResponse) => {
					const savedFiles = getChildNodes(response).map(fileNode => ({
						fileName: fileNode.name,
						mimeType: getPropertyValue(fileNode.children?.nodes?.[0]?.properties, 'jcr:mimeType')
					}));

					// Every selected fixture must be present in JCR after submission.
					expect(savedFiles.map(file => file.fileName).sort()).to.deep.equal(
						FIXTURE_FILES.map(file => file.fileName).sort()
					);

					savedFiles.forEach(savedFile => {
						const expectedMimeTypes = FIXTURE_FILES.find(file => file.fileName === savedFile.fileName)?.mimeTypes;
						if (!expectedMimeTypes) {
							throw new Error(`No expected MIME types configured for ${savedFile.fileName}`);
						}

						// Persisted MIME types should match the server-side detection we expect for each fixture.
						expect(savedFile.mimeType, `saved MIME type for ${savedFile.fileName}`).to.be.oneOf(expectedMimeTypes);
					});
				});
		});
	});
});
