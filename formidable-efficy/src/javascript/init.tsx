import {registry} from '@jahia/ui-extender';
import i18next from 'i18next';
import {EfficyOpportunityMappingCmp} from './OpportunityMapping/EfficyOpportunityMappingCmp';

export default function () {
    registry.add('callback', 'FormidableEfficyEditor', {
        targets: ['jahiaApp-init:20'],
        callback: () => {
            i18next.loadNamespaces('formidable-efficy');

            registry.add('selectorType', 'EfficyOpportunityMapping', {cmp: EfficyOpportunityMappingCmp, supportMultiple: true});

            console.debug('%c Formidable Efficy Extensions is activated', 'color: #00a1e0');
        }
    });
}
