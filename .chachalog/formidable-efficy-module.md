---
# Allowed version bumps: patch, minor, major
formidable: minor
---

Added the optional `formidable-efficy` module: a **Create Efficy Opportunity** form action for Efficy e-deal with the `EfficyOpportunityMapping` selector (field catalog per connection since e-deal has no metadata API, live referential values, submission-date and person-by-email sources, auto-map, refresh). Connections are factory configurations holding the tenant token (`org.jahia.modules.formidable.efficy-<id>.cfg`); same fail-or-log error policy as the other CRM connectors.
