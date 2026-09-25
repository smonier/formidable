import {jahiaComponent} from "@jahia/javascript-modules-library";
import {resolveFieldOptions} from "~/utils/optionsSource.server";
import OptionsSourceError from "~/design/OptionsSourceError";
import {type BaseValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {HelpText, helpTextId} from "@jahia/formidable-library";

interface RadiosProps extends BaseValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	"options"?: string[];
	required?: boolean;
}

jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:radio",
		name: "default"
	},
	(
		{"jcr:title": label, helpText, "options": rawChoices = [], required, ...validationMsgs}: RadiosProps,
		{currentNode, renderContext}
	) => {
		const inputName = currentNode.getName();
		const nodeId = currentNode.getIdentifier();
		const {choices: parsedChoices, sourceError} = resolveFieldOptions(currentNode, rawChoices, renderContext);
		if (sourceError) {
			return <OptionsSourceError label={label} required={required}/>;
		}

		const vAttrs = validationDataAttributes(validationMsgs);

		const helpId = helpText ? helpTextId(nodeId) : undefined;

		if (parsedChoices.length === 1) {
			const choice = parsedChoices[0];
			const inputId = `radio-${nodeId}`;
			return (
				<div className="fmdb-form-group">
					<input
						type="radio"
						id={inputId}
						name={inputName}
						className="fmdb-form-control"
						value={choice.value}
						defaultChecked={choice.selected}
						required={required}
						aria-describedby={helpId}
						{...vAttrs}
					/>
					<label htmlFor={inputId} className="fmdb-radio-label">
						{choice.label}
						{required && <span className="fmdb-required-indicator" aria-hidden="true">*</span>}
					</label>
					<HelpText id={helpId} text={helpText}/>
				</div>
			);
		}

		// radiogroup: what a fieldset of radios is, and the one role of the group that carries aria-readonly
		// (the jExperience prefill sets it when the author locks the field)
		return (
			<fieldset className="fmdb-form-group fmdb-radio-group" role="radiogroup" aria-describedby={helpId}>
				{label && (
					<legend className="fmdb-group-legend">
						{label}
						{required && <span className="fmdb-required-indicator" aria-hidden="true">*</span>}
					</legend>
				)}
				<HelpText id={helpId} text={helpText}/>
				<div className="fmdb-group-items">
					{parsedChoices.map((choice, idx) => {
						const inputId = `radio-${nodeId}-${idx}`;
						return (
							<div key={choice.value || String(idx)} className="fmdb-group-item">
								<input
									type="radio"
									id={inputId}
									name={inputName}
									className="fmdb-form-control"
									value={choice.value}
									defaultChecked={choice.selected}
									required={required}
									{...vAttrs}
								/>
								<label htmlFor={inputId} className="fmdb-radio-label">
									{choice.label}
								</label>
							</div>
						);
					})}
				</div>
			</fieldset>
		);
	}
);
