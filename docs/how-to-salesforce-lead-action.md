# How to send form submissions to Salesforce as Leads

The `formidable-salesforce` module adds a **Create Salesforce Lead** action to Formidable. A
contributor adds the action to any form, picks a Salesforce connection, and maps the Lead fields
to the form fields (or to constants) directly in Content Editor. At submit time the module builds
the Lead payload from the submitted values and calls the Salesforce REST API.

Relevant files:

- `formidable-salesforce/src/main/resources/META-INF/definitions.cnd` — `fmdbsfdc:createLeadAction`
- `formidable-salesforce/src/main/java/org/jahia/modules/formidable/salesforce/action/CreateLeadFormAction.java`
- `formidable-salesforce/src/main/java/org/jahia/modules/formidable/salesforce/config/SalesforceConnection.java`
- `formidable-salesforce/src/javascript/LeadMapping/` — the `SalesforceLeadMapping` selector type
- `formidable-salesforce/src/main/java/org/jahia/modules/formidable/salesforce/graphql/` — the
  `formidableSalesforce` GraphQL query used by the selector

## Architecture in one paragraph

Credentials never enter JCR. Each Salesforce org is an **OSGi factory configuration** file,
`org.jahia.modules.formidable.salesforce-<id>.cfg`, that becomes one `SalesforceConnection`
service. The action node only stores the connection **id** (choice list
`formidableSfdcConnections`), a JSON **field mapping**, a duplicate strategy and an error policy.
The selector reads the enclosing form's fields through the core JCR GraphQL API and the Lead
fields through the module's `formidableSalesforce.objectFields` query, which calls
`sobjects/Lead/describe` (cached) on behalf of a contributor who can edit the action node.
Authentication is the OAuth 2.0 **JWT Bearer** flow, signed with `java.security`; HTTP is the JDK
`HttpClient`; no dependency is embedded.

## 1. Salesforce setup (once per org)

1. Create an RSA key pair and a self-signed certificate:
   ```bash
   openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=jahia-formidable" \
     -keyout key.pem -out cert.pem
   openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem
   ```
2. In Salesforce Setup, create a **Connected App**: enable OAuth settings, check **Use digital
   signatures** and upload `cert.pem`, select at least the **Manage user data via APIs (api)**
   scope.
3. In the app's *Manage* page, set *Permitted Users* to **Admin approved users are
   pre-authorized** and assign the profile (or permission set) of the integration user.
4. Note the **Consumer Key** and the **username** of the integration user. That user needs
   create (and read/edit for the upsert strategy) rights on Leads.

## 2. Declare the connection on the Jahia server

Create `{jahia}/karaf/etc/org.jahia.modules.formidable.salesforce-prod.cfg` (the suffix after
the dash is the connection id shown to contributors unless `connectionId` is set):

```properties
label=Salesforce production
instanceUrl=https://login.salesforce.com
clientId=3MVG9...consumer key...
username=integration@acme.com
# PKCS#8 PEM. Either escape line breaks as \n on one line...
privateKey=-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC...\n-----END PRIVATE KEY-----
# ...or point to a file (Docker secret friendly) and leave privateKey empty:
# privateKeyPath=/run/secrets/salesforce-key.pem
apiVersion=v62.0
httpConnectTimeoutSeconds=5
httpRequestTimeoutSeconds=15
tokenCacheTtlSeconds=3000
describeCacheTtlSeconds=300
```

Use `https://test.salesforce.com` for a sandbox, or the org's My Domain URL. Only `https` on a
`salesforce.com` / `force.com` host is accepted. Several files = several connections. A
misconfigured file is still listed (suffixed "(misconfigured)") and the reason is in the log and
in the mapping editor, so operators can fix it without guessing.

Check it: `formidableSalesforce { testConnection(connectionId: "prod", contextPath: "/sites/<site>/contents") { ok message } }`
in the GraphQL playground as an editor of that path.

For automated tests only, `allowInsecureDevUrl=true` lets `instanceUrl` be plain `http` on
`localhost` / `host.docker.internal` (a mock Salesforce). Never in production.

