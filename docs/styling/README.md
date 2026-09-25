# Styling a form

How a template set — or, exceptionally, a form's own custom CSS — styles the forms rendered by
`formidable-elements` and by the optional `formidable-extended-inputs` module. Two pages carry the
contract; this one says how the pieces fit together:

- [Class hooks](class-hooks.md) — the stable class names the markup carries, the `data-` attributes
  that go with them, and the surfaces (live, edit mode, inspection previews) where they appear.
- [CSS variables](css-variables.md) — every value the base stylesheets hard-code, exposed for a
  template set to set.

## Where styles belong

A form's look normally belongs to the **site template set**, like any other content: ship the
rules in its stylesheet, targeting the class hooks. The **Custom CSS** field of a form
(`Style` section in Content Editor) is for the exceptional case where the site stylesheet cannot
be changed. Its content is injected in a `<style>` element next to the form, in live, preview
and edit mode alike, and it is **not scoped to the form**: a broad rule affects the whole page.

The base stylesheet of `formidable-elements` (`dist/assets/style.css`) carries the functional
rules — validation messages, the multi-step navigation, the spinner, the edit-mode cues — and,
since 0.5.0, one piece of plain looks: the **buttons**, which came out as the browser draws them
and made a form look unfinished wherever a template set had not styled them one by one. Fields,
labels and layout are still the template set's business. Every value the stylesheet hard-codes is
exposed as a CSS variable, so a template set overrides values without fighting selectors, and the
button rules are written at the lowest specificity (`.fmdb-btn`, one class) so that a rule of
yours scoped under `.fmdb-form` wins over them. The extended inputs follow the same rule with
their own prefix.

## What the markup looks like

Every form renders the same structure, whatever the template set. Most of it is server-rendered;
the multi-step navigation, the submission messages, the selected-files list and the validation
messages are drawn by the client under the same names. Read each line as *class on element*,
indented by nesting; `[…]` are attributes; the note on the right says when the branch exists.

```text
div.fmdb-spinner                                        while a submission is in flight
div.fmdb-message.fmdb-message-{success|error|maintenance}
│                                                       after a submission (client) or under read-only maintenance (server)
└─ div.fmdb-message-content
   ├─ small.fmdb-message-details.fmdb-message-error-details   error code and actions' progress, under an error
   └─ button.fmdb-btn.fmdb-btn-secondary.fmdb-new-form-btn
form.fmdb-form [data-fmdb-edit-mode="true"]             the form; a div [data-fmdb-cm-view="true"] in inspection previews
├─ header.fmdb-form-intro                               introduction rich text
├─ nav.fmdb-steps-nav                                   multi-step forms, drawn by the client
│  └─ span.fmdb-step-indicator [aria-current="step"]    one per visible step
│     ├─ span.fmdb-step-number
│     └─ span.fmdb-step-label
├─ div [data-fmdb-node-*] (.fmdb-form-fields in edit mode)   the field list
│  ├─ div.fmdb-step [data-fmdb-step]                    multi-step forms; a single-step form has none
│  │  ├─ h2.fmdb-step-title
│  │  ├─ div.fmdb-step-intro
│  │  └─ div.fmdb-form-element [data-fmdb-node-name] [data-fmdb-node-id] [data-fmdb-node-type]
│  │     │                                              one per element; .fmdb-logic-target when a rule drives it
│  │     ├─ div.fmdb-content.fmdb-content-text          a rich-text block among the fields
│  │     ├─ fieldset.fmdb-fieldset
│  │     │  ├─ legend.fmdb-fieldset-legend
│  │     │  └─ div.fmdb-fieldset-elements
│  │     │     └─ div.fmdb-form-element […]             the fieldset's elements, same wrapper
│  │     ├─ button.fmdb-btn.fmdb-btn-{primary|secondary|danger}   a Button field
│  │     └─ div.fmdb-form-group                         a field; a fieldset with .fmdb-radio-group | .fmdb-checkbox-group for a group
│  │        ├─ label.fmdb-form-label                    legend.fmdb-group-legend for a group; + .fmdb-file-label for a file
│  │        │  └─ span.fmdb-required-indicator
│  │        ├─ input|select|textarea.fmdb-form-control  .fmdb-invalid while the value is invalid
│  │        │  ┆ div.fmdb-group-items › div.fmdb-group-item › input.fmdb-form-control + label.fmdb-radio-label|.fmdb-checkbox-label
│  │        │  ┆ div.fmdb-file-input-container › … › div.fmdb-selected-files › ul.fmdb-file-list › li.fmdb-file-item  (file)
│  │        │  ┆ div.fmdb-range-row › input.fmdb-form-control.fmdb-range + output.fmdb-range-output   (range)
│  │        │  ┆ the extended inputs: their own fmdbext-* structure inside the group
│  │        ├─ div.fmdb-form-help
│  │        └─ div.fmdb-validation-error                injected by the client under an invalid control
│  └─ div [data-fmdb-node-*] …                          an element placed directly in the form: same wrapper, no fmdb-form-element
├─ div.fmdb-form-group.fmdb-captcha                     a protected form, on its last step
└─ div.fmdb-form-actions
   └─ button.fmdb-btn.fmdb-btn-{primary|secondary}      submit, reset; .fmdb-prev-btn and .fmdb-next-btn on a multi-step form
aside.fmdb-authoring-actions                            edit mode only: the form actions zone, after the form
```

`┆` marks alternatives for the control of a field. The full list of hooks, with what each one
is, is in [Class hooks](class-hooks.md); the variables that size and colour these pieces are in
[CSS variables](css-variables.md).

## Conventions

- **Two prefixes.** `fmdb-` for the core (`formidable-elements`), `fmdbext-` for the extended
  inputs. Anything else beside them in a `class` attribute is a hashed CSS-module class of the
  implementation (`_form_x1y2z3`): private, different at every build, never a target.
- **Three kinds of things in the markup.** *Class hooks*: stable names, kept across releases,
  the contract. *CSS variables* (`--fmdb-*`, `--fmdbext-*`): every value the base stylesheets
  hard-code; set them on `.fmdb-form` (or `:root`) rather than restyling the selectors. One
  exception to `.fmdb-form`: the submission message and its button are rendered **beside** the
  form, not inside it, so the variables they read — `--fmdb-btn-*` — have to be set on `:root`
  (or on `.fmdb-form, .fmdb-message`) or that button keeps the defaults while every other one
  follows you.
  *Data attributes*: some are part of the contract (`data-fmdb-node-name` to target one field,
  `data-fmdb-edit-mode` and `data-fmdb-cm-view` to target a surface, `data-fmdb-source-error`,
  `data-fmdb-logic-hidden`, `data-fmdb-prefilled`, `data-fmdb-action-type`, `data-fmdbext-icon`), the others are
  functional markers the client reads and may change with their feature — both lists are in
  [Class hooks](class-hooks.md).
- **Edit mode adds, live does not.** The Page Builder gets wrappers, spacing and the actions zone;
  nothing of it exists in live, so a stylesheet written against the hooks keeps its look while
  authoring.
- **A third-party field follows the same conventions**: see the HTML conventions for custom
  fields in [How to extend views and elements](../extension/how-to-extend-views-and-elements-from-third-party-module.md).

## Keeping the contract complete

Measured against the sources at `806417c` (2026-09-18): every class name and every variable the
modules render is in these two pages. No check keeps it so yet
([#305](https://github.com/Jahia/formidable/issues/305)); until one does, a hook or a variable
renamed, added or removed in the sources comes with its edit here.
