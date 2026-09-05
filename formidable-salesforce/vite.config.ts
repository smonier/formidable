import { defineConfig } from "vite";
import jahia from "@jahia/vite-federation-plugin";

export default defineConfig({
  build: {
    outDir: "./src/main/resources/javascript/apps/",
  },
  plugins: [
    jahia({
      // No remote type generation: its broker/worker processes outlive the build and hang Maven exec.
      dts: false,
      // The app shell owns the Apollo client and its cache: never ship a competing copy.
      // Without this, the bundle registers its own @apollo/client version (3.14.1 today) as a
      // singleton, the federation runtime elects it over the host's 3.14.0, and jContent ends up
      // mixing two Apollo copies on one cache ("store.merge expects a string ID", white page).
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
