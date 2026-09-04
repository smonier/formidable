# Mock HubSpot for the formidable-hubspot specs

`mock_hubspot.py` (Python 3, standard library only) fakes the CRM v3 endpoints the "Create
HubSpot Contact" action uses: `GET /crm/v3/properties/contacts`, `POST /crm/v3/objects/contacts`,
`PATCH /crm/v3/objects/contacts/{id}`, `POST /crm/v3/objects/contacts/search` and
`GET /crm/v3/objects/contacts?limit=1`. Contacts are kept in memory; `GET /__mock/contacts` lists
them and `DELETE /__mock/contacts` clears them. `reject@example.com` is refused with a
`VALIDATION_ERROR`; a second contact with an existing email answers the real 409 `CONFLICT`
("Contact already exists. Existing ID: n").

```bash
# 1. start the mock (port 8091, token expected as Bearer)
python3 tests/hubspot-mock/mock_hubspot.py 8091 pat-mock-token

# 2. the dev connection, id "mock" (suffix of the file name); must be readable by the Jahia user
cat > org.jahia.modules.formidable.hubspot-mock.cfg <<CFG
label=HubSpot mock (dev)
apiBaseUrl=http://host.docker.internal:8091
accessToken=pat-mock-token
allowInsecureDevUrl=true
propertiesCacheTtlSeconds=5
CFG
chmod 644 org.jahia.modules.formidable.hubspot-mock.cfg
docker cp org.jahia.modules.formidable.hubspot-mock.cfg <jahia-container>:/var/jahia/karaf/etc/
# owned by the Jahia user, or the configuration is loaded read-only
docker exec -u root <jahia-container> chown tomcat:tomcat /var/jahia/karaf/etc/org.jahia.modules.formidable.hubspot-mock.cfg
```

Specs `82-hubspot-contact-action` and `83-hubspot-contact-mapping-selector` skip themselves when
the mock is unreachable (`HUBSPOT_MOCK_URL`, default `http://localhost:8091`) or when the instance
does not declare a ready `mock` HubSpot connection. On a long-lived development instance run them
with the same longer timeouts as the Salesforce specs (see `tests/salesforce-mock/README.md`).
