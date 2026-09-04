# AGENTS.md
<!-- Build ID: 5445d7d19ae2 -->
<!-- APM Version: 0.14.1 -->
<!-- Source: local -->

## Project Overview

**Formidable** is Jahia's form management solution (replacement for Jahia Forms). It enables form creation with multi-step support, fieldsets, 14 core field types (plus 4 optional ones in formidable-extended-inputs), conditional logic, CAPTCHA, and a pluggable action pipeline (save to JCR, email, forward to endpoint).

### Monorepo Structure

Yarn 4 workspaces + Maven multi-module. Toolchain: Java 17 (Temurin), Node LTS, Yarn 4, Maven 3 (see `mise.toml`).

| Module | Role | Tech |
|---|---|---|
| `formidable-elements/` | Front-end — form rendering (React 19 SSR + client hydration via Islands) | Vite, `@jahia/vite-plugin`, TypeScript |
| `formidable-engine/` | Java/OSGi action pipeline + editor extensions (custom selectors, form results panel) | Maven bundle, `@jahia/vite-federation-plugin` (Module Federation, React 18) |
| `formidable-salesforce/` | Optional "Create Salesforce Lead" action (JWT bearer, REST) + `SalesforceLeadMapping` selector + `formidableSalesforce` GraphQL extension | Maven bundle, `@jahia/vite-federation-plugin` (React 18) |
| `formidable-hubspot/` | Optional "Create HubSpot Contact" action (private app token, CRM v3) + `HubspotContactMapping` selector + `formidableHubspot` GraphQL extension; mirrors formidable-salesforce | Maven bundle, `@jahia/vite-federation-plugin` (React 18) |
| `jahia-test-module/` | Java/JSP helper module for Cypress tests | Maven |
| `tests/` | Cypress E2E suite (not a Maven module) | Cypress 14, `@jahia/cypress` |

### Key Documentation

Architecture decisions and internal flows are documented in `docs/`:
- `form-submission-flow.md` — request lifecycle, pipeline steps, server-side safeguards
- `how-to-create-form-action.md` — step-by-step guide for a custom `FormAction` OSGi service
- `how-to-extend-views-and-elements-from-third-party-module.md` — rendering contract for external views and custom elements
- `cnd-module-ownership.md` — where JCR types belong (`formidable-elements` vs `formidable-engine`)
- `error-codes.md` — server-side error codes (FMDB-xxx)
- `captcha-server-side-validation.md` — provider verification and token handling
- `how-to-salesforce-lead-action.md` — Salesforce connection setup (factory .cfg), mapping JSON contract, value coercion
- `how-to-hubspot-contact-action.md` — HubSpot connection setup (private app token .cfg), contact property mapping, value coercion

## Global Instructions

# Jahia JavaScript Module Development

## Context

You are helping develop a **Jahia JavaScript Module** — a React-based template set for Jahia 8+. The module renders content from Jahia's JCR (Java Content Repository) using server-side React components (`.server.tsx`) and optional client-side islands (`.client.tsx`). Content is modelled in CND files, managed via Page Builder or jContent, and queried with JCR-SQL2 or GraphQL.

## Agent Principles

