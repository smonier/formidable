# formidable-efficy Changelog

Release notes for the whole repository are assembled by [chachalog](../.chachalog) into the root
`CHANGELOG.md`; this file tracks the module itself so it can be read (or extracted) on its own.

## Unreleased (0.5.0-SNAPSHOT)

### New Features

* New module, e-deal member of the CRM connector family: **Create Efficy Opportunity** form action
  (`fmdbeff:createOpportunityAction`), read-only compatible, with a fail-or-log error policy.
* Efficy connections as OSGi factory configurations (`org.jahia.modules.formidable.efficy-<id>.cfg`):
  tenant token inline or from a file, server URL allowlist (Efficy domains plus operator suffixes),
  configurable resources and entity, **field catalog** (`sqlName|Label|type[|required]`, standard
  Opportunity fields by default) since e-deal exposes no metadata API, referential values cached
  with TTL, default person and enterprise ids.
* `EfficyOpportunityMapping` Content Editor selector (React 18, Module Federation): catalog fields
  with live referential values, constants (referential, multi, boolean, number, date editors), the
  two Efficy sources **Submission date** and **Efficy person from the submitted email**, auto-map by
  name and French synonyms, warnings for required and unknown fields, **Refresh Efficy fields**.
* `formidableEfficy` GraphQL extension (`connections`, `objectFields(refresh)`, `testConnection`),
  authorized by `jcr:modifyProperties` on the edited node.
* Person lookup by `PerMail` filling the person field and, when unmapped, the enterprise field, with
  connection defaults as fallback; value coercion per catalog type; e-deal envelope error parsing.
* English and French labels; `dev-unsigned` Maven profile for local deployments.

### Security

* The GraphQL gate also requires the `contextPath` node to be the action node, the form's `actions`
  list or the form: `jcr:modifyProperties` alone let any authenticated account reach the Efficy
  metadata through its own user node. `connections` takes the same `contextPath`; the object
  argument of `objectFields` is gone. Error text returned to the editor carries the error code only.
* Coercion errors no longer echo the submitted value, the Efficy error message is logged at `DEBUG`
  only, and the API exception's `toString` drops it, so a visitor's input never reaches the log.

### Tests

* JUnit: value coercion, mapping parsing (four sources), URL allowlist, error envelope and catalog
  parsing.
* Cypress: `84-efficy-opportunity-action` (end-to-end submissions against a mock e-deal) and
  `85-efficy-opportunity-mapping-selector` (Content Editor), with the mock server in
  `tests/efficy-mock/`.

### Documentation

* `README.md` and `docs/how-to-efficy-opportunity-action.md`.
