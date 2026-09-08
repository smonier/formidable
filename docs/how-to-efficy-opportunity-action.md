# How to send form submissions to Efficy e-deal as Opportunities

The `formidable-efficy` module adds a **Create Efficy Opportunity** action to Formidable. It is
the Efficy e-deal member of the CRM connector family (`formidable-salesforce`,
`formidable-hubspot`): same module shape, same mapping selector, same operator-owned connections.
A contributor adds the action to any form, picks an Efficy connection and maps the Opportunity
fields to form fields, constants, the submission date, or the Efficy person found from the
submitted email. At submit time the module posts the opportunity to the e-deal
`base_data` API.

Relevant files:

- `formidable-efficy/src/main/resources/META-INF/definitions.cnd` — `fmdbeff:createOpportunityAction`
- `formidable-efficy/src/main/java/org/jahia/modules/formidable/efficy/action/CreateOpportunityFormAction.java`
- `formidable-efficy/src/main/java/org/jahia/modules/formidable/efficy/config/EfficyConnection.java`
- `formidable-efficy/src/javascript/OpportunityMapping/` — the `EfficyOpportunityMapping` selector type
- `formidable-efficy/src/main/java/org/jahia/modules/formidable/efficy/graphql/` — the
  `formidableEfficy` GraphQL query used by the selector

## What is different with e-deal

- **No metadata endpoint.** e-deal does not describe its entities, so the fields offered to
  contributors come from a **field catalog** declared in the connection (one line per field,
  `sqlName|Label|type[|required]`), shipped with the standard Opportunity fields. Referential
  fields (`OppStoID`, `OppOpbID`, `OppGammeShouhaitee_`…) get their values live from
  `service/1.0/referential_for?field=…`, cached and refreshable from the editor.
- **An opportunity needs a person and an enterprise.** The mapping offers a source *Efficy person
  from the submitted email*: the action looks the Person up by `PerMail`, sets the person field and,
  when the enterprise field is not mapped, the person's enterprise. The connection's
  `defaultPersonId` / `defaultEnterpriseId` are the last resort.
- **Submission date.** A source *Submission date* fills date fields (`OppDate`) with the day the
  form is submitted.
- **Authentication** is the tenant API token sent verbatim in the `Authorization` header, as the
  Efficy portal modules do.

## 1. Declare the connection on the Jahia server

Create `{jahia}/karaf/etc/org.jahia.modules.formidable.efficy-<id>.cfg`, owned and writable by
the Jahia process user (see the Salesforce how-to for the FileInstall read-only trap):

```properties
label=Efficy e-deal - production
serverUrl=https://acme.efficytest.cloud
appContext=MutuelleAssurance
apiVersion=1.0
token=...tenant api token...
# or tokenPath=/run/secrets/efficy-token
baseResource=base_data
serviceResource=service
objectType=Opportunity
# optional: override the field catalog (defaults to the standard Opportunity fields)
# fieldCatalog=OppTitle|Title|string|required\nOppDetail|Details|text\n...
defaultPersonId=
defaultEnterpriseId=
httpConnectTimeoutSeconds=5
httpRequestTimeoutSeconds=15
referentialCacheTtlSeconds=300
```

Only `https` on `efficy.com`, `efficy.cloud`, `efficytest.cloud` or an `extraAllowedHosts`
suffix is accepted. Check the connection with
`formidableEfficy { testConnection(connectionId: "<id>", contextPath: "/sites/<site>/contents/<form>/actions/<action>") { ok message } }`.

The default catalog: `OppTitle` (required), `OppDetail`, `OppEntID` (required, reference),
`OppPerID` (required, reference), `OppStoID` (required, referential), `OppOpbID` (required,
referential), `OppDate` (required, date), `OppStake` (required, number), `OppGammeShouhaitee_`
(referential-multi), `OppNumRef`, `OppCourtier_` (reference). Add a tenant's custom fields
(`OppNivSouhaite`, `OppRegimeAssurance`…) as extra lines.

## 2. Author the action

1. Enable `formidable-efficy` on the site, open the form's **actions** list and add
   **Create Efficy Opportunity**.
2. Pick the **Efficy connection**.
3. In **Opportunity field mapping**, map each field: form field, constant (referential fields
   offer their values), submission date, or person by email. **Auto-map by name** pre-fills the
   title, details, amount and reference from field names and synonyms, the person from an email
   field and the date from the submission day. Required catalog fields are starred and listed in
   a warning while unmapped; a row whose field left the catalog is flagged in red.
4. **Fail the submission when Efficy rejects the opportunity**: on by default.
5. Publish the form.

### What is stored

```json
{"version":1,"rows":[
  {"effField":"OppTitle","effType":"string","source":"field","fieldKey":"…","fieldName":"subject","nodeId":"…"},
  {"effField":"OppPerID","effType":"reference","source":"personByEmail","fieldKey":"…","fieldName":"email","nodeId":"…"},
  {"effField":"OppStoID","effType":"referential","source":"constant","value":"000000000000074f"},
  {"effField":"OppDate","effType":"date","source":"today"}
]}
```

### Value conversion

| Catalog type | Sent as |
|---|---|
| number | JSON number (`12,5` accepted) |
| boolean | JSON true/false; an unchecked checkbox is false |
| date | `yyyy-MM-dd` |
| datetime | `yyyy-MM-dd'T'HH:mm:ss` |
| referential-multi | JSON array of ids |
| referential, reference | the 16-character e-deal id |
| string, text | first value; a checkbox group is joined with `, ` |

## Errors and security

e-deal answers are wrapped in `{return_status, error_code, error_message}`; a `KO` status or a
non-2xx code is logged with the code and never with submitted values, and the submission fails
with 502 under the default policy. The action is `fmdbmix:readOnlyCompatibleAction`. Entity and
field names are validated against the SQL-name shape, filter values against the e-deal filter
syntax, and the GraphQL fields require `jcr:modifyProperties` on the edited node, which must be the action node, the form's `actions` list or the form.

Build locally with `mvn -Pdev-unsigned clean install` (Jahia Enterprise signature check, see the
Salesforce how-to).
