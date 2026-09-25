# Custom Validation

## Overview

Formidable replaces native browser validation tooltips with inline error messages displayed
directly in the DOM. The `Form` client component sets `form.noValidate = true` on mount,
which suppresses the browser's default tooltip UI while keeping the Constraint Validation API
fully functional.

When a field is invalid, a styled `<div>` is injected below it (or appended to its
`.fmdb-form-group` wrapper) with the resolved error message.

---

## Message resolution

When a field fails validation, the message is resolved using a two-level cascade:

| Priority | Source | How it works |
|---|---|---|
| 1 | Contributor-defined message | Read from `data-fmdb-msg-*` attributes on the `<input>` / `<select>` / `<textarea>` element |
| 2 | Browser default | `input.validationMessage` — the browser's built-in message, already localized to the user's language |

The first non-empty value wins. If the contributor has not set a custom message for the
specific validity failure, the browser's native message is used as a fallback.

### Resolution logic

```
for each ValidityState flag (valueMissing, typeMismatch, ...):
    if flag is true:
        if data-fmdb-msg-{flag} attribute exists and is non-empty → use it
        else → use input.validationMessage
```

This is implemented in `validationUtils.ts → resolveValidationMessage()`.

---

## Validation message mixins (CND)

Not all validity constraints apply to every field type. To avoid exposing irrelevant
properties in the Content Editor, validation messages are split into a hierarchy of three
mixins:

### `fmdbmix:validationMessages` (base)

Applies to: checkbox, radio, select, file, color, range — elements where only `required` can
fail (the range slider surfaces it through the constraint-validation API while untouched, since
the browser clamps the thumb into range and rangeOverflow/stepMismatch are unreachable).

| Property | ValidityState flag | Example trigger |
|---|---|---|
| `msgValueMissing` | `valueMissing` | Required field left empty |

### `fmdbmix:textValidationMessages` (extends base)

Applies to: text, email, textarea — elements with text-specific constraints.

| Property | ValidityState flag | Example trigger |
|---|---|---|
| `msgValueMissing` | `valueMissing` | Required field left empty |
| `msgTypeMismatch` | `typeMismatch` | Invalid email format |
| `msgPatternMismatch` | `patternMismatch` | Value does not match `pattern` regex |
| `msgTooShort` | `tooShort` | Value shorter than `minLength` |
| `msgTooLong` | `tooLong` | Value longer than `maxLength` |

### `fmdbmix:rangeValidationMessages` (extends base)

Applies to: date, datetime-local, number — elements with range and step constraints.

| Property | ValidityState flag | Example trigger |
|---|---|---|
| `msgValueMissing` | `valueMissing` | Required field left empty |
| `msgRangeUnderflow` | `rangeUnderflow` | Value before `min` date |
| `msgRangeOverflow` | `rangeOverflow` | Value after `max` date |
| `msgStepMismatch` | `stepMismatch` | Value does not match `step` increment |
| `msgBadInput` | `badInput` | Unparseable input (e.g. letters in a date field) |

### Date bounds: none, a fixed date, or the submission day

Each bound of a date or datetime-local field is a **mode**: `minBoundMode` /
`maxBoundMode` (from the `fmdbmix:dateBounds` / `fmdbmix:datetimeBounds`
contracts) hold `none`, `date`, `today` or `relative`. In the editor the mode is
a dropdown; choosing the fixed date reveals its calendar (a `jmix:dynamicFieldset`
mixin — `fmdbmix:fixedMinDate` and friends — carries the `min`/`max` property),
choosing the relative date reveals its offset (`fmdbmix:relativeMinDate` and
friends carry a signed amount and a unit), choosing the submission day shows
nothing more. Modes are exclusive by construction, so a bound is never an
ambiguous combination of a fixed date and a day-following one.

The `today` mode bounds the value by the day the visitor submits the form (a
birth date must not be in the future, an appointment must not be in the past).
The `relative` mode bounds it by that day **shifted by a signed offset** of
days, months or years: `-18 years` as a maximum keeps birth dates at least 18
years in the past (age limit), `+30 days` allows dates at most 30 days ahead
(booking window). Month and year arithmetic clamps to the end of shorter months
(January 31 + 1 month is February 28/29) — identically in the browser and on
the server.
Because the fragment cache would freeze any server-rendered date, the rendered
input resolves that bound at hydration, in the visitor's own timezone; the
`msgRangeUnderflow`/`msgRangeOverflow` messages apply to it exactly as to a fixed
bound. Server-side, the submission pipeline re-resolves the bound widened to the
extreme calendar day any inhabited timezone can currently be (UTC-12 for a
minimum, UTC+14 for a maximum), so a visitor is never rejected for a value their
own picker allowed, whatever the server's or the visitor's zone. Fixed bounds
stay exact.

Theme note: a `today`-bounded input renders inside an island wrapper
(`display: contents`), one extra DOM level — the same structure as the masked
text and range inputs. Layout is unaffected, but a theme selector using a child
combinator (`.fmdb-form-group > input`) skips exactly those inputs: use
descendant selectors (`.fmdb-form-group input`) instead.

Fields stored before the bound modes existed carry fixed `min`/`max` values and
no mode: a startup migration stamps them with the `date` mode (see the upgrade
notes). Until it runs, the values are still **enforced at validation time** (the
pipeline reads them on the underlying node), but the rendered inputs and the
editor do not show them — the migration is what brings them back everywhere.

### Mixin assignment

