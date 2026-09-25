import {Island, jahiaComponent} from "@jahia/javascript-modules-library";
import {type RangeValidationMessageProps, validationDataAttributes} from "@jahia/formidable-library";
import {HelpText, helpTextId} from "@jahia/formidable-library";
import TodayBoundedInput from "../Date/TodayBoundedInput.client";
import {resolveDateBounds} from "../Date/bounds";

interface InputDatetimeLocalProps extends RangeValidationMessageProps {
	"jcr:title"?: string;
	helpText?: string;
	defaultValue?: string;
	"minBoundMode"?: string;
	"maxBoundMode"?: string;
	"minRelativeAmount"?: number;
	"minRelativeUnit"?: string;
	"maxRelativeAmount"?: number;
	"maxRelativeUnit"?: string;
	min?: string;
	max?: string;
	step?: number;
	required?: boolean;
}

// Convert ISO datetime string to HTML datetime-local format (YYYY-MM-DDTHH:mm)
const formatDatetimeForInput = (isoDatetime?: string): string | undefined => {
	if (!isoDatetime) return undefined;

	try {
		// Parse ISO datetime and format for datetime-local input
		const date = new Date(isoDatetime);
		// Get local datetime in YYYY-MM-DDTHH:mm format
		const year = date.getFullYear();
		const month = String(date.getMonth() + 1).padStart(2, '0');
		const day = String(date.getDate()).padStart(2, '0');
		const hours = String(date.getHours()).padStart(2, '0');
		const minutes = String(date.getMinutes()).padStart(2, '0');

		return `${year}-${month}-${day}T${hours}:${minutes}`;
	} catch {
		// If parsing fails, return undefined to let browser handle invalid datetimes
		return undefined;
	}
};

jahiaComponent(
	{
		componentType: "view",
		nodeType: "fmdb:inputDatetimeLocal",
		name: "default"
	},
	(
		{
			"jcr:title": label,
			helpText,
			defaultValue,
			minBoundMode,
			maxBoundMode,
			minRelativeAmount,
			minRelativeUnit,
			maxRelativeAmount,
			maxRelativeUnit,
			min,
			max,
			step,
			required,
			...validationMsgs
		}: InputDatetimeLocalProps,
		{currentNode}
	) => {

		// Generate unique id and name for the input
		const inputId = `input-${currentNode.getIdentifier()}`;
		const inputName = currentNode.getName();

		const helpId = helpText ? helpTextId(currentNode.getIdentifier()) : undefined;

		const {minBound, maxBound, followsDay} = resolveDateBounds({
			minMode: minBoundMode,
			maxMode: maxBoundMode,
			min: formatDatetimeForInput(min),
			max: formatDatetimeForInput(max),
			minRelativeAmount,
			minRelativeUnit,
			maxRelativeAmount,
			maxRelativeUnit
		});

		// Shared between the static input and the today island so both render identical markup
		const inputAttributes = {
			id: inputId,
			name: inputName,
			"aria-describedby": helpId,
			className: "fmdb-form-control",
			defaultValue: formatDatetimeForInput(defaultValue),
			min: minBound.fixed,
			max: maxBound.fixed,
			step,
			required,
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

				{followsDay ? (
					// A bound following the submission day (as-is or shifted) cannot be a
					// server-rendered attribute (the fragment cache would freeze it): the
					// input becomes an island resolving it at hydration, in the visitor's
					// timezone.
					<Island
						component={TodayBoundedInput}
						props={{
							type: "datetime-local",
							minToday: minBound.today,
							maxToday: maxBound.today,
							minOffset: minBound.offset,
							maxOffset: maxBound.offset,
							inputAttributes
						}}
					/>
				) : (
					<input type="datetime-local" {...inputAttributes}/>
				)}
			</div>
		);
	}
);
