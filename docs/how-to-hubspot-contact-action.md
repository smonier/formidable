# How to send form submissions to HubSpot as Contacts

The `formidable-hubspot` module adds a **Create HubSpot Contact** action to Formidable. It is the
HubSpot twin of `formidable-salesforce` (see `how-to-salesforce-lead-action.md`): same module
shape, same mapping selector, same operator-owned connections. A contributor adds the action to
any form, picks a HubSpot connection, and maps contact properties to form fields (or constants)
in Content Editor. At submit time the module builds the contact payload and calls the HubSpot
CRM v3 API.

Relevant files:

- `formidable-hubspot/src/main/resources/META-INF/definitions.cnd` — `fmdbhs:createContactAction`
- `formidable-hubspot/src/main/java/org/jahia/modules/formidable/hubspot/action/CreateContactFormAction.java`
- `formidable-hubspot/src/main/java/org/jahia/modules/formidable/hubspot/config/HubspotConnection.java`
- `formidable-hubspot/src/javascript/ContactMapping/` — the `HubspotContactMapping` selector type
- `formidable-hubspot/src/main/java/org/jahia/modules/formidable/hubspot/graphql/` — the
  `formidableHubspot` GraphQL query used by the selector

## Architecture in one paragraph

Tokens never enter JCR. Each HubSpot portal is an **OSGi factory configuration** file,
`org.jahia.modules.formidable.hubspot-<id>.cfg`, that becomes one `HubspotConnection` service.
The action node only stores the connection **id** (choice list `formidableHubspotConnections`),
a JSON **property mapping**, a duplicate strategy and an error policy. The selector reads the
enclosing form's fields through the core JCR GraphQL API and the contact properties through
`formidableHubspot.objectFields`, which calls `GET /crm/v3/properties/contacts` (cached, with a
**Refresh HubSpot properties** button bypassing the cache) on behalf of a contributor who can edit
the action node. Authentication is a **private app access token** sent as a Bearer; HTTP is the
JDK `HttpClient`; no dependency is embedded.

## 1. HubSpot setup (once per portal)

1. In HubSpot, *Settings > Integrations > Private Apps*, create a private app with the scopes
   `crm.objects.contacts.read`, `crm.objects.contacts.write` and `crm.schemas.contacts.read`.
2. Copy its access token (`pat-...`, 44 characters). Note the portal id (account id) for the label.
   A *developer API key* (`eu1-…`, from the developer area) is not accepted by the CRM endpoints.

## 2. Declare the connection on the Jahia server

Create `{jahia}/karaf/etc/org.jahia.modules.formidable.hubspot-marketing.cfg` (the suffix after
the dash is the connection id unless `connectionId` is set). The file must be owned and writable
by the Jahia process user, otherwise FileInstall loads it read-only and the configuration UI
cannot save it:

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

Only `https` on a `hubapi.com` host is accepted. Several files = several connections. A
misconfigured file is still listed (suffixed "(misconfigured)") and the reason is in the log and
in the mapping editor. Check it with
`formidableHubspot { testConnection(connectionId: "marketing", contextPath: "/sites/<site>/contents/<form>/actions/<action>") { ok message } }`
in the GraphQL playground as an editor of that path.

For automated tests only, `allowInsecureDevUrl=true` lets `apiBaseUrl` be plain `http` on
`localhost` / `host.docker.internal` (a mock HubSpot, see `tests/hubspot-mock/`). Never in
production.

## 3. Author the action

1. Enable the `formidable-hubspot` module on the site, open the form in jContent, go to its
   **actions** list and add **Create HubSpot Contact**.
2. Pick the **HubSpot connection**.
3. In **Contact property mapping**, add rows: a HubSpot property on the left, a form field or a
   constant on the right. The left list holds every property the private app may write
   (not read-only, hidden, calculated or archived). **Auto-map by name** pre-fills obvious matches
   (email, firstname, lastname, company, phone, jobtitle...). HubSpot requires no property, but
   map **email** so deduplication and the upsert strategy work. A row whose property the
   connection does not expose is flagged in red: HubSpot would reject the whole contact for it.
4. **Duplicate handling**: *Always create* or *Update the existing contact with the same email*
   (searches the contact by `email`, then `PATCH`es it; a 409 "Contact already exists" from a
   contact the search index has not caught up with is also turned into an update).
5. **Fail the submission when HubSpot rejects the contact**: on by default.
6. Publish the form.

### Contacts, not Leads

HubSpot's equivalent of a Salesforce Lead is a Contact with lifecycle stage *Lead*: map the
constants `lifecyclestage` = `lead` and `hs_lead_status` = your status to have submissions show up
as leads. The separate Sales Hub *Leads object* (`crm/v3/objects/leads`, attached to a contact) is
not created by the action today.

### What is stored

```json
{"version":1,"rows":[
  {"hsProperty":"lastname","hsType":"string","hsFieldType":"text","source":"field","fieldKey":"…","fieldName":"lastname","nodeId":"…"},
  {"hsProperty":"hs_lead_status","hsType":"enumeration","hsFieldType":"select","source":"constant","value":"NEW"}
]}
```

Form fields are referenced by the engine's `fieldKey` first, then by uuid, then by node name.

### Value conversion

HubSpot takes every property value as a string; the module validates the shape:

| HubSpot type / field type | Sent as |
|---|---|
| bool, or field type booleancheckbox | `"true"`/`"false"`; an unchecked checkbox is `"false"` |
| number | numeric string (`12,5` accepted as `12.5`) |
| date | `yyyy-MM-dd` (date input) |
| datetime | ISO 8601 with the server zone offset, from a datetime-local input |
| enumeration with field type checkbox | values joined with `;` |
| anything else | first value; a checkbox group is joined with `, ` |

Blank values are omitted. Uploaded files are not sent (v1).

## Building and deploying locally

Same as formidable-salesforce: Jahia Enterprise refuses an unsigned `org.jahia.modules` bundle,
so for a local instance build with `mvn -Pdev-unsigned clean install` (never for a release), then
install the jar through the provisioning API.

## Error handling and security

HubSpot errors are parsed (`category`, `errors[].context.propertyName`) and logged without
submitted values; with the default policy the action fails with 502, surfaced to the visitor as
the generic action failure code (`docs/error-codes.md`). The action is
`fmdbmix:readOnlyCompatibleAction`. The API base URL is allowlisted, object types are validated
against the API-name shape, and the GraphQL fields require `jcr:modifyProperties` on the node (which must be the action node, the form's `actions` list or the form)
being edited (guests refused).
