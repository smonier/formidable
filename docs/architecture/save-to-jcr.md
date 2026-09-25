# Save To JCR

This document describes how the `fmdb:save2jcrAction` server-side action stores Formidable submissions in JCR.

## Overview

`SaveToJcrFormAction` is a `FormAction` OSGi service bound to the JCR node type:

- `fmdb:save2jcrAction`

When a form submission reaches the server-side pipeline, this action persists:

- submission metadata
- submitted field values
- uploaded files

under a site-level results tree.

## Storage Tree

The action stores data under:

```text
/sites/<site>/formidable-results/<form-results>/submissions/YYYY/MM/DD/<submission>
```

Example:

```text
/sites/industrial/formidable-results/form-actions/submissions/2026/05/05/submission-20260505-150707-a9e
```

The node types are:

- `fmdb:resultsFolder`
- `fmdb:formResults`
- `fmdb:submissions`
- `fmdb:splittedSubmission`
- `fmdb:formSubmission`

## One Results Folder Per Form

Each form has one `fmdb:formResults` node.

Identity is resolved through the `parentForm` weak reference stored on the `fmdb:formResults` node:

- if a matching `parentForm` already exists, that node is reused
- if no matching node exists, a new one is created

This gives two properties:

- stable identity across form renames
- a stable, technical identity independent of the folder name

The `fmdb:formResults` node name is not contractual. It is a historical folder name kept for operator readability, but functional identity is always resolved through `parentForm`.

## Submission Node Naming

Each submission node is created with a readable name:

```text
submission-YYYYMMdd-HHmmss-XXX
```

Example:

```text
submission-20260505-150707-a9e
```

Where:

- `YYYYMMdd-HHmmss` is a UTC timestamp generated server-side
- `XXX` is a short UUID-derived suffix

UTC is used intentionally for this technical node name so submission identifiers remain consistent across server JVM timezones. The authoritative persisted creation time is still `jcr:created`.

If a collision still happens, `JCRContentUtils.findAvailableNodeName(...)` adds Jahia's standard numeric suffix.

## Auto-Splitting

The `submissions` child node is configured for Jahia auto-splitting with:

```text
date,jcr:created,yyyy;date,jcr:created,MM;date,jcr:created,dd
```

This means the submission is first created under `submissions`, then moved into:

- year folder
- month folder
- day folder

using `JCRAutoSplitUtils.applyAutoSplitRules(...)`.

The content definition also allows direct `fmdb:formSubmission` children under `fmdb:submissions` as a fallback, so persistence is not blocked if splitting cannot happen.

## Important Implementation Detail

After auto-splitting, the submission node may have moved to another path.

Because of that, the implementation always reloads the node by identifier before writing the form data and files. This avoids using a stale JCR wrapper that still points to the pre-move path.

## Data Layout Inside One Submission

Each `fmdb:formSubmission` contains:

- metadata properties on the submission node itself
- a `data` child node for field values
- a `files` child node for uploaded files

### Metadata Properties

Typical submission-level properties:

- `origin` — always the constant `formidable` for submissions stored by this action
  (the property is mandatory on `fmdb:formSubmission`): a provenance discriminator, so
  an external writer of submission nodes (a legacy-forms import, a REST integration, a
  third-party action) stamps its own value and the results panel and exports can tell
  the sources apart
- `locale`
- `referer`
- `timeZone` — the submitter's time zone as the form client declares it (the browser's zone,
  an IANA id such as `Europe/Paris`), kept only when it is a zone the platform knows; absent
  for a submission posted outside a browser, and when the browser named a zone this platform's
  JVM does not know yet (logged at debug level). The results show it with the metadata and
  after each date-time value. The zone is the submitter's — where the text was typed — not a
  property of the value: a date-time meant for a fixed remote place still shows the submitter's
  zone

The per-form results container (`formidable-results/<form>`) is created on the first
submission with a collide-and-recover guard: the deterministic node name makes two
concurrent first submissions collide instead of silently creating two containers, and
the loser re-reads and reuses the winner's.

