import {describe, expect, it} from "vitest";
import {applyMask, extractRawValue, formatWithMask, maskToPattern, maskedCursorPosition} from "./mask.js";

describe("maskToPattern", () => {
	it("is undefined without a mask", () => {
		expect(maskToPattern(undefined)).toBeUndefined();
		expect(maskToPattern("")).toBeUndefined();
	});

	it("turns each token into its character class and keeps literals", () => {
		expect(maskToPattern("AA-9999")).toBe("^[A-Za-z][A-Za-z]-[0-9][0-9][0-9][0-9]$");
		expect(maskToPattern("Xx")).toBe("^[A-Za-z0-9][A-Za-z0-9]$");
	});

	it("escapes literals that are regex metacharacters", () => {
		expect(maskToPattern("(99) 99.99")).toBe("^\\([0-9][0-9]\\) [0-9][0-9]\\.[0-9][0-9]$");
	});
});

describe("applyMask", () => {
	it("returns the value untouched without a mask", () => {
		expect(applyMask("anything", "")).toBe("anything");
	});

	it("inserts the literals and applies the case of the tokens", () => {
		expect(applyMask("ab1234", "AA-9999")).toBe("AB-1234");
		expect(applyMask("AB", "xx")).toBe("ab");
	});

	it("drops the characters a token rejects and ignores input beyond the mask", () => {
		expect(applyMask("a1b234", "AA-9999")).toBe("AB-234");
		expect(applyMask("ab12345", "AA-9999")).toBe("AB-1234");
	});

	it("completes trailing literals once a token was consumed, unless told not to", () => {
		expect(applyMask("12", "(99)")).toBe("(12)");
		expect(formatWithMask("12", "(99)", {fillTrailingLiterals: false})).toBe("(12");
		expect(applyMask("", "(99)")).toBe("");
	});
});

describe("extractRawValue", () => {
	it("keeps only the alphanumerics", () => {
		expect(extractRawValue("(12) 34-ab")).toBe("1234ab");
	});
});

describe("maskedCursorPosition", () => {
	it("walks the mask past the literals that follow the consumed tokens", () => {
		expect(maskedCursorPosition("AA-9999", 0)).toBe(0);
		expect(maskedCursorPosition("AA-9999", 2)).toBe(2);
		expect(maskedCursorPosition("AA-9999", 3)).toBe(4);
		expect(maskedCursorPosition("AA-9999", 9)).toBe(7);
	});
});
