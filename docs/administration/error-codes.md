# Formidable Error Codes

When a form submission fails, the servlet returns a JSON body with an opaque error code:

```json
{ "success": false, "errorCode": "FMDB-006" }
```

When an action in the pipeline fails (`FMDB-008`), the response also includes action progress:

```json
{ "success": false, "errorCode": "FMDB-008", "actionsCompleted": 1, "actionsTotal": 3 }
```

- `actionsCompleted` — number of actions that finished successfully before the failure
- `actionsTotal` — total number of actions in the pipeline

When a field action refuses a value (`FMDB-015`), the response carries what the visitor is told, anchored on the field:

```json
{ "success": false, "errorCode": "FMDB-015",
  "messages": [ { "level": "error", "html": "We do not know this address", "field": "email" } ] }
```

- `level` — `error` for a refusal that blocks; the pre-check endpoint also answers `warning` for an action set to warn only
- `html` — the contributor's message, interpolated and escaped server-side, ready to render
- `field` — the node name of the refused field, what the page anchors the message on
- nothing else: the action node behind the message and its type stay in the server logs

The same array is the `messages` of the pre-check endpoint's answer, next to its `verdict` (`accept`, `advice`, `reject`).

Detailed reasons are written to server logs only and are never exposed to the caller.

## Glossary

| Code | HTTP status | Condition |
|---|---|---|
| `FMDB-001` | 415 | `Content-Type` is not `multipart/form-data` |
| `FMDB-002` | 400 | Required URL parameter `fid` is missing, blank, or not a valid UUID — or the `lang` parameter is not a valid language tag |
| `FMDB-003` | 413 | `Content-Length` exceeds `uploadMaxRequestSizeBytes` from `org.jahia.modules.formidable.cfg` — or, on the field-action pre-check, the body or the `value` exceeds `fieldActionMaxValueLength` |
| `FMDB-004` | 400 | Form node not found in the `live` workspace (invalid `fid`, form not published, or a form the caller cannot read). On the field-action pre-check (404): no such form for this visitor, or no field of that name carrying field actions |
| `FMDB-005` | 500 | CAPTCHA is required on the form but server-side verification is not fully configured (`captchaSiteKey` / `captchaSecretKey` / `captchaVerifyUrl` missing in `org.jahia.modules.formidable.cfg`) |
| `FMDB-006` | 400 | CAPTCHA token (`X-Formidable-Captcha-Token` header) is absent, expired, or rejected by the provider |
| `FMDB-007` | 400 | Multipart parsing failed for a technical reason — possible causes: per-file size limit, total request size limit, file count limit, or low-level stream parsing/read failure |
| `FMDB-008` | the status the action chose (400/500 from the helpers, 403/502 from the forward action…), 422 when the failure carried none | An action in the pipeline failed (e.g. forward target returned non-2xx, email could not be sent) |
| `FMDB-009` | 401 | Authentication required — the form carries `fmdbmix:authenticatedOnlyForm` and the current user is Guest. The field-action pre-check answers it too, for the same forms |
| `FMDB-010` | 400 | Submitted data failed validation — possible causes: choice value not in the allowed set, invalid text format (email, date, datetime-local, color), field constraint violation, or uploaded file MIME type rejected by the field/global allowlist |
| `FMDB-011` | 403 | Submission denied by the Jahia Security Filter check (for example, cross-origin request with no matching hosted `Origin` / `Referer`) |
| `FMDB-012` | 500 | Action list resolution failed — the pipeline could not read the configured `actions` node from the repository |
| `FMDB-013` | 400 | Conditional-logic coherence violation — the submission carries a value for a field the server can prove was hidden (from submitted values, or from the provider state the browser itself declared in the `X-Formidable-Logic-State` header). An honest browser never produces this: a hidden field's controls are disabled and not submitted |
| `FMDB-015` | 422 | A field action refused a value — the check of one field run server-side at submission (pipeline step 11b). The body carries a `messages` array naming the field, for a page to anchor the contributor's message on it (see below). No client reads it yet: the browser side is a later stage |
| `FMDB-016` | 429 | The field-action pre-check endpoint (`/modules/formidable-engine/field-action`) refused the call: this client address exceeded `fieldActionRateLimitPerMinute` |
| `FMDB-017` | 422 | One field carrying blocking field actions holds more answers to judge than `fieldActionMaxValuesPerField` — repeats of one answer count once. Each answer may cost a provider call, so the submission is refused before any action runs. The body carries a `messages` array naming the field, as `FMDB-015` does, and no client reads it yet |
| `FMDB-014` | 503 | Platform is in read-only maintenance mode and the form has at least one action presumed to write to the repository (its node type does not carry `fmdbmix:readOnlyCompatibleAction`). Also returned when an action execution hits the repository's own read-only rejection. The client shows a maintenance message instead of a technical error |
| `FMDB-500` | 500 | Unexpected internal, configuration, or provider/infrastructure verification error — check server logs (for example, invalid server-side validation metadata such as a malformed regex constraint, or a technical CAPTCHA verification failure) |

## Source

Error codes are defined in `formidable-engine/.../servlet/ErrorCode.java`.
