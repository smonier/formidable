import {jahiaComponent} from "@jahia/javascript-modules-library";
import {resolveFieldOptions} from "~/utils/optionsSource.server";
import OptionsSourceError from "~/design/OptionsSourceError";
import {type BaseValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {HelpText, helpTextId} from "@jahia/formidable-library";

interface SelectProps extends BaseValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	"options"?: string[];
	optionsEmptyLabel?: string;
	required?: boolean;
	multiple?: boolean;
	size?: number;
	disabled?: boolean;
	autofocus?: boolean;
}


jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:select",
		name: "default"
	},
	(
		{
			"jcr:title": label,
			helpText,
			options = [],
			optionsEmptyLabel,
			required,
			multiple,
			size,
			disabled,
			autofocus,
			...validationMsgs
		}: SelectProps,
		{currentNode, renderContext}
	) => {

		const selectId = `select-${currentNode.getIdentifier()}`;
		const selectName = currentNode.getName();

		const {choices: parsedOptions, sourceError} = resolveFieldOptions(currentNode, options, renderContext);
		if (sourceError) {
			return <OptionsSourceError label={label} required={required}/>;
		}

		const selectedValues = parsedOptions.filter(o => o.selected).map(o => o.value);
		const defaultValue = multiple ? selectedValues : (selectedValues[0] ?? undefined);

		const hasEmptyOption = Boolean(!multiple && optionsEmptyLabel?.trim());
		// The configured empty option supersedes any blank entry typed in the manual
		// options (the historical way of starting empty), so a form carrying both
		// never renders two empty options.
		const renderedOptions = hasEmptyOption ? parsedOptions.filter(o => o.value !== "") : parsedOptions;

		const helpId = helpText ? helpTextId(currentNode.getIdentifier()) : undefined;

		return (
			<div className="fmdb-form-group">
				{label && (
					<label htmlFor={selectId} className="fmdb-form-label">
						{label}
						{required && <span className="fmdb-required-indicator" aria-hidden="true">*</span>}
					</label>
				)}

				<HelpText id={helpId} text={helpText}/>

				<select
					id={selectId}
					name={selectName}
					aria-describedby={helpId}
					className="fmdb-form-control"
					required={required}
					multiple={multiple}
					size={size}
					disabled={disabled}
					autoFocus={autofocus}
					defaultValue={defaultValue}
					{...validationDataAttributes(validationMsgs)}
				>
					{/* Contributor-configured empty option: the field starts empty instead
					    of preselecting the first option, which also makes the native
					    required validation effective. Meaningless on a multiple select. */}
					{hasEmptyOption && (
						<option value="">{optionsEmptyLabel}</option>
					)}
					{renderedOptions.map((option) => (
						<option
							key={option.value || option.label}
							value={option.value}
						>
							{option.label}
						</option>
					))}
				</select>
			</div>
		);
	}
);
