---
# Allowed version bumps: patch, minor, major
formidable: minor
---

Added the form content model's type names as constants your module imports, instead of copying each one (#329)

A custom action, integrity check or integration now names a form type or a field marker through the published API rather than retyping the string, so a typo fails the build instead of silently matching nothing. See the extension guides for the classes to import.
