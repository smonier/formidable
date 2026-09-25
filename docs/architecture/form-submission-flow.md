# Form Submission Flow

## Overview

Form submission is handled by `FormSubmitServlet`, a dedicated OSGi servlet registered via
the **OSGi HTTP Whiteboard** (`service = { HttpServlet.class, Servlet.class }`, `alias=/formidable-engine/form-submit`).
It operates **outside the Jahia render chain**, ensuring it is always the first consumer of
the multipart request stream.

The pipeline is driven by the child nodes of the `actions` node (`fmdb:actionList`)
autocreated on every `fmdb:form`. Actions are executed in order, as configured by the
contributor.

> **Why a dedicated servlet?**  
> For Guest users, Jahia's FileUpload filter consumes the multipart stream before Jahia
> `Action` services run. The servlet bypasses this filter entirely.

---

## Pipeline

```
Browser
  └─ POST multipart/form-data → /modules/formidable-engine/form-submit?fid=UUID&lang=fr
       + header X-Formidable-Captcha-Token: TOKEN when CAPTCHA is enabled
       FormSubmitServlet → FormSubmissionPipeline (outside Jahia render chain):

         Gate 0   FormSubmitServlet       PermissionService.hasPermission on the auto-applied
                                          `formidable-submit` scope (same-origin via
                                          Origin/Referer) — before the pipeline starts
         Step 1   verifyMultipart         Content-Type must be multipart/form-data
         Step 2   readRoutingParams       fid (UUID-validated) + lang read from URL query params  — 0 byte read
         Step 3   guardContentLength      early reject if Content-Length > max                    — 0 byte read
         Step 4   resolveFormNode         JCR getNodeByIdentifier(fid) in "live"                 — 0 byte read
         Step 5   verifyPlatformWritable  if the platform is in read-only mode and an action
                                          lacks fmdbmix:readOnlyCompatibleAction → FMDB-014      — 0 byte read
         Step 6   verifyAuthentication    if fmdbmix:authenticatedOnlyForm → reject Guest
         Step 7   verifyCaptcha           if fmdbmix:captchaProtectedForm → verify CAPTCHA header — 0 byte read
         Step 8   collectFormFieldInfo    walk form node: build the field whitelist —
                                          per-field type, allowed options, accept types,
                                          constraints (required, min/maxLength, pattern,
                                          min/maxDate, min/maxNumber) — plus the logic
                                          rules and the container hierarchy               — 0 byte read
         Step 9   parseMultipart          FIRST AND ONLY read of the request stream
                   ├─ undeclared fields skipped inline (not read, not stored)
                   ├─ text fields  → validated → Map<String, List<String>>
                   │    ├─ choice fields (select, radio, checkbox): value checked against
                   │    │   allowed options declared in JCR
                   │    ├─ typed fields (email, date, datetime-local, color): format validated
                   │    └─ field constraints applied (required, minLength, maxLength, pattern,
                   │        minDate, maxDate)
                   └─ file parts   → List<FormFile>
                        ├─ size limits (per-file + total)
                        ├─ file count limit (config.getUploadMaxFileCount())
                        ├─ Tika magic-byte MIME detection
                        └─ MIME allowlist (field accept or global cfg)
         Step 10  validateLogicCoherence  reject values submitted for fields provably hidden
                                          by conditional logic (see
                                          conditional-logic-field-resolution.md)
         Step 11  validateRequired        post-parse: check required fields absent from the
                                          submitted body (e.g. unchecked checkbox/radio)
         Step 11b runFieldActions         the BLOCKING field actions of the answered, visible fields run
                                          again server-side, on every non-blank value, in list order, on
                                          the shared verdict cache;
                                          the first refusal → FMDB-015 with messages[] anchored on the
                                          field (see field-actions.md)
         Step 12  dispatchActions         execute fmdb:actionList nodes in order
                   ├─ fmdb:save2jcrAction:
                   │    - stores the submission under the site's formidable-results
                   │      (system session — see save-to-jcr.md)
                   ├─ fmdb:emailNotificationAction:
                   │    - subject + to normalized with FieldEscaper.headerSafe()
                   │    - HTML body uses FieldEscaper.html() for interpolated values
                   ├─ fmdb:emailContentAction:
                   │    - emails the submitted values; optionally attaches the validated
                   │      uploads (in memory), within the action's and the global caps
                   └─ fmdb:forwardAction:
                        - reads targetId from JCR node
                        - resolves URI via configService.resolveForwardTarget(targetId)
                          (targets defined in org.jahia.modules.formidable.cfg only)
                        - reconstructs multipart/form-data body with pre-parsed files
                        - POSTs to resolved URI

       └─ { "success": true, ...entries of the response enrichers } or { "success": false, "errorCode": "FMDB-XXX" }
  └─ show success or error message
```

