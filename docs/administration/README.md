# Administering Formidable

For the administrator of a Jahia instance running the forms.

- [Upgrade notes](upgrade-notes.md) — the manual steps between specific versions, and the migrations that run at startup
- [Error codes](error-codes.md) — the opaque codes a failed submission returns, and what each one means
- [CAPTCHA server-side validation](captcha-server-side-validation.md) — configuring a provider, when and how a token is verified
- [Results viewing permissions](results-permissions.md) — per-form access to the submission results, deletion, what happens when a form is deleted

Choice fields can take their options from categories or from content: declaring those sources is
described with the feature, in [Choice field options sources](../architecture/choice-field-options-sources.md#declaring-sources-administrator).

## CRM connector actions

The optional `formidable-salesforce`, `formidable-hubspot` and `formidable-efficy` modules each add
a form action that creates a record in a CRM. The administrator declares the connections in
operator configuration; authors map the form fields to the CRM fields in Content Editor.

- [CRM connector actions](crm-connector-actions.md) - the three actions side by side: connections, authoring, errors, security, troubleshooting
- [Salesforce Lead action](how-to-salesforce-lead-action.md) - the Connected App and JWT bearer setup, the connection file, the mapping
- [HubSpot Contact action](how-to-hubspot-contact-action.md) - the private app token, the connection file, contacts versus leads
- [Efficy Opportunity action](how-to-efficy-opportunity-action.md) - the tenant token, the field catalog, the person lookup by email
- [Consolidated changelog](crm-connector-actions-changelog.md) - the release notes of the three modules, read together