1. **Always invoke a skill before any Jahia task** — skills are the canonical source of patterns, gotchas, and API syntax. Never operate from memory alone.
2. **Never use `yarn dev` from an agent** — it is an interactive file watcher for human developers only. Always deploy with `yarn build && yarn jahia-deploy` (one-shot, non-interactive).
3. **Never hardcode URLs** — all navigable links must come from contributed content (JCR nodes, `j:linkType`, `buildNodeUrl`). This is a CMS: content owns the URLs.
4. **Never use `j:linkType: "external"` for internal pages** — use `"internal"` + `j:linknode`. External URLs break on environment changes, language switches, and vanity URL rewrites.
5. **Always verify before creating** — check that content types are deployed, site keys are correct, and area structures exist before attempting GraphQL mutations.
6. **All props are optional at runtime** — even mandatory CND fields. Always guard against `undefined` in views.
7. **Always include `-H "Origin: http://localhost:8080"` in every GraphQL curl** — omitting it returns `Permission denied` even with correct credentials.
8. **Build accessible HTML from the start** — every view must use semantic HTML (`<main>`, `<header>`, `<nav>`, `<footer>`, `<section>`, `<article>`), include exactly one `<h1>` per page, use a strict heading hierarchy (h1 → h2 → h3), add `alt` text to every `<img>`, and use sufficient colour contrast (≥ 4.5:1 for body text). Baking this in during authoring is faster than a post-hoc audit.
9. **Run one accessibility audit at the end** — after all components are built and content is published, invoke `/jahia-dev-accessibility` once to catch any remaining violations. Do not audit after every individual component; it wastes time on pages that are not yet complete.
10. **Batch builds and deploys** — build all components together, then run `yarn build && yarn jahia-deploy` once rather than after each individual component. Deploy once before populating content.

## Formidable Conventions

### CND Namespaces & Ownership

- `fmdb:` — concrete types (`fmdb:form`, `fmdb:step`, `fmdb:inputText`, …)
- `fmdbmix:` — mixins (`fmdbmix:formElement`, `fmdbmix:formAction`, `fmdbmix:captchaProtectedForm`, …)

CND locations:
- **Shared global types** (mixins, validation messages): `formidable-elements/settings/definitions.cnd`
- **Component-specific types**: `formidable-elements/src/components/<ComponentName>/definition.cnd`
- **Action & engine types** (actions, submissions, results, logic): `formidable-engine/src/main/resources/META-INF/definitions.cnd`

See `docs/cnd-module-ownership.md` for the decision framework.

### Component File Layout

Every component lives in `formidable-elements/src/components/<ComponentName>/`:

```
ComponentName/
├── definition.cnd          ← JCR type declaration
├── default.server.tsx      ← jahiaComponent() SSR view
├── Component.client.tsx    ← (optional) interactive Island
├── Component.client.module.css  ← (optional) CSS Module
└── types.ts                ← (optional) prop interfaces
```

Field types are nested under `Input/`: `Input/Text/`, `Input/Checkbox/`, `Input/Email/`, `Input/File/`, etc.

### CSS Conventions

- All class names use the `fmdb-` prefix (e.g. `fmdb-form`, `fmdb-form-group`, `fmdb-form-control`)
- Structural classes = plain strings; scoped overrides = CSS Modules (`*.module.css`) imported as `classes`
- Both coexist: `<div className={clsx("fmdb-form-group", classes.group)}>`
- **Never rename `fmdb-` classes** — Cypress tests target them directly

### Island Boundary

The `<Island>` is the SSR-to-client hydration boundary. **Props must be serialisable** — never pass `JCRNodeWrapper` objects. Extract scalars server-side with `getNodeProps()`.

### i18n

`react-i18next`, namespace `formidable-elements`, key prefix = node type name (e.g. `fmdb_form`, `fmdb_inputCheckbox`). Translation files: `formidable-elements/settings/locales/{en,fr}.json`.

### Action Pipeline (Java)

The public API for custom actions lives in `formidable-engine/src/main/java/org/jahia/modules/formidable/engine/api/`:
- `FormAction.java` — strategy interface (`getNodeType()` + `execute()`)
- `FormActionException.java` — exception with HTTP status (`badRequest()`, `serverError()`)
- `SubmittedFile.java` — file upload abstraction

Built-in actions: `SaveToJcrFormAction`, `SendEmailNotificationFormAction`, `SendEmailContentFormAction`, `ForwardSubmissionFormAction` (four — captcha is not an action: it is a mixin plus OSGi config, verified by the pipeline before the actions run).

### Adding a New Field Type

