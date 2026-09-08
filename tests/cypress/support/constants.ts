import {FORMIDABLE_TEST_SITE} from './fixtures'

/**
 * Formidable modules required by the runtime and editor features under test.
 */
export const FORMIDABLE_MODULE_IDS = [
	'formidable-elements',
	'formidable-engine',
	'formidable-extended-inputs',
	'formidable-test-module-samples-java',
	'formidable-salesforce',
	'formidable-hubspot',
	'formidable-efficy'
] as const

/**
 * Base content path for the test site
 * Used as the parent path for creating form content nodes
 */
export const CONTENT_PATH = `/sites/${FORMIDABLE_TEST_SITE.key}/contents`
export const SITE_HOME_PATH = `/sites/${FORMIDABLE_TEST_SITE.key}/home`

/**
 * JContent selectors for test automation
 */
export const JCONTENT_SELECTORS = {
	previewIframe: 'iframe[data-sel-role="edit-preview-frame"]'
}

/**
 * The form submission servlet and its logic-state declaration header, as the
 * production runtime uses them. Shared by every spec crafting or intercepting
 * direct submissions, so a rename is one edit.
 */
export const DIRECT_SUBMIT_PATH = '/modules/formidable-engine/form-submit'
export const LOGIC_STATE_HEADER = 'X-Formidable-Logic-State'

/**
 * The local calendar day shifted by offsetDays, as yyyy-MM-dd — the same clock
 * the browser evaluator reads (and the server's, in CI). Never toISOString,
 * which reads the UTC day and shifts around midnight for non-UTC runners.
 * Shared so every spec reasoning about "today" agrees on the same day.
 */
export const localDay = (offsetDays: number): string => {
	const date = new Date()
	date.setDate(date.getDate() + offsetDays)
	const month = String(date.getMonth() + 1).padStart(2, '0')
	const day = String(date.getDate()).padStart(2, '0')
	return `${date.getFullYear()}-${month}-${day}`
}
