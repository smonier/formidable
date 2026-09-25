import {Island, jahiaComponent} from "@jahia/javascript-modules-library";
import {
	applyMask,
	HelpText,
	helpTextId,
	maskToPattern,
	type TextValidationMessageProps,
	validationDataAttributes
} from "@jahia/formidable-library";
import MaskedTextInput from "./Text.client";

interface InputTextProps extends TextValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	placeholder?: string;
	defaultValue?: string;
	list?: string[];
	minLength?: number;
	maxLength?: number;
	// Advanced settings from mixin
	mask?: string;
	required?: boolean;
	autocomplete?: string;
	readonly?: boolean;
	autofocus?: boolean;
	disabled?: boolean;
	form?: string;
	dirname?: boolean;
	spellcheck?: boolean;
	pattern?: string;
	size?: number;
	title?: string;
}

// Default values declared outside component to prevent re-render issues
const DEFAULT_LIST: string[] = [];

jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:inputText",
		name: "default"
	},
	(
		{
			"jcr:title": label,
			helpText,
			placeholder,
			defaultValue,
			list = DEFAULT_LIST,
			mask,
			minLength,
			maxLength,
			required,
			autocomplete,
			readonly,
			autofocus,
			disabled,
			form,
			dirname,
			spellcheck = true,
			pattern: customPattern,
			size,
			title,
			...validationMsgs
		}: InputTextProps,
		{currentNode}
	) => {

		// Generate unique datalist ID for autocomplete functionality
		const generateDatalistId = () => `datalist-${currentNode.getIdentifier()}`;
		const datalistId = list.length > 0 ? generateDatalistId() : undefined;

		// Generate unique id and name for the input
		const inputId = `input-${currentNode.getIdentifier()}`;
		const inputName = currentNode.getName();

		// Use custom pattern if provided, otherwise use mask-derived pattern
		const finalPattern = customPattern || maskToPattern(mask);

		const helpId = helpText ? helpTextId(currentNode.getIdentifier()) : undefined;

		// Shared between the static input and the masked island so both render identical markup
		const inputAttributes = {
			type: "text",
			id: inputId,
			name: inputName,
			"aria-describedby": helpId,
			className: "fmdb-form-control",
			placeholder,
			list: datalistId,
			minLength,
			maxLength,
			pattern: finalPattern,
			required,
			"data-mask": mask,
			autoComplete: autocomplete,
			readOnly: readonly,
			autoFocus: autofocus,
			disabled,
			form,
			// When enabled, submits the field text direction as `{name}.dir` (same as Textarea)
			dirName: dirname ? `${inputName}.dir` : undefined,
			spellCheck: spellcheck,
			size,
			title,
			...validationDataAttributes(validationMsgs)
		};

		return (
			<div className="fmdb-form-group">
				{label && (
					<label htmlFor={inputId} className="fmdb-form-label">
						{label}
						{required && <span className="fmdb-required-indicator" aria-hidden="true">*</span>}
					</label>
				)}

				<HelpText id={helpId} text={helpText}/>

				{mask ? (
					// Hydrate only when a mask is configured; the default value is pre-formatted server-side
					<Island
						component={MaskedTextInput}
						props={{
							mask,
							defaultValue: defaultValue ? applyMask(defaultValue, mask) : undefined,
							inputAttributes
						}}
					/>
				) : (
					<input {...inputAttributes} defaultValue={defaultValue}/>
				)}

				{/* Render datalist for autocomplete if options are provided */}
				{list.length > 0 && (
					<datalist id={datalistId}>
						{list.map((option) => (
							<option key={option} value={option}/>
						))}
					</datalist>
				)}
			</div>
		);
	}
);
