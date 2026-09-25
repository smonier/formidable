# Custom CSS classes on a fieldset

A property added to the built-in fieldset — CSS class names the contributor types — in a
section of its own in the Content Editor, and a rendering that applies them. The contributor
opts in per fieldset.

## What the contributor gets

In the Content Editor of a fieldset, a **Style** section holds a **Custom Style** switch; on,
it reveals **CSS class names**, one or more values. Picking **Fieldset - Custom CSS class
names** in the **View** chooser renders the fieldset with those classes on its `<fieldset>`
element, next to Formidable's `fmdb-fieldset` hook, for the site's stylesheet to pick up.

## How it is built

- [`settings/definitions.cnd`](../settings/definitions.cnd) declares
  `fmdbsamplemix:customStyle`, a mixin that _extends_ `fmdb:fieldset` with the multi-valued
  `customCssClassname` property. Extending a type it does not own is what a third-party CND
  can do; the mixin is offered on every fieldset, behind a switch.
- [`settings/jahia-content-editor-forms/forms/fmdbsamplemix_customStyle.json`](../settings/jahia-content-editor-forms/forms/fmdbsamplemix_customStyle.json)
  gives the mixin its own **Style** section in the editor (its label comes from the module's
  [resource bundles](../settings/resources/), like the property's label and tooltip). Compare
  with the [help text position](help-text-position.md), where the setting is instead placed
  inside the built-in type's own fieldset and kept always on.
- [`src/components/Fieldset/customStyle.server.tsx`](../src/components/Fieldset/customStyle.server.tsx)
  registers the `customStyle` view on `fmdb:fieldset`. Like the
  [two-column fieldset](fieldset-two-columns.md), it owns the wrapper markup only and renders
  the fields through Formidable's `hidden.logic` view of the container.
