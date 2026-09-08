# formidable-hubspot Changelog

Release notes for the whole repository are assembled by [chachalog](../.chachalog) into the root
`CHANGELOG.md`; this file tracks the module itself so it can be read (or extracted) on its own.

## Unreleased (0.5.0-SNAPSHOT)

### New Features

* New module, twin of `formidable-salesforce`: **Create HubSpot Contact** form action
  (`fmdbhs:createContactAction`), read-only compatible, with create or upsert-by-email duplicate
  handling (HubSpot's 409 "Contact already exists" is turned into an update when upserting) and
  a fail-or-log error policy.
* HubSpot connections as OSGi factory configurations
  (`org.jahia.modules.formidable.hubspot-<id>.cfg`): private app access token inline or from a
  file, API base URL allowlist (`hubapi.com`), properties cache with TTL. Misconfigured
  connections stay listed with their reason.
* `HubspotContactMapping` Content Editor selector (React 18, Module Federation): writable contact
  properties from `GET /crm/v3/properties/contacts` (not read-only, hidden, calculated or
  archived) against the enclosing form's fields, constants with enumeration / multi-checkbox /
  boolean / date editors, auto-map by name and synonyms, warnings for type mismatches and
  properties the connection does not expose, **Refresh HubSpot properties** button.
* `formidableHubspot` GraphQL extension (`connections`, `objectFields(refresh)`,
  `testConnection`), authorized by `jcr:modifyProperties` on the edited node.
* Value coercion per HubSpot type and field type (bool / booleancheckbox, number, date,
  datetime, multi-value enumeration) as the string values HubSpot expects, and structured error
  parsing (`category`, `errors[].context.propertyName`).
* English and French labels; `dev-unsigned` Maven profile for local deployments on Jahia
  Enterprise.

### Security

* The GraphQL gate also requires the `contextPath` node to be the action node, the form's `actions`
  list or the form: `jcr:modifyProperties` alone let any authenticated account reach the HubSpot
  metadata through its own user node. `connections` takes the same `contextPath`; the object
  argument of `objectFields` is gone. Error text returned to the editor carries the error code only.
* Coercion errors no longer echo the submitted value, the HubSpot error message is logged at `DEBUG`
  only, and the API exception's `toString` drops it, so a visitor's input never reaches the log.

### Fixes

* `@apollo/client` is pinned to the app shell's exact version (3.14.0), so the federation runtime
  never elects a second Apollo copy (white jContent page with `Invariant Violation` after a server
  restart).

### Tests

* JUnit: value coercion, mapping parsing, URL allowlist, error and property parsing.
* Cypress: `82-hubspot-contact-action` (end-to-end submissions against a mock HubSpot, including
  the 409 conflict) and `83-hubspot-contact-mapping-selector` (Content Editor), with the mock
  server in `tests/hubspot-mock/`.

### Documentation

* `README.md` and `docs/how-to-hubspot-contact-action.md`, including the Contacts versus Leads
  model (lifecycle stage constants), the private app token requirement (developer API keys are
  refused by the CRM endpoints) and the connection file ownership requirement.
