---
# Allowed version bumps: patch, minor, major
formidable: minor
---

Changed the buttons of a form to come with a style of their own instead of the browser's default (#338)

Submit, reset, the multi-step pair, a Button field and the one offered after a submission are now drawn by the modules: a filled primary, an outlined secondary, a focus ring and a disabled state, every value set through a variable the styling guide documents. Sites that style their buttons from the site stylesheet are affected only if their rules target the button class alone: rules written under the form (`.fmdb-form .fmdb-btn`) still win, a bare `.fmdb-btn` ties and the last stylesheet loaded decides. To check, open a form and look at the buttons; if they no longer look like yours, either scope those rules under the form or set the `--fmdb-btn-*` variables instead of restyling. One thing to know either way: the message shown after a submission is rendered beside the form, so variables set on the form alone do not reach its button — set them on `:root`.
