import {gql} from '@apollo/client';
import {JCR_NODE_IDENTITY} from '../../graphql';

export const GET_FORM_RESULTS_LIST = gql`
    ${JCR_NODE_IDENTITY}
    query GetFormResultsList($resultsPath: String!, $workspace: Workspace = LIVE, $language: String!) {
        jcr(workspace: $workspace) {
            nodeByPath(path: $resultsPath) {
                ...JcrNodeIdentity
                children(typesFilter: {types: ["fmdb:formResults"]}) {
                    nodes {
                        ...JcrNodeIdentity
                        displayName(language: $language)
                        submissionsContainer: children(names: ["submissions"]) {
                            nodes {
                                canRemoveNode: hasPermission(permissionName: "jcr:removeNode")
                                canRemoveChildNodes: hasPermission(permissionName: "jcr:removeChildNodes")
                            }
                        }
                        parentForm: property(name: "parentForm") {
                            refNode {
                                ...JcrNodeIdentity
                                displayName(language: $language)
                            }
                        }
                    }
                }
            }
        }
    }
`;

export const GET_SUBMISSIONS = gql`
    ${JCR_NODE_IDENTITY}
    query GetSubmissions(
        $submissionsQuery: String!
        $limit: Int!
        $offset: Int!
        $workspace: Workspace = LIVE
    ) {
        jcr(workspace: $workspace) {
            nodesByQuery(
                query: $submissionsQuery
                queryLanguage: SQL2
                limit: $limit
                offset: $offset
            ) {
                pageInfo {
                    totalCount
                    hasNextPage
                }
                nodes {
                    ...JcrNodeIdentity
                    created: property(name: "jcr:created") {
                        value
                    }
                    origin: property(name: "origin") {
                        value
                    }
                    locale: property(name: "locale") {
                        value
                    }
                    referer: property(name: "referer") {
                        value
                    }
                    timeZone: property(name: "timeZone") {
                        value
                    }
                    data: children(names: ["data"]) {
                        nodes {
                            ...JcrNodeIdentity
                            properties {
                                name
                                value
                                values
                            }
                        }
                    }
                    files: children(names: ["files"]) {
                        nodes {
                            ...JcrNodeIdentity
                            children {
                                nodes {
                                    ...JcrNodeIdentity
                                    children {
                                        nodes {
                                            ...JcrNodeIdentity
                                            url
                                            thumbnailUrl(name: "thumbnail", checkIfExists: true)
                                            content: children(names: ["jcr:content"]) {
                                                nodes {
                                                    ...JcrNodeIdentity
                                                    mimeType: property(name: "jcr:mimeType") {
                                                        value
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
`;

export const GET_SUBMISSION_COUNT = gql`
    query GetSubmissionCount($countQuery: String!, $workspace: Workspace = LIVE) {
        jcr(workspace: $workspace) {
            nodesByQuery(
                query: $countQuery
                queryLanguage: SQL2
                limit: 1
                offset: 0
            ) {
                pageInfo {
                    totalCount
                }
            }
        }
    }
`;

export const DELETE_SUBMISSIONS = gql`
    mutation DeleteSubmissions($submissionsQuery: String!, $workspace: Workspace = LIVE) {
        jcr(workspace: $workspace) {
            mutateNodesByQuery(
                query: $submissionsQuery
                queryLanguage: SQL2
            ) {
                delete
            }
        }
    }
`;

export const GET_FORM_FIELD_LABELS = gql`
    query GetFormFieldLabels($formUuid: String!, $language: String!, $workspace: Workspace = LIVE) {
        jcr(workspace: $workspace) {
            nodeById(uuid: $formUuid) {
                fields: children(names: ["fields"]) {
                    nodes {
                        descendants(
                            typesFilter: {types: ["fmdbmix:formElement"], multi: ANY}
                        ) {
                            nodes {
                                name
                                displayName(language: $language)
                                isDate: isNodeType(type: {types: ["fmdbmix:dateField"]})
                                isDatetime: isNodeType(type: {types: ["fmdbmix:datetimeLocalField"]})
                            }
                        }
                    }
                }
            }
        }
    }
`;