## 3. Author the action

1. Open the form in jContent, go to its **actions** list and add **Create Salesforce Lead**.
2. Pick the **Salesforce connection**.
3. In **Lead field mapping**, add rows: a Salesforce field on the left, a form field or a
   constant on the right. The left list holds every Lead field the integration user may set on
   create (`createable` in the describe, deprecated fields excluded); it is cached server-side
   for `describeCacheTtlSeconds`, and **Refresh Salesforce fields** refetches it immediately after
   a schema or field-level-security change in the org. A row whose field the connection does not
   expose is flagged in red: Salesforce would reject the whole lead for it. **Auto-map by name** pre-fills obvious matches (email, first/last
   name, company, phone...). `Last Name` and `Company` are required by Salesforce; the editor
   warns while they are unmapped, and the action refuses to send an incomplete lead.
4. **Duplicate handling**: *Always create* or *Update the existing lead with the same email*
   (looks the Lead up by `Email`, converted leads excluded, then `PATCH`es it).
5. **Fail the submission when Salesforce rejects the lead**: on by default. Off means the visitor
   still gets a success and the rejection only goes to the server log.
6. Publish the form.

### What is stored

`fieldMapping` is a JSON document owned by the selector:

```json
{"version":1,"rows":[
  {"sfField":"LastName","sfType":"string","source":"field","fieldKey":"…","fieldName":"lastname","nodeId":"…"},
  {"sfField":"LeadSource","sfType":"picklist","source":"constant","value":"Web"}
]}
```

Form fields are referenced by the engine's `fieldKey` first (rename/copy proof), then by uuid,
then by node name, in the same spirit as conditional logic. Rows whose field disappeared are
skipped and logged, not sent.

### Value conversion

| Salesforce type | Sent as |
|---|---|
| boolean | `true`/`false`; an unchecked checkbox (not submitted) is `false` |
| int / double / currency / percent | number (`12,5` accepted) |
| date | `yyyy-MM-dd` (date input) |
| datetime | `yyyy-MM-dd'T'HH:mm:ss.SSS+hhmm` from a datetime-local input, server zone |
| multipicklist | values joined with `;` |
| anything else | first value; a checkbox group is joined with `, ` |

Blank values are omitted so Salesforce defaults apply. Uploaded files are not sent (v1).

## Building and deploying locally

The module is a regular Maven bundle of the reactor (`mvn clean install` at the root builds it,
front-end included). Jahia Enterprise only starts `org.jahia.modules` bundles carrying the
`Jahia-Signature` header, which the repository CI adds (`jahia-modules-action/update-signature`).
A locally built jar has no signature and is uninstalled right after install
(`BundleLicenseCheckerListener: Invalid license check` in the log). For a local instance, build
with the development profile, which relabels the bundle under a non-Jahia group:

```bash
cd formidable-salesforce && mvn -Pdev-unsigned clean install
```

then install `target/formidable-salesforce-0.5.0-SNAPSHOT.jar` through the provisioning API or
the module manager. Never release with that profile.

## Error handling and codes

Salesforce errors are parsed (`errorCode`, `fields`) and logged **without submitted values**.
With the default policy the action throws a `FormActionException` with HTTP 502, which the
pipeline turns into its generic action failure code for the visitor (see `docs/error-codes.md`).
The action is `fmdbmix:readOnlyCompatibleAction`: it keeps working during read-only maintenance.

## Security notes

- Instance URL allowlist (https + Salesforce domains) both for the configured URL and for the
  `instance_url` returned by the token endpoint.
- The GraphQL fields require the caller to hold `jcr:modifyProperties` on the node being edited
  (action node, or the form's `actions` list while creating); guests are refused.
- Object names are validated against the API-name shape before reaching a URL or SOQL; the
  email used by the upsert lookup is escaped for the SOQL literal.
- The token is cached for `tokenCacheTtlSeconds` (the JWT flow returns no expiry) and refreshed
  once on 401.
