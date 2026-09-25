# formidable-extended-inputs

Optional add-on field types for Formidable (see [issue #158](https://github.com/Jahia/formidable/issues/158)).

Keeps the core element set lean and HTML5-native while richer widgets live in this optional module.
It follows the third-party extension contract documented in
[`docs/extension/how-to-extend-views-and-elements-from-third-party-module.md`](../docs/extension/how-to-extend-views-and-elements-from-third-party-module.md)
and serves as a real-world validation of it.

## Field types

| Type                         | Description                                                                                              | Submitted value        |
| ---------------------------- | -------------------------------------------------------------------------------------------------------- | ---------------------- |
| `fmdbext:rating`             | Icon rating (stars / hearts / thumbs / number chips), configurable max (2–10), optional end labels       | number `1..max`        |
| `fmdbext:scale`              | Linear scale as number chips, configurable min/max/step, optional end labels                             | number                 |
| `fmdbext:scale` (view `nps`) | Standard Net Promoter Score presentation — forces 0–10, translated default end labels                    | number `0..10`         |
| `fmdbext:switch`             | Boolean yes/no; `toggle` or `buttons` display mode, presentation-only state labels                       | boolean `true`/`false` |
| `fmdbext:consent`            | Explicit terms/GDPR consent checkbox with rich-text statement and optional link to a terms page/document | `true`                 |

All types extend `fmdbmix:element` + `fmdbmix:validationMessages`, use the standard `fmdb-` markup
conventions (`name` = node name, `id` = `<type>-<uuid>`, `fmdb-form-group` wrapper), and are pure
SSR — no client Islands, all interactivity is native HTML + CSS.

## Design decisions (from issue #158)

- **Rating**: no half-steps (accessible radio-group model). Value stored as plain number; aggregation out of scope.
- **NPS**: implemented as a _view_ on the scale type, not a separate type.
- **Switch**: value is boolean; in `toggle` mode unchecked submits nothing (standard checkbox semantics) — use `buttons` mode when an explicit `false` answer is required.
- **Consent**: stored value is simply `true`; consent metadata (timestamp/version) is out of scope. The linked terms target **must be guest-readable**.

## Known limitations

- Content-type icons are placeholders (copies of the generic component icon).

(Two earlier limitations were lifted before release: the fields now carry the engine's
semantic mixins — `fmdbmix:numberField` / `fmdbmix:booleanField`, validated server-side
with their bounds — and they can be used as conditional-logic sources.)

## Build & deploy

Same workflow as `formidable-elements`:

```bash
yarn install          # from repo root
cd formidable-extended-inputs
yarn build            # tsc + vite build + yarn pack -> dist/package.tgz
yarn deploy           # jahia-deploy to the local Jahia
```

Requires `formidable-elements` and `formidable-engine` to be deployed first.
