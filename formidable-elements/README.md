# Welcome!

Your JavaScript module was successfully created. If this is your first time creating a module, you may want to consult the [Getting Started guide](https://academy.jahia.com/tutorials-get-started/front-end-developer/setting-up-your-dev-environment#create-a-new-project).

This README assumes you have a working development environment with Node.js, Yarn and Docker installed and configured. Please refer to the [Getting Started](https://academy.jahia.com/tutorials-get-started/front-end-developer/setting-up-your-dev-environment) guide if you need help setting up your environment.

## Getting Started

This module is accompanied by a Docker-based development environment. To get started, follow these steps:

```bash
# Install dependencies
yarn install

# Have a Jahia 8.2.2+ instance running on localhost:8080
# (this module ships no compose file — use your usual local instance)

# Build the module and start the dev mode
yarn build && yarn dev
```

These commands build the module and start a watcher that rebuilds and redeploys it to the local Jahia every time you make changes to the source code.

## Commands

This module comes with some scripts to help you develop your module. You can run them with `yarn <script>`:

| Category     | Script                | Description                                                             |
| ------------ | --------------------- | ----------------------------------------------------------------------- |
| Build        | `build`               | Produces a deployable artifact that can be uploaded to a Jahia instance |
| Build        | `deploy`              | Pushes the build artifact to a Jahia instance                           |
| Development  | `dev` (alias `watch`) | Watches for changes and rebuilds the module                             |
| Code quality | `format`              | Runs Prettier (a code formatter) on your code                           |
| Code quality | `lint`                | Runs ESLint (a linter) on your code                                     |
| Utils        | `clean`               | Removes build artifacts                                                 |
| Utils        | `package`             | Packs distributions files in a `.tgz` archive inside the `dist/` folder |
| Utils        | `watch:callback`      | Called every time a build succeeds in watch mode                        |

## Configuration

If you don't use default configuration for the Docker container port and credentials, please modify the provided `.env` file.

## Documentation

- [CND module ownership](../docs/architecture/cnd-module-ownership.md) — where JCR types and mixins belong, and how to choose between `formidable-elements` and `formidable-engine`
- [Form submission flow](../docs/architecture/form-submission-flow.md) — the 12-step pipeline, validation, CSRF posture, error codes
- [CAPTCHA server-side validation](../docs/administration/captcha-server-side-validation.md) — provider verification endpoints and examples
- [Conditional logic editor and runtime notes](../formidable-engine/src/javascript/ConditionalLogic/README.md) — stored rule format, `logicsSrc` synchronization, and duplicate system-name caveats
