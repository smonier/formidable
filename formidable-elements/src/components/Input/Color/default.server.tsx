import {jahiaComponent} from "@jahia/javascript-modules-library";
import {type BaseValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {HelpText, helpTextId} from "@jahia/formidable-library";

interface InputColorProps extends BaseValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	defaultValue?: string;
	alpha?: boolean;
	colorspace?: string;
	required?: boolean;
}

jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:inputColor",
		name: "default"
	},
	(
		{
			"jcr:title": label,
			helpText,
			defaultValue,
			// alpha, //Not standard in HTML5 input type color only supported in ios safari
			// colorspace, //Not standard in HTML5 input type color only supported in ios safari
			required,
			...validationMsgs
		}: InputColorProps,
		{currentNode}
	) => {

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
					type="color"
					id={inputId}
					name={inputName}
					aria-describedby={helpId}
					className="fmdb-form-control"
					defaultValue={defaultValue}
					// alpha={alpha}
					// colorspace={colorspace}
					required={required}
					{...validationDataAttributes(validationMsgs)}
				/>
			</div>
		);
	}
);
