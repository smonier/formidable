import {act} from "react";
import {createRoot, type Root} from "react-dom/client";
import {afterEach, beforeEach, describe, expect, it} from "vitest";
import {HelpText, helpTextId} from "./HelpText.js";

(globalThis as {IS_REACT_ACT_ENVIRONMENT?: boolean}).IS_REACT_ACT_ENVIRONMENT = true;

let container: HTMLDivElement;
let root: Root;

const mount = (element: React.ReactElement): HTMLDivElement | null => {
	act(() => {
		root.render(element);
	});
	return container.querySelector("div");
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

describe("helpTextId", () => {
	it("derives the id a control's aria-describedby points at from the node id", () => {
		expect(helpTextId("n1")).toBe("help-n1");
		expect(helpTextId("a1b2-c3")).toBe("help-a1b2-c3");
	});
});

describe("HelpText", () => {
	it("renders nothing without a text", () => {
		expect(mount(<HelpText id={helpTextId("n1")}/>)).toBeNull();
		expect(mount(<HelpText id={helpTextId("n1")} text=""/>)).toBeNull();
	});

	it("renders the help block the validation client and aria-describedby rely on", () => {
		const help = mount(<HelpText id={helpTextId("n1")} text="Type your <strong>name</strong>"/>);
		expect(help?.tagName).toBe("DIV");
		expect(help?.id).toBe("help-n1");
		expect(help?.className).toBe("fmdb-form-help");
		expect(help?.hasAttribute("aria-hidden")).toBe(false);
		// contributor-authored rich text is rendered as HTML, not escaped
		expect(help?.innerHTML).toBe("Type your <strong>name</strong>");
	});

	it("repeats the block decoratively: same markup, no id, hidden from assistive technology", () => {
		const help = mount(<HelpText id={helpTextId("n1")} text="Twice" decorative/>);
		expect(help?.className).toBe("fmdb-form-help");
		expect(help?.innerHTML).toBe("Twice");
		expect(help?.hasAttribute("id")).toBe(false);
		expect(help?.getAttribute("aria-hidden")).toBe("true");
	});
});
