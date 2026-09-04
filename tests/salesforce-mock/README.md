# Mock Salesforce for the formidable-salesforce specs

`mock_salesforce.py` (Python 3, standard library only) fakes the parts of Salesforce the
"Create Salesforce Lead" action uses: the JWT bearer token endpoint, `sobjects/Lead/describe`,
`sobjects/Lead` create, `PATCH` update, the SOQL lookup by email and `limits`. Leads are kept in
memory; `GET /__mock/leads` lists them and `DELETE /__mock/leads` clears them. The address
`reject@example.com` is refused with `FIELD_CUSTOM_VALIDATION_EXCEPTION`.

```bash
# 1. start the mock (port 8090, instance_url advertised to the Jahia container)
python3 tests/salesforce-mock/mock_salesforce.py 8090 http://host.docker.internal:8090

# 2. a throwaway key pair (the mock does not verify the signature, the module signs anyway)
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -subj "/CN=formidable-mock" -keyout key.pem -out cert.pem
openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem

# 3. the dev connection, id "mock" (the suffix of the file name)
cat > org.jahia.modules.formidable.salesforce-mock.cfg <<CFG
label=Salesforce mock (dev)
instanceUrl=http://host.docker.internal:8090
clientId=mock-consumer-key
username=mock@example.com
privateKey=$(awk 'NF' key-pkcs8.pem | paste -sd '|' - | sed 's/|/\\n/g')
allowInsecureDevUrl=true
describeCacheTtlSeconds=5
CFG
docker cp org.jahia.modules.formidable.salesforce-mock.cfg <jahia-container>:/var/jahia/karaf/etc/
# owned by the Jahia user, or the configuration is loaded read-only
docker exec -u root <jahia-container> chown tomcat:tomcat /var/jahia/karaf/etc/org.jahia.modules.formidable.salesforce-mock.cfg
```

Specs `80-salesforce-lead-action` and `81-salesforce-lead-mapping-selector` skip themselves
when the mock is unreachable (`SALESFORCE_MOCK_URL`, default `http://localhost:8090`) or when
the instance does not declare a ready `mock` connection.

## Running the specs on a long-lived development instance

```bash
SUPER_USER_PASSWORD=root yarn cypress run --browser chrome \
  --config "responseTimeout=90000,requestTimeout=30000,defaultCommandTimeout=15000" \
  --spec "cypress/e2e/actions/80-salesforce-lead-action.cy.ts,cypress/e2e/actions/81-salesforce-lead-mapping-selector.cy.ts"
```

The longer timeouts cover the site reset (delete + create through the provisioning API) on an
instance hosting many sites. Spec 80 publishes with `publishAndWaitLive` (polls the live
workspace) rather than `publishAndWaitJobEnding`, because a stale `EXECUTING` publication job
in the scheduler history makes the job-based wait time out forever; the `publish` option of
`createPublishedLiveFormPage` is how a spec opts into it.
