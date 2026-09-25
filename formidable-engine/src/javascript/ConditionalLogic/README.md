# Conditional Logic Editor

This folder contains the custom Content Editor selector used to configure conditional field visibility for Formidable fields carrying the `fmdbmix:formLogicElement` mixin.

Related docs:

- [`../../../../docs/architecture/conditional-logic-field-resolution.md`](../../../../docs/architecture/conditional-logic-field-resolution.md) for the repository-side persistence and source resolution model

## Goal

The editor exposes one logical rule per multivalue entry of the `logics` property.

Each rule defines:

- the source field
- the source field UUID
- the comparison operator
- the comparison value or values

At runtime, Formidable evaluates all stored rules on the target field with `AND` semantics.

## Files

### `ConditionalLogicCmp.tsx`

This is the UI component registered as the `ConditionalLogic` selector type in `formidable-engine/src/javascript/init.tsx`.

Its responsibilities are intentionally narrow:

- load candidate source fields for the current edited node
- render the editor controls
- react to user changes
- serialize the edited rule back to JSON through `onChange`
- preserve or recover the selected source by UUID whenever possible

The component uses Moonstone controls:

- `Dropdown` for source selection
- `Dropdown` for operator selection
- `Dropdown` for multivalue choice selection
- `Input type="date"` for date comparisons

The component does not implement tree traversal, parsing, or rule normalization directly. That logic lives in `ConditionalLogic.utils.ts`.

### `ConditionalLogic.types.ts`

This file centralizes the TypeScript types used by the feature:

- selector props
- GraphQL node shapes
- source field description
- logical rule structure
- supported operators and supported source field types

The purpose is to keep `ConditionalLogicCmp.tsx` readable and avoid repeating ad hoc inline types.

### `ConditionalLogic.utils.ts`

This file contains the non-visual logic:

- GraphQL queries used to resolve the current edited node and the parent form tree (using the shared `JCR_NODE_IDENTITY` fragment from `src/javascript/graphql/`)
- parsing of the stored JSON rule
- extraction of editor context values such as path, language, and workspace
- discovery of valid source fields located before the current target field
- logicId and source-field weakref resolution for existing rules
- mapping of source field types to supported operators
- normalization of the stored JSON payload

This separation matters because the selector has two different concerns:

1. editor rendering
2. form-structure analysis

Keeping them together made the main component harder to read and harder to evolve.

## Data flow

1. The editor opens on a field with the `logics` property.
2. `ConditionalLogicCmp.tsx` resolves the current node path from the selector context.
3. It queries the current node, then walks up to find the parent `fmdb:form`.
4. It queries the form tree under `fields`.
5. It flattens the form structure in display order.
6. It keeps only supported source field types that appear before the current field.
7. It excludes source fields already used by sibling `logics` entries (read from `formik.values`).
8. It identifies each source option by node UUID, not by system name.
9. If several candidate sources have the same display label, the editor disambiguates them visually by appending `:1`, `:2`, and so on to the label shown in the dropdown.
10. When an existing rule is edited, the selector resolves the source in this order:
   - `sourceFieldKey` stored in the JSON rule, matched against the fields' `fieldKey`
   - `sourceNodeId` stored in the JSON rule
   - `logicId -> logicsSrc -> logicNodeSource` weakref
   - legacy `sourceFieldName` fallback
11. If no source fields are available (none exist or all are taken), a text message is shown instead of the dropdowns.
12. The user selects:
   - a source field
   - an operator
   - zero, one, or multiple values depending on the source type
13. The selector serializes the rule as JSON and writes it back through `onChange`.

## Source eligibility: semantic mixins

Eligibility is mixin-driven, not type-name driven. A field type becomes a logic
source by carrying one of the engine's semantic value-kind mixins in its CND —
including field types contributed by third-party modules (see
`docs/extension/how-to-extend-views-and-elements-from-third-party-module.md`):

| Mixin | valueKind | Core types carrying it |
|---|---|---|
| `fmdbmix:choiceField` | `choice` | `fmdb:select`, `fmdb:radio`, `fmdb:checkbox` |
| `fmdbmix:dateField` | `date` | `fmdb:inputDate` |
| `fmdbmix:numberField` | `number` | — (third-party, e.g. rating/scale fields) |
| `fmdbmix:booleanField` | `boolean` | — (third-party, e.g. switch/consent fields) |
| `fmdbmix:textField` | `text` | `fmdb:inputText`, `fmdb:textarea`, `fmdb:inputEmail` |

`sourceDescriptors.ts` maps each valueKind to a default descriptor (operators,
value widget, choice property). Per-type overrides exist only where a core type
deviates from the kind default: `fmdb:select` stores choices under `options`,
and `fmdb:checkbox` swaps its operators based on how many choices it defines.

## Supported operators

### Choice (select, radio)

- `in`
- `notIn`

### Checkbox

Single checkbox:

- `isChecked`
- `isUnchecked`

Checkbox group:

- `containsAny`
- `containsAll`

### Date

- `before`
- `after`
- `on`
- `between`

### Number

- `eq`, `neq`, `lt`, `lte`, `gt`, `gte`
- `between` (numeric comparison, disambiguated from the date kind by the stored `valueKind`)

### Boolean

- `isTrue`
- `isFalse`

### Text

- `isNotEmpty` ("field is filled"), `isEmpty` — emptiness is whitespace-blankness
- `equals`, `contains` — case-sensitive comparison against a required non-empty
  expected value (an empty expected value never matches; `isEmpty` covers that case)

