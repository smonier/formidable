---
# Allowed version bumps: patch, minor, major
formidable: minor
---

Added the optional `formidable-salesforce` module: a **Create Salesforce Lead** form action whose Lead field mapping is authored in Content Editor with the new `SalesforceLeadMapping` selector (form fields and live Salesforce describe side by side, auto-map by name, required-field and unavailable-field warnings, refresh from Salesforce). Connections are operator-managed factory configurations (`org.jahia.modules.formidable.salesforce-<id>.cfg`, JWT bearer, credentials never in JCR); the action supports create or upsert by email and a fail-or-log error policy.
