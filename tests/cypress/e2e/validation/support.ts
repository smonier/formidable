import {createSite, deleteSite, enableModule} from '@jahia/cypress';
import {FORMIDABLE_MODULE_IDS} from '../../support/constants';
import {FORMIDABLE_TEST_SITE} from '../../support/fixtures';

// One "today" for the whole suite: the helper lives in support/constants, re-exported here for
// the validation specs that import it from their own support module.
export {localDay} from '../../support/constants';

export const useFormidableSite = () => {
	before(() => {
		deleteSite(FORMIDABLE_TEST_SITE.key);
		createSite(FORMIDABLE_TEST_SITE.key, FORMIDABLE_TEST_SITE.config);
		FORMIDABLE_MODULE_IDS.forEach(moduleId => enableModule(moduleId, FORMIDABLE_TEST_SITE.key));
	});

	beforeEach(() => {
		cy.login();
	});

	afterEach(() => {
		cy.logout();
	});
};
