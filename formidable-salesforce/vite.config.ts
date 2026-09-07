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
      exposes: {
        "./init": "./src/javascript/init.tsx",
      },
    }),
  ],
});
