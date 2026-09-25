export interface HelpTextProps {
	id?: string;
	text?: string;
	/**
	 * A decorative repeat of a help block the control already references (a
	 * rendering that shows the help above and below the field): no id, and
	 * hidden from assistive technology so a screen reader hears the help once.
	 */
	decorative?: boolean;
}

// Builds the stable id linking a control to its help text via aria-describedby.
// Static ids are preserved by the validation client (updateDescribedBy only
// adds/removes its own error id).
export const helpTextId = (nodeId: string) => `help-${nodeId}`;

// text is contributor-authored rich text (bold, italic, links) coming from a
// richtext property — rendered as HTML, same trust model as fmdb:richText.
export function HelpText({id, text, decorative = false}: HelpTextProps) {
	if (!text) {
		return null;
	}

	return (
		<div
			id={decorative ? undefined : id}
			className="fmdb-form-help"
			aria-hidden={decorative ? "true" : undefined}
			dangerouslySetInnerHTML={{__html: text}}
		/>
	);
}
