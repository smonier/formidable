---
# Allowed version bumps: patch, minor, major
formidable: minor
---

Added the optional `formidable-hubspot` module, twin of `formidable-salesforce`: a **Create HubSpot Contact** form action with the `HubspotContactMapping` selector (writable contact properties from the CRM v3 API, enumerations and multi-checkboxes, auto-map, refresh). Connections are factory configurations holding a private app token (`org.jahia.modules.formidable.hubspot-<id>.cfg`); the action supports create or upsert by email, turns HubSpot's duplicate-email conflict into an update when upserting, and follows the same fail-or-log error policy.
