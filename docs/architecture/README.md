# How Formidable works

For the maintainer: how the modules work, and why they are built this way.

## How it works

- [Form submission flow](form-submission-flow.md) — the request lifecycle, the twelve-step pipeline (plus the field actions' step 11b), the trust model and the server-side safeguards
- [Save to JCR](save-to-jcr.md) — how submissions and uploaded files are stored, the results tree, personal data
- [Export](export.md) — the multi-format export of the results (CSV, JSON) and how to add a format
- [Custom validation](custom-validation.md) — inline validation messages replacing the native browser tooltips, with per-field contributor overrides
- [Conditional logic field resolution](conditional-logic-field-resolution.md) — the weakref-based model of the conditional-logic dependencies, and how rules are resolved
- [Choice field options sources](choice-field-options-sources.md) — options from categories or from content: storage model, resolution, cache, writing a source initializer
- [Field actions](field-actions.md) — a check of one field's value run server-side while the visitor fills the form and again at submission: the form-action model one level down (marker, per-field list, switch in the field's editor), a Java service or a `hidden.execute` view as the action's code, the pre-check endpoint and pipeline step 11b sharing one verdict cache, the provider gateway that keeps credentials in the configuration
- [jExperience integration](jexperience-integration.md) — design and implementation log (revised 2026-09-10, browser-side): the auto-generated form mapping, the submission event sent by the tracker with the accepted values of the form's fields, minus those the author marked sensitive, prefill from the tracker's context, the send condition and consent gates, the form's UUID as its identity in jCustomer; phases 1 and 2 (profile-mappable fields, the jExperience editor section, the mapping rule kept in sync with publication) shipped, phase 3 (the submission event) in progress

## Why it is built this way

- [CND module ownership](cnd-module-ownership.md) — where JCR types and mixins belong, and how to choose between `formidable-elements` and `formidable-engine`
- [Dependency decisions](dependency-decisions.md) — embedded or provided dependencies in the Java modules, and why
- [Why `querySelector` in React](why-queryselector-in-react.md) — why the client components read the DOM directly
