# Help text position

A setting added to every built-in field with a help text — where that help text goes — shown
right under **Help text** in the Content Editor, and honoured by a rendering of the text input
that takes the place of Formidable's. Nothing changes in Formidable itself.

## What the contributor gets

Formidable displays a field's help text between the label and the field. With this module
enabled on a site, every field that has a **Help text** (text, email, textarea, number, range,
date, date and time, color, file, checkbox, radio, select) gains a **Help text position**
choice, right under **Help text** in the Content Editor, with no switch to turn on first:

- **Above the field** — the default, Formidable's rendering;
- **Below the field** — the help text follows the field;
- **Above and below the field** — the help text is shown in both places.

On the pages, the **text input** honours the choice. The other fields store it; honouring it
there is a matter of shipping a rendering for them, the way this module does for the text input.

Screen readers hear the help text once whatever the position: the field references a single
help block, and the repeat of "above and below" is marked decorative.

## How it is built

Four files, and nothing in Formidable:

1. **A mixin carrying the setting** — [`settings/definitions.cnd`](../settings/definitions.cnd),
   `fmdbsamplemix:helpTextPosition`. It _extends_ the twelve built-in field types that declare a
   help text (the property belongs to each type, not to a shared mixin) and holds one property,
   `helpTextPosition`, a choice list whose labels come from this module's resource bundles
   ([`settings/resources/`](../settings/resources/)).
2. **An editor override placing the setting under Help text** —
   [`settings/jahia-content-editor-forms/forms/fmdbsamplemix_helpTextPosition.json`](../settings/jahia-content-editor-forms/forms/fmdbsamplemix_helpTextPosition.json).
   A mixin of this kind would normally show up as a separate fieldset behind a switch. This
   single override moves the field into the edited type's own fieldset right after **Help
   text**, keeps the mixin always active so the value is saved without a switch, and hides the
   now empty fieldset of the mixin. One file serves the twelve types.
3. **A rendering of the text input honouring the setting** —
   [`src/components/Input/Text/default.server.tsx`](../src/components/Input/Text/default.server.tsx).
   It is registered as the `default` view of the text input with a priority above Formidable's,
   so on a site where the module is enabled every text input renders through it; there is
   nothing to pick. It keeps everything Formidable's rendering provides — field name and id, CSS
   hooks, help block referenced by the field, custom validation messages, the `pattern` and
   formatted default of a masked field — and only changes where the help text goes. Nothing of
   that contract is written out: the view imports it from
   [`@jahia/formidable-library`](../../../packages/formidable/README.md), the package
   Formidable's own views are built on, as a module of your own would (declared as `workspace:*`
   in this repository — the [module README](../README.md) says what to write in yours).
4. **The live mask** —
   [`src/components/Input/Text/Text.client.tsx`](../src/components/Input/Text/Text.client.tsx), a
   client island hydrated when the field has a mask, built on the library's `useMask` hook: the
   value is formatted while typing, as with Formidable's rendering.

The mechanics behind each piece (how the editor ranks fields, how views of the same name are
ordered, what a default view must keep) are the "Case 4" of
[How to extend Formidable views and elements from a third-party module](../../../docs/extension/how-to-extend-views-and-elements-from-third-party-module.md).

## Enabling it on a site

1. Build and deploy the module (`yarn build`, then install the archive from `dist/package.tgz`
   with the Module Manager or the provisioning API).
2. Enable the module on the site (**Site settings** > **Modules**, or the provisioning `enable`
   operation).

Sites where the module is not enabled keep Formidable's rendering and editor form untouched.

## Limits to know

- The optional field types of `formidable-extended-inputs` (rating, scale, switch, consent) are
  not covered: this module does not depend on that module.
- Taking over the default view replaces the standard rendering of _every_ text input of the
  site. The opt-in alternative is a view under a new name that the contributor picks in the
  **View** chooser, as the [two-column fieldset](fieldset-two-columns.md) does.

## Automated test

[`tests/cypress/e2e/fields/222-help-text-position-sample.cy.ts`](../../../tests/cypress/e2e/fields/222-help-text-position-sample.cy.ts)
enables the module on the test site, then checks the rendering of the three positions (and of a
field without the setting or without help text), what a masked field keeps (its pattern, its
formatted default and its formatting while typing), and the editor form of a text input and of a
select.