Each element type extends the appropriate mixin in its `definition.cnd`:

```cnd
// Text input — has text constraints
[fmdb:inputText] > ... fmdbmix:textValidationMessages

// Date input — has range constraints
[fmdb:inputDate] > ... fmdbmix:rangeValidationMessages

// Number input — has range constraints
[fmdb:inputNumber] > ... fmdbmix:rangeValidationMessages

// Checkbox — only required can fail
[fmdb:checkbox] > ... fmdbmix:validationMessages
```

The content editor form definition (`fmdbmix_validationMessages.json`,
`fmdbmix_textValidationMessages.json`, `fmdbmix_rangeValidationMessages.json`) surfaces
only the relevant properties for each mixin level.

---

## Data flow: server → client

Validation messages follow the standard Formidable server/client split. No Island is needed
for most individual inputs — the messages are passed as HTML data attributes.

```
Server (default.server.tsx)
  │
  │  1. Read msg* properties from JCR node (via the mixin)
  │  2. Call validationDataAttributes(validationMsgs)
  │  3. Spread result onto <input> / <select> / <textarea>
  │
  ▼
HTML output
  │
  │  <input type="text" ... data-fmdb-msg-value-missing="Please fill this in"
  │                          data-fmdb-msg-pattern-mismatch="Use format AB-1234" />
  │
  ▼
Client (Form.client.tsx)
  │
  │  useCustomFormValidation hook:
  │    - listens for 'invalid' events (capture phase)
  │    - calls resolveValidationMessage(input) → reads data-fmdb-msg-* attributes
  │    - calls showFieldError(input, message) → injects error <div> into DOM
  │    - listens for 'input'/'change' events → clears error when field becomes valid
  │    - listens for 'reset' → clears all errors
  │
  │  validateInputs(container):
  │    - called at form submit and step navigation
  │    - iterates all inputs, validates each, shows/clears errors
  │    - deduplicates radio/checkbox groups by name so each group is handled once
  │    - focuses the first invalid field
  │
  ▼
DOM
DOM
  <div class="fmdb-form-group">
    <label>Employee code</label>
    <input class="fmdb-form-control fmdb-invalid" ... />
    <div class="fmdb-validation-error" role="status">Use format AB-1234</div>
```

### Server-side helper

`validationProps.ts` exports `validationDataAttributes()` which converts mixin properties
to `data-fmdb-msg-*` HTML attributes. Each server component uses it:

```tsx
// In default.server.tsx
const { "jcr:title": label, required, ...validationMsgs } = props;

<input
    type="text"
    required={required}
    {...validationDataAttributes(validationMsgs)}
/>
```

Only non-empty messages are rendered as attributes. Empty strings are converted to
`undefined` so they do not appear in the HTML output.

---

## Styling

The classes (`fmdb-validation-error` on the injected message, `fmdb-invalid` on the control) and
the `--fmdb-validation-error-*` / `--fmdb-invalid-*` variables are part of the styling contract:
see [Class hooks](../styling/class-hooks.md) and [CSS variables](../styling/css-variables.md#validation).

---

## Multi-step forms

Validation is enforced per-step during navigation. When the user clicks "Next":

1. `validateInputs()` is called on the current step container
2. If any field is invalid, errors are shown and navigation is blocked
3. The first invalid field receives focus

At final submission, `validateInputs()` runs on the entire `<form>` element to catch any
field that might have been missed.

---

## Checkbox groups (special case)

Checkbox groups use an Island (`Checkbox.client.tsx`) because validating "at least one
checked" requires client-side JavaScript across multiple `<input>` elements.

The group validation uses `setCustomValidity()` on all checkboxes in the group:

- If a checkbox in the group exposes `data-fmdb-msg-value-missing` → that message is used
- Otherwise, if the Island receives an `errorMessage` prop → that message is used
- Otherwise → the i18n key `fmdb_inputCheckbox.error` is used as fallback

Current implementation detail:

- A single checkbox uses the standard server-side `validationDataAttributes()` flow
- A multi-checkbox group is rendered through `Checkbox.client.tsx` and receives the `required` flag
- For multi-checkbox groups, `validationDataAttributes(...)` are spread onto each `<input>` so `data-fmdb-msg-value-missing` overrides work
- If no custom message is provided (and no `errorMessage` prop is passed), the group falls back to the translated `fmdb_inputCheckbox.error` message

---

## Key files

| File | Role |
|---|---|
| `src/utils/validationUtils.ts` | `resolveValidationMessage`, `showFieldError`, `clearFieldError`, `clearAllFieldErrors` |
| `packages/formidable/src/validationProps.ts` | `validationDataAttributes` — server-side helper to convert mixin props to data attributes (UI-contract package, published as `@jahia/formidable-library`, #178, #308) |
| `src/hooks/useCustomFormValidation.ts` | `useCustomFormValidation` hook + `validateInputs` function |
| `src/design/validation.css` | CSS classes and custom properties |
| `settings/definitions.cnd` | `fmdbmix:validationMessages`, `fmdbmix:textValidationMessages`, `fmdbmix:rangeValidationMessages` |
| `settings/jahia-content-editor-forms/forms/fmdbmix_validationMessages.json` | Content Editor form for base mixin |
| `settings/jahia-content-editor-forms/forms/fmdbmix_textValidationMessages.json` | Content Editor form for text mixin |
| `settings/jahia-content-editor-forms/forms/fmdbmix_rangeValidationMessages.json` | Content Editor form for range mixin |
