import {Island, jahiaComponent} from "@jahia/javascript-modules-library";
import FileInput from "./File.client";
import {type BaseValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {HelpText, helpTextId} from "@jahia/formidable-library";

interface InputFileProps extends BaseValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	accept?: string[];
	multiple?: boolean;
	required?: boolean;
}

jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:inputFile",
		name: "default"
	},
	(
		{"jcr:title": label, helpText, accept, multiple, required, ...validationMsgs}: InputFileProps,
		{currentNode}
	) => {
		// Generate unique id and name
		const inputId = `input-${currentNode.getIdentifier()}`;
		const inputName = currentNode.getName();

		const helpId = helpText ? helpTextId(currentNode.getIdentifier()) : undefined;

		return (
			<div className="fmdb-form-group">
				{/* both hooks: fmdb-form-label is what a stylesheet gives every field label, fmdb-file-label
				    what it adds for this one — a file label carrying only the second escaped the first */}
				{label && (
					<label htmlFor={inputId} className="fmdb-form-label fmdb-file-label">
						{label}
						{required && <span className="fmdb-required-indicator" aria-hidden="true">*</span>}
					</label>
				)}
				<HelpText id={helpId} text={helpText}/>
				<Island
					component={FileInput}
					props={{
						inputId,
						inputName,
						accept,
						multiple,
						required,
						describedBy: helpId,
						validationAttributes: validationDataAttributes(validationMsgs)
					}}
				/>
			</div>
		);
	}
);
