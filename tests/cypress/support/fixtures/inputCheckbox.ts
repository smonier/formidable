import {CheckboxData, JahiaNode, NodeProperty} from './types';

export const CHECKBOX_SINGLE: CheckboxData = {
	name: 'simpleCheckbox',
	title: 'Accept Terms',
	choices: [{value: 'accepted', label: 'Accept terms', selected: false}]
};

export const CHECKBOX_SINGLE_COMPLETE: CheckboxData = {
	name: 'completeCheckbox',
	title: 'I agree to terms',
	required: true,
	choices: [{value: 'agreed', label: 'I agree to terms', selected: true}]
};

export const CHECKBOX_GROUP: CheckboxData = {
	name: 'simpleCheckboxGroup',
	title: 'Select your interests',
	choices: [
		{value: 'sports', label: 'Sports', selected: false},
		{value: 'music', label: 'Music', selected: false}
	]
};

export const CHECKBOX_GROUP_COMPLETE: CheckboxData = {
	name: 'completeCheckboxGroup',
	title: 'Required interests',
	required: true,
	choices: [
		{value: 'reading', label: 'Reading', selected: false},
		{value: 'sports', label: 'Sports', selected: true},
		{value: 'music', label: 'Music', selected: false}
	]
};

export function getCheckboxNode(data: CheckboxData = CHECKBOX_SINGLE): JahiaNode {
	const properties: NodeProperty[] = [];
	if (data.title) properties.push({name: 'jcr:title', value: data.title, language: 'en'});
	if (data.helpText) properties.push({name: 'helpText', value: data.helpText, language: 'en'});
	if (data.required !== undefined) properties.push({name: 'required', value: String(data.required), type: 'BOOLEAN'});
	properties.push({name: 'optionsMode', value: 'manual'});
	properties.push({
		name: 'options',
		values: data.choices.map(c => JSON.stringify({value: c.value, label: c.label, selected: c.selected ?? false})),
		language: 'en'
	});
	return {name: data.name || 'checkbox', primaryNodeType: 'fmdb:checkbox', mixins: ['fmdbmix:manualOptions'], properties};
}
