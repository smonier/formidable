import type {JCRNodeWrapper} from "org.jahia.services.content";
import {type ReactNode} from 'react';


// Props mapped from JCR node properties by the JS modules library
export interface FormServerProps {
	intro?: string;
	submissionMessage?: string;
	errorMessage?: string;
	maintenanceMessage?: string;
	showResetBtn?: boolean;
	showNewFormBtn?: boolean;
	showTryAgainBtn?: boolean;
	submitBtnLabel?: string;
	resetBtnLabel?: string;
	newFormBtnLabel?: string;
	tryAgainBtnLabel?: string;
	previousBtnLabel?: string;
	nextBtnLabel?: string;
	showStepsNav?: boolean;
	css?: string;
	destination?: JCRNodeWrapper;
}

// Props passed to the Form Island (client-side)
export interface FormProps extends Omit<FormServerProps, 'destination'> {
	formId: string;
	formTitle?: string;
	locale: string;
	stepLabels?: string[];
	stepIds?: string[];
	captcha?: {siteKey: string; widgetVar: string; tokenField: string; widgetTimeoutSeconds?: number};
	destinationUrl?: string;
	submitActionUrl?: string;
	isSubmitDisabled?: boolean;
	isEditMode?: boolean;
	children: ReactNode;
}
