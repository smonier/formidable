import { defineConfig } from "vite";
import jahia from "@jahia/vite-federation-plugin";

export default defineConfig({
  build: {
    outDir: "./src/main/resources/javascript/apps/",
  },
  plugins: [
    jahia({
      // @apollo/client is pinned in package.json to the app shell's exact version (3.14.0): the
      // bundle shares the same version as the host, so the federation runtime never elects a
      // different Apollo copy. Do NOT use `shared: {..., import: false}` here: the vite plugin still
      // registers a share entry whose loader throws "must be provided by host", and any webpack
      // remote electing it breaks the whole Jahia UI.
      exposes: {
        "./init": "./src/javascript/init.tsx",
      },
    }),
  ],
});
