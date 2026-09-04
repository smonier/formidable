import {registry} from '@jahia/ui-extender';
import i18next from 'i18next';
import {SalesforceLeadMappingCmp} from './LeadMapping/SalesforceLeadMappingCmp';

export default function () {
    registry.add('callback', 'FormidableSalesforceEditor', {
        targets: ['jahiaApp-init:20'],
        callback: () => {
            i18next.loadNamespaces('formidable-salesforce');

            registry.add('selectorType', 'SalesforceLeadMapping', {cmp: SalesforceLeadMappingCmp, supportMultiple: true});

            console.debug('%c Formidable Salesforce Extensions is activated', 'color: #00a1e0');
        }
    });
}
