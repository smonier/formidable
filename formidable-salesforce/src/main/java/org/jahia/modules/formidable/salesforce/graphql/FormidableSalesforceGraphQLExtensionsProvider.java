package org.jahia.modules.formidable.salesforce.graphql;

import org.jahia.modules.graphql.provider.dxm.DXGraphQLExtensionsProvider;
import org.osgi.service.component.annotations.Component;

/**
 * Registers this bundle with graphql-dxm-provider so its {@code @GraphQLTypeExtension} classes
 * are scanned. Marker component, no implementation needed.
 */
@Component(service = DXGraphQLExtensionsProvider.class, immediate = true)
public class FormidableSalesforceGraphQLExtensionsProvider implements DXGraphQLExtensionsProvider {
}
