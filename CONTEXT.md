# Formidable – Full context for AI session restoration

## Monorepo structure

Yarn monorepo + Maven multi-module. Root: `/formidable-modules/`.

| Module | Role |
|---|---|
| `formidable-elements/` | Jahia front-end module – form rendering (React SSR + client hydration) |
| `formidable-engine/` | Jahia editor extension + Java/OSGi action pipeline |
| `formidable-extended-inputs/` | Optional field types (consent, switch, rating, scale) |
| `formidable-jexperience-engine/` | jExperience integration (Java): profile mapping of fields, the mapping rule kept in sync with publication, the submission event sent by the browser through jExperience's tracker; prefill to come — `docs/architecture/jexperience-integration.md` |
| `packages/formidable/` | npm package `@jahia/formidable-library` — the rendering contract the views import; `workspace:*` inside the monorepo (a version range would still resolve to the workspace, only `npm:` forces the registry), published at release |
| `jahia-test-module/` | Test modules for Cypress: JSP template set, tsx template set, and the two third-party extension examples meant to be copied — `formidable-test-module-samples-tsx` (definitions, editor overrides, views) and `formidable-test-module-samples-java` (external `FormAction`, choicelist initializer, content-integrity checks) |
| `tests/` | Cypress E2E suite (not a Maven module) |

Toolchain: Java 17 (Temurin), Node LTS, Yarn 4, Maven 3 (see `mise.toml`).

---

## formidable-elements – Front-end architecture

### Server / client convention

Every component lives in `formidable-elements/src/components/<ComponentName>/`.

- `[view].server.tsx` → rendered server-side by Jahia via `jahiaComponent()` from `@jahia/javascript-modules-library`
- `[Name].client.tsx` → interactive React component, hydrated via `<Island>`

The `<Island>` boundary is the only place where props cross from server to client. **Props must be serialisable** (no JCR node objects).

### Form flow (canonical example)

```
default.server.tsx        ← jahiaComponent() for fmdb:form, reads JCR props
  └─ <Island component={Form} props={...}>
        └─ Form.client.tsx  ← React, handles submission, multi-step, captcha
              └─ Captcha.client.tsx  ← captcha widget (Turnstile / hCaptcha / reCAPTCHA)
```

### Key files – Form

