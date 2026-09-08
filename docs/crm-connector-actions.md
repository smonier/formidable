# CRM connector actions: Salesforce, HubSpot, Efficy

Three optional Formidable modules send form submissions to a CRM as a new record. They share one
architecture, one authoring experience and one security model; only the CRM-specific parts differ.
This page is the entry point for the family. Each module keeps its own README with the full
reference, and its own changelog:

| Module | Action (node type) | Creates | Content Editor selector | README | Changelog |
|---|---|---|---|---|---|
| `formidable-salesforce` | Create Salesforce Lead (`fmdbsfdc:createLeadAction`) | Salesforce **Lead** | `SalesforceLeadMapping` | [README](../formidable-salesforce/README.md) | [CHANGELOG](../formidable-salesforce/CHANGELOG.md) |
| `formidable-hubspot` | Create HubSpot Contact (`fmdbhs:createContactAction`) | HubSpot **Contact** | `HubspotContactMapping` | [README](../formidable-hubspot/README.md) | [CHANGELOG](../formidable-hubspot/CHANGELOG.md) |
| `formidable-efficy` | Create Efficy Opportunity (`fmdbeff:createOpportunityAction`) | Efficy e-deal **Opportunity** | `EfficyOpportunityMapping` | [README](../formidable-efficy/README.md) | [CHANGELOG](../formidable-efficy/CHANGELOG.md) |

Consolidated release notes for the three modules: [crm-connector-actions-changelog.md](crm-connector-actions-changelog.md).
Step-by-step guides: [Salesforce](how-to-salesforce-lead-action.md), [HubSpot](how-to-hubspot-contact-action.md),
[Efficy](how-to-efficy-opportunity-action.md).

## Contents

