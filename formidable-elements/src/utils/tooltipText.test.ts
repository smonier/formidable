import {describe, expect, it} from "vitest";
import {tooltipText} from "./tooltipText";

describe("tooltipText", () => {
	it("keeps words apart where markup separated them", () => {
		expect(tooltipText("Sends an email.<br/><b>Usage:</b> <i>notify a team</i>.")).toBe("Sends an email. Usage: notify a team .");
	});

	it("leaves a comparison sign in prose as written", () => {
		expect(tooltipText("Timeout must be < 30 and > 0 seconds.")).toBe("Timeout must be < 30 and > 0 seconds.");
		expect(tooltipText("Use a value < the maximum. <b>Note:</b> integers only.")).toBe(
			"Use a value < the maximum. Note: integers only."
		);
	});

	it("decodes the entities the editor writes", () => {
		expect(tooltipText("Tom &amp; Jerry &lt;3 &#233;t&#xE9; &quot;ok&quot;")).toBe('Tom & Jerry <3 été "ok"');
	});

	it("shows a numeric entity beyond Unicode as the replacement character", () => {
		expect(tooltipText("&#1114112;")).toBe("�");
	});

	it("collapses whitespace", () => {
		expect(tooltipText("  Sends \n\n an   email.  ")).toBe("Sends an email.");
	});
});
