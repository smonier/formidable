import {JahiaNode, NodeProperty, SelectData} from './types';

export const SELECT_SINGLE: SelectData = {
	name: 'department',
	title: 'Department',
	required: true,
	options: [
		// Empty first option (no value) so the field starts empty instead of
		// defaulting to the first valid value. Treated as empty by required validation.
		{value: '', label: 'Please select', selected: false},
		{value: 'engineering', label: 'Engineering', selected: false},
		{value: 'sales', label: 'Sales', selected: false},
		{value: 'support', label: 'Support', selected: false}
	]
};

export const SELECT_MULTIPLE: SelectData = {
	name: 'regions',
	title: 'Regions',
	multiple: true,
	size: 4,
	options: [
		{value: 'emea', label: 'EMEA', selected: true},
		{value: 'na', label: 'North America', selected: false},
		{value: 'latam', label: 'LATAM', selected: true},
		{value: 'apac', label: 'APAC', selected: false}
	]
};

export const SELECT_EMPTY_LABEL: SelectData = {
	name: 'contractType',
	title: 'Contract type',
	required: true,
	// Contributor-configured empty option: same starting-empty behavior as
	// SELECT_SINGLE's blank entry, without polluting the option list.
	emptyLabel: 'Choose a contract type',
	options: [
		// Legacy blank entry (the historical way of starting empty): superseded
		// by the configured empty option, never rendered twice.
		{value: '', label: 'Pick one', selected: false},
		{value: 'permanent', label: 'Permanent', selected: false},
		{value: 'fixed-term', label: 'Fixed term', selected: false}
	]
};

export const SELECT_MULTIPLE_EMPTY_LABEL: SelectData = {
	name: 'workingDays',
	title: 'Working days',
	multiple: true,
	// Meaningless on a multiple select: the renderer must ignore it.
	emptyLabel: 'Choose your days',
	options: [
		{value: 'monday', label: 'Monday', selected: false},
		{value: 'friday', label: 'Friday', selected: false}
	]
};

export const SELECT_DISABLED: SelectData = {
	name: 'archivedStatus',
	title: 'Archived status',
	disabled: true,
	options: [
		{value: 'closed', label: 'Closed', selected: true},
		{value: 'open', label: 'Open', selected: false}
	]
};

export function getSelectNode(data: SelectData = SELECT_SINGLE): JahiaNode {
	const properties: NodeProperty[] = [];

	if (data.title) properties.push({name: 'jcr:title', value: data.title, language: 'en'});
	if (data.helpText) properties.push({name: 'helpText', value: data.helpText, language: 'en'});
	if (data.emptyLabel) properties.push({name: 'optionsEmptyLabel', value: data.emptyLabel, language: 'en'});
	if (data.required !== undefined) properties.push({name: 'required', value: String(data.required), type: 'BOOLEAN'});
	if (data.multiple !== undefined) properties.push({name: 'multiple', value: String(data.multiple), type: 'BOOLEAN'});
	if (data.size !== undefined) properties.push({name: 'size', value: String(data.size), type: 'LONG'});
	if (data.disabled !== undefined) properties.push({name: 'disabled', value: String(data.disabled), type: 'BOOLEAN'});
	if (data.autofocus !== undefined) properties.push({name: 'autofocus', value: String(data.autofocus), type: 'BOOLEAN'});
	properties.push({name: 'optionsMode', value: 'manual'});
	properties.push({
		name: 'options',
		values: data.options.map(option => JSON.stringify({
			value: option.value,
			label: option.label,
			selected: option.selected ?? false
		})),
		language: 'en'
	});

	return {
		name: data.name || 'select',
		primaryNodeType: 'fmdb:select',
		mixins: ['fmdbmix:manualOptions'],
		properties
	};
}
