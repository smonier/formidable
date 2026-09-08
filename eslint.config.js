// @ts-check
import { defineConfig } from "eslint/config";
import { includeIgnoreFile } from "@eslint/compat";
import eslint from "@eslint/js";
import tseslint from "typescript-eslint";
import path from "node:path";
import globals from "globals";
import eslintReact from "@eslint-react/eslint-plugin";
import pluginCypress from "eslint-plugin-cypress";

export default defineConfig(
  {
    languageOptions: {
      globals: { ...globals.browser, ...globals.jest, ...globals.node },
    },
  },

  // JS/TS recommended
  eslint.configs.recommended,
  { files: ["**/*.ts", "**/*.tsx"], extends: tseslint.configs.recommended },

  // React
  eslintReact.configs["recommended-typescript"],
  {
    rules: {
      // We know what we're doing
      "@eslint-react/dom/no-dangerously-set-innerhtml": "off",
    },
  },

  // Cypress
  pluginCypress.configs.recommended,
  {
    // Cypress support helpers are not React: the hook-naming heuristic misfires
    // on the use* fixture installers (useFormidableSite and friends).
    files: ["tests/**"],
    rules: {
      "@eslint-react/no-unnecessary-use-prefix": "off",
    },
  },
  {
    files: ["**/*.cy.ts"],
    rules: {
      // Stop reporting `expect().to.exist`
      "@typescript-eslint/no-unused-expressions": "off",
    },
  },

  // Ignore the same files as .gitignore
  includeIgnoreFile(path.resolve(import.meta.dirname, ".gitignore")),
  {
    ignores: [
      "**/fixtures/expected/**",
      // includeIgnoreFile only reads the ROOT .gitignore; this rule lives in
      // formidable-engine/.gitignore (the generated module-federation bundle,
      // ~600 false positives when linted).
      "formidable-engine/src/main/resources/javascript/apps/**",
      "formidable-salesforce/src/main/resources/javascript/apps/**",
      "formidable-hubspot/src/main/resources/javascript/apps/**",
      "formidable-efficy/src/main/resources/javascript/apps/**",
    ],
  },
);
