import {JahiaNode} from './types';

export type ChoiceFieldType = 'fmdb:select' | 'fmdb:radio' | 'fmdb:checkbox';

interface ChoiceFieldData {
	primaryNodeType: ChoiceFieldType;
	name: string;
	title: string;
	required?: boolean;
	multiple?: boolean;
}

export interface SourcedChoiceFieldData extends ChoiceFieldData {
	sourceKey: string;
}

export interface CategoryChoiceFieldData extends ChoiceFieldData {
	rootCategoryUuid: string;
}

export interface ContentChoiceFieldData extends ChoiceFieldData {
	rootNodeUuid: string;
	nodeType: string;
}

/** A choice field carrying the mixin of one options mode and the properties that mode reads. */
function getChoiceFieldNode(data: ChoiceFieldData, mixin: string, modeProperties: JahiaNode['properties']): JahiaNode {
	const properties: JahiaNode['properties'] = [{name: 'jcr:title', value: data.title, language: 'en'}, ...modeProperties];

	if (data.required !== undefined) properties.push({name: 'required', value: String(data.required), type: 'BOOLEAN'});
	if (data.multiple !== undefined) properties.push({name: 'multiple', value: String(data.multiple), type: 'BOOLEAN'});

	return {name: data.name, primaryNodeType: data.primaryNodeType, mixins: [mixin], properties};
}

/**
 * A choice field whose options come from an admin-declared options source
 * (fmdbmix:sourcedOptions), resolved at render and submit time.
 */
export function getSourcedChoiceFieldNode(data: SourcedChoiceFieldData): JahiaNode {
	return getChoiceFieldNode(data, 'fmdbmix:sourcedOptions', [
		{name: 'optionsMode', value: 'sourced'},
		{name: 'optionsSourceKey', value: data.sourceKey}
	]);
}

/**
 * A choice field whose options are the direct children of a picked category
 * (fmdbmix:categoryOptions): value = category name, label = localized title.
 */
export function getCategoryChoiceFieldNode(data: CategoryChoiceFieldData): JahiaNode {
	return getChoiceFieldNode(data, 'fmdbmix:categoryOptions', [
		{name: 'optionsMode', value: 'category'},
		{name: 'optionsRootCategory', value: data.rootCategoryUuid, type: 'WEAKREFERENCE'}
	]);
}

/**
 * A choice field whose options are the descendants of a picked root node filtered
 * by a content type (fmdbmix:contentOptions): value = path relative to the root,
 * label = localized displayable name.
 */
export function getContentChoiceFieldNode(data: ContentChoiceFieldData): JahiaNode {
	return getChoiceFieldNode(data, 'fmdbmix:contentOptions', [
		{name: 'optionsMode', value: 'content'},
		{name: 'optionsRootNode', value: data.rootNodeUuid, type: 'WEAKREFERENCE'},
		{name: 'optionsNodeType', value: data.nodeType}
	]);
}

/**
 * An editorial text content carrying a bilingual title, the target of choice in
 * content-options tests: its localized jcr:title is what the resolution uses as
 * option label.
 */
export function getTitledTextNode(name: string, titleEn: string, titleFr: string): JahiaNode {
	return {
		name,
		primaryNodeType: 'jnt:text',
		mixins: ['mix:title'],
		properties: [
			{name: 'jcr:title', value: titleEn, language: 'en'},
			{name: 'jcr:title', value: titleFr, language: 'fr'},
			{name: 'text', value: titleEn, language: 'en'},
			{name: 'text', value: titleFr, language: 'fr'}
		]
	};
}

export function getCategoryNode(name: string, titleEn: string, titleFr: string): JahiaNode {
	return {
		name,
		primaryNodeType: 'jnt:category',
		properties: [
			{name: 'jcr:title', value: titleEn, language: 'en'},
			{name: 'jcr:title', value: titleFr, language: 'fr'}
		]
	};
}

/**
 * Declares the options sources in the module OSGi configuration. The
 * configuration is instance-global: specs that change it mid-test must
 * restore their own declaration afterwards.
 */
export function setOptionsSourcesConfig(lines: string[]): Cypress.Chainable {
	return cy.runProvisioningScript({
		script: {
			fileContent: JSON.stringify([{
				editConfiguration: 'org.jahia.modules.formidable',
				properties: {optionsSources: lines.join('\n')}
			}]),
			type: 'application/json'
		}
	});
}

/** The documented default of the optionsQueryMaxResults configuration. */
export const OPTIONS_QUERY_MAX_RESULTS_DEFAULT = 100;

/**
 * Caps how many options a content-mode choice field may resolve. The
 * configuration is instance-global: specs that lower it must restore
 * OPTIONS_QUERY_MAX_RESULTS_DEFAULT afterwards.
 */
export function setOptionsQueryMaxResults(max: number): Cypress.Chainable {
	return cy.runProvisioningScript({
		script: {
			fileContent: JSON.stringify([{
				editConfiguration: 'org.jahia.modules.formidable',
				properties: {optionsQueryMaxResults: String(max)}
			}]),
			type: 'application/json'
		}
	});
}
