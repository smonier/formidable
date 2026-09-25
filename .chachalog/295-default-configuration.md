---
# Allowed version bumps: patch, minor, major
formidable: minor
---

Changed the configuration file to be deployed with the module, ready to edit, with every setting at its default (#295)

**Breaking change** for instances configured through the Felix console (or directly in ConfigAdmin) before 0.5. Those settings lived in no file; when the module is upgraded, the deployed file `karaf/etc/org.jahia.modules.formidable.cfg` takes over and replaces them with its defaults.

You are affected if the module was configured that way and the file did not exist before the upgrade. To check, after the upgrade, open the file: if this instance had CAPTCHA keys, forward targets, option sources or upload limits and the file shows them at their defaults, they were reset. On a running server the module writes them back into the file itself and logs `carried over into the file:` with their names; when it could not — the log then shows `Gave up carrying … over` — or when the file was loaded before the module started (the usual case after an upgrade done while Jahia was stopped, possible on a running server), a warning at startup points at the file, created or changed moments before.

What to do: re-enter the settings in the file, or through the provisioning API. The change applies without a restart; the file is now the one place the module is configured from.

Instances configured through the provisioning API are not affected: that configuration came from a file from the start, which is kept as it is.
