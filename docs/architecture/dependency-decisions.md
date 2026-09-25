# Dependency Decisions

This project uses two distinct dependency strategies in its Java modules:

- `provided` for APIs and platform libraries that are expected to be supplied by Jahia at runtime
- embedded compile-scope libraries only when the module must carry its own isolated implementation

## `formidable-engine`

### Embedded libraries

- `org.apache.tika:tika-core`
  - embedded intentionally in the OSGi bundle
  - used for MIME detection during file upload validation — deliberately filename-aware (content plus declared filename), as the pom comment documents
  - kept isolated from Jahia's platform-provided Tika line through OSGi bundle class loading

### Provided libraries

- `org.jahia.server:jahia-impl`
  - declared with global transitive exclusions
  - used only as the anchor API dependency for Jahia server types
- `javax.jcr:jcr`
- `javax.servlet:javax.servlet-api`
- `org.slf4j:slf4j-api`
- `org.osgi:osgi.cmpn`
- `org.osgi:osgi.annotation`
- `org.json:json`
- `com.sun.mail:javax.mail`
- `javax.activation:activation`
- `org.springframework:spring-context-support`
- `commons-lang:commons-lang`
- `commons-fileupload:commons-fileupload`
- `commons-io:commons-io`
- `org.apache.commons:commons-text`

### Notes

- `commons-io` is intentionally kept as `provided`, even though `tika-core` can depend on it transitively.
- `slf4j-api` is excluded from `tika-core` transitives because the platform already provides the API.
- The module uses `maven-dependency-plugin:analyze-only` to keep the declared dependency surface explicit.

### OSGi SPI surface

- Only `org.jahia.modules.formidable.engine.api` is exported from the bundle.
- That package is the public SPI for third-party modules and currently contains:
  - `FormAction`, `FormActionException`, `SubmittedFile` — the action SPI
  - `FieldAction`, `FieldActionRequest`, `FieldActionResult`, `FieldActionGateway` — the field-action SPI: the check of one field's value, in Java, and the gateway to the configured providers (see [field actions](field-actions.md))
  - `SubmissionResponseEnricher`, `AcceptedSubmission` — the response SPI: entries a module of its own adds to the JSON body of an accepted submission (see [how to enrich the submission response](../extension/how-to-enrich-the-submission-response.md))
  - `ChoiceOptionsResolver` — the number of choices a choice field offers, counted as the views render it (manual list or options source); read by the jExperience integration for the checkbox's cardinality
  - `FmdbNodeType`, `FmdbMixin`, `FmdbProperty`, `FmdbNodeName` — the names of the content model, so a module names a node type once instead of copying the string (see `cnd-module-ownership.md`, "Naming these types from Java")
- All other `org.jahia.modules.formidable.engine.*` packages are internal implementation details with no compatibility promise.

### Technical debt: split SPI and runtime bundles

- Current state:
  - `formidable-engine` still ships both the public SPI (`org.jahia.modules.formidable.engine.api`) and the runtime implementation (servlet, pipeline, built-in actions, config service).
  - External modules should depend only on the SPI package, but they still wire to the same OSGi bundle as the runtime.
- Why this matters:
  - deploying a new `formidable-engine` bundle can refresh modules that import the SPI, even when only runtime internals changed
  - the SPI cannot be versioned independently from the engine implementation
  - the exported package boundary is now clean, but the bundle boundary is still broader than necessary
- Target architecture:
  - `formidable-api`
    - contains only stable SPI types such as `FormAction`, `FormActionException`, `SubmittedFile`, `SubmissionResponseEnricher`, `AcceptedSubmission`, and future public DTOs
    - intended as the compile-time dependency for external modules
  - `formidable-engine`
    - contains only runtime implementation
    - should no longer export the action SPI
- Decision:
  - do not split now
  - keep the current package-level SPI isolation as the intermediate step
  - revisit the split only if Formidable gains multiple external consumers or if OSGi refresh cascades become an operational issue

## `formidable-jexperience-engine`

### Provided libraries

