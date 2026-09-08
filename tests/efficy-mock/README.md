# Mock Efficy e-deal for the formidable-efficy specs

`mock_efficy.py` (Python 3, standard library only) fakes the e-deal endpoints the "Create Efficy
Opportunity" action uses under the application context `MockContext`: `POST
base_data/1.0/Opportunity` (envelope with `bean_data`), `GET base_data/1.0/Person?filter={{[PerMail,=,…]}}`
(knows `ada@example.com`), and `GET service/1.0/referential_for?field=…` for `OppStoID`, `OppOpbID`
and `OppGammeShouhaitee_`. Opportunities are kept in memory; `GET /__mock/opportunities` lists them
and `DELETE /__mock/opportunities` clears them. An `OppTitle` of `REJECT` is refused with
`BEAN_VALIDATION_ERROR`; the standard required fields are enforced like the real tenant.

```bash
# 1. start the mock (port 8092, token expected verbatim in Authorization)
python3 tests/efficy-mock/mock_efficy.py 8092 mock-efficy-token

# 2. the dev connection, id "mock"; must be owned by the Jahia user
cat > org.jahia.modules.formidable.efficy-mock.cfg <<CFG
label=Efficy mock (dev)
serverUrl=http://host.docker.internal:8092
appContext=MockContext
token=mock-efficy-token
allowInsecureDevUrl=true
referentialCacheTtlSeconds=5
defaultPersonId=0000000000000001
defaultEnterpriseId=0000000000000001
CFG
docker cp org.jahia.modules.formidable.efficy-mock.cfg <jahia-container>:/var/jahia/karaf/etc/
docker exec -u root <jahia-container> chown tomcat:tomcat /var/jahia/karaf/etc/org.jahia.modules.formidable.efficy-mock.cfg
```

Specs `84-efficy-opportunity-action` and `85-efficy-opportunity-mapping-selector` skip themselves
when the mock is unreachable (`EFFICY_MOCK_URL`, default `http://localhost:8092`) or when the
instance does not declare a ready `mock` Efficy connection. Run them with the longer timeouts
described in `tests/salesforce-mock/README.md`.
