import { defineConfig } from "vite";
import jahia from "@jahia/vite-federation-plugin";

export default defineConfig({
  build: {
    outDir: "./src/main/resources/javascript/apps/",
  },
  plugins: [
    jahia({
      // The app shell owns the Apollo client and its cache: never ship a competing copy.
      // A bundle that registers its own @apollo/client version as a singleton can get elected
      // over the host's after a server restart, and jContent then mixes two Apollo copies on one
      // cache (white page, "Invariant Violation"). Same protection as formidable-salesforce/hubspot.
      shared: {
        // @ts-expect-error `import: false` is implemented by @module-federation/vite but missing from the Jahia plugin typing
        "@apollo/client": { singleton: true, import: false, requiredVersion: "^3.14.0" },
      },
      exposes: {
        "./init": "./src/javascript/init.tsx",
      },
    }),
  ],
});