## Stored rule format

Each `logics` entry is stored as JSON. Example:

```json
{
  "logicId": "a1b2c3d4",
  "sourceNodeId": "4028c1e2-934f-2f92-0193-4f6ac4f00041",
  "sourceFieldKey": "550e8400-e29b-41d4-a716-446655440000",
  "sourceFieldName": "iAm",
  "sourceFieldType": "fmdb:radio",
  "valueKind": "choice",
  "operator": "in",
  "values": ["individual", "professional"]
}
```

Date example:

```json
{
  "logicId": "e5f6a7b8",
  "sourceNodeId": "4028c1e2-934f-2f92-0193-4f6ac4f00077",
  "sourceFieldName": "startDate",
  "sourceFieldType": "fmdb:inputDate",
  "operator": "between",
  "values": ["2026-01-01", "2026-12-31"]
}
```

Notes:

- `sourceFieldKey` is the stable business reference to the source field (its `fieldKey`,
  a hidden server-assigned UUID that survives renames, copies, and imports). The editor
  writes it when the source carries a key; the Java sync backfills it otherwise.
- `sourceNodeId` is the technical source identifier for new and normalized rules.
- `sourceFieldName` is still kept as metadata and legacy fallback.
- `valueKind` denormalizes the source's kind at authoring time so both runtime
  evaluators (browser and Java) pick the right comparison semantics — 'between'
  compares numerically for `number` and lexicographically (ISO dates) otherwise.
  Rules stored before it existed have no `valueKind` and keep date semantics.
- `logicId` is used to bind the JSON rule to its `logicsSrc/<logicId>` child node.

## Repository-side synchronization

The editor JSON is not the only persisted representation.

For each rule, the repository also maintains:

- a hidden `logicsSrc` child node under the target field
- one `fmdb:logicSrc` child per `logicId`
- a `logicNodeSource` weakreference pointing to the actual source node

`FormLogicSyncService` keeps both representations aligned:

- during normal authoring, it updates or creates weakrefs from the JSON rule
- after subtree duplication, it remaps colliding `fieldKey`s, removes out-of-scope weakrefs and tries to rebuild them
- source resolution prefers `sourceFieldKey`, then `sourceNodeId`, then a valid existing weakref, then `sourceFieldName` (see `docs/architecture/conditional-logic-field-resolution.md`)

`FormDuplicationCleanupListener` is the backend trigger for that duplication cleanup. It now covers:

- import paths
- workspace copy paths
- regular session-save copy paths such as GraphQL `copyNode`

To avoid unnecessary work on ordinary node creation, the listener only processes:

- a copied `fmdbmix:formLogicElement` that already carries `logics` or `logicsSrc`
- a copied `fmdb:form` subtree that contains at least one descendant with `logics` or `logicsSrc`

## Why the source-field lookup is done in TSX

This implementation intentionally keeps the source discovery logic in the editor layer instead of using a Jahia Java choicelist initializer.

Reason:

- the selector needs dynamic behavior driven by the current edited field
- the available operators depend on the chosen source type
- the value control depends on the chosen source field content
- keeping all of that close to the selector avoids splitting the feature across unrelated stacks

## Runtime counterpart

This folder only covers the Content Editor side.

The runtime visibility evaluation is implemented in `formidable-elements`:

- server wrappers add field metadata and serialized logics
- server wrappers enrich rendered rules with `sourceNodeId` from `logicsSrc` when available
- `Form.client.tsx` listens to form changes
- `conditionalLogic.ts` evaluates the rules and hides or shows the target wrappers
- browser-side evaluation prefers `sourceNodeId` and falls back to `sourceFieldName` only for legacy or degraded cases

## Limitation: duplicate system names

Conditional logic is not fully safe when two different form fields share the same JCR/system name.

The current implementation strongly prefers UUID-based resolution, but some recovery paths still fall back to `sourceFieldName`, especially:

- legacy rules that were saved before `sourceNodeId` was introduced
- imported or copied rules whose weakref is broken and must be rebound
- runtime fallback when a rendered rule has no usable `sourceNodeId`

In those cases, the feature may bind a rule to the wrong homonymous field.

Example:

- fieldset `termination` contains a radio field named `select-an-option`
- fieldset `reduction` also contains a radio field named `select-an-option`
- a target field should depend on `reduction/select-an-option`
- if the UUID and weakref are unavailable, the fallback by name may resolve `termination/select-an-option` instead

Editor behavior in that situation:

- the source dropdown tries to help the contributor by showing labels such as `select-an-option:1` and `select-an-option:2`
- this suffix is only a visual disambiguation in the editor
- it does not create a new system name and does not remove the underlying ambiguity of same-name fields

What the user can observe:

- the target field appears or disappears based on the wrong answer
- a field may stay hidden in the browser when it should be visible
- server-side required validation may treat the field as hidden or visible from the wrong source field state

Recommendation: keep system names unique across the form whenever a field can participate in conditional logic.

## Maintenance note

If new source field types are added later, update the following places together:

1. supported source types in `ConditionalLogic.types.ts` / `ConditionalLogic.utils.ts`
2. operator mapping in `ConditionalLogic.utils.ts`
3. value editor behavior in `ConditionalLogicCmp.tsx`
4. runtime evaluation in `formidable-elements/src/utils/conditionalLogic.ts`
