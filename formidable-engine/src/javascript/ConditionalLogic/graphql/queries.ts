import {gql} from '@apollo/client';
import {JCR_NODE_IDENTITY} from '../../graphql';

export const CURRENT_NODE_BY_PATH = gql`
    ${JCR_NODE_IDENTITY}
    query ConditionalLogicCurrentNodeByPath($path: String!, $workspace: Workspace!, $language: String!) {
        jcr(workspace: $workspace) {
            nodeByPath(path: $path) {
                site { defaultLanguage }
                ...JcrNodeIdentity
                displayName(language: $language)
                primaryNodeType { name }
                ancestors(fieldFilter: {filters: [{fieldName: "primaryNodeType.name", value: "fmdb:form"}]}) {
                    ...JcrNodeIdentity
                    primaryNodeType { name }
                }
                descendant(relPath: "logicsSrc") {
                    children {
                        nodes {
                            name
                            property(name: "logicNodeSource") {
                                refNode {
                                    name
                                    uuid
                                }
                            }
                        }
                    }
                }
            }
        }
    }
`;

export const FORM_TREE_BY_PATH = gql`
    ${JCR_NODE_IDENTITY}
    query ConditionalLogicFormTreeByPath($path: String!, $workspace: Workspace!, $language: String!, $defaultLanguage: String!) {
        jcr(workspace: $workspace) {
            nodeByPath(path: $path) {
                ...JcrNodeIdentity
                descendants(
                    typesFilter: {types: ["fmdbmix:formElement", "fmdbmix:formStep"], multi: ANY}
                ) {
                    nodes {
                        ...JcrNodeIdentity
                        displayName(language: $language)
                        primaryNodeType { name }
                        isChoiceField: isNodeType(type: {types: ["fmdbmix:choiceField"]})
                        isDateField: isNodeType(type: {types: ["fmdbmix:dateField"]})
                        isNumberField: isNodeType(type: {types: ["fmdbmix:numberField"]})
                        isBooleanField: isNodeType(type: {types: ["fmdbmix:booleanField"]})
                        isTextField: isNodeType(type: {types: ["fmdbmix:textField"]})
                        properties(names: ["options", "fieldKey"], language: $language) {
                            name
                            value
                            values
                        }
                        defaultProperties: properties(names: ["options"], language: $defaultLanguage) {
                            name
                            values
                        }
                    }
                }
            }
        }
    }
`;

