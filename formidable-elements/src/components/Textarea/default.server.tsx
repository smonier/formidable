import {jahiaComponent} from "@jahia/javascript-modules-library";
import {type TextValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {HelpText, helpTextId} from "@jahia/formidable-library";

interface TextareaProps extends TextValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	placeholder?: string;
	defaultValue?: string;
	minLength?: number;
	maxLength?: number;
	rows?: number;
	cols?: number;
	// Advanced settings from mixin
	required?: boolean;
	autocomplete?: string;
	spellcheck?: boolean;
	readonly?: boolean;
	autofocus?: boolean;
	disabled?: boolean;
	form?: string;
	dirname?: boolean;
	wrap?: 'soft' | 'hard' | 'off';
	resize?: 'none' | 'both' | 'horizontal' | 'vertical';
}

jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:textarea",
		name: "default"
	},
	(
		{
			"jcr:title": label,
			helpText,
			placeholder,
			defaultValue,
			minLength,
			maxLength,
			rows = 4,
			cols,
			wrap = 'soft',
			resize = 'vertical',
			required,
			autocomplete,
			spellcheck = true,
			readonly,
			autofocus,
			disabled,
			form,
			dirname,
			...validationMsgs
		}: TextareaProps,
		{currentNode}
	) => {

		// Generate unique id and name for the textarea
		const textareaId = `textarea-${currentNode.getIdentifier()}`;
		const textareaName = currentNode.getName();

		// Build style object for resize control
		const textareaStyle: React.CSSProperties = {
			resize: resize
		};

		const helpId = helpText ? helpTextId(currentNode.getIdentifier()) : undefined;

		return (
			<div className="fmdb-form-group">
				{label && (
					<label htmlFor={textareaId} className="fmdb-form-label">
						{label}
						{required && <span className="fmdb-required-indicator" aria-hidden="true">*</span>}
					</label>
				)}

				<HelpText id={helpId} text={helpText}/>

				<textarea
					id={textareaId}
					name={textareaName}
					aria-describedby={helpId}
					className="fmdb-form-control"
					placeholder={placeholder}
					defaultValue={defaultValue}
					rows={rows}
					cols={cols}
					wrap={wrap}
					minLength={minLength}
					maxLength={maxLength}
					required={required}
					autoComplete={autocomplete}
					spellCheck={spellcheck}
					style={textareaStyle}
					readOnly={readonly}
					autoFocus={autofocus}
					disabled={disabled}
					form={form}
					{...(dirname && {'dirname': `${textareaName}.dir`})}
					{...validationDataAttributes(validationMsgs)}
				/>
			</div>
		);
	}
);
