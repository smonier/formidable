export interface BaseValidationMessageProps {
	msgValueMissing?: string;
}

export interface TextValidationMessageProps extends BaseValidationMessageProps {
	msgTypeMismatch?: string;
	msgPatternMismatch?: string;
	msgTooShort?: string;
	msgTooLong?: string;
}

export interface RangeValidationMessageProps extends BaseValidationMessageProps {
	msgRangeUnderflow?: string;
	msgRangeOverflow?: string;
	msgStepMismatch?: string;
	msgBadInput?: string;
}

type AnyValidationMessageProps = BaseValidationMessageProps | TextValidationMessageProps | RangeValidationMessageProps;

export const validationDataAttributes = (props: AnyValidationMessageProps): Record<string, string | undefined> => {
	const attrs: Record<string, string | undefined> = {};
	if ('msgValueMissing' in props) attrs['data-fmdb-msg-value-missing'] = props.msgValueMissing || undefined;
	if ('msgTypeMismatch' in props) attrs['data-fmdb-msg-type-mismatch'] = (props as TextValidationMessageProps).msgTypeMismatch || undefined;
	if ('msgPatternMismatch' in props) attrs['data-fmdb-msg-pattern-mismatch'] = (props as TextValidationMessageProps).msgPatternMismatch || undefined;
	if ('msgTooShort' in props) attrs['data-fmdb-msg-too-short'] = (props as TextValidationMessageProps).msgTooShort || undefined;
	if ('msgTooLong' in props) attrs['data-fmdb-msg-too-long'] = (props as TextValidationMessageProps).msgTooLong || undefined;
	if ('msgRangeUnderflow' in props) attrs['data-fmdb-msg-range-underflow'] = (props as RangeValidationMessageProps).msgRangeUnderflow || undefined;
	if ('msgRangeOverflow' in props) attrs['data-fmdb-msg-range-overflow'] = (props as RangeValidationMessageProps).msgRangeOverflow || undefined;
	if ('msgStepMismatch' in props) attrs['data-fmdb-msg-step-mismatch'] = (props as RangeValidationMessageProps).msgStepMismatch || undefined;
	if ('msgBadInput' in props) attrs['data-fmdb-msg-bad-input'] = (props as RangeValidationMessageProps).msgBadInput || undefined;
	return attrs;
};
