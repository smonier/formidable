# Conditional Logic Field Resolution

## Purpose

Describe the persistence and resolution model currently used by Formidable conditional logic.

The current model keeps two representations in sync:

- the authoring payload stored in the multivalue `logics` property
- the repository-side dependency index stored under `logicsSrc`

## Field identity: `fieldKey`

Every form element carrying `fmdbmix:formLogicElement` (fields, fieldsets, steps) owns a
`fieldKey` property: a random UUID string that is its **stable business identity**.

- unlike the JCR UUID, it survives import and copy
- unlike the node name, it survives renames
- unlike the visible label, it is unique within a form

It is hidden from contributors and assigned server-side:

- `FieldKeyAssignmentListener` assigns it when the element is created
- `FormLogicSyncService` assigns it on the fly to legacy elements it touches (sync target,
  resolved rule sources, and every logic element visited during duplication cleanup)

## Stored rule format

Each `logics` entry is stored as JSON.

Example:

```json
{
  "logicId": "a1b2c3d4",
  "sourceNodeId": "4028c1e2-934f-2f92-0193-4f6ac4f00041",
  "sourceFieldKey": "550e8400-e29b-41d4-a716-446655440000",
  "sourceFieldName": "role",
  "sourceFieldType": "fmdb:select",
  "valueKind": "choice",
  "operator": "notIn",
  "values": ["marketing", "sales"]
}
```

Meaning:

- `logicId` identifies the rule and matches the child node name under `logicsSrc`
- `sourceFieldKey` is the **business reference** to the source field (its `fieldKey`);
  primary resolution criterion
- `sourceNodeId` is the technical source identifier (JCR UUID); fast path and tie-breaker
- `sourceFieldName` and `sourceFieldType` remain editor/runtime metadata and legacy fallback
- `valueKind` (`choice`, `text`, `number`, `boolean`, `date`) is stamped by the editor from
  the source field and picks the comparison semantics on BOTH evaluators where an operator
  is shared across kinds: `between` compares numerically for the `number` kind and as
  strings otherwise, and only the `date` kind gives the `today` sentinel its meaning — a
  rule stored without it compares `today` as a literal string and `"9" > "10"`

### Date rules relative to the submission day

A date rule (`before`, `after`, `on`, `between` on a `date` value kind) may compare against
the **submission day** instead of a fixed date: the stored `value` — or either entry of
`values` for `between` — is then the sentinel string `today`, which the editor writes when
the contributor checks the **Submission day** toggle next to the date input. The sentinel
is unambiguous because a date input can never produce that literal, and it only applies to
date comparisons — a text rule comparing against the string `today` keeps comparing the
literal.

Each evaluator resolves the sentinel at evaluation time: the browser uses the visitor's
local calendar day on every re-evaluation, and the server uses the day the submission
declared (see the logic state header below). The stored rule always keeps the sentinel,
never a resolved date.

## Sources outside the form: providers

A rule may designate something other than a previous field. `sourceType` names the provider,
and the rule carries that provider's single config key instead of any source-field key:

| `sourceType` | Config key | Designates |
|---|---|---|
| absent, or `field` | — (uses `sourceFieldKey` & co.) | another field of the same form |
| `jsVariable` | `variable` | a dotted `window.*` path; segments may be numeric array indexes, e.g. `dataLayer.0.event` |
| `urlParam` | `param` | a query-string parameter of the page URL |
| `cookie` | `cookie` | a cookie readable from JavaScript |

```json
{
  "logicId": "e5f6a7b8",
  "sourceType": "urlParam",
  "param": "utm_campaign",
  "operator": "equals",
  "value": "spring-sale"
}
```

Every provider state is a single optional string, so all of them offer the same operators:
`exists`, `notExists`, `equals`, `notEquals`, `contains`.

A rule that cannot be evaluated — a `sourceType` this module version does not ship, a
missing reference, an unknown operator — fails closed everywhere: the target field stays
hidden, its wrapper carries `data-fmdb-logic-unresolved` naming the reason, and a console
warning is emitted once per reason. An unevaluable rule therefore never silently behaves
like an evaluated one.

### Server-side consequence