- [What the three actions have in common](#what-the-three-actions-have-in-common)
- [What differs per CRM](#what-differs-per-crm)
- [Connections: operator configuration](#connections-operator-configuration)
- [Authoring an action in Content Editor](#authoring-an-action-in-content-editor)
- [What happens on submission](#what-happens-on-submission)
- [Errors, logging and the visitor](#errors-logging-and-the-visitor)
- [Security model](#security-model)
- [Build, deploy, test](#build-deploy-test)
- [Troubleshooting](#troubleshooting)
- [Adding a fourth connector](#adding-a-fourth-connector)

## What the three actions have in common

- **A Formidable action** implementing the engine's `FormAction` SPI, declared as an OSGi
  Declarative Services component. It extends `fmdbmix:formAction` and
  `fmdbmix:readOnlyCompatibleAction`, so it can be added to any form's action list, in any order,
  and keeps working when the site is in read-only maintenance mode. Dropping the action on a form
  is the only wiring needed: the mapping is authored inside the action node.
- **Connections outside the JCR.** Credentials never enter the repository. A connection is an OSGi
  factory configuration file (`org.jahia.modules.formidable.<crm>-<id>.cfg`) owned by the
  operator; the action node only stores the connection id, chosen from a choicelist that lists the
  connections currently loaded, including misconfigured ones with the reason they are unusable.
- **A field-mapping selector** in Content Editor (React 18, Module Federation, moonstone). It shows
  the CRM's writable fields on one side and the enclosing form's fields on the other, maps them
  automatically by name and synonyms, lets the author pick a form field or type a constant with a
  type-aware editor (picklist, boolean, date, number...), warns about unmapped required fields,
  type mismatches and fields the connection does not expose, and offers a **Refresh** button that
  bypasses the server-side cache. The mapping is persisted as a JSON document in the
  `fieldMapping` property.
- **A GraphQL extension** per module (`formidableSalesforce`, `formidableHubspot`,
  `formidableEfficy`) exposing `connections(contextPath)`, `objectFields(connectionId, contextPath, refresh)`
  and `testConnection(connectionId, contextPath)`. Every query requires `jcr:modifyProperties` on the node being
  edited, which must be the action node, the form's `actions` list or the form (any other writable node is
  refused, since every account owns its own user node), and refuses guests. Error text returned to the editor
  carries the CRM error code only.
- **Value coercion** from the submitted strings to what the CRM expects per field type, and
  **structured error parsing** of the CRM's error payload.
- **A fail-or-log policy**: `failSubmissionOnError` (default true) makes a CRM failure fail the
  whole submission with error code `FMDB-008`; when false the error is logged and the other actions
  still run.
- **English and French** labels, a `dev-unsigned` Maven profile for local deployments on Jahia
  Enterprise, JUnit tests and two Cypress specs each, run against a mock server shipped in
  `tests/`.

## What differs per CRM

| | Salesforce | HubSpot | Efficy e-deal |
|---|---|---|---|
| Authentication | OAuth 2.0 JWT bearer (consumer key + PKCS#8 private key, `java.security`) | Private app access token (`pat-...`). Developer API keys are refused by the CRM endpoints | Tenant token sent verbatim in `Authorization` |
| Field discovery | `sobjects/Lead/describe`, createable and non-deprecated fields | `GET /crm/v3/properties/contacts`, writable properties (not read-only, hidden, calculated or archived) | No metadata API: a **field catalog** per connection (`sqlName\|Label\|type[\|required]`), standard Opportunity fields by default; referential values fetched live from `service/1.0/referential_for` |
| Create call | `POST sobjects/Lead` | `POST crm/v3/objects/contacts` | `POST base_data/1.0/Opportunity` with `{data:{bean_data}}` and a `return_status` envelope |
| Duplicate handling | `duplicateStrategy`: create, or upsert by `Email` (query then PATCH) | `duplicateStrategy`: create, or upsert by `email`; HubSpot's 409 "Contact already exists" becomes an update | None: every submission is a new opportunity |
| Extra mapping sources | field, constant | field, constant | field, constant, **submission date**, **Efficy person from the submitted email** (`PerMail` lookup fills the person and, when unmapped, the enterprise) plus connection-level default ids |
| Allowed hosts | `salesforce.com`, `force.com` | `hubapi.com` | `efficy.com`, `efficy.cloud`, `efficytest.cloud`, plus `extraAllowedHosts` |
| Caches | token (refresh on 401) and describe (TTL) | properties (TTL) | referential values (TTL) |
| Typical constants | `Company`, `LeadSource`, custom `__c` fields | `lifecyclestage`, `hs_lead_status` | state (`OppStoID`), probability (`OppOpbID`), amount (`OppStake`) |

## Connections: operator configuration

Create one file per connection in Karaf's `etc/` directory (in the Docker image:
`/var/jahia/karaf/etc/`). The suffix after the dash is the connection id shown to authors unless
`connectionId` overrides it.

```properties
# org.jahia.modules.formidable.salesforce-prod.cfg
label=Production org
instanceUrl=https://login.salesforce.com
clientId=<consumer key>
username=integration@example.com
privateKeyPath=/run/secrets/salesforce-key.pem
```

```properties
# org.jahia.modules.formidable.hubspot-prod.cfg
label=Marketing portal
accessTokenPath=/run/secrets/hubspot-token
```

```properties
# org.jahia.modules.formidable.efficy-prod.cfg
label=e-deal production
serverUrl=https://<tenant>.efficy.cloud
appContext=<application context>
tokenPath=/run/secrets/efficy-token
# fieldCatalog=OppTitle|Title|string|required; OppDetail|Details|text; ...
```

Rules that apply to all three:

- The file must be **readable and writable by the Jahia process user** (uid 9999, `tomcat`, in the
  Docker image). A file FileInstall cannot write is loaded read-only and shows up as
  `ReadOnlyConfigurationException` in the OSGi Configurations Manager.
- Secrets may be inline or referenced by a `...Path` key pointing at a file (Docker secret
  friendly). Prefer the file.
- FileInstall picks up changes on modification time: `touch` the file after editing it in place.
- A connection that fails validation (missing key, host outside the allowlist, unreadable secret)
  is still listed in the editor with its reason, so authors know why they cannot pick it.

## Authoring an action in Content Editor

1. Open the form in jContent, go to its **actions** list and add the action (Create Salesforce
   Lead, Create HubSpot Contact or Create Efficy Opportunity). Actions run in list order; keep
   **Save to JCR** first if you want a local copy even when the CRM refuses the record.
2. Pick the **connection**.
3. Use the mapping selector: press **Auto-map by name** (or accept the mapping proposed on first open), fix the
   remaining rows, add constants for values the visitor does not type (company, lead source,
   lifecycle stage, opportunity state...) with **Add mapping**, then **Refresh** if the CRM schema changed since the
   server cached it.
4. Choose the duplicate strategy (Salesforce and HubSpot) and whether a CRM error should fail
   the submission.
5. Save and **publish** the form: submissions run against the live workspace.

The mapping references form fields by their stable `fieldKey`, so renaming a field's label or
moving it between steps does not break the mapping; deleting a field leaves a row the selector
flags as missing.

## What happens on submission

1. The engine runs the actions in order and hands each the submitted values keyed by field name.
2. The action loads its connection, parses the mapping, resolves every row (form value, constant,
   or a CRM-specific source), coerces each value to the CRM field type and drops empty optional
   values.
3. CRM-specific pre-steps run: Salesforce and HubSpot look the record up by email when upserting;
   Efficy resolves the person by email and applies connection defaults.
4. The record is created (or updated) with one API call. Unknown fields reported by the CRM are
   surfaced in the error and, for Salesforce and HubSpot, flagged in the editor as fields the
   connection does not expose.

## Errors, logging and the visitor

- A CRM failure is logged at `WARN` with the connection id, the CRM error code and the offending
  fields when the CRM names them; the CRM's own message, which may quote the rejected value, is logged
  at `DEBUG` only. Coercion errors name the field and the type, never the value. Tokens and private keys
  are never logged.
- With `failSubmissionOnError=true` the visitor sees the form's error message and the submission
  reports `FMDB-008` (see [error-codes.md](error-codes.md)); actions later in the list do not run.
- With `failSubmissionOnError=false` the submission succeeds from the visitor's point of view and
  the failure is only in the logs. Use this when the CRM is a secondary copy of a submission
  already saved to JCR.

## Security model

- Credentials live in operator configuration, never in the JCR and never in the browser. The
  selector talks to the CRM through the module's GraphQL extension only.
- GraphQL queries require `jcr:modifyProperties` on the edited node, which must be the action node, the
  form's `actions` list or the form, and refuse guests.
- Outbound URLs are validated against a per-CRM host allowlist, so a mistyped or hostile
  connection file cannot turn Jahia into a proxy.
- Entity and field names are validated before they reach a URL or a filter expression; the Efficy
  client additionally refuses lookup values carrying e-deal filter syntax.
- The action runs with the submission's context; it never opens a system JCR session.

## Build, deploy, test

```bash
# from the repository root, Java 17
mvn -pl formidable-salesforce,formidable-hubspot,formidable-efficy -am install -Pdev-unsigned
```

Deploy the three jars with the provisioning API or the module manager, enable each module on the
site, then reload the jContent tab (the editor bundles are federated at page load).

Tests:

| Spec | Covers |
|---|---|
| `80-salesforce-lead-action`, `81-salesforce-lead-mapping-selector` | submissions against `tests/salesforce-mock/` (port 8090), selector |
| `82-hubspot-contact-action`, `83-hubspot-contact-mapping-selector` | submissions against `tests/hubspot-mock/` (port 8091) including the 409 conflict, selector |
| `84-efficy-opportunity-action`, `85-efficy-opportunity-mapping-selector` | submissions against `tests/efficy-mock/` (port 8092) including the person lookup, selector |

Each mock is a dependency-free Python server; the Jahia container reaches it as
`host.docker.internal:<port>`. The specs recreate the `FormidableSite4Tests` site, so run the
playground again afterwards if you use it for manual testing.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Action missing from the actions list | Module not enabled on the site, or stale jContent tab | Enable the module on the site, reload the tab |
| Mapping shows a raw JSON string instead of the selector | Editor bundle not loaded (module stopped, or federation failure) | Check the module is Started, reload; look at the browser console |
| Connection listed with a reason and greyed out | Configuration failed validation | Read the reason, fix the `.cfg`, `touch` it |
| `ReadOnlyConfigurationException` on the connection | `.cfg` file not writable by the Jahia user | `chown` it to the Jahia user, `touch` it |
| Salesforce `INVALID_FIELD` | Field not on the Lead, or a custom field named without `__c` | Refresh fields, remap to the flagged field |
| HubSpot `INVALID_AUTHENTICATION` | Developer API key instead of a private app token | Create a private app, use its `pat-` token |
| Efficy `Unknown operator` or `KO` envelope | Filter syntax in a lookup value, or a catalog `sqlName` unknown to the tenant | Check the catalog against the tenant's Opportunity fields |
| White jContent page after a Jahia restart | A UI extension shares `@apollo/client` at another version than the app shell | All editor bundles pin the host's exact Apollo version; rebuild the offending module |

## Adding a fourth connector

Copy the smallest module (`formidable-hubspot`) and keep its package layout: `config/` (OCD +
connection), `client/` (REST client, URL validator, error parser), `mapping/` (rows, coercer,
payload builder), `action/`, `choicelist/`, `graphql/`. Keep the same GraphQL shape and the same
mapping JSON contract (`version`, `rows[]` with `source`, `fieldKey`, `fieldName`, `nodeId`,
`value`) so the selectors stay interchangeable, and add two Cypress specs plus a mock server. The
[how-to-create-form-action](how-to-create-form-action.md) page covers the engine SPI itself.
