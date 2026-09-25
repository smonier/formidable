import {useCallback, useEffect, useRef, useState} from 'react';
import type {ReactNode} from "react";
import {useTranslation} from "react-i18next";
import {HelpText} from '@jahia/formidable-library';

interface CheckboxClientProps {
	label?: string;
	required?: boolean;
	errorMessage?: string;
	helpText?: string;
	helpId?: string;
	children: ReactNode;
}

export default function Checkbox({ label, required = false, errorMessage, helpText, helpId, children }: CheckboxClientProps) {
	const [isValid, setIsValid] = useState(true);
	const fieldsetRef = useRef<HTMLFieldSetElement>(null);
	const {t} = useTranslation("formidable-elements", { keyPrefix: "fmdb_inputCheckbox" });

	const validateGroup = useCallback(
		(checkboxes: HTMLInputElement[]) => {
			const hasChecked = checkboxes.some((cb) => cb.checked);

			// Priority: data attribute from mixin > errorMessage prop > i18n fallback
			const validationMessage =
				checkboxes[0]?.getAttribute('data-fmdb-msg-value-missing')
				|| errorMessage
				|| t("error");

			checkboxes.forEach((checkbox) => {
				checkbox.setCustomValidity(hasChecked ? "" : validationMessage);
			});

			return hasChecked;
		},
		[errorMessage, t],
	);

	useEffect(() => {
		if (!required || !fieldsetRef.current) return;

		const fieldset = fieldsetRef.current;
		const checkboxes = Array.from(
			fieldset.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'),
		);

		if (checkboxes.length === 0) return;

		// Handle checkbox change events
		const handleCheckboxChange = () => {
			const valid = validateGroup(checkboxes);
			setIsValid(valid);
		};

		// Attach change listeners to all checkboxes
		checkboxes.forEach((checkbox) => {
			checkbox.addEventListener("change", handleCheckboxChange);
		});

		// Initial validation
		validateGroup(checkboxes);

		// Cleanup
		return () => {
			checkboxes.forEach((checkbox) => {
				checkbox.removeEventListener("change", handleCheckboxChange);
			});
		};
	}, [required, validateGroup]);

	return (
		<fieldset
			ref={fieldsetRef}
			className="fmdb-form-group fmdb-checkbox-group"
			aria-invalid={!isValid}
			aria-required={required}
			aria-describedby={helpId}
		>
			{label && (
				<legend className="fmdb-group-legend">
					{label}
					{required && (
						<span className="fmdb-required-indicator" aria-hidden="true">
							*
						</span>
					)}
				</legend>
			)}
			<HelpText id={helpId} text={helpText}/>
			{children}
		</fieldset>
	);
}