Provider state lives in the browser, and the submitted field values do not carry it. At
submit time the browser therefore **declares** the provider state it saw — the current
value (or absence) of every reference the form's rules read — in the
`X-Formidable-Logic-State` header (base64-encoded JSON, the same transport pattern as the
captcha token). The server evaluates provider rules against that single declaration, so
every rule reading the same reference gets the same answer.

The decoded payload is versioned JSON: `v` names the schema version (currently `1`), and
`providers` maps each provider source type to the references it read — the declared value
as a string, or `null` for "read and absent" (distinct from an empty string). Only the
references actually used by the form's rules are declared, never the whole browser state.
When a rule compares a date against the submission day, the visitor's local calendar day
rides along as `today`:

```json
{
  "v": 1,
  "providers": {
    "cookie":   {"consent-marketing": "yes"},
    "urlParam": {"promo": null}
  },
  "today": "2026-08-20"
}
```

The declared day is only accepted when it is a day it currently **is somewhere on
Earth** — the window from the westmost inhabited offset to the eastmost (UTC-12 to
UTC+14, the same widening the date bounds use), derived from the evaluation instant
rather than from the server's own calendar day; both evaluators then resolve `today`
to that same agreed day, and date-vs-today verdicts stay exact measurements. A day
outside the window is ignored like a missing declaration: the server then knows the
visitor's day only up to that window, so it evaluates each date-vs-today rule against
every day the window allows (two or three), and a verdict that flips inside the window
degrades to the fail-safe (hidden, required skipped, nothing acted upon) instead of
ever rejecting a value the visitor's own picker legitimately allowed.

A `between` rule mixing the submission day with a fixed date can be **emptied by time
alone**: `[today → fixed]` once the fixed date is over, `[fixed → today]` until it is
reached. Such an interval matches nothing by construction, so both evaluators **ignore
the rule** (it counts as satisfied) instead of hiding its field forever; near the flip,
the ambiguity window makes the verdict a fail-safe like any day-dependent one. The rule
editor steers away from authoring these (each calendar is bounded by the other side,
equality included since both bounds are) and warns when a stored rule currently matches
no date.

Deployment note: the sentinel travels in the ordinary rule value, so a runtime that
predates it compares the string `today` literally (a `before` rule would then always
hold). Both modules ship in the same release — upgrade them together before authoring
submission-day rules, and do not author such rules while an older formidable-elements
still serves the forms.

A declaration the server cannot interpret — unreadable base64, malformed JSON, a version
other than `1` — is treated as no declaration at all (the fail-safe below), never as an
error: a generation mismatch between a deployed client and server must degrade to the safe
behaviour, not to a wrong reading.

With a declaration, a provider-gated field behaves like a field-gated one: visible fields
have their **required validation enforced**, and a value submitted for a field the
declaration hides is **rejected** (`FMDB-013`, see below). Without a declaration — an older
client, a direct HTTP call — the historical fail-safe applies: the rule counts as not
satisfied, the field as hidden, its required validation is skipped and submitted values are
kept. In practice: *a required field shown only by a provider rule is enforced for browsers
that declare, and optional for clients that do not.* Use a field source when required-ness
must hold against any client.

### Coherence check: values for provably hidden fields are rejected

A field hidden in the browser has its controls disabled, and disabled controls are not
submitted. So when the server can **prove** a field was hidden — from submitted values for
field-sourced rules, or from the submission's own declaration for provider rules — a value
for that field cannot come from an honest browser. Such a submission is rejected with
`FMDB-013` instead of storing the value.

This is a coherence check, not enforcement: the declaration is forgeable, and a client that
declares nothing simply keeps the fail-safe above. What it guarantees is that one single
declared state backs every rule reading it (complementary conditions can no longer both
fail), and that a value smuggled into a provably hidden field is detected instead of
reaching the stored submission, the results screen, exports and form actions.

### A field that becomes hidden loses what was typed in it

Hiding a field disables every control inside it, and a disabled control is not submitted. So if
a condition turns false *after* the visitor filled the field, the value they typed is dropped
silently — it is not stored, and no error is shown.

