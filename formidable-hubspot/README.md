# formidable-hubspot

Optional Formidable module that sends form submissions to **HubSpot as Contacts**.

A contributor adds the **Create HubSpot Contact** action to any form, picks a HubSpot connection
and maps the contact properties to the form fields (or to constants) directly in Content Editor.
At submit time the action builds the contact from the submitted values and calls the HubSpot
CRM v3 API. Tokens never enter the repository: each HubSpot portal is an operator-managed OSGi
configuration file.

It is the twin of [`formidable-salesforce`](../formidable-salesforce/README.md): same
architecture, same selector, same error policies. Only the CRM layer differs.

## Contents

- [What you get](#what-you-get)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [HubSpot setup](#hubspot-setup)
- [Declaring a connection on the Jahia server](#declaring-a-connection-on-the-jahia-server)
- [Authoring the action](#authoring-the-action)
- [Contacts, lifecycle stage and the Leads object](#contacts-lifecycle-stage-and-the-leads-object)
- [How values are sent](#how-values-are-sent)
- [Errors and logging](#errors-and-logging)
- [Security model](#security-model)
- [Build, deploy, test](#build-deploy-test)
- [Troubleshooting](#troubleshooting)
- [Limitations and roadmap](#limitations-and-roadmap)

## What you get

| Piece | Where | Purpose |
| --- | --- | --- |
| `fmdbhs:createContactAction` | `src/main/resources/META-INF/definitions.cnd` | Action node type placed under a form's `actions` list (`fmdbmix:formAction`, read-only compatible) |
| `CreateContactFormAction` | `src/main/java/.../action/` | The `FormAction` OSGi service executed by the Formidable pipeline |
| `HubspotConnection` (factory) | `src/main/java/.../config/` | One DS component per `org.jahia.modules.formidable.hubspot-<id>.cfg`: token, REST client, properties cache |
| `formidableHubspotConnections` | `src/main/java/.../choicelist/` | Choice list feeding the connection dropdown of the action |
| `formidableHubspot` GraphQL | `src/main/java/.../graphql/` | `connections`, `objectFields`, `testConnection` for the editor |
| `HubspotContactMapping` selector | `src/javascript/ContactMapping/` | React 18 Content Editor widget (Module Federation) editing the mapping |
| Labels | `src/main/resources/resources/*.properties`, `src/main/resources/javascript/locales/*.json` | English and French |

## Architecture

```
Content Editor (jContent, React 18)                  Formidable submission pipeline (Java)
┌──────────────────────────────────┐                 ┌──────────────────────────────────────┐
│ HubspotContactMapping selector   │                 │ FormSubmitServlet → actions in order │
│  ├─ form fields    ← core JCR GQL│                 │   └─ CreateContactFormAction         │
│  └─ contact props  ← formidable- │                 │        ├─ FieldMapping (JSON)        │
│        Hubspot.objectFields      │                 │        ├─ FormFieldIndex (form tree) │
└──────────────┬───────────────────┘                 │        ├─ ContactPayloadBuilder      │
               │ stores fieldMapping JSON             │        └─ HubspotConnection.client   │
               ▼                                     └──────────────────┬───────────────────┘
      fmdbhs:createContactAction node                                   │ HTTPS, Bearer token
      connectionId · fieldMapping · duplicateStrategy · failSubmissionOnError
                                                                        ▼
      karaf/etc/org.jahia.modules.formidable.hubspot-<id>.cfg ──► api.hubapi.com (CRM v3)
```

- **Connections are operator configuration.** One factory `.cfg` per portal; the action node
  only stores the connection id.
- **Authentication** is a HubSpot **private app access token** sent as a Bearer. No dependency
  is embedded: HTTP is the JDK `HttpClient`, JSON the platform `org.json`.
- **Field identity.** Mapping rows reference a form field by the engine's stable `fieldKey`,
  then by uuid, then by node name.
- **Property metadata** (`GET /crm/v3/properties/contacts`) is filtered to writable properties
  (not read-only, hidden, calculated or archived), cached per connection
  (`propertiesCacheTtlSeconds`) and refreshable from the editor.

## Prerequisites

- Jahia 8.2.2+ (Enterprise), `formidable-engine` and `formidable-elements` 0.5+, `graphql-dxm-provider`
- A HubSpot portal with a private app (below)
- Build: Java 17, Maven 3, Node 22+, Yarn 4 (see the repository `mise.toml`)

## HubSpot setup

Once per portal, in the CRM portal itself (not the developer area, whose *developer API key* is
refused by the CRM endpoints): *Settings → Integrations → Private Apps → Create a private app*,
with the scopes `crm.objects.contacts.read`, `crm.objects.contacts.write` and
`crm.schemas.contacts.read`. Copy the access token (`pat-eu1-…` / `pat-na1-…`). Rotating the token
in HubSpot invalidates the previous one immediately: update the `.cfg` at the same time.

## Declaring a connection on the Jahia server

Create `{jahia}/karaf/etc/org.jahia.modules.formidable.hubspot-<id>.cfg`. The suffix after the
dash is the connection id unless `connectionId` is set. The file must be **owned and writable by
the Jahia process user** (in Docker: `docker cp` then
`docker exec -u root <container> chown tomcat:tomcat /var/jahia/karaf/etc/<file>`): a file the
process can only read is loaded, but FileInstall marks the configuration read-only and the OSGi
Configurations Manager cannot save it (`ReadOnlyConfigurationException`).

```properties
label=HubSpot marketing portal
apiBaseUrl=https://api.hubapi.com
accessToken=pat-eu1-...
# or, Docker-secret friendly, leave accessToken empty and set:
# accessTokenPath=/run/secrets/hubspot-token
portalId=145510925
httpConnectTimeoutSeconds=5
httpRequestTimeoutSeconds=15
propertiesCacheTtlSeconds=300
```

| Key | Default | Notes |
| --- | --- | --- |
| `connectionId` | file suffix | Stable id stored on action nodes |
| `label` | id | Shown to contributors |
| `apiBaseUrl` | `https://api.hubapi.com` | `https` on `hubapi.com` (or a subdomain) only |
| `accessToken` / `accessTokenPath` | — | Private app token, inline or in a file |
| `portalId` | — | Informational (label, logs) |
| `allowInsecureDevUrl` | `false` | Development only: plain `http` on `localhost` / `host.docker.internal` (mock server) |

A misconfigured file is still listed, suffixed "(misconfigured)", with the reason in the log and
in the mapping editor. Verify a connection from the GraphQL playground as an editor of the path:

```graphql
{ formidableHubspot {
    connections { id label ready error }
    testConnection(connectionId: "marketing", contextPath: "/sites/<site>/contents") { ok message }
} }
```

`testConnection` lists one contact with the token; `INVALID_AUTHENTICATION` means the token is
wrong or revoked, `MISSING_SCOPES` that the private app lacks a scope.

## Authoring the action

1. Enable the `formidable-hubspot` module on the site.
2. Open the form in jContent, go to its **actions** list and add **Create HubSpot Contact**.
3. Pick the **HubSpot connection**.
4. In **Contact property mapping** add rows: a HubSpot property on the left, a form field or a
   constant on the right. Enumerations offer their options (multi-checkbox properties accept
   several), boolean checkboxes a true/false choice, dates a date picker.
   - **Auto-map by name** pre-fills matches by name, label and common synonyms (email, firstname,
     lastname, company, phone, mobilephone, jobtitle, website, city, country, state, address, zip,
     message, industry, salutation), never overwriting.
   - **Refresh HubSpot properties** refetches the property list, bypassing the server cache.
   - HubSpot requires no property on create, but map **email**: it is the deduplication key and
     the upsert strategy relies on it. A row whose property the connection does not expose is
     flagged in red: HubSpot would reject the whole contact.
5. **Duplicate handling**: *Always create a new contact*, or *Update the existing contact with the
   same email* (search by `email`, then `PATCH`). HubSpot itself refuses a second contact with an
   existing email with a 409 naming the existing id; with the update strategy that contact is
   updated, with the create strategy the conflict is an error.
6. **Fail the submission when HubSpot rejects the contact**: on by default.
7. Publish the form.

The stored `fieldMapping` property is a JSON document owned by the selector:

```json
{"version":1,"rows":[
  {"hsProperty":"lastname","hsType":"string","hsFieldType":"text","source":"field","fieldKey":"…","fieldName":"lastname","nodeId":"…"},
  {"hsProperty":"hs_lead_status","hsType":"enumeration","hsFieldType":"select","source":"constant","value":"NEW"}
]}
```

## Contacts, lifecycle stage and the Leads object

HubSpot's equivalent of a Salesforce Lead is a **Contact** whose lifecycle stage is *Lead*. This
is what the action creates, and what HubSpot forms create too. To have submissions show up as
leads in the portal, add two constant rows to the mapping: `lifecyclestage` = `lead` and
`hs_lead_status` = the status your team uses (the editor offers the portal's values). Portals
with lifecycle automation may stamp the stage themselves.

HubSpot also has a separate **Leads object** (`crm/v3/objects/leads`), used by the Sales Hub
Professional and Enterprise prospecting workspace: a lead there is attached to an existing
contact and carries a pipeline stage. The action does not create those records today; it is a
candidate extension (an option creating a Sales Hub lead linked to the contact, scope
`crm.objects.leads.write`).

## How values are sent

HubSpot takes every property value as a string; the module validates and normalises the shape.

| HubSpot type / field type | Sent as |
| --- | --- |
| bool, or field type booleancheckbox | `"true"` / `"false"`; an unchecked checkbox (not submitted) is `"false"` |
| number | numeric string (`12,5` accepted as `12.5`) |
| date | `yyyy-MM-dd` (date input) |
| datetime | ISO 8601 with the server zone offset, from a datetime-local input |
| enumeration with field type checkbox | values joined with `;` |
| anything else | first value; a checkbox group is joined with `, ` |

Blank values are omitted. Rows whose form field no longer exists are skipped and logged. A
submission that maps no non-empty value is refused (HubSpot needs at least one property).
Uploaded files are not sent.

## Errors and logging

HubSpot errors are parsed (`category`, `errors[].context.propertyName`) and logged with the
action path, never with submitted values. With the default policy the action throws a
`FormActionException` with HTTP 502, surfaced to the visitor as the generic action failure code
(`FMDB-008`, see `docs/error-codes.md`). Typical log lines:

```
Created HubSpot contact 1234567 from /sites/x/contents/form/actions/hubspotContact (5 property(ies))
HubSpot refused the contact: VALIDATION_ERROR on [email] (HTTP 400) - Property values were not valid
HubSpot refused the contact: CONFLICT (HTTP 409) - Contact already exists. Existing ID: 1234567
```

The action is `fmdbmix:readOnlyCompatibleAction`.

## Security model

- The action node holds a connection **id**, never a token.
- API base URL allowlist (https + `hubapi.com`).
- Object types and record ids are validated against their API shape before reaching a URL.
- GraphQL fields require `jcr:modifyProperties` on the node being edited (action node, or the
  form's `actions` list while creating); guests are refused.

## Build, deploy, test

```bash
yarn install                              # repo root
cd formidable-hubspot
mvn -Pdev-unsigned clean install          # Java + front-end (yarn lint, tsc, vitest, vite)
```

`-Pdev-unsigned` is for **local instances only** (Jahia Enterprise refuses an unsigned
`org.jahia.modules` bundle; CI signs releases). Install
`target/formidable-hubspot-0.5.0-SNAPSHOT.jar` through the module manager or the provisioning
API, then enable the module on the site.

Tests:

- Unit tests (JUnit 5): value coercion, mapping parsing, URL allowlist, error and property
  parsing — `mvn test`.
- End-to-end (Cypress, in `tests/`): `cypress/e2e/actions/82-hubspot-contact-action.cy.ts`
  (creation, string coercion, upsert, 409 conflict, error policies) and
  `83-hubspot-contact-mapping-selector.cy.ts` (Content Editor selector). They need the mock
  HubSpot server and a `mock` connection, see [`tests/hubspot-mock/README.md`](../tests/hubspot-mock/README.md);
  they skip themselves otherwise.

## Troubleshooting

| Symptom | Cause / fix |
| --- | --- |
| Module installs then disappears; `BundleLicenseCheckerListener: Invalid license check` | Unsigned local build: use `-Pdev-unsigned` (or a CI-signed jar) |
| Action missing from the *Create* menu of the actions list | Module not enabled on the site |
| Mapping field shows a raw JSON text box | jContent tab loaded before the module was deployed: hard-reload |
| `INVALID_AUTHENTICATION: Authentication credentials not found` | Token wrong, rotated or private app deleted: paste a fresh token in the `.cfg` |
| Same error with a 36-character `eu1-…` value | That is a **developer API key** (app-eu1.hubspot.com/developer-api-key/…), which HubSpot no longer accepts on CRM endpoints: create a private app in the portal and use its `pat-eu1-…` token |
| `MISSING_SCOPES` | Add the contacts read/write and schema read scopes to the private app |
| `CONFLICT: Contact already exists` with the *create* strategy | Expected: switch to *Update the existing contact with the same email* |
| Config file ignored, `Permission denied` in the FileInstall log | Make the `.cfg` readable by the Jahia user; FileInstall only rescans a file whose modification time changed |
| `ReadOnlyConfigurationException` when saving in the OSGi Configurations Manager | The file is not writable by the Jahia user: `chown` it to that user and `touch` it, FileInstall then drops the READ_ONLY attribute |

## Limitations and roadmap

- Contacts only; the internals take the object type as a parameter (companies, deals, tickets
  would follow the same path).
- Uploaded files are not sent to HubSpot.
- No association of the contact to a company or a HubSpot form submission (the Forms API is a
  different product from the CRM objects API).
- The test suite needs the Python mock on the host; an in-Jahia mock servlet would let CI run
  the specs.