1. Create `formidable-elements/src/components/Input/MyField/definition.cnd`:
   ```cnd
   [fmdb:myField] > jnt:content, fmdbmix:element
   ```
2. Create `default.server.tsx` with `jahiaComponent({ componentType: "view", nodeType: "fmdb:myField", name: "default" }, ...)`
3. HTML `name` = `currentNode.getName()`; HTML `id` = `input-${currentNode.getIdentifier()}`

### Adding a New Action Type

1. Add CND to `formidable-engine/src/main/resources/META-INF/definitions.cnd`:
   ```cnd
   [fmdb:myAction] > jnt:content, fmdbmix:formAction, mix:title
   ```
2. Create Java class with `@Component(service = FormAction.class)` implementing `FormAction`
3. Read config from `actionNode` properties, not from hardcoded values
4. If the action never writes to the repository, also extend `fmdbmix:readOnlyCompatibleAction`;
   otherwise its forms are blocked (FMDB-014) while the platform is in read-only maintenance
   (see `docs/how-to-create-form-action.md`)

## Developer Commands

```bash
# Install dependencies (from repo root)
yarn install

# Front-end build (formidable-elements)
cd formidable-elements && yarn build

# Watch mode (rebuild + auto-redeploy to local Jahia)
cd formidable-elements && yarn dev

# Editor extension build (formidable-engine JS)
cd formidable-engine && yarn build

# Full Maven build (all modules)
mvn clean install

# Start local Jahia via Docker
# a running Jahia 8.2.2+ on localhost:8080 (this module ships no compose file; tests/docker-compose.yml exists for CI)

# Cypress tests (Jahia must be running on localhost:8080)
cd tests && yarn e2e:ci      # headless
cd tests && yarn e2e:debug   # interactive (Cypress UI)

# Manual-testing playground — when someone asks for "the playground" or to
# "rebuild the test set", this is the command they mean (see tests/README.md)
cd tests && yarn playground

# Lint / format (from repo root or any workspace)
yarn lint
yarn format
```

## Test Conventions

- Test suites: `tests/cypress/e2e/{fields,validation,security,actions,integrity,logics}/`
- Page objects: `tests/cypress/page-object/` (Form.ts, Fieldset.ts, per-element wrappers in `elements/`)
- Typed JCR node factories per element type: `tests/cypress/support/fixtures/` (`tests/cypress/fixtures/` holds raw files, groovy scripts and XML imports)
- Tests create JCR content via `addNode()`, navigate into the jContent preview iframe, assert against `fmdb-` CSS selectors
- Disabled specs use `.cy.ts.disabled` extension
- Scenario documents: `tests/scenarios/` (coverage summaries + regression scenarios)

## Skill Map

Skills are stored in `.agents/skills/`. Each skill has a `SKILL.md` file with detailed instructions. Start with `/jahia` if unsure where to begin.

### Development

| Skill | Purpose |
|-------|---------|
| `/jahia-dev` | Entry point — detect project state, guide to next step |
| `/jahia-dev-create-template-set` | Scaffold a new Jahia JS module |
| `/jahia-dev-start-local` | Start Jahia locally (Docker or bare metal) |
| `/jahia-dev-build-component` | Build a complete component (CND + view) — start here |
| `/jahia-dev-define-content-type` | Define a CND content type + types.ts |
| `/jahia-dev-create-view` | Implement a React view (.server.tsx + CSS Module) |
| `/jahia-dev-create-page-template` | Create a page template with Areas |
| `/jahia-dev-query-content` | Write JCR-SQL2 queries and useJCRQuery |
| `/jahia-dev-review` | Code review: 8 critical checks, 9 warnings, 11 suggestions |
| `/jahia-dev-accessibility` | Audit live pages with axe-core, fix WCAG 2.1 AA violations |
| `/jahia-dev-screenshot` | Screenshot reference + local render for visual comparison |
| `/jahia-dev-debug` | Debug build/deploy/runtime errors end-to-end |
| `/jahia-dev-cypress` | Scaffold and write Cypress E2E tests for Jahia JS modules |
| `/jahia-dev-import-from` | Build a component inspired by an external URL |
| `/jahia-dev-ui-extension` | Build Jahia back-office UI extensions (actions, panels, dialogs) |
| `/jahia-dev-osgi-module` | Conventions for Jahia OSGi/Java bundle modules |
| `/jahia-dev-jexperience` | Integrate with jExperience and jCustomer |
| `/jahia-dev-apis` | Jahia API reference |
| `/jahia-dev-ops` | Operational tooling |
| `/jahia-dev-java` | Java-specific development patterns |
| `/jahia-dev-properties` | Jahia property handling |

