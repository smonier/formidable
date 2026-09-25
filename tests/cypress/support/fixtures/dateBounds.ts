import {InputWithBoundModes, NodeProperty} from './types';

/**
 * Writes the bound-mode properties and mixins shared by the date and datetime
 * fixture builders (fmdbmix:dateBounds / fmdbmix:datetimeBounds contracts). A
 * fixed value implies the 'date' mode and an offset the 'relative' mode — each
 * mode's dynamic-fieldset mixin carries its properties, and a property is only
 * written when its mode ends up selected.
 */
export function pushBoundModeProperties(
	properties: NodeProperty[],
	mixins: string[],
	data: InputWithBoundModes & {min?: string; max?: string},
	flavor: 'Date' | 'Datetime'
): void {
	const minMode = data.minBoundMode ?? (data.min ? 'date' : undefined) ?? (data.minRelative ? 'relative' : undefined);
	const maxMode = data.maxBoundMode ?? (data.max ? 'date' : undefined) ?? (data.maxRelative ? 'relative' : undefined);
	// A relative mode without its offset is a node shape no editor can produce
	// (the fieldset properties are mandatory): fail the fixture loudly instead of
	// silently building a today-equivalent bound the spec believes is relative.
	if ((minMode === 'relative' && !data.minRelative) || (maxMode === 'relative' && !data.maxRelative)) {
		throw new Error('A relative bound mode requires its minRelative/maxRelative offset');
	}
	if (minMode) properties.push({name: 'minBoundMode', value: minMode});
	if (maxMode) properties.push({name: 'maxBoundMode', value: maxMode});
	if (minMode === 'date') mixins.push(`fmdbmix:fixedMin${flavor}`);
	if (maxMode === 'date') mixins.push(`fmdbmix:fixedMax${flavor}`);
	if (minMode === 'date' && data.min) properties.push({name: 'min', value: data.min, type: 'DATE'});
	if (maxMode === 'date' && data.max) properties.push({name: 'max', value: data.max, type: 'DATE'});
	if (minMode === 'relative' && data.minRelative) {
		mixins.push(`fmdbmix:relativeMin${flavor}`);
		properties.push({name: 'minRelativeAmount', value: String(data.minRelative.amount), type: 'LONG'});
		properties.push({name: 'minRelativeUnit', value: data.minRelative.unit});
	}

	if (maxMode === 'relative' && data.maxRelative) {
		mixins.push(`fmdbmix:relativeMax${flavor}`);
		properties.push({name: 'maxRelativeAmount', value: String(data.maxRelative.amount), type: 'LONG'});
		properties.push({name: 'maxRelativeUnit', value: data.maxRelative.unit});
	}
}