### Response enrichers

Once every action succeeded, the servlet asks the `SubmissionResponseEnricher` OSGi services
(engine `api` package) for entries to add to the `200` body, next to `success`. Each receives an
`AcceptedSubmission` — the live form node, its site key, the locale and a snapshot of the validated
parameters (declared, non-file fields) — and answers a map of top-level keys. An enricher never
fails a submission: an exception is logged and its entries left out; the keys the servlet writes
(`success`, `errorCode`, `actionsCompleted`, `actionsTotal`, `messages`) cannot be overwritten; a rejected
submission is never enriched. The jExperience module contributes its `jexperience` block this way.
See [How to enrich the submission response](../extension/how-to-enrich-the-submission-response.md).

> **DoS mitigation (defence in depth):**
- Gate 0: cross-origin requests are rejected before the multipart pipeline starts
> - Step 3: oversized requests are rejected early when a `Content-Length` header is present — 0 byte read
> - Step 7: invalid CAPTCHA token → rejected before any file data is read
> - Step 8: field whitelist built before parsing — undeclared fields never touch memory or disk
> - Steps 4–8 are all O(1) or cheap JCR lookups; the network stream is only read at step 9

`guardContentLength` is an optimization, not the definitive size limit. When a client submits
the request with `Transfer-Encoding: chunked`, `getContentLengthLong()` returns `-1`, so step 3
cannot reject the request before the body is read. In that case the authoritative request-size
enforcement still happens at step 9, where `ServletFileUpload.setSizeMax(...)` aborts oversized
multipart bodies during streaming.

### Routing query params and security header

| Transport | Value | Set by |
|---|---|---|
| `fid` query param | JCR identifier (UUID) of the `fmdb:form` node | `default.server.tsx` |
| `lang` query param | BCP 47 language tag (e.g. `en`, `fr`) | `default.server.tsx` |
| `X-Formidable-Captcha-Token` header | CAPTCHA token — only when CAPTCHA protection is enabled on the form | `useFormSubmission.ts` at submit time |
| `X-Formidable-Logic-State` header | One base64 declaration of the provider state (JS variables, URL params, cookies) the browser evaluated its logic rules against — lets the server evaluate provider rules coherently | `useFormSubmission.ts` at submit time |
| `X-Formidable-Time-Zone` header | The browser's time zone (an IANA id such as `Europe/Paris`), stored on the submission when it names a zone the platform knows — the results tell where a typed date-time applies | `useFormSubmission.ts` at submit time |

No hidden `<input>` fields are injected into the form body for routing.
The hidden field the CAPTCHA widget injects (the provider's `captchaTokenField`, read from
the configuration) is deleted from `FormData` by the submit hook before submission — it is
never in the body, and the effective token is sent in the `X-Formidable-Captcha-Token` header.

Submissions are always processed against the `live` workspace.
Edit and preview modes disable the submit button server-side; any bypass attempt receives
a 400 (`FMDB-004`) because the node will not be found in `live`.

---

## Security mixins on `fmdb:form`

| Mixin | Effect |
|---|---|
| `fmdbmix:captcha` | Author-facing wrapper mixin on `fmdb:form`; enables the engine-owned `fmdbmix:captchaProtectedForm` semantic |
| `fmdbmix:requireAuthentication` | Author-facing wrapper mixin on `fmdb:form`; enables the engine-owned `fmdbmix:authenticatedOnlyForm` semantic |

Both mixins are applied via the Content Editor. Neither requires configuration in JCR properties.

Internally, the submission pipeline reads `fmdbmix:captchaProtectedForm` and
`fmdbmix:authenticatedOnlyForm`.

---

## Read-only maintenance mode

When the platform is switched to Read Only or Full Read Only, JCR writes are rejected at the
repository level. Formidable handles this natively for forms whose actions persist data:

