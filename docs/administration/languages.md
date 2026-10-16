# Visitor languages

The languages in which a visitor reads what Formidable writes on the page itself and in the answers to a
submission. The texts a contributor writes into a form (labels, help texts, response messages, option labels) are
content: they follow the site's languages, like any other content.

## What is translated, per language

| Language | File | Front-end messages | Defaults written into a new form | Server answers |
|---|---|---|---|---|
| English | `en.json`, base bundles | Yes | Yes | Yes |
| French | `fr.json`, `_fr` bundles | Yes | Yes | Yes |
| European Portuguese | `pt.json`, `_pt` bundles | Yes | Yes | Yes |
| German, Spanish | `_de`, `_es` bundles | No: English | Yes | Yes |

- **Front-end messages**: the texts of the views and the browser, through i18next, in
  `formidable-elements/settings/locales/<language>.json` (file selection, buttons, steps, progress, the CAPTCHA and
  maintenance notices, an unavailable options source) and `formidable-extended-inputs/settings/locales/<language>.json`
  (the NPS scale's end labels, the switch's Yes/No, the consent's terms link).
- **Defaults written into a new form**: the values a form or a field receives when it is created in that
  language (CND `resourceBundle(...)` defaults): the submission, error and maintenance messages and the six button
  labels (`formidable-elements`), the switch's Yes/No (`formidable-extended-inputs`), the refusal message of a field
  action (`formidable-engine`). A form created before the language was added keeps what it was created with.
- **Server answers**: the sentences the engine writes into a submission's answer in the visitor's language, read from
  `formidable-engine`'s bundle without falling back to the server's own locale: a field action's refusal without a
  message of its own, and the refusal of a field carrying too many values to be checked.

## Portuguese is European Portuguese

`pt` is written for Portugal (`utilizador`, `ficheiro`, `ecrã`, `registar`, `palavra-passe`), not for Brazil, and
serves a site in `pt` as well as in `pt_PT`: Java resolves `pt_PT` to the `_pt` bundles; i18next falls back from a
regional code to its language. That fallback of the front-end messages for `pt_PT` was not verified on a running
Jahia. A site in Brazilian Portuguese (`pt_BR`) reads the European texts.

The Portuguese resource bundles (`formidable-elements_pt.properties`, `formidable-extended-inputs_pt.properties`,
`formidable-engine_pt.properties`) hold only the texts a visitor reads, listed above.

## Not in scope: the editor

The labels of the editor are not translated into Portuguese: the content types, properties and tooltips of the
Content Editor (the rest of the CND resource bundles), the jContent screens of the engine (Form Results, the import,
the selectors: `formidable-engine/src/main/resources/javascript/locales/`), and the jExperience integration's labels.
A Portuguese editor reads them in English, from the base bundles.

## Keeping the languages in step

`scripts/check-locales.mjs`, run by the static-analysis job, fails the build when a `<language>.json` does not carry
exactly the keys of its `en.json`, in the same order and with the same `{{placeholders}}`, or when a translated
bundle holds a key its base bundle does not have. A translated bundle may hold fewer keys than its base: that is how
the Portuguese bundles keep to the visitor texts. A key added to an `en.json` is added to every other file of the
folder in the same change.
