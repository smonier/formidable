import {Island, jahiaComponent} from "@jahia/javascript-modules-library";
import {type BaseValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {HelpText, helpTextId} from "@jahia/formidable-library";
import RangeInput from "./Range.client";

interface InputRangeProps extends BaseValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	defaultValue?: number;
	minValue?: number;
	maxValue?: number;
	step?: number;
	minLabel?: string;
	maxLabel?: string;
	list?: string[];
	required?: boolean;
	// Advanced settings from mixin
	title?: string;
	autofocus?: boolean;
	disabled?: boolean;
	form?: string;
}

// Default values declared outside component to prevent re-render issues
const DEFAULT_LIST: string[] = [];

jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:inputRange",
		name: "default"
	},
	(
		{
			"jcr:title": label,
			helpText,
			defaultValue,
			minValue,
			maxValue,
			step,
			minLabel,
			maxLabel,
			list = DEFAULT_LIST,
			required,
			title,
			autofocus,
			disabled,
			form,
			...validationMsgs
		}: InputRangeProps,
		{currentNode}
	) => {

		// Generate unique datalist ID for the slider tick marks
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

				<Island
					component={RangeInput}
					props={{
						name: inputName,
						inputId,
						helpId,
						datalistId,
						minValue: minValue ?? 0,
						maxValue: maxValue ?? 100,
						step,
						defaultValue,
						minLabel,
						maxLabel,
						required,
						title,
						autofocus,
						disabled,
						form,
						validationAttributes: validationDataAttributes(validationMsgs)
					}}
				/>

				{/* Render datalist for slider tick marks if values are provided */}
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