- **Scoping is per action type.** An action type carrying the `fmdbmix:readOnlyCompatibleAction`
  mixin declares "I never write to the repository, I can run while the platform is read-only".
  The built-in email and forward actions carry it; `fmdb:save2jcrAction` does not. The polarity
  is deliberately inverted: an action type **without** the mixin — including any third-party
  action that never considered read-only mode — is presumed to write and blocks its form during
  maintenance. Worst case an unaffected form is over-blocked; a submission is never lost.
- **Render time (UX layer):** `default.server.tsx` reads the platform state (`SettingsBean`)
  and the form's action nodes. A repository-writing form in live renders as the maintenance
  message alone — the form markup is not emitted at all, no island is mounted — and the
  fragment gets a short cache TTL (60s) so the message does not outlive the maintenance
  window. Edit/preview rendering is unaffected.
- **Submit time (correctness boundary):** pipeline step 5 (`verifyPlatformWritable`) rejects
  the submission with `FMDB-014` (HTTP 503) before authentication, CAPTCHA, or any byte of the
  body is processed — and before any action side effect (no notification email for a submission
  that will never be saved). This covers cached fragments and the window where the mode is
  switched between render and submit.
- **Runtime safety net:** the mixin is a contract, not a proof. If an undeclared action still
  writes (lying or outdated third-party action, mode switched mid-request), the repository's
  own read-only rejection is detected in the failure cause chain and mapped to `FMDB-014`
  instead of the generic `FMDB-008`.

The maintenance message is a contributor property (`maintenanceMessage` on `fmdbmix:responses`,
**Response Messages** tab, after the error message): a localized rich text pre-filled from the
module resource bundle (`maintenanceMessage.default`), so contributors can reword it per form.
The client maps `FMDB-014` to the same message, never to the generic error banner — forms
created before the property existed fall back to the bundled text
(`fmdb_form.maintenanceUnavailable`). Forms whose actions all carry the mixin (e.g. email-only
forms) keep working normally during maintenance.

---

## Trust model

The submission pipeline resolves the target form node with the current user's `live` JCR session, so normal
read permissions on the form still apply at form-resolution time. Once the form has been resolved and validated,
the configured actions execute under a system session. This is intentional: Guest and low-privilege users must
still be able to trigger server-side effects such as sending emails or saving submissions. The trust boundary is
therefore the form configuration itself: contributors who can configure a form and its action list are treated as
trusted actors, because the runtime will execute those configured actions with elevated JCR privileges.

---

## CSRF and cross-origin protection

The single CSRF control on every submission — guest or authenticated — is the
`formidable-submit` Jahia Security Filter scope, auto-applied for `origin: hosted`:
requests that do not present a same-origin `Origin` or `Referer` never reach the
multipart pipeline.

