import {jahiaComponent} from "@jahia/javascript-modules-library";
import {type TextValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {HelpText, helpTextId} from "@jahia/formidable-library";

interface InputEmailProps extends TextValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	placeholder?: string;
	defaultValue?: string;
	list?: string[];
	pattern?: string;
	minLength?: number;
	maxLength?: number;
	required?: boolean;
	autocomplete?: string;
	multiple?: boolean;
}

// Default values declared outside component to prevent re-render issues
const DEFAULT_LIST: string[] = [];

jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:inputEmail",
		name: "default"
	},
	(
		{
			"jcr:title": label,
			helpText,
			placeholder,
			defaultValue,
			list = DEFAULT_LIST,
			pattern,
			minLength,
			maxLength,
			required,
			autocomplete,
			multiple,
			...validationMsgs
		}: InputEmailProps,
		{currentNode}
	) => {


		// Generate unique datalist ID for autocomplete functionality
		const generateDatalistId = () => `datalist-${currentNode.getIdentifier()}`;
		const datalistId = list.length > 0 ? generateDatalistId() : undefined;

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
					type="email"
					id={inputId}
					name={inputName}
					aria-describedby={helpId}
					className="fmdb-form-control"
					placeholder={placeholder}
					defaultValue={defaultValue}
					list={datalistId}
					pattern={pattern}
					minLength={minLength}
					maxLength={maxLength}
					required={required}
					multiple={multiple}
					autoComplete={autocomplete}
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
