---
# Allowed version bumps: patch, minor, major
formidable: minor
---

Added markers so your own module can offer a form type, or a field that submits one value per choice (#329)

Carry the form marker on a content type of your own and the submission pipeline, the permissions sync and the results treat it as a form; carry the choice-cardinality marker on a field that draws one input per choice and it is read like the built-in checkbox. Neither marker needs a property, and the built-in types already carry them.
