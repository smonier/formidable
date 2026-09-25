import {JahiaNode, NodeProperty, RadioData} from './types';

export const RADIO_SINGLE: RadioData = {
	name: 'contactPreference',
	title: 'Preferred contact method',
	required: true,
	choices: [{value: 'email', label: 'Email', selected: true}]
};

export const RADIO_GROUP: RadioData = {
	name: 'deliveryMethod',
	title: 'Delivery method',
	required: true,
	choices: [
		{value: 'standard', label: 'Standard', selected: false},
		{value: 'express', label: 'Express', selected: true},
		{value: 'pickup', label: 'Pickup', selected: false}
	]
};

export function getRadioNode(data: RadioData = RADIO_SINGLE): JahiaNode {
	const properties: NodeProperty[] = [];

	if (data.title) properties.push({name: 'jcr:title', value: data.title, language: 'en'});
	if (data.helpText) properties.push({name: 'helpText', value: data.helpText, language: 'en'});
	if (data.required !== undefined) properties.push({name: 'required', value: String(data.required), type: 'BOOLEAN'});
	properties.push({name: 'optionsMode', value: 'manual'});
	properties.push({
		name: 'options',
		values: data.choices.map(choice => JSON.stringify({
			value: choice.value,
			label: choice.label,
			selected: choice.selected ?? false
		})),
		language: 'en'
	});

	return {
		name: data.name || 'radio',
		primaryNodeType: 'fmdb:radio',
		mixins: ['fmdbmix:manualOptions'],
		properties
	};
}
