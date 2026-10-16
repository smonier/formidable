# Administering Formidable

For the administrator of a Jahia instance running the forms.

- [Upgrade notes](upgrade-notes.md) — the manual steps between specific versions, and the migrations that run at startup
- [Configuration files](configuration.md) — the five files of the engine, one per theme, the file of the Forms import module, and the one file per entry of two lists; what each holds, and how the single file of earlier builds is carried over
- [Visitor languages](languages.md) - the languages a visitor reads the forms in (English, French, European Portuguese), what each one covers, and why the editor stays in English in Portuguese
- [Error codes](error-codes.md) — the opaque codes a failed submission returns, and what each one means
- [CAPTCHA server-side validation](captcha-server-side-validation.md) — configuring a provider, when and how a token is verified
- [Field actions: services and limits](field-actions.md) — where a check's service and credential are configured, the limits of the pre-check endpoint, and how to try a check without an account
- [Results viewing permissions](results-permissions.md) — per-form access to the submission results, deletion, what happens when a form is deleted
- [Importing the forms and results of Jahia Forms](forms-import.md) — the export to take, the Import button of the Results page, what the import recreates and reports, what to do after

Choice fields can take their options from categories or from content: declaring those sources is
described with the feature, in [Choice field options sources](../architecture/choice-field-options-sources.md#declaring-sources-administrator).
