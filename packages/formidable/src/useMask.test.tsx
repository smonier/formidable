import {act} from "react";
import {createRoot, type Root} from "react-dom/client";
import {afterEach, beforeEach, describe, expect, it} from "vitest";
import {useMask} from "./useMask.js";

(globalThis as {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT = true;

function MaskedProbe({mask}: {mask: string}) {
	const {inputRef, handleInput} = useMask({mask});
	return <input ref={inputRef} onInput={handleInput} defaultValue=""/>;
}

let container: HTMLDivElement;
let root: Root;

const mount = (mask: string): HTMLInputElement => {
	act(() => {
		root.render(<MaskedProbe mask={mask}/>);
	});
	return container.querySelector("input") as HTMLInputElement;
};

/** As the browser does: the value is already changed and the caret placed when the input event fires. */
const typeAt = (input: HTMLInputElement, value: string, caret: number, inputType = "insertText") => {
	act(() => {
		input.value = value;
		input.setSelectionRange(caret, caret);
		input.dispatchEvent(new InputEvent("input", {bubbles: true, inputType}));
	});
};

beforeEach(() => {
	container = document.createElement("div");
	document.body.appendChild(container);
	root = createRoot(container);
});

afterEach(() => {
	act(() => {
		root.unmount();
	});
	container.remove();
});

describe("useMask", () => {
	it("formats while typing at the end and keeps the caret at the end", () => {
		const input = mount("AA-9999");
		typeAt(input, "ab1", 3);
		expect(input.value).toBe("AB-1");
		expect(input.selectionStart).toBe(4);
	});

	it("keeps the caret after the character edited in the middle of the value", () => {
		const input = mount("AA-9999");
		typeAt(input, "AB-1234", 7);
		// The user deletes the "2": the caret stays after "AB-1"
		typeAt(input, "AB-134", 4, "deleteContentBackward");
		expect(input.value).toBe("AB-134");
		expect(input.selectionStart).toBe(4);
		// …and types a "5" there: four raw characters precede the caret, which lands after the "5"
		typeAt(input, "AB-1534", 5);
		expect(input.value).toBe("AB-1534");
		expect(input.selectionStart).toBe(5);
	});

	it("does not re-append a trailing literal the user just deleted", () => {
		const input = mount("(99)");
		typeAt(input, "12", 2);
		expect(input.value).toBe("(12)");
		// The caret was at the end: it follows the completed literal (the raw count alone would put it at 3)
		expect(input.selectionStart).toBe(4);
		// Backspace on the ")": the literal must stay gone, or it could never be removed
		typeAt(input, "(12", 3, "deleteContentBackward");
		expect(input.value).toBe("(12");
		// Typing again completes the literal; the extra digit has no token left and is ignored
		typeAt(input, "(123", 4);
		expect(input.value).toBe("(12)");
	});
});
