# Why Formidable uses `querySelector` inside React components

## Context

Formidable renders forms using Jahia's **Island architecture**. Each form field is a
separate server-side component (`*.server.tsx`) that may hydrate its own independent
React tree via an `<Island>` boundary.

```
Form.client.tsx  ← Island (React root #1)
  └─ <form>      ← contains server-rendered HTML
       ├─ (plain fields: server-rendered markup, no island at all — selects, radios…)
       ├─ File.client.tsx                ← Island (its own React root)
       ├─ Range.client.tsx               ← Island
       ├─ Text.client.tsx                ← Island (only when a mask is set)
       ├─ Checkbox.client.tsx            ← Island (group behaviour)
       └─ TodayBoundedInput.client.tsx   ← Island (date/datetime with a day-following bound)
```

(A field hydrates its own island only when it has client behaviour; a select never
does. The captcha widget is one more island under the Form.)

Each Island is a **self-contained React application**. There is no shared React context,
no common state tree, and no parent–child prop passing between the Form island and the
field islands.

## Why direct DOM access is necessary

Features like conditional logic visibility need the Form component to inspect the current
state of field components (selected values, checked status, etc.) and toggle their
visibility. Because these fields live in separate React roots, the only shared layer
between them is **the DOM itself**.

The server-side views emit `data-*` attributes on wrapper elements:

- `data-fmdb-node-name` — the JCR node name (stable field identifier)
- `data-fmdb-node-id` — the JCR node UUID
- `data-fmdb-logics` — serialized conditional logic rules
- `data-fmdb-logic-hidden` — current visibility state

The Form client component reads these attributes via `querySelectorAll` to evaluate
conditional logic and toggle `display` / `aria-hidden` on the wrapper `<div>`.

## Why this is acceptable

Using `querySelector` inside a React component is generally an anti-pattern because it
bypasses the virtual DOM. However, the React documentation explicitly acknowledges a
legitimate use case: **interacting with DOM nodes that React does not own**.

In Formidable, the Form component does not render the field elements — Jahia's server
runtime does. The field wrappers and their `data-*` attributes are external to the Form
React tree. This is equivalent to integrating with third-party widgets, web components,
or server-rendered markup — all cases where direct DOM access is the standard approach.

## Alternatives considered

| Approach | Why it was not chosen |
|---|---|
| Shared React context / provider | Impossible — Islands are separate React roots that do not share a provider tree. |
| Global state store (e.g. Zustand) | Adds a dependency and synchronization layer for a problem that `data-*` attributes solve simply. |
| Custom DOM events | Viable but more complex — each field would need to emit events and the Form would need listeners. Provides no benefit over reading `data-*` attributes directly. |
| Single React root for the entire form | Would break the Island architecture and require all field components to be client-side, losing the benefits of server-side rendering. |

## Summary

Direct DOM access via `querySelector` is a deliberate architectural choice driven by
Jahia's Island rendering model. It is scoped to the `<form>` element (never the full
document). The visibility pass reads `data-*` attributes and current control values,
toggles wrapper visibility AND disables/re-enables every control inside a hidden wrapper —
that disabling is load-bearing: it keeps hidden values out of `FormData`, which is what
the server's logic-coherence check (FMDB-013) relies on. This keeps the implementation
simple and avoids introducing unnecessary abstractions across isolated React roots.

