---
# Allowed version bumps: patch, minor, major
formidable: minor
---

Changed the form rendering module's identity; upgrading from 0.3.0 or earlier requires a one-time reinstall (#187)

**Breaking change.** You are affected if any version up to 0.3.0 of the form rendering module is installed: uploading the new version fails with "Module upload failed because another module formidable-elements exists."

Earlier versions were packaged under a placeholder group id; the module is now published under the official Jahia group id, as required for distribution on the Jahia App Store. Jahia treats a module with the same name but a different group id as a different module and blocks the upload.

To upgrade, in **Administration > Server > Modules and Extensions > Modules**:

1. Upgrade formidable-engine to the new version **first**, **with "Validate module definitions" unticked**: the new version removes submission properties no version since 0.2.0 has written, so the validation rejects the upload as a major definition change. This is expected — values stored by a 0.1.x instance are left in place.
2. Stop and uninstall the old formidable-elements. **Do not tick the option to delete the module content when uninstalling** — that choice erases every form and every submission stored in the repository. Left unticked, forms and submissions are fully preserved; forms simply stop rendering while the module is absent.
3. Install the new formidable-elements, **with "Validate module definitions" unticked**: the new version reorganizes some field properties, so the validation rejects the upload as a major definition change. This is expected — the automatic content migrations take over for the existing content.
4. **Re-enable formidable-elements on every site that uses it**: the uninstall removed it from the sites' enabled modules, and forms show a "Module error" box until it is enabled again. A server restart does not repair this; re-enabling the module does, immediately.
5. Check that forms render again. Existing content is migrated automatically; the migrated fields may show as *modified* (pending publication) afterwards — nothing is actually pending, publishing them is optional and only clears the flag.

This is a one-time procedure: later upgrades install in place as usual. The full walkthrough, with the exact messages to expect, is in [docs/administration/upgrade-notes.md](https://github.com/Jahia/formidable/blob/main/docs/administration/upgrade-notes.md).
