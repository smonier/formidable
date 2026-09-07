# formidable-salesforce Changelog

Release notes for the whole repository are assembled by [chachalog](../.chachalog) into the root
`CHANGELOG.md`; this file tracks the module itself so it can be read (or extracted) on its own.

## Unreleased (0.5.0-SNAPSHOT)

### New Features

* New module: **Create Salesforce Lead** form action (`fmdbsfdc:createLeadAction`), read-only
  compatible, with create or upsert-by-email duplicate handling and a fail-or-log error policy.
* Salesforce connections as OSGi factory configurations
  (`org.jahia.modules.formidable.salesforce-<id>.cfg`): OAuth 2.0 JWT bearer signed with
  `java.security`, PKCS#8 key inline or from a file, instance URL allowlist, token cache with
  refresh on 401, describe cache with TTL. Misconfigured connections stay listed with their
  reason.
* `SalesforceLeadMapping` Content Editor selector (React 18, Module Federation): Salesforce Lead
  fields (createable, non-deprecated) against the enclosing form's fields, constants with
  picklist / boolean / date editors, auto-map by name and synonyms, warnings for unmapped
  required fields, type mismatches and fields the connection does not expose, and a
  **Refresh Salesforce fields** button bypassing the server cache. Works in edit and create mode.
* `formidableSalesforce` GraphQL extension (`connections`, `objectFields(refresh)`,
  `testConnection`), authorized by `jcr:modifyProperties` on the edited node.
* Value coercion per Salesforce type (boolean, int, double, currency, percent, date, datetime,
  multipicklist) and structured Salesforce error parsing (`errorCode`, `fields`).
* English and French labels; `dev-unsigned` Maven profile for local deployments on Jahia
  Enterprise.

### Fixes

* `@apollo/client` is pinned to the app shell's exact version (3.14.0), so the federation runtime
  never elects a second Apollo copy (white jContent page with `Invariant Violation` after a server
  restart).

### Tests

* JUnit: value coercion, mapping parsing, URL allowlist, error and describe parsing.
* Cypress: `80-salesforce-lead-action` (end-to-end submissions against a mock Salesforce) and
  `81-salesforce-lead-mapping-selector` (Content Editor), with the mock server in
  `tests/salesforce-mock/`.

### Documentation

* `README.md` and `docs/how-to-salesforce-lead-action.md`, including the connection file
  ownership requirement (a file the Jahia user cannot write is loaded read-only).
