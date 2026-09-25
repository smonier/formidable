# Class hooks

Stable class names, kept across releases. Most are rendered server-side; the multi-step navigation,
the submission messages, the selected-files list and the validation messages are drawn by the
client, under the same names. Where each one sits in the rendered tree is drawn in
[Styling a form](README.md#what-the-markup-looks-like); the values that size and colour them are in
[CSS variables](css-variables.md).

## The form and its fields

| Class | Element |
|---|---|
| `fmdb-form` | The `<form>`; carries `data-fmdb-edit-mode="true"` in the Page Builder. In jContent's inspection previews it is a `div` carrying `data-fmdb-cm-view="true"` (see below) — avoid qualifying rules with the tag (`form.fmdb-form`) |
| `fmdb-form-intro` | The introduction rich text |
| `fmdb-content`, `fmdb-content-text` | A rich-text content block placed among the fields |
| `fmdb-form-group` | The wrapper of one field (label + control + help); `fmdb-radio-group`, `fmdb-checkbox-group`, `fmdb-captcha` refine it |
| `fmdb-form-element` | The wrapper of each element rendered inside a step or a fieldset (an element placed directly in the form has none) |
| `fmdb-form-label`, `fmdb-file-label`, `fmdb-radio-label`, `fmdb-checkbox-label` | Field labels. A file field carries `fmdb-form-label fmdb-file-label`, so a rule written for every label reaches it too; the radio and checkbox ones label an option, not the field, whose label is the group legend |
| `fmdb-group-legend` | Legend of a radio or checkbox group |
| `fmdb-group-items`, `fmdb-group-item` | Options of a radio or checkbox group |
| `fmdb-required-indicator` | The `*` of a required field |
| `fmdb-form-control` | Inputs, selects and textareas |
| `fmdb-options-source-error`, `fmdb-field-error` | A choice field whose options source is unavailable: its group carries the first, with `data-fmdb-source-error="blocking"` or `"optional"`, and the message paragraph the second |
| `fmdb-fieldset`, `fmdb-fieldset-legend`, `fmdb-fieldset-elements` | A fieldset, its legend, and the wrapper of its fields |
| `fmdb-step`, `fmdb-step-title`, `fmdb-step-intro`, `fmdb-steps-nav`, `fmdb-step-indicator`, `fmdb-step-number`, `fmdb-step-label` | Multi-step structure and navigation; an indicator holds its number badge and its label |
| `fmdb-form-actions`, `fmdb-btn`, `fmdb-btn-primary`, `fmdb-btn-secondary`, `fmdb-btn-danger`, `fmdb-new-form-btn`, `fmdb-next-btn`, `fmdb-prev-btn` | Buttons, including the multi-step navigation pair; a Button field carries the variant the contributor chose (`primary`, `secondary`, `danger`). The modules style these hooks themselves (variables: `--fmdb-btn-*`). The message's button — new form, try again — is **outside** `<form>`, so a stylesheet scoping its buttons under `.fmdb-form` leaves that one raw |
| `fmdb-form-help` | Help text under a field (present on nearly every field) |
| `fmdb-range`, `fmdb-range-row`, `fmdb-range-output`, `fmdb-range-end-label` | Range slider structure (variables: `--fmdb-range-gap`, `--fmdb-range-output-min-width`, `--fmdb-range-end-label-size`) |
| `fmdb-message`, `fmdb-message-content`, `fmdb-message-success`, `fmdb-message-error`, `fmdb-message-maintenance`, `fmdb-message-details`, `fmdb-message-error-details` | Submission feedback; the two `-details` classes sit on the `<small>` under an error, carrying the error code and the actions' progress |
| `fmdb-file-*` (`-input-container`, `-list`, `-item`, `-info`, `-name`, `-size`, `-remove`, `-selection-note`), `fmdb-selected-files`, `fmdb-selected-files-title` | File field and its selected files: `fmdb-selected-files` wraps the list under its heading, `fmdb-file-info` holds a file's name and size |
| `fmdb-validation-error`, `fmdb-invalid` | Inline validation (see [Custom validation](../architecture/custom-validation.md)) |
| `fmdb-logic-target` | Wrapper of an element driven by conditional logic (see below) |
| `fmdb-spinner` | The submission overlay |
| `fmdb-form-fields` | The field list — in edit mode only, where the authoring spacing below pads it |
| `fmdb-form-reference-empty` | A form reference with no form selected — in edit mode only |

Every element wrapper also exposes `data-fmdb-node-name`, `data-fmdb-node-id` and
`data-fmdb-node-type`, for rules that target one field by name:
`.fmdb-form [data-fmdb-node-name="email"] { … }`.

A field a prefill filled (the jExperience integration, from the visitor profile) and the author made
read-only or hidden carries `data-fmdb-prefilled="readonly"` or `"hidden"` on that same wrapper: the
core tones the read-only one down and takes the pointer off its select, radio, checkbox and colour controls
(variables in [CSS variables](css-variables.md#prefilled-fields)), and hides the other inline, its value
still submitted.

Four other `data-fmdb-*` attributes are functional markers the client reads, not styling hooks,
and they may change with the feature they serve: `data-fmdb-step` on each step wrapper (the
class `fmdb-step` is the hook), `data-fmdb-msg-*` on the controls (the contributor's validation
messages, see [Custom validation](../architecture/custom-validation.md)), `data-fmdb-logics` and
`data-fmdb-logic-value` (conditional logic, see
[How to extend views and elements](../extension/how-to-extend-views-and-elements-from-third-party-module.md)).

## Surfaces: live, edit mode, inspection previews

The same hooks render on three surfaces, told apart by an attribute on the `fmdb-form` element:
none in live, `data-fmdb-edit-mode="true"` in the Page Builder, `data-fmdb-cm-view="true"` in
jContent's inspection previews.

jContent's scriptless preview surfaces (the preview drawer, the Content Editor preview)
render the `cm` view: an inspection of the form's content — every step stacked under its
title, logic-driven fields visible on their card, no buttons. There the `fmdb-form` hook is
a `div` (nothing submits), and it carries `data-fmdb-cm-view="true"` instead of the edit-mode
attribute: a stylesheet targets that surface with
`.fmdb-form[data-fmdb-cm-view="true"] { … }`. The form's own CSS and this module's
stylesheet apply; a template set's stylesheet only reaches previews opened from a page
(jContent injects the hosting page's CSS there).

In edit mode the form gets what authoring needs and live never sees: the field list is wrapped
in `fmdb-form-fields` (so it can be clicked around its steps), a form reference with no form
selected renders `fmdb-form-reference-empty`, and the form actions zone below is drawn after the
form. The spacing that comes with it is set by variables, in
[CSS variables](css-variables.md#authoring-spacing).

## Conditional logic

Every element carrying at least one visibility rule is wrapped in `.fmdb-logic-target`
(server-rendered, so present in live, preview and edit mode; the current state stays in
`data-fmdb-logic-hidden`). Nothing is drawn in live. In edit mode
(`form[data-fmdb-edit-mode="true"]`) and in jContent's inspection previews
(`[data-fmdb-cm-view="true"]`, the other surface that shows logic-hidden fields) the core
lifts the element's form group on a light grey card with a soft shadow, shrunk to its
content, so contributors spot conditional fields. The card is drawn by variables, and can be
turned off or replaced: see [CSS variables](css-variables.md#conditional-logic).

## Form actions zone (edit mode)

Actions run after the submission and have no place in the visitor's form, so on a page they
were invisible while authoring. In edit mode the form renders its action list as a zone of its
own, under the buttons: a header, one compact card per action — its rank in the execution
order (the list is orderable: dragging a card in the Page Builder reorders the pipeline), the
type icon, the contributor's title with the action's key parameter — the first small text or
choice property its type declares after the title (recipient, forward target), a choice shown
by its label —
and smaller, the type description its module declares for the Content Editor (the
`<type>.ui.tooltip` key of the module's resource bundle) — then the list's own **New Form
Action** button (the placeholder's accepted type is the `fmdbmix:formAction` mixin, so jContent
shows one button and then the type chooser). A form without any action gets a warning instead
of the cards: its submissions are neither stored nor sent.

Unlike the spacing above, the zone IS drawn (`aside.fmdb-authoring-actions`, light grey, dashed):
it is authoring chrome, not the visitor's form, and must read as such; a business stylesheet
does not style it. To order it after the buttons, the form is a flex column while authoring
(`form[data-fmdb-edit-mode="true"]`); its children were stacked blocks already. Nothing of the
zone exists in live, preview or the `cm` view.

Its structure, for the rare rule that must reach inside it — the
[variables](css-variables.md#form-actions-zone) are the intended surface:

| Class | Element |
|---|---|
| `fmdb-authoring-actions` | The zone, an `aside` |
| `fmdb-authoring-actions-header`, `fmdb-authoring-actions-title`, `fmdb-authoring-actions-hint` | The header row: the title (list icon and count) and the hint on the right |
| `fmdb-authoring-actions-glyph` | The list's type icon in the title, and the triangle of the warning |
| `fmdb-authoring-actions-empty` | The "no action" warning |
| `fmdb-authoring-actions-list`, `fmdb-authoring-actions-item` | The ordered list of cards and each of its items |
| `fmdb-authoring-action` | One action card; carries `data-fmdb-action-type` with the action's node type |
| `fmdb-authoring-action-icon`, `fmdb-authoring-action-body` | The type icon and the text column beside it |
| `fmdb-authoring-action-line`, `fmdb-authoring-action-title`, `fmdb-authoring-action-detail` | The first line: the action's title (or its type label) and its key parameter |
| `fmdb-authoring-action-description` | The second line: the type's description |

## Extended inputs

The optional `formidable-extended-inputs` module adds four fields — consent, rating, scale and
switch — rendered server-side with no client island: every state is native HTML and CSS. Each keeps
the shared hooks of the form (`fmdb-form-group` on its wrapper, `fmdb-group-legend`,
`fmdb-required-indicator`, `fmdb-form-help`, `fmdb-form-control` on the consent checkbox) and adds
hooks of its own under the `fmdbext-` prefix. Its stylesheet hard-codes nothing without a
`--fmdbext-*` variable, set on `.fmdb-form` like the others
([CSS variables](css-variables.md#extended-inputs)).

| Class | Element |
|---|---|
| `fmdbext-consent`, `fmdbext-consent-input`, `fmdbext-consent-label`, `fmdbext-consent-statement`, `fmdbext-consent-terms-link` | The consent field: its group, the checkbox, the label, the statement in it, and the link to the terms |
| `fmdbext-rating`, `fmdbext-rating-body`, `fmdbext-rating-items`, `fmdbext-rating-item`, `fmdbext-rating-input`, `fmdbext-rating-label` | The rating field: a `fieldset`, its body, the row of items (carries `data-fmdbext-icon`: `star`, `heart`, `thumb` or `number`), and each item's radio input and label |
| `fmdbext-scale`, `fmdbext-scale-body`, `fmdbext-scale-items`, `fmdbext-scale-item`, `fmdbext-scale-input`, `fmdbext-scale-label` | The scale field: a `fieldset`, its body, the row of chips, and each chip's radio input and label |
| `fmdbext-switch`, `fmdbext-switch-toggle-wrapper`, `fmdbext-switch-input`, `fmdbext-switch-toggle-label`, `fmdbext-switch-track`, `fmdbext-switch-text`, `fmdbext-switch-state`, `fmdbext-switch-state-on`, `fmdbext-switch-state-off` | The switch field in its `toggle` display mode: the group, the checkbox and its label, the track drawn beside the text, and the on/off state texts |
| `fmdbext-switch`, `fmdbext-switch-buttons`, `fmdbext-switch-button`, `fmdbext-switch-button-label` | The switch field in its `buttons` display mode: a `fieldset` holding two radio chips |
| `fmdbext-end-labels` | The minimum and maximum labels under a rating or a scale |
| `fmdbext-edit-warning` | A configuration warning shown to contributors in edit mode (a value clamped, a missing bound) |
