import {registry} from '@jahia/ui-extender';
import i18next from 'i18next';
import {HubspotContactMappingCmp} from './ContactMapping/HubspotContactMappingCmp';

export default function () {
    registry.add('callback', 'FormidableHubspotEditor', {
        targets: ['jahiaApp-init:20'],
        callback: () => {
            i18next.loadNamespaces('formidable-hubspot');

            registry.add('selectorType', 'HubspotContactMapping', {cmp: HubspotContactMappingCmp, supportMultiple: true});

            console.debug('%c Formidable HubSpot Extensions is activated', 'color: #00a1e0');
        }
    });
}
