import {defineConfig} from "vitest/config";

export default defineConfig({
	test: {
		include: ["src/**/*.test.{ts,tsx}"],
		// jsdom: the useMask test drives a real <input> (value, caret, input events)
		environment: "jsdom"
	}
});