- `org.jahia.modules:jexperience` 4.2.1 — compile-time API for `ContextServerService` and the Unomi
  `PropertyType`; `provided`, every transitive excluded. The artifact is only on Nexus' internal
  group, so the module pom declares that repository and the build needs the matching server
  credentials (`.github/maven.settings.xml` in CI, a developer's own `settings.xml` locally).
- `org.jahia.modules:formidable-engine` — `provided`, for the exported `api` package only (`ChoiceOptionsResolver`);
  imported as `org.jahia.modules.formidable.engine.api;version="[0.5,1)"`.
- `commons-lang:commons-lang` — referenced by the signatures of the JCR wrappers the unit tests mock,
  never called directly.

### Notes

- OSGi imports: `org.jahia.modules.jexperience.admin;version="[3.4,5)"` (jExperience exports it at the
  module version) and `org.apache.unomi.api;version="[2.1,4)"` (jExperience embeds and exports the Unomi
  API of the jCustomer it pairs with: 2.1.0 in 3.4.0, 2.5.0 from 3.5 to 3.9, 3.0.0 in 4.2.1). The one
  bundle resolves on the 3.x line from 3.4 and on the 4.x line, and a 4.x upgrade resolves without a
  rebuild. The floor is where the measurement stops, not a hope: 3.4.0 is the oldest jar measured, and
  every member this module calls has the same JVM descriptor in 3.4.0, 3.5.2, 3.6.3, 3.7.1, 3.8.0, 3.9.0
  and 4.2.1 — `ContextServerService.isAvailable`, `getContextServerStatus`, `executeGetRequest`,
  `executePostRequest`, `executeDeleteRequest`; `PropertyType.getValueTypeId`, `isMultivalued`,
  `isProtected`; `Item.getItemId`; `MetadataItem.getMetadata`; `Metadata.getId`, `getName`,
  `getSystemTags`, `isHidden`, `isReadOnly` (javap on the jars and the `unomi-api` each embeds,
  2026-09-21). What the module needs beyond the API holds down the line too: every tracker function the
  client script calls exists in 3.4.0's `wem.min.js` (`getFormNamesToWatch`, `buildFormEvent`,
  `collectEvent`, `getLoadedContext`, `_registerCallback`, `digitalDataOverrides`,
  `requiredProfileProperties`, `wemLoaded`, `disableTrackedConditionsListeners`, `activateWem`); the two
  attributes that keep the tracker off a Formidable form are honoured everywhere — `data-form-id` by the
  initial scan of every 3.x, `data-wem-observed` by the form observer that appears in 3.7.1 (no observer
  before it, so nothing to keep off); the Form mappings screen of 3.4.0 writes the same rule shape; and
  Unomi 2.1.0's `PropertyHelper.setProperty`, like 2.5.0's and 3.0.0's, returns on a null value before any
  strategy — the guard the "unanswered field" contract relies on (#340). Exercised end to end on
  jExperience 3.9.0 + jCustomer 2.5.0 (PR #343): resolution, the 18 properties in the editor, the 4 rules,
  a profile written and read back, the tracker sending. The ranges are held by a gate, not by that one
  session: the `jexperience-floor` Maven profile recompiles the module and its tests against
  `jexperience.floor.version` (3.4.0, resolved from Nexus' enterprise group: the jar is on the public group
  but the parent pom its descriptor needs is not, so the profile declares the enterprise repository behind
  the credentials the build already carries), and the CI runs it on every change — a call
  to a member that exists only on the 4.x line (`ContextServerStatus.isNotInError()`) builds green against
  4.2.1 and fails there, verified with a throwaway probe. The first version of the module, 2026-09-14,
  declared `[4,5)` and `[3,4)` although its documentation announced the ranges open to 3.4+: on any 3.x
  the bundle did not resolve.
- `maven-dependency-plugin:analyze-only` with `failOnWarning`, as in the engine.
- The mapping rules are built and compared as plain maps: no import of Unomi's rule, condition or action packages, no JSON library at runtime (jExperience's admin client serialises the maps). `org.json` is a test dependency, for the golden rule.
- `jahia-depends`: `formidable-engine` (the marker mixin), `formidable-elements` (`fmdb:form`, the type the publication listener and the render
  filter apply to) and `jexperience`.

### OSGi SPI surface

- **Nothing is exported.** No module consumes the listener, the catalog or the initializer, and an
  exported package is a compatibility promise (see the engine's section above). `SubmissionResponseEnricher`
  landed in the **engine's** `api` package instead of this module's: the servlet calls it, so it is the
  engine's contract, and this module is one of its implementations.

## `jahia-test-module/formidable-test-module-templateset-jsp`

### Provided libraries

- `org.jahia.server:jahia-impl`
  - declared with global transitive exclusions
- `org.jahia.server:jahia-taglib`
- `javax.servlet:jstl`
- `javax.servlet:javax.servlet-api`

### Notes

- This module is JSP-only, so some dependencies are used by JSP/taglib resources rather than Java source imports.
