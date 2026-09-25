# formidable-modules

## What is Formidable?
Formidable is the new solution to manage Forms with Jahia. It will fully replace Jahia Forms. It is currently in development.

## Main architecture principles
Formidable is based on Jahia standard technologies:
- Forms, steps, fieldsets and fields are regular content items
- Rendering / views use JavaScript modules, built in React / TSX
- Actions are declared in Java / OSGi, as per any other action inside Jahia
- The editor extension (custom selectors, form results admin) uses Module Federation via `@jahia/vite-federation-plugin`

## Prerequisites
- Jahia 8.2.2+
- JavaScript modules 1.2.0+
- jContent 3.6+

## Documentation

[`docs/README.md`](docs/README.md) is the map: one folder per reader.

- [`docs/styling/`](docs/styling/README.md) — for the template set: how the rendered form is structured, the class hooks, the CSS variables
- [`docs/extension/`](docs/extension/README.md) — for a third-party module: adding a form action, a view, a container, a field
- [`docs/administration/`](docs/administration/README.md) — for the administrator: upgrade notes, error codes, CAPTCHA verification, results permissions
- [`docs/architecture/`](docs/architecture/README.md) — for the maintainer: how the modules work, and why they are built this way

## Scope

The current release includes the ability to:

- Create forms, with multi-step support, fieldsets, and 14 core field types (plus 4
  optional ones in formidable-extended-inputs — Consent, Rating, Scale, Switch):
  - Text input (with optional pattern, mask, datalist)
  - Textarea (with resize, spellcheck, autocomplete options)
  - Email (with pattern, datalist, multiple recipients)
  - Select (dropdown, with multiple selection and size options)
  - Checkbox (single or group)
  - Radio (single or group)
  - Date (with min/max/step)
  - Datetime-local (with min/max/step)
  - Number (with min/max/step)
  - Range (slider with bounds, end labels and tick marks)
  - File upload (with MIME type filtering, multiple files, image/video/PDF support)
  - Color
  - Hidden
  - Rich text (embeddable HTML content block)
- Create form references (reuse an existing form via weak reference)
- Conditional logic (show/hide fields based on other field values, using weakref-based dependency resolution)
- 4 built-in form actions:
  - Save to JCR (persists submissions and uploaded files under `formidable-results/`)
  - Send email notification (with `${fieldName}` interpolation in subject and body)
  - Send email content (sends the full submission by email, with optional file attachments)
  - Forward to external endpoint (multipart/form-data POST to operator-configured targets)
- CAPTCHA support (tested with Google reCAPTCHA, Cloudflare Turnstile and hCaptcha)
- Require authenticated user (reject anonymous submissions server-side)
- Server-side submission validation (field constraints, allowed MIME types, choice value allowlists, cross-origin request checks)
- Form results admin panel in jContent (only for forms using the Save to JCR action):
  - Browse saved submissions per form
  - Detail panel with metadata, field values, file cards with thumbnails and preview
  - Keyboard navigation (arrow keys)
  - Multi-format export (CSV, JSON) with date range filtering
  - Per-form access control via `fmdb-results-reader` role (results are private by default)
  - Submission deletion (admin-only in v1)
- Custom CSS injection per form (see [Styling a form](docs/styling/README.md) for the class hooks and CSS variables)
- Extension points:
  - Create your own action (implement `FormAction` OSGi service via `org.jahia.modules.formidable.engine.api`)
  - Overwrite the view for a field type
  - Create a new field type (CND + server view + optional client Island)

### Packaging
- 7 modules:
  - **formidable-elements** — provides the fields, form structure and rendering views
  - **formidable-engine** — provides the action framework (Java/OSGi), CAPTCHA verification, editor extensions (custom selectors, form results panel)
  - **formidable-extended-inputs** — provides the optional extra field types, as a separate module so a site can stay on the core set
  - **formidable-jexperience-engine** — integrates the forms with jExperience when it is installed: fields mapped to the visitor's profile from a **jExperience** section of the editor, the form's identity in jCustomer (Java/OSGi, depends on `formidable-engine` and `jexperience`; the mapping rule, the submission event and the prefill follow — see [`docs/architecture/jexperience-integration.md`](docs/architecture/jexperience-integration.md))
  - **formidable-salesforce** - optional: the "Create Salesforce Lead" action (Java/OSGi) plus its `SalesforceLeadMapping` Content Editor selector (React 18, Module Federation)
  - **formidable-hubspot** - optional: the "Create HubSpot Contact" action plus its `HubspotContactMapping` selector, same architecture
  - **formidable-efficy** - optional: the "Create Efficy Opportunity" action (e-deal) plus its `EfficyOpportunityMapping` selector, with a field catalog per connection
- 1 npm package, [`@jahia/formidable-library`](packages/formidable/README.md) — the markup contract and input-mask behaviour a module of your own builds on to render form fields, published with each release

### Current known limitations
- When selecting a field, users don't know what it will look like. This pain point will be addressed globally inside Jahia in 2026.
- Forms embedded in a page can be edited through Page Builder (each element gets its own
  box, and the form's actions are listed under it with their own **New Form Action**
  button), but forms cannot yet be created or authored in Page Builder mode from jContent's
  Content Folders — that is targeted for 1.0. Until then, form authoring happens in
  jContent's list or structured view.