For newly stored submissions, `SaveToJcrFormAction` does not persist `ipAddress`,
`submitterUsername`, or `userAgent`. Older submissions may still contain those legacy
properties if they were created before that change.

### Personal Data

At submission level, the request-derived metadata still persisted by default is:

- `referer` — the HTTP `Referer` header, when present
- `timeZone` — the browser's time zone from the `X-Formidable-Time-Zone` header, when it names
  a zone the platform knows

The referer may contain personal data or contextual URL information depending on the site
and browser behavior; it is stored to help operators understand where a submission came from
in the site flow and to support operational troubleshooting. The time zone is coarse location
data (one of some 600 zones), and beside the locale and the referer it narrows a submitter
further than either does alone; it is stored because a date-time typed in a form cannot be
read in another zone without it. Submitted field
values and uploaded files may of course also contain personal data depending on the form
design; those are persisted because `fmdb:save2jcrAction` is explicitly a storage action.

### Field Values

Field values are stored as JCR properties on the `data` child node (`fmdb:submissionData`):

```text
<submission>/data
```

The `fmdb:submissionData` type uses residual property definitions with `indexed=no`:

```
- * (string) indexed=no
- * (string) multiple indexed=no
```

This ensures submitted form data (names, emails, addresses) never enters the Lucene search index — avoiding index bloat, improving re-indexing performance, and keeping personal data out of search results. The dashboard queries submissions by `jcr:created` on `fmdb:formSubmission`, then navigates to `data` to read properties directly.

Rules:

- single-value fields become single JCR properties
- multi-value fields become multi-valued JCR properties

#### Field Labels in the Dashboard

Field labels (human-readable names like "Email Address" instead of `email`) are resolved at read time from the form node, not stored in the submission data.

The dashboard uses a GraphQL query (`GET_FORM_FIELD_LABELS`) to read `displayName` (i.e. `jcr:title`) of each field from the `parentForm` in the dashboard user's UI language (`uilang`). This provides consistent, locale-aware labels across all submissions regardless of which language the visitor submitted in.

If the form has been deleted (the `parentForm` weakreference no longer resolves), the dashboard falls back to the raw JCR field name.

### Uploaded Files

Uploaded files are stored under:

```text
<submission>/files/<fieldName>/<fileName>/jcr:content
```

The file binary is stored in `jcr:data`, with:

- `jcr:mimeType`
- `jcr:lastModified`

The storage file name is based on the parser-generated safe storage name, then normalized through `findAvailableNodeName(...)`.

## Execution Flow

At runtime, the action performs the following steps:

1. Resolve the parent form from the action node.
2. Read validated uploaded files from the `files` argument passed by the submission pipeline.
3. Resolve or create `/sites/<site>/formidable-results`.
4. Resolve or create the form-specific `fmdb:formResults`.
5. Ensure auto-splitting is enabled on `submissions`.
6. Create the `fmdb:formSubmission` node.
7. Save the session.
8. Apply Jahia auto-splitting.
9. Save the session again.
10. Reload the moved submission node by identifier.
11. Write submitted field values into `data`.
12. Write uploaded files into `files`.
13. Save the session.

## Notes

- The `files` node is not autocreated — it is only added when uploaded files are present. This avoids an empty `jnt:folder` on every text-only submission.
- Field values use residual properties on a single `data` node rather than one child node per field. A 20-field form produces 1 node with 20 properties, not 20 child nodes. At 10,000 submissions this avoids 190,000 unnecessary nodes.
- Submissions are written directly to the `live` workspace using a system session. There is no publish step.
- Folder names are intended to stay readable for operators, but they are not a functional identifier.
- `parentForm` is the real form identity key.
- Renaming a form does not create a second logical results folder; the existing folder is reused as-is.
- Field labels are resolved at read time from the form node via GraphQL, not stored in the submission data. If the form is deleted, the dashboard falls back to the raw field name.