| File | Role |
|---|---|
| `src/components/Form/default.server.tsx` | Computes `submitActionUrl`, `captcha`, `stepLabels`; passes everything to the Island |
| `src/components/Form/Form.client.tsx` | XHR submission (Jahia's CSRFGuard integrates with XMLHttpRequest, not fetch), multi-step, CAPTCHA guard |
| `src/components/Form/Captcha.client.tsx` | Renders the captcha widget via the provider's native API |
| `src/components/Form/types.ts` | `FormServerProps`, `FormProps`, `CaptchaProvider` |
| `src/components/Form/definition.cnd` | Mixins `fmdbmix:responses`, `fmdbmix:buttons`, `fmdbmix:multiStep`, `fmdbmix:style`, `fmdbmix:captcha`, `fmdbmix:requireAuthentication`, the `fmdb:actionList`/`fmdb:fieldList` lists and the `fmdb:form` type |

### Vite

- Build: `@jahia/vite-plugin`, input globs `**/*.server.tsx` + `**/*.client.tsx`
- Alias `~` → `formidable-elements/src` (in both `vite.config.mjs` and `tsconfig.json`)
- Output: `dist/`; deployed to Jahia in watch mode with `yarn dev`

### CSS

All class names use the `fmdb-` prefix (e.g. `fmdb-form`, `fmdb-form-group`, `fmdb-form-control`).
Structural classes = plain strings; scoped overrides = CSS Modules (`*.module.css`) imported as `classes`.
Both coexist on the same element:

```tsx
<div className={clsx("fmdb-form-group", classes.group)}>
```

**Never rename `fmdb-` classes**: Cypress tests target them directly.

### i18n

`react-i18next`, namespace `formidable-elements`, key prefix = node type name (e.g. `fmdb_form`, `fmdb_inputCheckbox`).
Translation files: `formidable-elements/settings/locales/en.json` and `fr.json`.
Server-side labels come from JCR properties (`jcr:title`).

---

## CND – Type declaration conventions

Namespaces:
- `fmdb:` – concrete types (`fmdb:form`, `fmdb:step`, `fmdb:inputText`, …)
- `fmdbmix:` – mixins (`fmdbmix:formElement`, `fmdbmix:formStep`, `fmdbmix:formAction`, …)

Shared global types: `formidable-elements/settings/definitions.cnd`
Component-specific types: `formidable-elements/src/components/<ComponentName>/definition.cnd`
Action engine types: `formidable-engine/src/main/resources/META-INF/definitions.cnd`

### Rules for a new field type

1. Declare `[fmdb:myField] > jnt:content, fmdbmix:element, fmdbmix:profileMappableField` in its `definition.cnd` — the marker lets the field be mapped to a jCustomer profile property (jExperience module); leave it out of a file field. Add the value-kind mixin matching what the field submits
2. Create `default.server.tsx` with `jahiaComponent({ componentType: "view", nodeType: "fmdb:myField", name: "default" }, ...)`
3. HTML `name` = `currentNode.getName()`; HTML `id` = `input-${currentNode.getIdentifier()}`

### Mixin hierarchy

The structural mixins are split across the two modules — the engine owns the
semantics it interprets, the elements module owns the authoring entry points
(full ownership catalog: `docs/architecture/cnd-module-ownership.md`).

```
fmdbmix:formElement (engine)   > mix:title, fmdbmix:formLogicElement, orderable
  └─ fmdbmix:element (elements) ← what concrete field types extend; adds nothing
                                   today (reserved elements-side extension point)
fmdbmix:formContent (elements) ← non-field content embeddable in a form
fmdbmix:formStep (engine)      > fmdbmix:formContainer — step marker
fmdbmix:profileMappableField (engine) ← marker: the field can be mapped to a jCustomer profile property;
                                   fmdbmix:jExperienceProfileMapping (formidable-jexperience-engine) extends it
fmdbmix:component (elements)   ← makes a type visible/droppable in the editor
```

### Core form types

```cnd
[fmdb:form] > jnt:content, fmdbmix:component, jmix:visibleInContentTree,
              fmdbmix:buttons, fmdbmix:responses, fmdbmix:multiStep, fmdbmix:style, mix:title
  orderable
  - intro (string, richtext) i18n
  + fields (fmdb:fieldList) = fmdb:fieldList autocreated mandatory
  + actions (fmdb:actionList) = fmdb:actionList autocreated mandatory

[fmdb:step] > jnt:content, fmdbmix:formStep, mix:title
  orderable
  + * (fmdbmix:formElement)
  + * (fmdbmix:formContent)
```

Fields live under the autocreated `fields` list and actions under the autocreated
`actions` list — JCR/GraphQL code must go through those children, never directly under
the form node. `fmdb:captchaProvider` has been removed: captcha is the `fmdbmix:captcha`
marker mixin on the form plus server-side OSGi configuration (see the captcha section).

---

## Captcha – Full integration

There is no captcha node type. The pieces are:

- **Server config** (admin): `org.jahia.modules.formidable.cfg` carries
  `captchaSiteKey`, `captchaScriptUrl`, `captchaSecretKey`, `captchaWidgetVar`,
  `captchaTokenField` and `captchaVerifyUrl` (plus the two HTTP timeouts) — all six are
  needed; without `captchaVerifyUrl` verification counts as not configured and every
  submission of a captcha form is rejected with FMDB-005.
- **Per-form opt-in** (contributor): the `fmdbmix:captcha` mixin on the form node. It
  extends the engine-owned `fmdbmix:captchaProtectedForm`, which is what the submission
  pipeline checks.
- **Render**: `CaptchaRenderFilter` (Java) injects `siteKey`, `scriptUrl`, `widgetVar`
  and `tokenField` as request attributes when the mixin is present; `default.server.tsx`
  reads them, adds the provider script (with `render=explicit`) and renders the
  `<Captcha>` island.
- **Submit**: the client reads the token from the widget, DELETES the provider's native
  hidden field from `FormData`, and sends the token in the `X-Formidable-Captcha-Token`
  header. Server-side, pipeline step 7 (`verifyCaptcha`) reads that header and
  `FormidableConfigService.verifyCaptcha` calls the provider's `siteverify` endpoint —
  before any byte of the body is read.

There is no provider derivation: everything provider-specific (verify URL, widget
variable, native token field name) is explicit configuration. The only URL inspection is
`ensureCaptchaExplicit` in `default.server.tsx`, which appends `render=explicit` for the
script hosts that auto-render (Cloudflare, `google.com/recaptcha`, `recaptcha.net`). See
`docs/administration/captcha-server-side-validation.md`.

---

## formidable-engine – Action pipeline

### Overview

Every `fmdb:form` carries a mandatory, autocreated `actions` child (`fmdb:actionList`, see `Form/definition.cnd`) — there is no opt-in mixin. `default.server.tsx` always sets:
```
submitActionUrl = /modules/formidable-engine/form-submit?fid={form uuid}&lang={locale}
```

### Submission flow (client-side)

`handleSubmit` sends ONE `XMLHttpRequest` (no `fetch`, no parallel requests, no
`customTarget` — that concept never shipped) to
`/modules/formidable-engine/form-submit?fid=…&lang=…` with the full `FormData`, plus:

- `X-Formidable-Captcha-Token` header when a captcha is configured (the widget's native
  hidden field is deleted from `FormData` first)
- `X-Formidable-Logic-State` header: one base64 declaration of the provider state
  (JS variables, URL params, cookies) the browser evaluated its logic rules against

### Server side: servlet + pipeline (there is no Jahia Action)

Submission is an OSGi HTTP-Whiteboard servlet — `FormSubmitServlet`, gated by the
`formidable-submit` Security Filter scope (`origin: hosted`) — that delegates to
`FormSubmissionPipeline`, a 12-step pipeline plus step 11b, the field actions (see `docs/architecture/form-submission-flow.md`, the
authoritative walkthrough). The pipeline resolves the form, validates everything
(whitelist, types, constraints, logic coherence), THEN runs the form's `actions`
children in order: for each action node, the OSGi `FormAction` service whose
`getNodeType()` matches the node's primary type executes. On `FormActionException` the
response carries the exception's HTTP status and an opaque `errorCode`; on success,
`{"success": true}` plus whatever the `SubmissionResponseEnricher` services add to the body
(see `docs/extension/how-to-enrich-the-submission-response.md`).

### Java class layout

```
org.jahia.modules.formidable.engine
├── api/                                 ← the EXPORTED SPI (Export-Package)
│   ├── FormAction.java                  ← strategy interface (getNodeType + execute)
│   ├── FieldAction.java, FieldActionRequest.java, FieldActionResult.java, FieldActionGateway.java
│   │                                    ← the field-action SPI: one field's value judged server-side (docs/architecture/field-actions.md)
│   ├── FormActionException.java         ← exception carrying an httpStatus
│   ├── SubmittedFile.java               ← a validated uploaded file
│   ├── ChoiceOptionsResolver.java       ← how many choices a choice field offers
│   ├── AcceptedSubmission.java          ← what the pipeline accepted (form, site, locale, parameters)
│   └── SubmissionResponseEnricher.java  ← adds entries to the 200 of an accepted submission
├── servlet/                             ← the submission: its entry point, its pipeline, what reads the request
│   ├── FormSubmitServlet.java           ← whiteboard entry point
│   ├── FormSubmissionPipeline.java      ← the 12 steps, plus 11b (runFieldActions)
│   └── FormDataParser.java, FieldValidator.java, FormFieldMetadataCollector.java
└── actions/                             ← one folder per kind, and what the two kinds share
    ├── common/   ActionSummaryService (a type's label, tooltip and icon), FieldEscaper, TemplateInterpolator
    ├── form/     ContentDispositionUtils
    │   ├── email/   SendEmailNotificationFormAction, SendEmailContentFormAction
    │   ├── forward/ ForwardSubmissionFormAction, HostnameResolutionService
    │   └── storage/ SaveToJcrFormAction
    └── field/    FieldActionDispatcher, FieldActionServlet (/field-action), FieldActionRuntime,
                  FieldActionCollector, FieldActionGatewayImpl, VerdictCache, FieldActionsCache,
                  RateLimiter, RenderServiceViewRenderer, ResolvedFieldAction…
```

### `FormAction` interface (the SPI third parties compile against)

```java
public interface FormAction {
    String getNodeType();    // e.g. "fmdb:emailNotificationAction"
    void execute(
        JCRNodeWrapper actionNode,
        HttpServletRequest req,
        JCRSessionWrapper session,
        Map<String, List<String>> parameters,
        List<SubmittedFile> files
    ) throws FormActionException;
}
```

No `RenderContext` (there is no render at submit time), and the fifth parameter carries
the validated uploaded files.

### `FormActionException`

```java
FormActionException.badRequest("message");    // HTTP 400
FormActionException.serverError("message");   // HTTP 500
```

### Built-in actions

Captcha is NOT an action (see the captcha section). The action types are
`fmdb:emailNotificationAction`, `fmdb:emailContentAction`, `fmdb:forwardAction` and
`fmdb:save2jcrAction` — all but save2jcr carry `fmdbmix:readOnlyCompatibleAction`
(see `docs/administration/upgrade-notes.md` and the read-only maintenance behaviour).

#### `fmdb:emailNotificationAction` → `SendEmailNotificationFormAction`

CND:
```cnd
[fmdb:emailNotificationAction] > jnt:content, fmdbmix:formAction, mix:title
 - to (string) indexed=no
 - from (string) indexed=no
 - subject (string) i18n indexed=no
 - templateMessage (string, textarea) i18n indexed=no
```

Behaviour:
- `${fieldName}` interpolation in `subject` (values passed through as plain text, then
  the whole header normalized with `headerSafe`) and in `templateMessage` (values
  HTML-escaped with `FieldEscaper.html`); `to` and `from` are NOT interpolated — they
  are only normalized with `headerSafe`, so `to = ${email}` is sent literally
- Requires Jahia `MailService` configured (SMTP in Jahia admin)
- Call: `new MailMessage()` + `setTo/setFrom/setSubject/setHtmlBody` +
  `mailService.sendMessage(message)` — the message is queued through a Camel route, so
  asynchronous SMTP failures are logged by Jahia/Camel and do not propagate to the caller

### Adding a new action type

1. Add to `formidable-engine/src/main/resources/META-INF/definitions.cnd`:
   ```cnd
   [fmdb:myAction] > jnt:content, fmdbmix:formAction, mix:title
    - myProp (string) indexed=no
   ```
2. Create the Java class:
   ```java
   @Component(service = FormAction.class)
   public class MyFormAction implements FormAction {
       @Override public String getNodeType() { return "fmdb:myAction"; }
       @Override public void execute(...) throws FormActionException { ... }
   }
   ```
3. Read configuration properties from `actionNode` with `node.hasProperty(name) ? node.getProperty(name).getString() : null`

### `fmdbmix:formAction` mixin

```cnd
[fmdbmix:formAction] mixin
```
Pure marker — no properties. Enables the editor `contentpicker` to list all available action nodes across the site.

---

## formidable-engine – Editor extension (JS)

Entry point: `src/javascript/init.ts`
Registers extensions into the Jahia registry (`@jahia/ui-extender`):
- `SelectOptions`: custom selectorType for list-field options
- `ConditionalLogicCmp`: custom selectorType for the `logics` property on `fmdbmix:formLogicElement` nodes
Build: `@jahia/vite-federation-plugin` (Module Federation), output in `src/main/resources/javascript/apps/`.

---

## formidable-engine – Conditional Logic (weakref model)

### Model overview

A field that depends on another field's value carries:
- A `logics` multi-value string property (JSON rules)
- An autocreated `logicsSrc` child node (`fmdb:logicList`) with one `fmdb:logicSrc` child per rule, each holding a `logicNodeSource` weakreference

The canonical business reference for which field a rule targets is the source field's
`fieldKey` (stored in the rule as `sourceFieldKey`); `sourceNodeId` and the weakreference
are technical shortcuts, `sourceFieldName` the legacy fallback. The authoritative
walkthrough is `docs/architecture/conditional-logic-field-resolution.md`.

### JSON format in `logics`

```json
{
  "logicId": "c0b7e4a9",
  "sourceNodeId": "uuid-of-source-field",
  "sourceFieldKey": "uuid-like-business-key",
  "sourceFieldName": "role",
  "sourceFieldType": "fmdb:select",
  "valueKind": "choice",
  "operator": "in",
  "values": ["admin", "editor"]
}
```

- `logicId` → matches the name of a child node under `logicsSrc`
- `sourceFieldKey` → the source field's `fieldKey`, primary resolution criterion
- `sourceNodeId` → UUID shortcut / tie-breaker
- `sourceFieldName` → name-based fallback (last resort)
- `valueKind` → picks the comparison semantics on both evaluators (numeric `between`,
  the `today` sentinel for the `date` kind)

### Source resolution order (`FormLogicSourceResolver`, at SAVE time)

Runs when a form is saved (the sync listeners), never at evaluation time — see
`docs/architecture/conditional-logic-field-resolution.md` for the full order. In short: the
`fieldKey` chain first (sourceNodeId if it carries the key, then the weakref, then the
first matching field in document order), the legacy chain (sourceNodeId, weakref,
sourceFieldName) for rules stored before `fieldKey` existed; the sync then backfills
the JSON so legacy rules converge.

### Java class layout (after refactoring)

```
org.jahia.modules.formidable.engine.logic
├── FormLogicSyncService.java        ← orchestrator: sync() and cleanupAfterDuplication()
├── FormLogicJsonEntry.java          ← JSON parsing/migration (logicId auto-gen, sourceNodeId update)
├── FormLogicSourceResolver.java     ← 3-step source resolution with document-order validation
├── FormSourceFieldIndex.java        ← index of valid source fields before target in document order
├── FormLogicReferenceStore.java     ← CRUD on logicsSrc child nodes
└── ConditionalLogicEvaluator.java   ← server-side rule evaluation for submission validation
```

### Key design decisions

- **Document-order validation**: a source field is only valid if it appears before the target in depth-first form tree traversal. This prevents rules from referencing a homonymous field in a later fieldset (e.g. two `select-an-option` under different fieldsets).
- **cleanupAfterDuplication()**: removes only broken `logicsSrc` nodes (weakrefs pointing outside the new form's subtree). Preserves the JSON `logics` entries so that `sync()` can re-resolve via `sourceFieldName` fallback.
- **Import resilience**: after import, weakrefs may point to the correct imported nodes or be stale. The 3-step resolution handles both cases.

### Test scenarios

Full behavioral specification: `tests/scenarios/logics.md` (11 sections, from backend sync to runtime visibility).

### Cypress test specs

| Spec | Covers |
|---|---|
| `50-conditional-logic-selector-type.cy.ts` | Content Editor UI: source filtering, operators by type, value dropdowns, sibling exclusion, save/reload |
| `51-conditional-logic-copy-paste.cy.ts` | Backend duplication: whole-form copy rebinding, duplicate source names, single-field copy degradation |
| `52-conditional-logic-backend.cy.ts` | Backend sync: sourceNodeId persistence, logicsSrc weakref creation |
| `53-conditional-logic-import.cy.ts` | Backend import: XML import rebinding, sourceNodeId repair after import, duplicate source names |

### Cypress fixtures and page objects

- `tests/cypress/support/fixtures/logics.ts` — form factories, XML import helpers, GraphQL query for `logicsSrc`, `parseStoredLogicRule()`
- `tests/cypress/page-object/ConditionalLogicEditor.ts` — opens Content Editor on a target field's logic tab
- `tests/cypress/page-object/ConditionalLogicField.ts` — interacts with the conditional logic selector (dropdowns, menus, rules)
- `tests/cypress/fixtures/imports/conditional-logic-form.xml` — minimal import fixture with one source and one target logic
- `tests/cypress/fixtures/imports/conditional-logic-duplicates.xml` — import fixture covering duplicate source names

---

## User roles

| Role | Action |
|---|---|
| **Admin** | Configures the server side: `org.jahia.modules.formidable.cfg` (captcha keys, upload limits, forward targets) |
| **Contributor** | Creates a `fmdb:form` and fills its autocreated `actions` list (every form has one) |

---

## Developer commands

```bash
# Install dependencies (from repo root)
yarn install

# Front-end build (once)
cd formidable-elements && yarn build

# Watch mode (rebuild + auto-redeploy)
cd formidable-elements && yarn dev

# Local Jahia: any running 8.2.2+ instance on localhost:8080 (the modules ship no
# compose file; tests/docker-compose.yml exists for CI); deploy with each module's
# `yarn deploy` (jahia-deploy)

# Full Maven build
mvn clean install

# Cypress tests (Jahia must be running on localhost:8080)
cd tests && yarn e2e:ci      # headless
cd tests && yarn e2e:debug   # interactive

# Lint / format
yarn lint
yarn format
```

---

## Cypress tests

Page-object pattern:
- `tests/cypress/page-object/Form.ts` and `Fieldset.ts` – top-level page objects
- `tests/cypress/page-object/elements/` – per-element wrappers
- `tests/cypress/page-object/ConditionalLogicEditor.ts` and `ConditionalLogicField.ts` – conditional logic editor
- `tests/cypress/support/fixtures/` – typed JCR node factories per element type (including `logics.ts` for conditional logic forms)

Each test creates JCR content via `addNode()`, navigates into the JContent preview iframe, and asserts against `fmdb-` CSS selectors.
Conditional logic tests (`tests/cypress/e2e/logics/`) use GraphQL mutations (`copyNode`, `setNodeProperty`, `importContent`), XML fixtures for import, and the Content Editor page object.
Disabled specs use the `.cy.ts.disabled` extension.

Test scenarios specification: `tests/scenarios/logics.md`

---

## Known pitfalls / technical decisions

- **Email API**: use `new MailMessage()` + `setHtmlBody` + `mailService.sendMessage(message)`
  (queued through Camel; async SMTP failures do not propagate). The 7-argument
  `sendMessage(from, to, …)` form is not used in this codebase.
- **Captcha token**: the client DELETES the widget's native hidden field from `FormData`
  and sends the token in the `X-Formidable-Captcha-Token` header; the server reads that
  header only. Never rely on the token being in the POST body.
- **Turnstile `render=explicit`**: required, otherwise the widget renders automatically and ignores `containerRef`. Added by `ensureCaptchaExplicit` in `default.server.tsx`.
- **Island props must be serialisable**: never pass a `JCRNodeWrapper` to an Island. Extract scalars server-side with `getNodeProps()`.
