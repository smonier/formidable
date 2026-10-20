---
# Allowed version bumps: patch, minor, major
formidable: patch
---

Fixed a required checkbox group failing accessibility checks (axe `aria-allowed-attr`): its fieldset no longer carries `aria-required`, which ARIA does not allow on a group; the group's validation still says when no box is ticked, as a radio group does
