# formidable-efficy

Optional Formidable module that sends form submissions to **Efficy e-deal as Opportunities**.

A contributor adds the **Create Efficy Opportunity** action to any form, picks an Efficy connection
and maps the Opportunity fields to form fields, constants, the submission date, or the Efficy person
found from the submitted email, directly in Content Editor. At submit time the action posts the
opportunity to the e-deal `base_data` API. Tokens never enter the repository: each Efficy tenant is
an operator-managed OSGi configuration file.

It is the e-deal member of the CRM connector family, next to
[`formidable-salesforce`](../formidable-salesforce/README.md) and
[`formidable-hubspot`](../formidable-hubspot/README.md): same architecture, same selector, same
error policies. Only the CRM layer differs.

Part of the CRM connector family with the two other modules: see
[docs/crm-connector-actions.md](../docs/crm-connector-actions.md) for the shared architecture and
the side-by-side comparison, and its [consolidated changelog](../docs/crm-connector-actions-changelog.md).

## Contents

- [What you get](#what-you-get)
- [Architecture](#architecture)
- [What is specific to e-deal](#what-is-specific-to-e-deal)
- [Prerequisites](#prerequisites)
- [Declaring a connection on the Jahia server](#declaring-a-connection-on-the-jahia-server)
- [Authoring the action](#authoring-the-action)
- [How values are sent](#how-values-are-sent)
- [Errors and logging](#errors-and-logging)
- [Security model](#security-model)
- [Build, deploy, test](#build-deploy-test)
- [Troubleshooting](#troubleshooting)
- [Limitations and roadmap](#limitations-and-roadmap)

## What you get

| Piece | Where | Purpose |
| --- | --- | --- |
| `fmdbeff:createOpportunityAction` | `src/main/resources/META-INF/definitions.cnd` | Action node type placed under a form's `actions` list (`fmdbmix:formAction`, read-only compatible) |
| `CreateOpportunityFormAction` | `src/main/java/.../action/` | The `FormAction` OSGi service executed by the Formidable pipeline |
| `EfficyConnection` (factory) | `src/main/java/.../config/` | One DS component per `org.jahia.modules.formidable.efficy-<id>.cfg`: token, REST client, field catalog, referential cache |
| `formidableEfficyConnections` | `src/main/java/.../choicelist/` | Choice list feeding the connection dropdown of the action |
| `formidableEfficy` GraphQL | `src/main/java/.../graphql/` | `connections`, `objectFields`, `testConnection` for the editor |
| `EfficyOpportunityMapping` selector | `src/javascript/OpportunityMapping/` | React 18 Content Editor widget (Module Federation) editing the mapping |
| Labels | `src/main/resources/resources/*.properties`, `src/main/resources/javascript/locales/*.json` | English and French |

## Architecture

```
Content Editor (jContent, React 18)                  Formidable submission pipeline (Java)
┌──────────────────────────────────┐                 ┌──────────────────────────────────────┐
│ EfficyOpportunityMapping selector│                 │ FormSubmitServlet → actions in order │
│  ├─ form fields   ← core JCR GQL │                 │   └─ CreateOpportunityFormAction     │
│  └─ catalog +     ← formidable-  │                 │        ├─ FieldMapping (JSON)        │
│     referentials    Efficy.      │                 │        ├─ FormFieldIndex (form tree) │
│                     objectFields │                 │        ├─ OpportunityPayloadBuilder  │
└──────────────┬───────────────────┘                 │        ├─ Person lookup by PerMail   │
               │ stores fieldMapping JSON             │        └─ EfficyConnection.client    │
               ▼                                     └──────────────────┬───────────────────┘
      fmdbeff:createOpportunityAction node                              │ HTTPS, token header
      connectionId · fieldMapping · failSubmissionOnError               ▼
      karaf/etc/org.jahia.modules.formidable.efficy-<id>.cfg ──► {server}/{appContext}/api/base_data/1.0/Opportunity
```

## What is specific to e-deal

- **No metadata endpoint.** e-deal does not describe its entities, so the fields offered to
  contributors come from the connection's **field catalog** (`sqlName|Label|type[|required]`,
  separated by new lines or semicolons), shipped with the standard Opportunity fields. Referential
  fields (`OppStoID`, `OppOpbID`, `OppGammeShouhaitee_`…) get their values live from
  `service/1.0/referential_for?field=…`, cached (`referentialCacheTtlSeconds`) and refreshable from
  the editor with **Refresh Efficy fields**.
- **An opportunity needs a person and an enterprise.** The mapping offers the source *Efficy person
  from the submitted email*: the action looks the Person up by `PerMail`, sets the person field
  and, when the enterprise field is not mapped, the person's enterprise. The connection's
  `defaultPersonId` / `defaultEnterpriseId` are the last resort, so an anonymous visitor still
  produces an opportunity.
- **Submission date.** The source *Submission date* fills date fields (`OppDate`) with the day
  the form is submitted.
- **Authentication** is the tenant API token sent verbatim in the `Authorization` header, as the
  Efficy portal modules do. No dependency is embedded: HTTP is the JDK `HttpClient`, JSON the
  platform `org.json`.
- **Field identity** follows the family rule: `fieldKey`, then uuid, then node name.
- **Shared front-end libraries.** `@apollo/client` is pinned to the app shell's exact version
  (3.14.0); never declare it `import: false` in the vite config (see the Salesforce README).

## Prerequisites

- Jahia 8.2.2+ (Enterprise), `formidable-engine` and `formidable-elements` 0.5+, `graphql-dxm-provider`
- An Efficy e-deal tenant (server URL, application context) and an API token allowed to read
  `Person`, read referentials and create the target entity
- Build: Java 17, Maven 3, Node 22+, Yarn 4

## Declaring a connection on the Jahia server

Create `{jahia}/karaf/etc/org.jahia.modules.formidable.efficy-<id>.cfg`. The suffix is the
connection id unless `connectionId` is set. The file must be **owned and writable by the Jahia
process user**, otherwise FileInstall loads it read-only and the OSGi Configurations Manager cannot
save it.

```properties
label=Efficy e-deal - production
serverUrl=https://acme.efficytest.cloud
appContext=MutuelleAssurance
apiVersion=1.0
token=...tenant api token...
# or, Docker-secret friendly, leave token empty and set:
# tokenPath=/run/secrets/efficy-token
baseResource=base_data
serviceResource=service
objectType=Opportunity
# optional override, default = the standard Opportunity fields listed below
# fieldCatalog=OppTitle|Title|string|required; OppDetail|Details|text; OppNivSouhaite|Desired level|referential
defaultPersonId=
defaultEnterpriseId=
extraAllowedHosts=
httpConnectTimeoutSeconds=5
httpRequestTimeoutSeconds=15
referentialCacheTtlSeconds=300
```

| Key | Default | Notes |
| --- | --- | --- |
| `serverUrl` | — | `https` on `efficy.com`, `efficy.cloud`, `efficytest.cloud` or an `extraAllowedHosts` suffix |
| `appContext` | — | First path segment of the tenant (e.g. `MutuelleAssurance`) |
| `token` / `tokenPath` | — | Sent verbatim in `Authorization` |
| `objectType` | `Opportunity` | e-deal entity to create |
| `fieldCatalog` | standard Opportunity fields | `sqlName|Label|type[|required]`; types `string`, `text`, `number`, `date`, `datetime`, `boolean`, `referential`, `referential-multi`, `reference` |
| `defaultPersonId`, `defaultEnterpriseId` | — | Used when the person lookup finds nobody / the enterprise is not mapped |
| `allowInsecureDevUrl` | `false` | Development only: plain `http` on `localhost` / `host.docker.internal` (mock) |

Default catalog: `OppTitle`*, `OppDetail`, `OppEntID`* (reference), `OppPerID`* (reference),
`OppStoID`* (referential), `OppOpbID`* (referential), `OppDate`* (date), `OppStake`* (number),
`OppGammeShouhaitee_` (referential-multi), `OppNumRef`, `OppCourtier_` (reference); `*` = required
by a standard tenant. Add the tenant's custom fields as extra entries.

Verify a connection from the GraphQL playground as an editor of the path:

```graphql
{ formidableEfficy {
    connections { id label ready error }
    testConnection(connectionId: "production", contextPath: "/sites/<site>/contents") { ok message }
} }
```

## Authoring the action

1. Enable the `formidable-efficy` module on the site.
2. Open the form in jContent, go to its **actions** list and add **Create Efficy Opportunity**.
3. Pick the **Efficy connection**.
4. In **Opportunity field mapping** add rows: an Efficy field on the left (required fields first,
   starred), and on the right a form field, a constant (referential fields offer their values), the
   submission date, or the person found from an email field.
   - **Auto-map by name** pre-fills title, details, amount and reference from field names and
     synonyms (French included), the person from an email field and `OppDate` from the submission day.
   - **Refresh Efficy fields** reloads the catalog and the referential values.
   - Warnings list unmapped required fields and type mismatches; a row whose field left the catalog
     is flagged in red.
5. **Fail the submission when Efficy rejects the opportunity**: on by default. Off means the visitor
   gets a success and the rejection is only logged.
6. Publish the form.

The stored `fieldMapping` property is a JSON document owned by the selector:

```json
{"version":1,"rows":[
  {"effField":"OppTitle","effType":"string","source":"field","fieldKey":"…","fieldName":"subject","nodeId":"…"},
  {"effField":"OppPerID","effType":"reference","source":"personByEmail","fieldKey":"…","fieldName":"email","nodeId":"…"},
  {"effField":"OppStoID","effType":"referential","source":"constant","value":"000000000000074f"},
  {"effField":"OppDate","effType":"date","source":"today"}
]}
```

## How values are sent

The action posts `{"data":{"bean_data":{...}}}` to `base_data/1.0/<objectType>`:

| Catalog type | Sent as |
| --- | --- |
| number | JSON number (`12,5` accepted) |
| boolean | JSON true/false; an unchecked checkbox is false |
| date | `yyyy-MM-dd` (date input, or the submission day) |
| datetime | `yyyy-MM-dd'T'HH:mm:ss` |
| referential-multi | JSON array of ids |
| referential, reference | the 16-character e-deal id |
| string, text | first value; a checkbox group is joined with `, ` |

Blank values are omitted. Rows whose form field no longer exists are skipped and logged. Required
catalog fields still empty after the person lookup and the defaults make the action refuse to send.
Uploaded files are not sent.

## Errors and logging

e-deal wraps every answer in `{return_status, error_code, error_message}`; a `KO` status or a
non-2xx code is parsed and logged with the action path, never with submitted values. Under the
default policy the action throws a `FormActionException` with HTTP 502, surfaced to the visitor as
the generic action failure code (`FMDB-008`). Typical log lines:

```
Created Efficy Opportunity 000000000284d551 from /sites/x/contents/form/actions/efficyOpportunity (8 field(s))
Efficy refused the Opportunity: 400 (HTTP 400) - Missing mandatory fields: [OppStake]
Required Efficy field(s) not mapped or empty: [OppEntID]
```

The action is `fmdbmix:readOnlyCompatibleAction`.

## Security model

- The action node holds a connection **id**, never a URL or token.
- Server URL allowlist (https + Efficy domains or operator-declared suffixes).
- Entity and field names are validated against the SQL-name shape; the email used in the Person
  filter is refused when it contains filter syntax characters.
- GraphQL fields require `jcr:modifyProperties` on the node being edited; guests are refused.

## Build, deploy, test

```bash
yarn install                              # repo root
cd formidable-efficy
mvn -Pdev-unsigned clean install          # Java + front-end (yarn lint, tsc, vitest, vite)
```

`-Pdev-unsigned` is for **local instances only** (Jahia Enterprise refuses an unsigned
`org.jahia.modules` bundle; CI signs releases). Install
`target/formidable-efficy-0.5.0-SNAPSHOT.jar` through the module manager or the provisioning API,
then enable the module on the site.

Tests:

- Unit tests (JUnit 5): value coercion, mapping parsing, URL allowlist, error envelope and catalog
  parsing — `mvn test`.
- End-to-end (Cypress, in `tests/`): `cypress/e2e/actions/84-efficy-opportunity-action.cy.ts`
  (creation with person lookup and submission date, defaults fallback, error policies, required
  fields) and `85-efficy-opportunity-mapping-selector.cy.ts` (Content Editor selector). They need
  the mock e-deal server and a `mock` connection, see [`tests/efficy-mock/README.md`](../tests/efficy-mock/README.md);
  they skip themselves otherwise.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Module installs then disappears; `BundleLicenseCheckerListener: Invalid license check` | Unsigned local build: use `-Pdev-unsigned` (or a CI-signed jar) |
| Action missing from the *Create* menu of the actions list | Module not enabled on the site |
| Mapping field shows a raw JSON text box | jContent tab loaded before the module was deployed: hard-reload |
| `testConnection` answers `401` / `Unauthorized` | Wrong or expired tenant token |
| `Unknown operator` or `null parameter is mandatory` | A filter the tenant does not accept; the module only uses `=` filters, check a customised `baseResource` |
| `[DataDictionary] can't find field with sql name "X"` when loading fields | A catalog line names a referential field the tenant does not have: fix the `fieldCatalog` |
| `Missing mandatory fields` from e-deal | Map or default the field (person and enterprise usually come from the email lookup and the connection defaults) |
| `ReadOnlyConfigurationException` when saving in the OSGi Configurations Manager | The file is not writable by the Jahia user: `chown` it and `touch` it |

## Limitations and roadmap

- One entity per connection (Opportunity by default); another entity means another connection
  with its own catalog.
- No update or deduplication of opportunities (e-deal offers no natural key for them).
- Uploaded files are not attached to the opportunity.
- The test suite needs the Python mock on the host; an in-Jahia mock servlet would let CI run the specs.