This is intended for field sources: a field the visitor can no longer see must not contribute a
value. It is worth knowing for providers, because their state moves without any visitor action —
a consent cookie revoked in another tab, a datalayer entry replaced by a client-side route
change — so the case is reachable without anyone doing anything wrong. Prefer conditions on
state that is stable for the lifetime of the page, and avoid gating an already-filled field on
something that can flip late.

### Asking for a re-evaluation

A JS variable is sampled, because a plain object gives no change notification. Anything that
changes provider state can instead ask for an immediate re-evaluation by dispatching a
`fmdb:logic-invalidate` event — after pushing to a datalayer, after a consent banner is
answered, after a client-side route change. It is listened for on the document, so
dispatching it there reaches every form on the page:

```js
document.dispatchEvent(new Event("fmdb:logic-invalidate", {bubbles: true}));
```

Dispatching it on an element inside the page works too, but only with `bubbles: true` —
the event has to bubble up to the document.

Note that only **leaf** values are watched reliably: a variable pointing at an object
stringifies identically whatever changes inside it, so mutating the object without replacing
the watched value produces no re-evaluation. Dispatch the event in that case.

## Repository-side structure

For each target field carrying `fmdbmix:formLogicElement`, the repository may also contain:

```text
TARGET element (the fieldset that shows/hides)
  ├─ fieldKey = "..."
  ├─ logics = ["{ \"logicId\": \"a1b2c3d4\", \"sourceFieldKey\": \"550e...\", ... }"]
  └─ logicsSrc (fmdb:logicList)                          (technical storage)
       └─ a1b2c3d4 (fmdb:logicSrc)                       ← node name = logicId
            └─ logicNodeSource ────weakreference────▶ SOURCE element (the select)
                                                        └─ fieldKey = "550e..."
```

Intent:

- `logics` remains the authoring format
- `logicsSrc` is technical storage
- `logicNodeSource` is the repository-native weakreference used by server-side metadata collection and runtime enrichment

Mind the two distinct identities in that picture:

- **`logicId` identifies the rule.** Its only job is pairing the JSON entry of the
  `logics` array with its storage twin `logicsSrc/<logicId>` — a link internal to the
  target element, whose two sides travel together in any export or copy. It is never
  what breaks.
- **`fieldKey` identifies the source field.** It is the pointer that survives what
  kills the other source references: an import regenerates every JCR UUID (so
  `sourceNodeId` and the `logicNodeSource` weakreference both die), a rename changes
  the node name behind `sourceFieldName`, and homonym fields make a name ambiguous.
  `logicId` cannot help there — it only leads back to the rule's own storage node,
  whose weakreference is just as dead.

In short: `logicId` answers "which rule is this?", `sourceFieldKey` answers "which
field does this rule listen to?".

## Resolution order (at save time)

This resolution belongs to `FormLogicSourceResolver` and runs when a form is SAVED (the
sync listeners): it is what keeps the stored JSON pointing at the right field across
renames and duplications. Neither runtime evaluator re-runs it — the browser matches on
`sourceNodeId`/`sourceFieldName` against the rendered wrappers, and the server resolves
through the collected `logicId` map; both rely on this save-time sync having backfilled
the JSON. The order used at save time:

1. when the rule carries a `sourceFieldKey`:
   1. `sourceNodeId`, if the designated node carries that key (tie-breaker while several
      nodes transiently share a key, e.g. right after a same-form duplication)
   2. the existing `logicsSrc/<logicId>/logicNodeSource` weakreference, under the same
      key-match condition
   3. the first field (document order, before the target) whose `fieldKey` matches
2. legacy chain, for rules stored before `fieldKey` existed:
   1. `sourceNodeId` from the JSON rule
   2. an existing `logicsSrc/<logicId>/logicNodeSource` weakreference, if still valid
   3. `sourceFieldName` as fallback

Whatever path resolved the source, the sync then backfills the JSON: `sourceNodeId` is
refreshed and `sourceFieldKey` is written from the resolved source's `fieldKey` (assigning
one to the source when missing). Legacy rules therefore converge to the new format on
their first sync.

## Synchronization rules

`FormLogicSyncService` is responsible for keeping JSON and `logicsSrc` aligned.

### During normal authoring

When `logics` is added, changed, or removed:

