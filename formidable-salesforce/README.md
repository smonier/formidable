# formidable-salesforce

Optional Formidable module that sends form submissions to **Salesforce as Leads**.

A contributor adds the **Create Salesforce Lead** action to any form, picks a Salesforce
connection and maps the Lead fields to the form fields (or to constants) directly in Content
Editor. At submit time the action builds the Lead from the submitted values and calls the
Salesforce REST API. Credentials never enter the repository: each Salesforce org is an
operator-managed OSGi configuration file.

Its HubSpot twin is [`formidable-hubspot`](../formidable-hubspot/README.md); both share the same
architecture and the same authoring experience.

## Contents

- [What you get](#what-you-get)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Salesforce setup](#salesforce-setup)
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
| `fmdbsfdc:createLeadAction` | `src/main/resources/META-INF/definitions.cnd` | Action node type placed under a form's `actions` list (`fmdbmix:formAction`, read-only compatible) |
| `CreateLeadFormAction` | `src/main/java/.../action/` | The `FormAction` OSGi service executed by the Formidable pipeline |
| `SalesforceConnection` (factory) | `src/main/java/.../config/` | One DS component per `org.jahia.modules.formidable.salesforce-<id>.cfg`: JWT auth, REST client, describe cache |
| `formidableSfdcConnections` | `src/main/java/.../choicelist/` | Choice list feeding the connection dropdown of the action |
| `formidableSalesforce` GraphQL | `src/main/java/.../graphql/` | `connections`, `objectFields`, `testConnection` for the editor |
| `SalesforceLeadMapping` selector | `src/javascript/LeadMapping/` | React 18 Content Editor widget (Module Federation) editing the mapping |
| Labels | `src/main/resources/resources/*.properties`, `src/main/resources/javascript/locales/*.json` | English and French |

## Architecture

```
Content Editor (jContent, React 18)                  Formidable submission pipeline (Java)
┌──────────────────────────────────┐                 ┌──────────────────────────────────────┐
│ SalesforceLeadMapping selector   │                 │ FormSubmitServlet → actions in order │
│  ├─ form fields   ← core JCR GQL │                 │   └─ CreateLeadFormAction            │
│  └─ Lead fields   ← formidable-  │                 │        ├─ FieldMapping (JSON)        │
│        Salesforce.objectFields   │                 │        ├─ FormFieldIndex (form tree) │
└──────────────┬───────────────────┘                 │        ├─ LeadPayloadBuilder/Coercer │
               │ stores fieldMapping JSON             │        └─ SalesforceConnection.client│
               ▼                                     └──────────────────┬───────────────────┘
      fmdbsfdc:createLeadAction node                                    │ HTTPS, JWT bearer
      connectionId · fieldMapping · duplicateStrategy · failSubmissionOnError
                                                                        ▼
      karaf/etc/org.jahia.modules.formidable.salesforce-<id>.cfg ──► Salesforce org
```

- **Connections are operator configuration.** One factory `.cfg` per org; the action node only
  stores the connection id. Removing the file removes the connection, no restart needed.
- **Authentication** is the OAuth 2.0 JWT Bearer flow, signed RS256 with `java.security`. No
  third-party library is embedded: HTTP is the JDK `HttpClient`, JSON is the platform `org.json`.
- **Shared front-end libraries.** `@apollo/client` is pinned in `package.json` to the app shell's
  exact version (3.14.0): the bundle then shares the same version as the host and the federation
  runtime never elects a different Apollo copy (two copies on one cache break jContent). Do not
  declare it `import: false` in the vite config: the Jahia plugin still registers a share entry whose
  loader throws "must be provided by host", and any remote electing it takes the whole UI down.
  React, Moonstone and i18next follow the Jahia plugin defaults.
- **Field identity.** Mapping rows reference a form field by the engine's stable `fieldKey`,
  then by uuid, then by node name, so renaming or copying a field does not break the mapping.
- **Describe metadata** (`sobjects/Lead/describe`) is filtered to createable, non-deprecated
  fields, cached per connection (`describeCacheTtlSeconds`) and refreshable from the editor.

## Prerequisites

- Jahia 8.2.2+ (Enterprise), `formidable-engine` and `formidable-elements` 0.5+, `graphql-dxm-provider`
- A Salesforce org with API access and a Connected App (below)
- Build: Java 17, Maven 3, Node 22+, Yarn 4 (see the repository `mise.toml`)

## Salesforce setup

Once per org:

1. Create an RSA key pair and a self-signed certificate:
   ```bash
   openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=jahia-formidable" \
     -keyout key.pem -out cert.pem
   openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem   # PKCS#8 is required
   ```
2. *Setup → App Manager → New Connected App*: enable OAuth settings, tick **Use digital
   signatures** and upload `cert.pem`, add at least the **Manage user data via APIs (api)** scope.
3. On the app's *Manage* page set *Permitted Users* to **Admin approved users are pre-authorized**
   and assign the profile or permission set of the integration user. Without this the token
   exchange fails with `invalid_grant: user hasn't approved this consumer`.
4. Note the **Consumer Key** and the **username** of the integration user. That user needs
   create rights on Leads (plus read and edit for the upsert strategy) and field-level access
   to every field you intend to map.

## Declaring a connection on the Jahia server

Create `{jahia}/karaf/etc/org.jahia.modules.formidable.salesforce-<id>.cfg`. The suffix after the
dash is the connection id shown to contributors unless `connectionId` is set. The file must be
**owned and writable by the Jahia process user** (in Docker: `docker cp` then
`docker exec -u root <container> chown tomcat:tomcat /var/jahia/karaf/etc/<file>`): a file the
process can only read is loaded, but FileInstall marks the configuration read-only and the OSGi
Configurations Manager cannot save it (`ReadOnlyConfigurationException`).

```properties
label=Salesforce production
# login URL (JWT audience and REST base): login.salesforce.com, test.salesforce.com or your My Domain
instanceUrl=https://login.salesforce.com
clientId=3MVG9...consumer key...
username=integration@acme.com
# PKCS#8 PEM on one line, line breaks written as \n ...
privateKey=-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC...\n-----END PRIVATE KEY-----
# ... or a file (Docker secret friendly), leaving privateKey empty:
# privateKeyPath=/run/secrets/salesforce-key.pem
apiVersion=v62.0
httpConnectTimeoutSeconds=5
httpRequestTimeoutSeconds=15
# the JWT flow returns no token expiry: cache for this long, refresh on the first 401
tokenCacheTtlSeconds=3000
describeCacheTtlSeconds=300
```

| Key | Default | Notes |
| --- | --- | --- |
| `connectionId` | file suffix | Stable id stored on action nodes |
| `label` | id | Shown to contributors |
| `instanceUrl` | — | `https` on `salesforce.com` / `force.com` / `salesforce-sites.com` only |
| `clientId`, `username` | — | Connected App consumer key, integration user |
| `privateKey` / `privateKeyPath` | — | PKCS#8 only; PKCS#1 (`BEGIN RSA PRIVATE KEY`) is rejected with a conversion hint |
| `apiVersion` | `v62.0` | `vNN.0` |
| `allowInsecureDevUrl` | `false` | Development only: plain `http` on `localhost` / `host.docker.internal` (mock server) |

Several files mean several connections. A misconfigured file is still listed, suffixed
"(misconfigured)", and the reason appears in the log and in the mapping editor. Verify a
connection from the GraphQL playground as an editor of the path:

```graphql
{ formidableSalesforce {
    connections { id label ready error }
    testConnection(connectionId: "prod", contextPath: "/sites/<site>/contents") { ok message }
} }
```

## Authoring the action

1. Enable the `formidable-salesforce` module on the site (jContent only offers content types of
   modules enabled on the current site).
2. Open the form in jContent, go to its **actions** list and add **Create Salesforce Lead**.
3. Pick the **Salesforce connection**.
4. In **Lead field mapping** add rows: a Salesforce field on the left (required fields first,
   marked `*`), a form field or a constant on the right. Picklists offer their values, booleans a
   true/false choice, dates a date picker.
   - **Auto-map by name** pre-fills matches by name, label and common synonyms (email, first and
     last name, company, phone, title, website, city, country, description…), never overwriting.
   - **Refresh Salesforce fields** refetches the describe, bypassing the server cache.
   - Warnings appear for unmapped required fields (`Last Name`, `Company`), for type mismatches
     (a text field mapped to a number or a date), and in red for a field the connection does not
     expose (removed, or hidden by field-level security): Salesforce would reject the whole lead.
5. **Duplicate handling**: *Always create a new lead*, or *Update the existing lead with the same
   email* (SOQL lookup by `Email` excluding converted leads, then `PATCH`).
6. **Fail the submission when Salesforce rejects the lead**: on by default. Off means the visitor
   gets a success and the rejection is only logged.
7. Publish the form.

The stored `fieldMapping` property is a JSON document owned by the selector:

```json
{"version":1,"rows":[
  {"sfField":"LastName","sfType":"string","source":"field","fieldKey":"…","fieldName":"lastname","nodeId":"…"},
  {"sfField":"LeadSource","sfType":"picklist","source":"constant","value":"Web"}
]}
```

## How values are sent

| Salesforce type | Sent as |
| --- | --- |
| boolean | `true` / `false`; an unchecked checkbox (not submitted) is `false` |
| int, double, currency, percent | number (`12,5` accepted) |
| date | `yyyy-MM-dd` (date input) |
| datetime | `yyyy-MM-dd'T'HH:mm:ss.SSS±hhmm`, server zone, from a datetime-local input |
| multipicklist | values joined with `;` |
| anything else | first value; a checkbox group is joined with `, ` |

Blank values are omitted so Salesforce defaults apply. Rows whose form field no longer exists
are skipped and logged. Uploaded files are not sent.

## Errors and logging

Salesforce errors are parsed (`errorCode`, `fields[]`) and logged with the action path, never
with submitted values. With the default policy the action throws a `FormActionException` with
HTTP 502, which the pipeline surfaces to the visitor as its generic action failure code
(`FMDB-008`, see `docs/error-codes.md`); earlier actions of the form (for instance Save to JCR)
have already run. Typical log lines:

```
Created Salesforce Lead 00Q... from /sites/x/contents/form/actions/salesforceLead (5 field(s))
Salesforce refused the lead: INVALID_FIELD (HTTP 400) - No such column 'Description' on sobject of type Lead
Salesforce refused the lead: REQUIRED_FIELD_MISSING on [LastName] (HTTP 400) - ...
```

The action is `fmdbmix:readOnlyCompatibleAction`: it keeps working while the platform is in
read-only maintenance mode.

## Security model

- The action node holds a connection **id**, never a URL or a credential.
- Instance URL allowlist (https + Salesforce domains), also applied to the `instance_url`
  returned by the token endpoint.
- Object names are validated against the API-name shape before reaching a URL or SOQL; the email
  of the upsert lookup is escaped for the SOQL literal.
- GraphQL fields require the caller to hold `jcr:modifyProperties` on the node being edited
  (action node, or the form's `actions` list while creating); guests are refused. No CSRF
  allowlist is needed because the editor talks GraphQL.
- The token is cached for `tokenCacheTtlSeconds` and refreshed once on 401.

## Build, deploy, test

```bash
yarn install                              # repo root
cd formidable-salesforce
mvn -Pdev-unsigned clean install          # Java + front-end (yarn lint, tsc, vitest, vite)
```

`-Pdev-unsigned` is for **local instances only**: Jahia Enterprise refuses an `org.jahia.modules`
bundle without the `Jahia-Signature` header, which the repository CI adds. The profile relabels
the bundle under `org.jahia.community.modules` so it starts locally; never release with it.
Install `target/formidable-salesforce-0.5.0-SNAPSHOT.jar` through the module manager or the
provisioning API (`installOrUpgradeBundle`), then enable the module on the site.

Tests:

- Unit tests (JUnit 5): value coercion, mapping parsing, URL allowlist, error parsing —
  `mvn test`.
- End-to-end (Cypress, in `tests/`): `cypress/e2e/actions/80-salesforce-lead-action.cy.ts`
  (creation, coercion, upsert, error policies, required fields) and
  `81-salesforce-lead-mapping-selector.cy.ts` (Content Editor selector). They need the mock
  Salesforce server and a `mock` connection, see [`tests/salesforce-mock/README.md`](../tests/salesforce-mock/README.md);
  they skip themselves otherwise.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Module installs then disappears; log says `BundleLicenseCheckerListener: Invalid license check` | Unsigned local build: use `-Pdev-unsigned` (or a CI-signed jar) |
| Action missing from the *Create* menu of the actions list | Module not enabled on the site |
| Mapping field shows a raw JSON text box | jContent tab loaded before the module was deployed: hard-reload |
| Connection listed as "(misconfigured)" | Read the reason in the log or in the editor: bad URL host, missing key, PKCS#1 key… |
| `invalid_grant: user hasn't approved this consumer` | Pre-authorize the integration user on the Connected App |
| `INVALID_FIELD: No such column 'X'` | The field is not createable or not visible to the integration user: remove it from the mapping (the editor flags it in red) or fix field-level security |
| Config file ignored, `Permission denied` in the FileInstall log | Make the `.cfg` readable by the Jahia user; FileInstall only rescans a file whose modification time changed |
| White jContent page after a restart, console shows `Invariant Violation` from Apollo | A bundle registered its own `@apollo/client` as a shared singleton and the runtime elected it over the host's copy. The module pins `@apollo/client` to the host's exact version in `package.json`; keep the pin aligned with the shell when bumping dependencies, and never use `shared: {..., import: false}` |
| `ReadOnlyConfigurationException` when saving in the OSGi Configurations Manager | The file is not writable by the Jahia user: `chown` it to that user and `touch` it, FileInstall then drops the READ_ONLY attribute |

## Limitations and roadmap

- Lead only. The internals (`SObject` parameter, generic coercion) are ready for other objects.
- Uploaded files are not sent to Salesforce.
- One integration user per connection; per-site connections are modelled with several `.cfg`
  files and the site's editors picking the right one.
- The test suite needs the Python mock on the host; an in-Jahia mock servlet would let CI run
  the specs.
