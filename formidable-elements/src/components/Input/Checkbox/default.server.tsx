import {Island, jahiaComponent} from "@jahia/javascript-modules-library";
import Checkbox from "./Checkbox.client";
import {resolveFieldOptions} from "~/utils/optionsSource.server";
import OptionsSourceError from "~/design/OptionsSourceError";
import {type BaseValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {resolveUrlPlaceholders} from "~/utils/richTextUtils";
import {HelpText, helpTextId} from "@jahia/formidable-library";

interface CheckboxProps extends BaseValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	"options"?: string[];
	required?: boolean;
}
jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:checkbox",
		name: "default"
	},
	(
		{"jcr:title": label, helpText, "options": rawChoices = [], required, ...validationMsgs}: CheckboxProps,
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
			const inputId = `checkbox-${nodeId}`;
			return (
				<div className="fmdb-form-group">
					<input
						type="checkbox"
						id={inputId}
						name={inputName}
						className="fmdb-form-control"
						value={choice.value}
						defaultChecked={choice.selected}
						required={required}
						aria-describedby={helpId}
						{...vAttrs}
					/>
					<label htmlFor={inputId} className="fmdb-checkbox-label">
						{choice.label}
						{required && <span className="fmdb-required-indicator" aria-hidden="true">*</span>}
					</label>
					<HelpText id={helpId} text={helpText}/>
				</div>
			);
		}
		return (
			<Island component={Checkbox} props={{ label, required, helpText: resolveUrlPlaceholders(helpText, renderContext), helpId }}>
				<div className="fmdb-group-items">
					{parsedChoices.map((choice, idx) => {
						const inputId = `checkbox-${nodeId}-${idx}`;
						return (
							<div key={choice.value || String(idx)} className="fmdb-group-item">
								<input
									type="checkbox"
									id={inputId}
									name={inputName}
									className="fmdb-form-control"
									value={choice.value}
									defaultChecked={choice.selected}
									{...vAttrs}
								/>
								<label htmlFor={inputId} className="fmdb-checkbox-label">
									{choice.label}
								</label>
							</div>
						);
					})}
				</div>
			</Island>
		);
	}
);