### Content Management

| Skill | Purpose |
|-------|---------|
| `/jahia-content` | Entry point — detect site state, route to content operations |
| `/jahia-content-explore-structure` | Map content types, properties, enums on an unknown site |
| `/jahia-content-query-content` | List and inspect content via GraphQL |
| `/jahia-content-create-content` | Create nodes, folders, articles, bulk-populate |
| `/jahia-content-move-content` | Restructure the content tree |
| `/jahia-content-translate-content` | Translate existing nodes to a new language and publish |

### Java Backend Review

| Skill | Purpose |
|-------|---------|
| `/jahia-review-java` | Full 6-pass review of a Jahia Java/backend module |
| `/jahia-java-security` | Security model — Security Filter, CSRF Guard, ACLs, captcha |
| `/jahia-java-osgi` | OSGi component patterns — @Component, @Reference, lifecycle |
| `/jahia-java-jcr` | JCR patterns — sessions, workspaces, nodes, mixins, versioning |
| `/jahia-java-concurrency` | Thread safety — volatile, locking, atomics, CopyOnWriteArrayList |
| `/jahia-java-persistence` | Persistence — JPA/Hibernate with JCR, N+1, transactions |

## Canonical References

Always fetch these when uncertain about version-sensitive topics:

| Topic | URL |
|-------|-----|
| Getting started / dev environment | https://academy.jahia.com/tutorials-get-started/front-end-developer/setting-up-your-dev-environment |
| Hero section tutorial | https://academy.jahia.com/tutorials-get-started/front-end-developer/making-a-hero-section |
| Blog / content listing | https://academy.jahia.com/tutorials-get-started/front-end-developer/making-a-blog |
| Page templates | https://academy.jahia.com/tutorials-get-started/front-end-developer/the-about-us-page |
| i18n (CND attribute, useTranslation, language switcher) | https://academy.jahia.com/documentation/jahia-cms/jahia-8-2/developer/javascript-module-development/preparing-for-internationalization-i18n |
| GraphQL API | https://academy.jahia.com/documentation/developer/jahia/8/api-documentation/graphql-api |
| Native Jahia mixins & node types | https://github.com/Jahia/jahia/tree/master/war/src/main/webapp/WEB-INF/etc/repository/nodetypes |
| JavaScript modules monorepo | https://github.com/Jahia/javascript-modules |
| Developer training | https://github.com/Jahia/developer-training/blob/main/js-training/slides.md |
| Integration best practices | https://github.com/Jahia/gautier-braindump/blob/main/articles/integration-best-practices/README.md |

## Local Development URLs

When Jahia is running at `http://localhost:8080` (default credentials: `root` / `root1234`):

- **Login**: http://localhost:8080/cms/login
- **Page Builder**: http://localhost:8080/jahia/page-builder
- **jContent**: http://localhost:8080/jahia/jcontent
- **GraphQL playground**: http://localhost:8080/modules/graphql
- **JCR browser**: http://localhost:8080/modules/tools/jcrBrowser.jsp
- **Definitions browser**: http://localhost:8080/modules/tools/definitionsBrowser.jsp

---