1. ensure the target element has a `fieldKey`
2. drop targetless leftover rules (see below)
3. parse each JSON rule
4. ensure every rule has a `logicId`
5. resolve the source field (see resolution order)
6. update `sourceNodeId` and `sourceFieldKey` in the JSON if needed
7. create or update `logicsSrc/<logicId>`
8. remove orphan `logicsSrc` children not referenced by any remaining JSON rule

### Targetless leftovers are removed at save

A rule whose target was never chosen can do nothing but hide its field in live
(rules fail closed), so the save-time synchronization removes it instead of
storing it: a field rule without a source field, or a rule of one of the
providers **this module version ships** whose reference is empty (`variable`,
`param`, `cookie`). These are the leftovers of an "Add" click that was never
configured.

The cleanup is deliberately narrow, everything else round-trips untouched:

- a reference that is **filled but invalid** is kept — it carries intent, the
  editor shows it in error and the runtime fails closed;
- a rule with an **unknown source type** (authored by a newer module version)
  is never touched: this version cannot tell an empty configuration from one
  it simply cannot read;
- an unparseable entry is kept as stored rather than silently lost.

### After duplication, import, or session-save copy

When a subtree duplication occurs:

1. when the added node is an element inside an existing form (same-form copy/paste),
   remap `fieldKey` collisions first: every copied element whose key already exists
   outside the copied subtree gets a fresh key, and rules **inside the copied subtree**
   are rewritten to the fresh keys — the copy references its own internal sources,
   the original is untouched
2. assign a `fieldKey` to every logic element that lacks one
3. find `logicsSrc` entries whose weakreference points outside the current form
4. remove only those broken or out-of-scope `logicsSrc` children
5. preserve the JSON `logics` entries
6. rerun synchronization so the source can be rebound from `sourceFieldKey`,
   `sourceNodeId`, a still-valid local weakref, or `sourceFieldName`

Full-form duplications and cross-form copies never collide (keys are random UUIDs), so
step 1 is a no-op for them; import repair relies on `sourceFieldKey` (JCR UUIDs change,
exported properties do not).

## Invariants

The system should maintain these invariants:

1. every valid JSON rule has a non-empty `logicId`
2. every field rule converges to carrying a `sourceFieldKey` after its first sync
3. `fieldKey` is unique within a form once duplication remapping has run
4. every live `logicsSrc/<logicId>` child corresponds to an active JSON rule
5. `logicNodeSource` must point to a source field within the same form when the mapping is valid

## Import/export and copy behavior

### Import/export

The model relies on:

- `sourceFieldKey` in JSON (survives the UUID changes caused by import)
- `sourceNodeId` in JSON (fast path, repaired when stale)
- `logicNodeSource` weakreferences in `logicsSrc`

Import/export no longer involves any legacy field-id migration path. Any repair work is about rebinding broken or out-of-scope references, not migrating an older persisted identifier format.

### Copy/paste or workspace copy

- a rule is valid only if its source can still be resolved safely in the copied form
- broken external weakrefs are removed
- surviving JSON rules are rebound when possible
- unresolved rules may remain degraded until a valid source can be resolved again

The duplication cleanup listener currently runs for:

- `IMPORT`
- `WORKSPACE_COPY`
- `SESSION_SAVE` copy paths such as GraphQL `copyNode`

The `SESSION_SAVE` support exists because some supported copy flows do not surface as `WORKSPACE_COPY`. The listener is guarded and only runs when the added node or copied form subtree already contains `logics`, `logicsSrc`, or a `fieldKey` (freshly created elements have none of those, so normal authoring is unaffected).

## Historical limitation: duplicate system names

Before `fieldKey`, conditional logic was not fully safe when two different fields shared
the same system name (e.g. `termination/select-an-option` and `reduction/select-an-option`):
when `sourceNodeId` and the weakref were both unavailable, the name-based fallback could
bind the rule to the wrong homonym.

Rules carrying a `sourceFieldKey` are immune: the key designates one specific field
regardless of names and labels. The limitation only remains for legacy rules that have
never been re-synced (their first sync backfills the key from whatever source the legacy
chain resolves — keeping system names unique until then remains a good practice).
