import {jahiaComponent} from "@jahia/javascript-modules-library";
import {type RangeValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {HelpText, helpTextId} from "@jahia/formidable-library";

interface InputNumberProps extends RangeValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	placeholder?: string;
	defaultValue?: number;
	minValue?: number;
	maxValue?: number;
	step?: number;
	list?: string[];
	required?: boolean;
	// Advanced settings from mixin
	title?: string;
	readonly?: boolean;
	autofocus?: boolean;
	disabled?: boolean;
	form?: string;
}

// Default values declared outside component to prevent re-render issues
const DEFAULT_LIST: string[] = [];

jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:inputNumber",
		name: "default"
	},
	(
		{
			"jcr:title": label,
			helpText,
			placeholder,
			defaultValue,
			minValue,
			maxValue,
			step,
			list = DEFAULT_LIST,
			required,
			title,
			readonly,
			autofocus,
			disabled,
			form,
			...validationMsgs
		}: InputNumberProps,
		{currentNode}
	) => {

		// Generate unique datalist ID for autocomplete functionality
		const datalistId = list.length > 0 ? `datalist-${currentNode.getIdentifier()}` : undefined;

		// Generate unique id and name for the input
		const inputId = `input-${currentNode.getIdentifier()}`;
		const inputName = currentNode.getName();

		const helpId = helpText ? helpTextId(currentNode.getIdentifier()) : undefined;

		return (
			<div className="fmdb-form-group">
				{label && (
					<label htmlFor={inputId} className="fmdb-form-label">
						{label}
						{required && <span className="fmdb-required-indicator" aria-hidden="true">*</span>}
					</label>
				)}

				<HelpText id={helpId} text={helpText}/>

				<input
					type="number"
					id={inputId}
					name={inputName}
					aria-describedby={helpId}
					className="fmdb-form-control"
					placeholder={placeholder}
					defaultValue={defaultValue}
					min={minValue}
					max={maxValue}
					step={step}
					list={datalistId}
					required={required}
					title={title}
					readOnly={readonly}
					autoFocus={autofocus}
					disabled={disabled}
					form={form}
					{...validationDataAttributes(validationMsgs)}
				/>

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
