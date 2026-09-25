# Two-column fieldset

An extra rendering of the built-in fieldset that lays its fields out in two columns. The
contributor opts in per fieldset; Formidable's own rendering stays the default.

## What the contributor gets

In the Content Editor of a fieldset, the **View** chooser (Layout section) offers
**Fieldset - Two columns** next to Formidable's views. Picked, the fieldset's fields flow in two
columns on the page — one column on narrow screens, under 720 pixels.

## How it is built

- [`src/components/Fieldset/twoColumns.server.tsx`](../src/components/Fieldset/twoColumns.server.tsx)
  registers a view named `twoColumns` on `fmdb:fieldset`, with the display name the chooser
  shows. It owns the wrapper markup only: the fields themselves are rendered through
  Formidable's `hidden.logic` view of the container, so conditional logic, the Page Builder
  buttons and the per-field view choices keep working as in the built-in rendering.
- [`src/components/Fieldset/twoColumns.module.css`](../src/components/Fieldset/twoColumns.module.css)
  holds the grid; the view loads the built stylesheet on the page with `AddResources`.

Why a container view must delegate to `hidden.logic`, and what happens when it does not, is
explained in
[How to extend Formidable views and elements from a third-party module](../../../docs/extension/how-to-extend-views-and-elements-from-third-party-module.md)
("Case 1").
