import {describe, expect, it} from "vitest";
import {validationDataAttributes} from "./validationProps.js";

describe("validationDataAttributes", () => {
	it("maps every message prop to the data attribute the validation client reads", () => {
		expect(
			validationDataAttributes({
				msgValueMissing: "required",
				msgTypeMismatch: "type",
				msgPatternMismatch: "pattern",
				msgTooShort: "short",
				msgTooLong: "long"
			})
		).toEqual({
			"data-fmdb-msg-value-missing": "required",
			"data-fmdb-msg-type-mismatch": "type",
			"data-fmdb-msg-pattern-mismatch": "pattern",
			"data-fmdb-msg-too-short": "short",
			"data-fmdb-msg-too-long": "long"
		});
		expect(
			validationDataAttributes({
				msgValueMissing: "required",
				msgRangeUnderflow: "under",
				msgRangeOverflow: "over",
				msgStepMismatch: "step",
				msgBadInput: "bad"
			})
		).toEqual({
			"data-fmdb-msg-value-missing": "required",
			"data-fmdb-msg-range-underflow": "under",
			"data-fmdb-msg-range-overflow": "over",
			"data-fmdb-msg-step-mismatch": "step",
			"data-fmdb-msg-bad-input": "bad"
		});
	});

	it("emits no attribute for a prop the view does not pass", () => {
		expect(validationDataAttributes({})).toEqual({});
		expect(Object.keys(validationDataAttributes({msgValueMissing: "required"}))).toEqual(["data-fmdb-msg-value-missing"]);
	});

	it("turns a blank message into an undefined attribute, which React then omits", () => {
		const attrs = validationDataAttributes({msgValueMissing: "", msgTooShort: undefined});
		expect("data-fmdb-msg-value-missing" in attrs).toBe(true);
		expect(attrs["data-fmdb-msg-value-missing"]).toBeUndefined();
		expect("data-fmdb-msg-too-short" in attrs).toBe(true);
		expect(attrs["data-fmdb-msg-too-short"]).toBeUndefined();
	});

	it("ignores props that are not validation messages", () => {
		expect(validationDataAttributes({msgValueMissing: "required", helpText: "no"} as never)).toEqual({
			"data-fmdb-msg-value-missing": "required"
		});
	});
});
