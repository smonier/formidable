/**
 * Shared input-mask logic used by both the server view (pattern derivation,
 * default value formatting) and the client island (live formatting while typing).
 *
 * Mask tokens:
 * - `9` digit (0-9)
 * - `A` letter, converted to uppercase
 * - `a` letter, converted to lowercase
 * - `X` alphanumeric, converted to uppercase
 * - `x` alphanumeric, converted to lowercase
 * Any other character is a fixed literal (e.g. `-`, `(`, `)`, ` `, `/`).
 */

interface MaskTokenConfig {
	pattern: RegExp;
	/** Character class used to derive the HTML `pattern` attribute (kept permissive: without JS no case transform happens). */
	patternSource: string;
	transform?: (char: string) => string;
}

const MASK_TOKENS: Record<string, MaskTokenConfig> = {
	"9": {pattern: /[0-9]/, patternSource: "[0-9]"},
	"A": {pattern: /[a-zA-Z]/, patternSource: "[A-Za-z]", transform: (char) => char.toUpperCase()},
	"a": {pattern: /[a-zA-Z]/, patternSource: "[A-Za-z]", transform: (char) => char.toLowerCase()},
	"X": {pattern: /[a-zA-Z0-9]/, patternSource: "[A-Za-z0-9]", transform: (char) => char.toUpperCase()},
	"x": {pattern: /[a-zA-Z0-9]/, patternSource: "[A-Za-z0-9]", transform: (char) => char.toLowerCase()}
};

/** Strip everything that can never match a mask token, keeping only alphanumerics. */
export const extractRawValue = (value: string): string => value.replace(/[^a-zA-Z0-9]/g, "");

interface FormatOptions {
	/**
	 * Complete trailing fixed literals once every remaining mask position is a
	 * literal (e.g. `(99)` -> `(12)`), so the mask-derived pattern can be
	 * satisfied. Disabled during deletions, otherwise removing a trailing
	 * literal would immediately re-append it.
	 */
	fillTrailingLiterals?: boolean;
}

/**
 * Format a value according to the mask. Characters rejected by the current
 * token are dropped (not truncating the rest of the input), literals are
 * inserted automatically once a following token character is typed, and input
 * beyond the mask length is ignored. Package-internal: the option exists for
 * the hook's deletion keystrokes; `applyMask` is the public entry point.
 */
export const formatWithMask = (value: string, mask: string, {fillTrailingLiterals = true}: FormatOptions = {}): string => {
	if (!mask) return value;

	let masked = "";
	let maskIndex = 0;
	let consumedToken = false;

	for (const char of extractRawValue(value)) {
		// Emit any fixed literals sitting before the next token position
		while (maskIndex < mask.length && !MASK_TOKENS[mask[maskIndex]]) {
			masked += mask[maskIndex];
			maskIndex++;
		}

		if (maskIndex >= mask.length) break;

		const token = MASK_TOKENS[mask[maskIndex]];
		if (token.pattern.test(char)) {
			masked += token.transform ? token.transform(char) : char;
			maskIndex++;
			consumedToken = true;
		}
		// Rejected characters are silently dropped
	}

	if (fillTrailingLiterals && consumedToken && maskIndex < mask.length) {
		const remainder = mask.slice(maskIndex);
		if (Array.from(remainder).every((char) => !MASK_TOKENS[char])) {
			masked += remainder;
		}
	}

	return masked;
};

/**
 * A value formatted by the mask: literals inserted, rejected characters dropped,
 * input beyond the mask ignored, trailing literals completed so the value
 * satisfies the pattern `maskToPattern` derives from the same mask.
 */
export const applyMask = (value: string, mask: string): string => formatWithMask(value, mask);

/** Convert a mask to a regex string suitable for the HTML `pattern` attribute. */
export const maskToPattern = (mask?: string): string | undefined => {
	if (!mask) return undefined;

	const pattern = Array.from(mask)
		.map((char) => {
			const token = MASK_TOKENS[char];
			if (token) return token.patternSource;
			// Escape fixed characters so they match literally in the regex
			return char.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
		})
		.join("");

	return `^${pattern}$`;
};

/**
 * Cursor index in the masked value after consuming `rawCount` token characters.
 * Masked output aligns 1:1 with mask positions, so walking the mask is enough.
 */
export const maskedCursorPosition = (mask: string, rawCount: number): number => {
	let consumed = 0;
	let index = 0;

	while (index < mask.length && consumed < rawCount) {
		if (MASK_TOKENS[mask[index]]) consumed++;
		index++;
	}

	return index;
};
