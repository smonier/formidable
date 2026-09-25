# Extending Formidable from another module

For the developer of a Jahia module that adds to the forms: a server-side action, a view, a
container, a field.

- [How to create a form action](how-to-create-form-action.md) — the `FormAction` OSGi service, its node type, the input it receives, how it fails, its authoring support
- [Field actions](../architecture/field-actions.md) — the `FieldAction` OSGi service or the `hidden.execute` view a field-action type binds to, its CND shape, the request it receives, the verdict it answers, the provider gateway; the how-to case and the editor zone follow in the next pull requests
- [How to enrich the submission response](how-to-enrich-the-submission-response.md) — the `SubmissionResponseEnricher` OSGi service: entries added to the `200` of an accepted submission, what it receives, the rules of the body, the `formidable:submitted` DOM event that hands them to the page
- [How to extend views and elements from a third-party module](how-to-extend-views-and-elements-from-third-party-module.md) — the two rendering contracts, new views for existing containers, new container types, new leaf fields, contributor settings on built-in fields, the HTML conventions of a custom field, making a field a conditional-logic source
- [`@jahia/formidable-library`](../../packages/formidable/README.md) — the published package a module of your own builds on: the help block, the validation-message attributes, the input mask

The class hooks and variables a custom field is expected to follow are in
[Styling a form](../styling/README.md); the [samples module](../../jahia-test-module/formidable-test-module-samples-tsx/README.md)
shows each extension case running.
