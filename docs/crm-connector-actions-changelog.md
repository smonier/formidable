# CRM connector actions: consolidated changelog

Release notes for the three CRM connector modules, read together. Each module also keeps its own
file ([Salesforce](../formidable-salesforce/CHANGELOG.md), [HubSpot](../formidable-hubspot/CHANGELOG.md),
[Efficy](../formidable-efficy/CHANGELOG.md)); the repository-wide notes are assembled by
[chachalog](../.chachalog) into the root `CHANGELOG.md`.

## Unreleased (0.5.0-SNAPSHOT), branch `feature/crm-connector-actions`

### Added

**Shared by the three modules**

* A Formidable action per CRM, read-only compatible, with a `failSubmissionOnError` fail-or-log
  policy (`FMDB-008` when the submission is failed).
* Connections as OSGi factory configurations (`org.jahia.modules.formidable.<crm>-<id>.cfg`),
  secrets inline or from a file, host allowlist per CRM, misconfigured connections listed with
  their reason.
* A Content Editor mapping selector per CRM (React 18, Module Federation): CRM fields against the
  enclosing form's fields, auto-map by name and synonyms, constants with type-aware editors,
  warnings for required, mismatched and unavailable fields, a refresh button bypassing the server
  cache. Mapping persisted as JSON in `fieldMapping`, keyed by the stable `fieldKey`.
* A GraphQL extension per CRM (`connections`, `objectFields(refresh)`, `testConnection`),
  authorized by `jcr:modifyProperties` on the edited node, guests refused.
* Value coercion per CRM field type and structured CRM error parsing.
* English and French labels, `dev-unsigned` Maven profile, JUnit tests, two Cypress specs per
  module with a dependency-free mock server under `tests/`.
* Documentation: a README and a how-to per module, this family page
  ([crm-connector-actions.md](crm-connector-actions.md)) and this consolidated changelog.

**formidable-salesforce: Create Salesforce Lead** (`fmdbsfdc:createLeadAction`)

* OAuth 2.0 JWT bearer signed with `java.security` (PKCS#8 key inline or from a file), token
  cache with refresh on 401, describe cache with TTL, allowlist `salesforce.com` / `force.com`.
* Lead fields from `sobjects/Lead/describe`, createable and non-deprecated.
* `duplicateStrategy`: create, or upsert by `Email`.
* Coercion for boolean, int, double, currency, percent, date, datetime and multipicklist; error
  parsing of `errorCode` and `fields`.
* Cypress `80-salesforce-lead-action`, `81-salesforce-lead-mapping-selector`; mock on port 8090.

**formidable-hubspot: Create HubSpot Contact** (`fmdbhs:createContactAction`)

* Private app access token (developer API keys are refused by the CRM endpoints, documented),
  properties cache with TTL, allowlist `hubapi.com`.
* Writable contact properties from `GET /crm/v3/properties/contacts` (not read-only, hidden,
  calculated or archived).
* `duplicateStrategy`: create, or upsert by `email`; HubSpot's 409 "Contact already exists" is
  turned into an update when upserting.
* Coercion for bool / booleancheckbox, number, date, datetime and multi-value enumeration; error
  parsing of `category` and `errors[].context.propertyName`.
* Documentation of the Contacts versus Leads model (lifecycle stage constants).
* Cypress `82-hubspot-contact-action`, `83-hubspot-contact-mapping-selector`; mock on port 8091.

**formidable-efficy: Create Efficy Opportunity** (`fmdbeff:createOpportunityAction`)

* Tenant token sent verbatim, allowlist `efficy.com` / `efficy.cloud` / `efficytest.cloud` plus
  `extraAllowedHosts`, configurable resources and entity.
* A field catalog per connection (`sqlName|Label|type[|required]`, standard Opportunity fields by
  default) since e-deal exposes no metadata API; referential values fetched live and cached with
  TTL.
* Two extra mapping sources: the submission date, and the Efficy person resolved from the
  submitted email (`PerMail`), whose enterprise fills the enterprise field when unmapped;
  connection-level default person and enterprise ids as fallback.
* Creation through `POST base_data/1.0/<entity>` with the `return_status` envelope parsed;
  entity and field names validated, lookup values refused when they carry filter syntax.
* Cypress `84-efficy-opportunity-action`, `85-efficy-opportunity-mapping-selector`; mock on
  port 8092.

### Security

* The GraphQL gate now also requires the `contextPath` node to be the module's action node, the form's
  `actions` list or the form: a permission check alone let any authenticated account read the CRM
  metadata and exercise the connections through its own user node. `connections` takes the same
  `contextPath` and gate; the free object argument of `objectFields` is gone (Lead, contacts, or the
  Efficy connection's entity). Error text returned to the editor carries the CRM error code only.
* Log hygiene: coercion errors no longer echo the submitted value, the CRM's own error message is
  logged at `DEBUG` only, and the API exceptions' `toString` (printed in stack traces) drops it too.

### Fixed

* All editor bundles, including `formidable-engine`, pin `@apollo/client` to the app shell's exact
  version (3.14.0) instead of shipping their own or declaring it host-provided, so the federation
  runtime cannot elect a second Apollo copy after a Jahia restart (white jContent page with
  `Invariant Violation`) nor fail with "Shared module '@apollo/client' must be provided by host".

### Known limitations

* One record type per module: Lead, Contact, Opportunity. Other objects need a new action or a
  configurable entity (Efficy already exposes `objectType` in the connection).
* No retry queue: a CRM outage fails or logs the submission according to `failSubmissionOnError`;
  keep Save to JCR first in the action list to retain a copy.
* Efficy has no duplicate strategy, since e-deal offers no natural key for opportunities.
* Modules are not signed for Jahia Enterprise; local deployments use the `dev-unsigned` profile.