**Jahia CSRFGuard does not protect this endpoint.** It briefly did (an early config
extended `urlPatterns` to the servlet path), but jahia-csrf-guard 4.3 started enforcing
the token on authenticated sessions and rejected every logged-in submission — the page
never carries a token for this XHR. The module therefore ships the opposite
configuration, and it is the one in force (#200):

```
META-INF/configurations/org.jahia.modules.jahiacsrfguard-formidable.cfg
  whitelist = /modules/formidable-engine/form-submit*
```

An authenticated submission thus carries no second, non-forgeable credential: the
same-origin gate is the whole CSRF posture, for guests and authenticated visitors alike.

### Protection matrix

| Form configuration | Protection that applies | Residual risk |
|---|---|---|
| Guest form without CAPTCHA | `formidable-submit` Security Filter only (`origin: hosted`) | Relies solely on the browser-supplied same-origin `Origin` / `Referer` signal enforced by the Security Filter |
| Guest form with `fmdbmix:captcha` | `formidable-submit` Security Filter + CAPTCHA token validation | The CAPTCHA token adds a second non-replayable credential tied to the hosting page |
| Authenticated form without CAPTCHA | `formidable-submit` Security Filter only — CSRFGuard whitelists this path | Same as the guest row: the origin signal is the only CSRF control |
| Authenticated form with `fmdbmix:captcha` | `formidable-submit` Security Filter + CAPTCHA token validation | CAPTCHA is defence in depth, not the primary CSRF control |

### Why CSRFGuard is not part of this posture

Guest pages must stay CDN-cacheable, so a per-session token cannot be injected into
cached HTML — guest traffic could never rely on CSRFGuard. For authenticated visitors,
CSRFGuard 4.3 enforced a token this XHR never carries and blocked every legitimate
submission, so the module whitelists the endpoint instead of shipping a protection that
only rejected honest use.

Forms using `fmdbmix:captcha` add another barrier: the wrapper enables the
engine-owned `fmdbmix:captchaProtectedForm` semantic, and the CAPTCHA token is a
non-replayable credential tied to the hosting page. This is defence in depth, not the
primary CSRF control.

---

## Field whitelist (step 8 → step 9)

`FormFieldMetadataCollector` walks the `fields` child node (`fmdb:fieldList`) of the
`fmdb:form`, recursing through the containers (`fmdb:step`, `fmdb:fieldset`), and builds:

- `Map<String, FieldInfo> fieldInfos` — one record per declared field: the JCR node type,
  the semantic flags (choice, file, email, date, datetime-local, color, number, boolean),
  the allowed choice values (plus a `choicesUnresolvable` flag when a source cannot
  deliver), the file accept types and the `FieldConstraints` (required, minLength,
  maxLength, pattern, minDate, maxDate, minNumber, maxNumber)
- `Map<String, List<ConditionalLogicRule>> fieldLogicRules` — the parsed logic rules, per
  field and per conditional container
- `Map<String, String> logicIdToFieldName` — resolves a rule's stored source reference to
  the source field's current name
- `Map<String, Set<String>> fieldParentContainers` — each field's (and nested conditional
  container's) direct conditional containers, which the server evaluator walks so a field
  inherits every enclosing verdict

The declared field names (the keys of `fieldInfos`) are the whitelist.

During `parseAll()`, any multipart item whose field name is absent from `allowedNames` is
**skipped without reading its content**. The Commons FileUpload iterator advances past
undeclared items automatically.

This prevents:
- Injection of arbitrary fields into forwarded data
- Exploitation of `${fieldName}` interpolation in email templates
- Storage or processing of data the form was not designed to collect

---

## Input validation (step 9)

`FormDataParser` validates every text field before it enters the pipeline:

Dispatch is on the semantic **mixins**, not on a list of node type names: a custom field
type carrying `fmdbmix:choiceField` gets the same control as the built-in ones.

| Field category | Control |
|---|---|
| Choice fields (`fmdbmix:choiceField` — select, radio, checkbox, …) | Value checked against the allowed set; rejected if not in it. Manual options are read from the site's default language; sourced/category/content options are **re-resolved live** at validation time, and a source that cannot deliver rejects the submission (FMDB-010) rather than accepting anything |
| Typed fields (`email`, `date`, `datetime-local`, `color` mixins) | Format validated with a strict regex |
| Number and boolean fields (`fmdbmix:numberField`, `fmdbmix:booleanField`) | Numeric parse with `minNumber`/`maxNumber` bounds; boolean literal check |
| All text fields | `FieldConstraints` applied: required, minLength, maxLength, pattern, minDate, maxDate, minNumber, maxNumber |

Required fields that are legitimately absent from the multipart body (e.g. unchecked
checkbox) are detected at step 11 (`validateRequired`) after parsing, rather than during
parsing.

Plain-text values are preserved as submitted. XSS protection is applied at each output sink
by escaping for the target context, not by mutating input during parsing.

`FormDataParser.ParseException` carries an explicit failure type:
- `VALIDATION` for submitted data rejected by business validation rules
- `TECHNICAL` for multipart parsing or stream-processing failures
- `CONFIGURATION` for invalid server-side validation metadata or form configuration

`FormSubmissionPipeline` maps these parser failures to opaque API codes:
- `VALIDATION` -> `FMDB-010`
- `TECHNICAL` -> `FMDB-007`
- `CONFIGURATION` -> `FMDB-500`

---

## Field actions (step 11b)

A field may carry actions of its own — checks of its value against something the browser cannot know,
an external provider or a business rule ([Field actions](field-actions.md)). The browser asks for them
while the form is being filled (`/modules/formidable-engine/field-action`); the pipeline runs the
**blocking** ones again here, after `validateRequired` and before any form action, on every non-blank
value of the fields the visitor answered and the logic shows. The dispatcher is the same as the endpoint's,
the verdict cache too,
so the honest browser's second run costs no second provider call. The first refusal ends the submission
with `FMDB-015` and a `messages` array the browser anchors on the field; `messages` is a key of the
servlet, reserved from the response enrichers like `success` and `errorCode`.

---

## Output escaping (step 12 — email actions)

`FieldEscaper` centralises output escaping:

| Method | Context |
|---|---|
| `html(value)` | Escapes a value for safe insertion into HTML element content — used in HTML email body |
| `headerSafe(value)` | Strips `\r`, `\n`, `\t` and trims — applied to `to` and `subject` headers |
| `plainText(value)` | Null-safe passthrough — used in plain-text email body |

---

## File upload security (FormDataParser.parseAll)

All file parts pass through `FormDataParser` which enforces the following controls in order:

| # | Control | Implementation |
|---|---|---|
| 1 | Field whitelist | Undeclared fields skipped before any read |
| 2 | Per-file size limit | `upload.setFileSizeMax()` |
| 3 | Total request size limit | `upload.setSizeMax()` |
| 4 | File part count limit (CVE-2023-24998) | `upload.setFileCountMax(config.getUploadMaxFileCount())` — requires commons-fileupload ≥ 1.5 |
| 5 | Filename sanitisation | Filename normalized with Jahia's standard JCR node-name escaping rules; blank results fall back to `upload` |
| 6 | MIME type detection | Apache Tika filename-aware detection via `Tika.detect(byte[], String)` (ignores client-supplied `Content-Type`, but uses the original filename extension to disambiguate ambiguous formats) |
| 7 | MIME type allowlist | Field-level `accept` property (multiple choicelist) takes priority; falls back to global cfg allowlist. Rejections at this step are treated as validation failures (`FMDB-010`), not technical parse failures |

Limits and the global allowlist are configured in `org.jahia.modules.formidable.cfg` via `FormidableConfig`.
The module ships that file (`META-INF/configurations/org.jahia.modules.formidable.cfg`, every
setting at its default): Jahia copies it to `digital-factory-data/karaf/etc` the first time the
module starts without it, and never overwrites the copy afterwards (its first line is the
`# default configuration` marker the extender looks for), so edits made in the file or through the
provisioning API (`editConfiguration`) are kept. Avoid the Felix Web Console for this PID: it
rewrites the file in a typed syntax (`L"5"`, quoted strings) that a `.cfg` file does not read back.
Upgrading an installation configured before the file existed (0.4 and earlier) through the
Felix console or directly in ConfigAdmin — not through the provisioning API, which writes the
`karaf/etc` file itself, so that configuration came from a file from the start and keeps it: that
first copy would reset the configuration to the defaults, so the engine spots the switch (the
configuration gains its `felix.fileinstall.filename`) and writes the previous settings back into
the file, as strings. Until that write comes back through the file, the settings in force stay
the previous ones (no window on the defaults); the write is kept pending while it fails
(ConfigurationAdmin bound later, a failed update) — three attempts, after which the file's values
are declared in force and an error names the settings to re-enter, rather than keeping every
later edit of the file from applying; a retry leaves alone any setting edited before it (an edit
landing in the very instant of the write would be overwritten — the configuration's change
counter tells, and a warning says so), and the logs name the settings, never their values.
Limit: fileinstall loads the copied file one to two seconds after the module is resolved; the
previous settings are only seen when the engine's components activate before that — usual on a
running server, not guaranteed, and never after an upgrade done while Jahia was stopped. A miss
looks exactly like a fresh install to the engine, so a first activation that already comes from a
file created or changed moments before is reported with a warning pointing at it. Check the engine's log for
"carried over into the file" after such an upgrade, and see
[docs/administration/upgrade-notes.md](../administration/upgrade-notes.md) for who is affected and how to check.

### Why the parser uses `Tika.detect(byte[], String)`

`FormDataParser` intentionally uses filename-aware Tika detection:

- `Tika.detect(byte[], String)`

instead of content-only detection:

- `Tika.detect(byte[])`

Some formats are ambiguous when Tika only sees raw bytes. The original filename
extension gives Tika enough context to classify the file more accurately.

Typical examples observed in this codebase:

- `sample.csv`
  - `detect(byte[])` can fall back to `text/plain`
  - `detect(byte[], "sample.csv")` resolves cleanly to `text/csv`
- `cats.mkv`
  - content-only detection can return a generic Matroska/container type
  - filename-aware detection aligns better with the expected `video/x-matroska`
- `webm` and similar media/container formats
  - content-only detection can be too generic for allowlist validation
  - filename-aware detection is usually closer to the MIME authors expect

Using `detect(byte[], String)` keeps the server-side allowlist logic simpler:

- no CSV-specific fallback from `text/plain` to `text/csv`
- fewer ad hoc aliases for ambiguous container formats
- better alignment between:
  - author-configured `accept` values
  - browser-selected files
  - server-side MIME validation

This is less strict than pure content-only detection because the filename
participates in classification. That tradeoff is accepted here because the
allowlist is meant to provide predictable validation for legitimate user uploads,
not only maximal resistance to ambiguous-but-benign file formats.

If stricter hardening is needed later, the alternative would be to compare both:

1. `detect(byte[])`
2. `detect(byte[], String)`

and only accept curated divergences explicitly.

### Uploaded file lifecycle and temporary-file handling

`FormDataParser` does **not** create temporary files on disk for uploaded parts, and therefore
does not perform any explicit temp-file cleanup after processing.

More precisely:

- It uses the **streaming API** from Commons FileUpload: `ServletFileUpload#getItemIterator(req)`.
- For each file part, it opens the part stream and reads it into a `ByteArrayOutputStream`.
- It then stores the uploaded content in the `FormFile` record as `byte[] data`, not as a `File`
  handle or a disk-backed `FileItem`.

As a consequence:

- there is **no `DiskFileItemFactory`** in this flow
- there is **no spool-to-`/tmp` step** performed by `FormDataParser`
- there is **nothing to delete on disk** from `FormDataParser` itself

After parsing, the validated uploads remain in memory as `List<FormFile>` and are converted
to the SPI-level `List<SubmittedFile>` passed to each `FormAction`.

Actions that need uploaded files reuse that in-memory list:

- `SendEmailContentFormAction` reads the submitted files list and, when enabled, attaches the
  in-memory `byte[]` payloads to the outgoing email
- `ForwardSubmissionFormAction` reads the same submitted files list and rebuilds a new
  `multipart/form-data` request body in memory before POSTing it to the configured target

Those actions do not manipulate temporary files on disk either.

Because upload payloads stay in memory for the lifetime of the submission, operators should
cap concurrent access to the submit endpoint at the reverse-proxy or ingress layer so worst-case
heap pressure remains bounded under large-file workloads.

> **Operational note:** if temporary upload files are observed under the servlet container's
> temp directory, they are not created by `FormDataParser` itself. They would have to come from
> another layer such as the servlet container, an upstream filter, or a different multipart
> handling path.

---

## CAPTCHA

| Condition | Behaviour |
|---|---|
| `fmdbmix:captcha` mixin present on the form | Wrapper resolves to `fmdbmix:captchaProtectedForm`; token verified at step 7 before any file data is read |
| `fmdbmix:captcha` mixin absent | No CAPTCHA semantic on the form; pipeline continues |

CAPTCHA configuration is read from `org.jahia.modules.formidable.cfg` — not stored in JCR:
`captchaSiteKey`, `captchaSecretKey`, `captchaScriptUrl`, `captchaVerifyUrl`, the two
provider-specific names `captchaWidgetVar` (the global object the provider script exposes) and
`captchaTokenField` (the hidden field the widget injects), and the timeouts
`captchaHttpConnectTimeoutSeconds` / `captchaHttpRequestTimeoutSeconds` — the connect one doubles
as the client's wait budget for the widget API to appear. `CaptchaRenderFilter` hands the site key,
the script URL, the two provider names and that budget to the form view as request attributes, so
the client never hardcodes a provider.

The CAPTCHA widget injects a hidden field into the DOM, but the submit hook removes it
(`formData.delete(tokenField)`) before submission and sends the token through the
`X-Formidable-Captcha-Token` header instead. The token never appears in the request body.
The per-provider field names and verification endpoints are listed in
[captcha-server-side-validation.md](../administration/captcha-server-side-validation.md).

---

## Action nodes (`fmdb:actionList`)

Every `fmdb:form` has an autocreated `actions` child node of type `fmdb:actionList`. The
contributor adds action nodes inside via the Content Editor, or from the actions zone the
Page Builder shows under a form placed on a page (edit mode only: the zone lists the actions
in their execution order, warns when there is none, and carries the list's **New Form Action**
button — see `docs/styling/README.md`, "Form actions zone"). Actions are executed in the order they
appear in the list.

| Node type | Description |
|---|---|
| `fmdb:save2jcrAction` | Saves form data as JCR child nodes under `formidable-results` (see `docs/architecture/save-to-jcr.md`) |
| `fmdb:emailNotificationAction` | Sends a notification email via Jahia `MailService`; `${fieldName}` interpolation in subject and body; headers normalized and HTML body escaped |
| `fmdb:emailContentAction` | Sends the submitted form content by email; may optionally attach validated uploaded files |
| `fmdb:forwardAction` | Forwards declared form fields + pre-parsed files to a target endpoint resolved from config by ID |

### forwardAction — target registry

The target URL is never stored in JCR. The contributor picks a `targetId` from a choicelist
populated by `FormidableForwardTargetsInitializer`. The available targets are defined by an
administrator in `org.jahia.modules.formidable.cfg`:

```
forwardTargets=salesforce-prod|Salesforce Prod|https://api.salesforce.com/services/\n\
crm-staging|CRM Staging|https://crm.internal/hook
```

(Entries are separated by newlines *inside the value*: in the `.cfg` properties format that
is an escaped `\n` plus a `\` line continuation, as above — two plain lines would leave
only the first target configured.)

Optional development-only targets can be enabled explicitly:

```
enableDevForwardTargets=true
devForwardTargets=local-api|Local API|http://localhost:3000/hook\n\
docker-api|Docker API|http://host.docker.internal:8080/hook
```

`devForwardTargets` only accepts plain HTTP on `localhost` and `host.docker.internal`.

`FormidableConfigService.resolveForwardTarget(targetId)` returns an `Optional`, empty for an
unknown ID — the forward action then fails the submission as a configuration error. This
design prevents SSRF: contributors can only reach pre-approved endpoints.

To add a new action type, see `AGENTS.md` → *Form action pipeline*.

---

## Error responses

Failed submissions return `{ "success": false, "errorCode": "FMDB-XXX" }`, plus
`actionsCompleted` / `actionsTotal` when the failure happened inside the action pipeline —
the client shows that progress under the error message.
Detailed reasons are written to server logs only. See `docs/administration/error-codes.md` for the full glossary.

---

## Edit / Preview mode

`default.server.tsx` passes `isSubmitDisabled = true` when
`renderContext.isEditMode() || renderContext.isPreviewMode()`. The submit button is disabled
and shows a tooltip. No request is sent to the servlet.

---

## CSRF

Form submissions use `XMLHttpRequest` (not `fetch`) because Jahia's OWASP CSRFGuard
integrates with `XMLHttpRequest` where it applies. It does not apply here: the module
whitelists `/modules/formidable-engine/form-submit*` in its CSRFGuard configuration, and
the same-origin `formidable-submit` Security Filter is the CSRF control for this endpoint
(see *CSRF and cross-origin protection* above).

---

## Key files

| File | Role |
|---|---|
| `src/hooks/useFormSubmission.ts` | `handleSubmit` — removes the CAPTCHA widget field from `FormData`, sets `X-Formidable-Captcha-Token`, POSTs via XHR |
| `src/components/Form/default.server.tsx` | Builds `submitActionUrl` with `fid` and `lang` query params |
| `formidable-engine/.../servlet/FormSubmitServlet.java` | OSGi entry point — checks `formidable-submit` permission, then delegates to `FormSubmissionPipeline` |
| `formidable-engine/.../servlet/FormSubmissionPipeline.java` | The pipeline, steps 1 to 12 plus 11b (field actions) — all submission logic |
| `formidable-engine/.../servlet/ErrorCode.java` | Error code enum — see `docs/administration/error-codes.md` |
| `formidable-engine/.../servlet/FormDataParser.java` | Secure multipart parser: whitelist, input validation, Tika, allowlist, size + count limits |
| `formidable-engine/.../actions/common/FieldEscaper.java` | Output escaping utility: `html`, `headerSafe`, `plainText` |
| `formidable-engine/.../actions/form/forward/ForwardSubmissionFormAction.java` | Resolves `targetId` via `FormidableConfigService`; forwards declared fields only |
| `formidable-engine/.../actions/form/email/SendEmailNotificationFormAction.java` | Sends notification email; headers normalized with `headerSafe()`; HTML body escapes values with `html()` |
| `formidable-engine/.../actions/form/email/SendEmailContentFormAction.java` | Sends the submitted form content by email; can optionally attach validated uploaded files, capped by action-level and global upload limits |
| `formidable-engine/.../api/FormAction.java` | Interface implemented by each action type |
| `formidable-engine/.../config/FormidableConfigService.java` | Reads unified cfg; resolves forward targets by ID; verifies CAPTCHA
