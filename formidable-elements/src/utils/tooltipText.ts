/**
 * A Content Editor tooltip (rich text, light formatting allowed) as one plain line: what the
 * actions zone shows under a card, and repeats in the card's `title`.
 */

// The named entities a tooltip may carry; the text is rendered as React text, so entities must
// be decoded here or the reader sees them literally.
const NAMED_ENTITIES: Record<string, string> = {amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " "};

// A numeric entity beyond Unicode is the replacement character, as a browser would show it.
const codePoint = (value: number): string => (value <= 0x10ffff ? String.fromCodePoint(value) : "�");

const decodeEntities = (text: string): string =>
	text.replace(/&(#x[0-9a-fA-F]+|#\d+|[a-zA-Z]+);/g, (whole, entity: string) => {
		if (entity.startsWith("#x")) return codePoint(parseInt(entity.slice(2), 16));
		if (entity.startsWith("#")) return codePoint(parseInt(entity.slice(1), 10));
		return NAMED_ENTITIES[entity] ?? whole;
	});

/**
 * Tags are dropped — only what reads as a tag, a `<` followed by a letter or a closing slash, so
 * "< 30 and > 0" in a numeric parameter's tooltip stays as written, the way a browser reads it —
 * each replaced by a space, so text that markup separated ("First.<br/>Second.") does not run
 * together; entities are decoded; whitespace is collapsed.
 */
export const tooltipText = (html: string): string =>
	decodeEntities(html.replace(/<\/?[a-zA-Z][^>]*>/g, " ")).replace(/\s+/g, " ").trim();
