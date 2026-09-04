import {gql} from '@apollo/client';

/**
 * The edited node and its enclosing form. The action node lives under
 * <form>/actions/<action>; in CREATE mode the edited path is the actions list itself,
 * whose ancestor is still the form — so the ancestors filter resolves the form in both
 * modes without requiring the current node to appear anywhere else.
 */
export const CURRENT_NODE_BY_PATH = gql`
    query HubspotContactMappingCurrentNodeByPath($path: String!, $workspace: Workspace!) {
        jcr(workspace: $workspace) {
            nodeByPath(path: $path) {
                uuid
                workspace
                name
                path
                primaryNodeType { name }
                ancestors(fieldFilter: {filters: [{fieldName: "primaryNodeType.name", value: "fmdb:form"}]}) {
                    uuid
                    workspace
                    name
                    path
                    primaryNodeType { name }
                }
            }
        }
    }
`;

/**
 * Every form element of the form with the semantic mixin flags that decide its
 * eligibility (file fields are not supported in v1) and its value kind (type hints).
 */
export const FORM_FIELDS_BY_PATH = gql`
    query HubspotContactMappingFormFieldsByPath($path: String!, $workspace: Workspace!, $language: String!) {
        jcr(workspace: $workspace) {
            nodeByPath(path: $path) {
                uuid
                workspace
                name
                path
                descendants(typesFilter: {types: ["fmdbmix:formElement"], multi: ANY}) {
                    nodes {
                        uuid
                        workspace
                        name
                        path
                        displayName(language: $language)
                        primaryNodeType { name }
                        isNonSubmittable: isNodeType(type: {types: ["fmdbmix:nonSubmittable"]})
                        isChoiceField: isNodeType(type: {types: ["fmdbmix:choiceField"]})
                        isDateField: isNodeType(type: {types: ["fmdbmix:dateField"]})
                        isNumberField: isNodeType(type: {types: ["fmdbmix:numberField"]})
                        isBooleanField: isNodeType(type: {types: ["fmdbmix:booleanField"]})
                        isTextField: isNodeType(type: {types: ["fmdbmix:textField"]})
                        isEmailField: isNodeType(type: {types: ["fmdbmix:emailField"]})
                        isFileField: isNodeType(type: {types: ["fmdbmix:fileField"]})
                        properties(names: ["fieldKey"]) {
                            name
                            value
                        }
                    }
                }
            }
        }
    }
`;

/**
 * Createable Contact properties of a Hubspot connection, served by the module's own
 * GraphQL extension (Java side). contextPath is the edited node path: the action node
 * in edit mode, the actions list in create mode — the server uses it to authorize the
 * call against the site the form belongs to. refresh bypasses the server-side describe cache
 * (the "Refresh Hubspot fields" button).
 */
export const CONTACT_PROPERTIES = gql`
    query HubspotContactMappingContactFields($connectionId: String!, $contextPath: String!, $refresh: Boolean) {
        formidableHubspot {
            objectFields(connectionId: $connectionId, objectType: "contacts", contextPath: $contextPath, refresh: $refresh) {
                name
                label
                type
                fieldType
                required
                createable
                picklistValues {
                    value
                    label
                }
            }
        }
    }
`;
