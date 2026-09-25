# jExperience Integration

> **Status: design specification; phase 1 shipped 2026-09-11 and reviewed 2026-09-14 (PR #324),
> phases 2 and 3 shipped, phase 4 (prefill) to come — the roadmap at the end says what is shipped.** First design dated
> 2026-09-09 (server-side event and prefill), **revised 2026-09-10 after Romain's review: everything
> the visitor triggers runs in the browser, through jExperience's tracker**. The implementation
> lands on the `feat/jexperience-integration` branch and this document is updated as each phase ships.
> Targets: Formidable 0.5.x with jExperience 3.4 or later and its jCustomer 2.x, or jExperience 4.x and jCustomer
> 3.x — one bundle, its OSGi ranges `[3.4,5)` on jExperience's admin package and `[2.1,4)` on the Unomi API, held
> by a CI gate that recompiles the module against the floor (the measurement and the gate:
> [dependency decisions](dependency-decisions.md), "Notes"). Developed and proven on 4.2.1 + jCustomer 3.0.0,
> exercised end to end on 3.9.0 + jCustomer 2.5.0.

## Overview

When jExperience is installed and configured on a site, a Formidable form gains three
capabilities without anyone touching jExperience by hand:

- **A submission event.** Each accepted submission of a *tracked* form reaches jCustomer as the
  standard `form` event, sent by jExperience's tracker from the visitor's browser, with the values
  the server accepted. It feeds analytics, goals and segments exactly as a Forms submission does.
- **A form mapping.** Fields the author maps to profile properties produce a standard jCustomer
  form-mapping rule, created and kept in sync at publication. Marketers see it in the jExperience
  **Form mappings** screen.
- **Prefill.** A mapped field is pre-filled from the visitor's profile in the browser, with the
  profile properties the tracker loads anyway when the page opens.

What the 2026-09-10 revision changed, in one sentence: **Formidable's server never talks to
jCustomer about a visitor**. It publishes the mapping rule, feeds the profile-property dropdown of
the editor, and tells the browser which values it accepted; the tracker does the rest. The first
design (server-sent event, server-side prefill through a render filter, a server-side probe of
jCustomer's rules) is recorded in the [decision log](#decision-log) with the reasons it was dropped.

Non-goals for this iteration, each recorded with its reason in the decision log:

- A custom event type of Formidable's own. Standard rules never fire on it.
- A server-side event or prefill. Replaced by the browser-side design.
- A server-side probe of jCustomer's rules to know whether a form is referenced.
- A form-level mapping table in the editor. Recorded as a later option.

---

## Three things, one event

The author's mental model has three independent intents. jExperience implements them as one
event with two consumers, and that coupling drives every decision below.

| | Lives in | What it is |
|---|---|---|
| **The `form` event** | jCustomer | The trace of one submission: `eventType form`, target `itemType form` with the form's UUID as `itemId`, the page as source, the accepted values under `flattenedProperties.fields`. Persisted in jCustomer's event store, hence the statistics and dashboards. |
| **The mapping rule** | jCustomer | A rule tagged `formMappingRule` whose condition is a `formEventCondition` on that identifier and whose actions copy event fields into profile properties. Nothing about it lives in JCR. It is a *consumer* of the event: no event, no profile update. |
| **Prefill** | Browser | Reading the profile properties back into the form. It needs a mapped property to know what to read, but a mapping does not imply prefill. |

**What "tracked" means.** A form is tracked, and its submissions sent, in two cases:

1. **The author mapped at least one field** to a profile property. Mapping is a request to
   collect: without the event the mapping rule never fires.
2. **A marketer referenced the form in jCustomer** — a goal, a segment on a past event, a rule
   with a `formEventCondition`. jCustomer tells the browser about those references in the
   `trackedConditions` of every context response, and the tracker exposes them as
   `wem.getFormNamesToWatch()`. Formidable reads that list; see
   [The send condition](#the-send-condition-and-the-consent-gates) for why it is kept.

A form that is neither mapped nor referenced sends nothing, prefills nothing and has no rule —
the behaviour Forms has today. The option of a form-level switch ("send every submission") is
recorded and not built.

---

## Architecture

The mechanism fits in one sentence: **the browser talks to jCustomer through jExperience's
tracker, which is already on every live page; Formidable's server only publishes the mapping rule,
feeds the editor's property dropdown, and tells the browser what it accepted.** Formidable never
holds jCustomer credentials and never sees the visitor's profile.

```
Visitor's browser                          Formidable (server)          jExperience module      jCustomer
                                                                        (server, Jahia)         (CDP)

page  ◄── HTML (cached) with the form, an inline digitalDataOverrides
          push and a JSON config block ─── formidable-elements + the jExperience render filter

wem.js ─── context request, requiredProfileProperties included ───────────────────────────────►
       ◄── profile properties, trackedConditions ──────────────────────────────────────────────

island prefills the mapped fields

submit ─── POST ───────────────────────────► pipeline: whitelist, validation, actions
       ◄── 200 + accepted fields ────────────

island ─── wem.collectEvent(form event, accepted fields) ─────────────────────────────────────►
                                                                                                rule → profile

author  ─── publish ───────────────────────► listener ─── mapping rule ─── via the module ───►
```

One new Java module, `formidable-jexperience-engine`, holds every jExperience-specific piece.
Two small, generic changes land in the existing modules: a **submission-response enrichment SPI**
in the engine, and a **DOM event after a successful submission** in the elements' form island.
Nothing in Formidable depends on jExperience; the new module depends on both.

| Piece | Module | Role |
|---|---|---|
| `fmdbmix:profileMappableField` | formidable-engine, CND | Marker mixin, no properties: "this field can take part in a profile mapping". Declared as a supertype by every mappable field type in the elements and extended-inputs modules, and by third-party fields that want the feature. |
| `fmdbmix:jExperienceSensitiveField` | jexperience-engine, CND | The author's per-field "this value never leaves the site", held from the save and not from the next publication (the enricher reads both workspaces; see the decision log): one boolean, on a mixin that `extends` the marker like the mapping does and inherits `jmix:templateMixin`, so the Content Editor renders the checkbox without an enable switch. It has to be answerable before the mapping fieldset is switched on, and a switch of its own could contradict that one. |
| `fmdbmix:jExperienceProfileMapping` | jexperience-engine, CND | Property mixin that `extends` the marker, so it reaches every field claiming it without naming a field type or depending on the extended inputs: profile property (choicelist), write strategy. Surfaced as a "jExperience" section in the field's editor form through a Content Editor form override. |
| `jExperiencePrefill` + `jExperiencePrefillThen` | jexperience-engine, CND | Two properties of the mapping mixin, not a mixin of their own: the author's "fill this field from the visitor profile when the page opens", and what the page then does once the value is in the field — leave it editable, make it read-only, or hide it, the value still submitted. A field is prefilled when it is mapped to a property, has the switch on, and is not sensitive; the profile's value replaces a default value the author gave the field, and a field the profile has no value for keeps its default, untouched by the option. The option is required by the editor form only, and its choicelist chains `PrefillThenChoiceListInitializer`, which replaces the three answers with one message while the switch is off. |
| `ProfilePropertiesChoiceListInitializer` | jexperience-engine | Lists profile properties compatible with the field's shape (`FieldShapes`, inferred from the value-kind mixins), filtered on flags and system tags (`ProfilePropertyFilter`), through the module's admin client. Property types change rarely: `ProfilePropertyCatalog` keeps them one minute per site (a property just created in jExperience shows at the next opening); past the minute a failed read reports the schema unavailable rather than serving a list that may no longer be true. An unreachable jCustomer, or a schema with no property of the field's kind (jCustomer ships no boolean property), gives one message entry with an empty value, pre-selected so the closed select reads it (jcontent's `defaultProperty`), never a blank or broken dropdown. When jCustomer answers, the list is the truth: a stored mapping it does not carry is not offered and the Content Editor resets it. When jCustomer cannot be asked, the stored mapping is the one entry of the list, described as left unchanged, so a save during the outage cannot wipe a mapping the author never touched. A message without a select would need a selector of the module's own — a UI bundle, left for a later phase. Nothing reusable exists today in jExperience; the generic half is written so it can be lifted there later. |
| `MappingRuleSyncListener` | jexperience-engine | Live-workspace publication listener under `/sites`, **without a node-type filter**: it keeps two kinds of events by name — `j:lastPublished` added or changed (publication writes it on every published node; the nearest form above is resynchronised) and a node removed whose parent still exists (the head of a removal: the form above, or the removed node itself by its identifier). A typed listener would miss a form deleted by a published deletion and a field whose jExperience section was switched off. |
| `MappingRuleSynchronizer` | jexperience-engine | Builds the rule from live in the site's default language, compares it with the stored one (`GET /cxs/rules/{id}`, 204 = absent), posts only a change, deletes when the form leaves live or maps nothing. Coalesces a publication's bursts of events (2 s); leaves alone a site with no jExperience configuration; keeps a pending list retried every minute while jCustomer cannot be asked, guarded so that no error can stop the retries. |
| `MappingRule` | jexperience-engine | The rule as the JSON map jCustomer stores — id `formidable-form-mapping_<site>_<uuid>`, conditions, one `setPropertyAction` per mapped field with the value parameter of the property's type — and the "owned" projection the diff compares. |
| `FormMappingReader` | jexperience-engine | Reads a published form's mapped fields and applies the dropdown's own rule at publication: a property gone from the schema or no longer fitting the field's shape is skipped and logged, never turned into an action. A node that is not a form maps nothing — Jahia republishes a folder by removing and re-adding it in live, and the listener then resolves the folder by its identifier — so a rule is written for a form only. |
| `FormJExperienceRenderFilter` | jexperience-engine | Render filter on `fmdb:form` (same family as `CaptchaRenderFilter`). On a site whose pages carry the tracker — jExperience among the site's modules **and** settings for it, `JExperienceSite` — it writes next to the form: a JSON config block (`{formId, name, path, prefill}` — the mappings for sending are not in it, the accepted values reach the script through the submission's answer; `prefill` names, field by field, the profile property to read and, when the author asked for it, what follows the write — `then`, read-only or hidden) and a `<jahia:resource>` declaration of the module's client script, which core hoists into the `<head>` and keeps one for the whole page. Nothing inline and nothing per form: that one script reads every block of the page and asks the tracker for the union of the prefill properties in a single `digitalDataOverrides` entry. Its output carries no visitor data: the fragment stays cached — and the form's mappable fields are registered as dependencies of that fragment, so that mapping a field or switching its prefill on refreshes the block, which the form's own node, unchanged by either, would not. The filter sits just inside the fragment cache (priority 17; core's `CacheFilter` is 16.5): that is what makes the block part of the form's fragment and the fields part of its dependencies — see the decision log. |
| `formidable-jxp.js` | jexperience-engine, static resource | The client half, one instance per page. At load, pushes the union of every block's `prefill` properties once into `digitalDataOverrides`, before the tracker reads it. Once the context is loaded and the island has taken the form over (`formidable:ready`), writes the profile's values into the mapped fields the visitor has not touched — a trusted `input` or `change` event marks a control touched; the live state is never compared with the default one, which the browser sets on its own — an author's default gives way to the profile's value, a field the profile has no value for is left as it is, never blanked, and a written field is then left editable, made read-only or hidden as the author asked (`data-fmdb-prefilled` on its wrapper) — by their shape: a string, a number, the calendar day or minute in the visitor's zone for a date or a date-time, an option or a radio matched by value, a group or a multiple select by the values listed, a boolean; of a multi-valued property, the first non-empty scalar — an empty array or an object writes nothing. Then dispatches `input` and `change`, and `formidable:prefill` on a hidden control. Warns once when the page has no `digitalDataOverrides` or no tracker to register with. On `formidable:submitted`, decides with `shouldCollect()` and sends the `form` event through `wem.collectEvent`. |
| `SubmissionResponseEnricher` SPI | formidable-engine, `api` package | Called by the pipeline after all actions succeeded, with the form node, the site and the validated parameters; returns a JSON block to add to the 200. The jExperience module contributes `jexperience: {formId, fields}` — the accepted values of the form's fields, minus the ones marked sensitive — when the site's pages carry the tracker (`JExperienceSite`). Enrichers never fail the submission. |
| `formidable:submitted` | formidable-elements, `Form.client.tsx` | DOM `CustomEvent` (bubbling) dispatched after a 200, carrying the form's UUID and the parsed response. The elements module knows nothing of jExperience: it only says "this was accepted, here is what the server answered". |
| `formidable:ready` | formidable-elements, `Form.client.tsx` | DOM `CustomEvent` (bubbling) dispatched from the island's mount effect, together with `noValidate`, carrying the form's UUID: "the island is in charge from here". A script that writes into the fields waits for it, so that no island resets what it wrote. |
| `formidable:reset` | formidable-elements, `Form.client.tsx` | DOM `CustomEvent` (bubbling) dispatched once a reset has been applied — the visitor's Reset button, or the one the island performs after an accepted submission. It waits for the end of the task, since the native `reset` event fires *before* the browser restores the defaults, and a listener that fills the form again must not be undone by the reset itself. |
| `formidable:newForm` | formidable-elements, `Form.client.tsx` | DOM `CustomEvent` (bubbling) dispatched when the visitor asks for another form from the message that followed a submission. Same element, emptied. |
| `formidable:prefill` | jexperience-engine → formidable-elements, `Range.client.tsx` | DOM `CustomEvent` (bubbling) the client script dispatches on a **hidden** control after writing it, with `{value, then}`. The range slider's named control is a hidden mirror of the island's state, which a write cannot reach — React reconciles it back at the next render — so the island takes the value from the event, under the prefill's own rules spelt in `rangePrefill.ts` (visitor's answer kept, author's default given way to, a number within the bounds snapped to the step). A plain hidden field has no island and keeps the written value. The island also applies what follows the write (`then`): the script knows what it wrote into the mirror, not what the slider accepted, and the mirror is barred from constraint validation — so `readOnly` disables the slider and marks the wrapper, `hidden` hides the wrapper, both only for a value the island took (`applyAfterPrefill`, unit-tested under jsdom). |

The Java of `formidable-jexperience-engine` sits in packages named for their concern, one each, and none
is exported (`Export-Package: !*`): "public" there means "read across the module's packages", nothing more.

| Package | Holds |
|---|---|
| `model` | `JxpMixin`, `JxpProperty` — the module's own CND names, spelt once; `DefinitionsCndTest` keeps them and the CND saying the same, both ways |
| `profile` | the visitor profile schema as jCustomer describes it: `ProfilePropertyCatalog`, `ProfilePropertyDescriptor`, `ProfilePropertyFilter`, `ProfilePropertiesUnavailableException` |
| `field` | what a field is to a mapping: its shape (`FieldShape`, `FieldShapes`) and the author's sensitive flag (`SensitiveField`) |
| `choicelist` | the editor's dropdown: `ProfilePropertiesChoiceListInitializer` |
| `rule` | the mapping rule and its life in jCustomer: `MappingRule`, `FormMappingReader`, `MappingRuleSynchronizer`, `MappingRuleSyncListener` |
| `render` | the page: `FormJExperienceRenderFilter` |
| `submission` | the answer: `SubmissionEventEnricher` |
| `util` | `JExperienceSite` (is the site tracked, is it configured), `Json` |

`render`, `rule`, `submission` and `choicelist` are the entry points, a Jahia or engine hook each; they read
`field` and `profile`, and everything reads `model` and `util`. Nothing reads an entry point back.

---

## Data flows

Four moments, on the same lanes every time. A step marked *via jExperience* goes through the
module's admin client; a step marked *response* is an answer. Everything involving a visitor is
in the first lane or in jExperience's tracker.

### Editing: choosing a profile property for a field

```
1  Author's browser    ──►  Formidable            opens a field, section "jExperience"
2  Formidable          ──►  jExperience module    which profile properties fit this field?
3  jExperience module  ──►  jCustomer             GET /cxs/profiles/properties/targets/profiles   (cached)
4  jCustomer           ──►  Formidable            property types, filtered by field shape and tags   (response)
5  Formidable          ──►  Author's browser      dropdown of compatible properties                   (response)
6  Author's browser    ──►  Formidable            saves property, prefill toggle and strategy
```

The dropdown is server-fed. The editor never needs a jExperience permission or the proxy,
because the choicelist initializer calls jCustomer with the module's admin client.

### Publishing: keeping the mapping rule in sync

```
1  Author's browser    ──►  Formidable            publishes the form
2  Formidable                                     listener: mapped fields → rule, compared with the existing one
3  Formidable          ──►  jCustomer             POST the rule — or DELETE it when nothing is mapped   (via jExperience)
4  jCustomer                                      rule stored, visible in Form mappings
```

Diff before POST, as the Forms bridge does, so a publication that changes nothing does not
churn the rule. The rule id is derived from the site key and the form's UUID, so renaming a
form never orphans a rule.

| Hop | Carries | Trust |
|---|---|---|
| Author → live | The field nodes with their mixin properties | Editor permissions, as any publication |
| Listener → jCustomer | Rule id, scope, one `setPropertyAction` per mapped field | Admin client owned by jExperience; the listener runs in a system session |

### Rendering and prefill: the tracker loads what the form needs

```
1  Visitor's browser   ──►  Formidable            GET page
2  Formidable          ──►  Visitor's browser     cached HTML: the form; before it, the render filter's JSON config
                                                  block with the prefill pairs, and the client script    (response)
2b formidable-jxp.js (head, defer)                reads every block of the page, pushes the union of their
                                                  properties once into digitalDataOverrides
3  wem.js (end of page)                           starts; applies the overrides at DOMContentLoaded
4  wem.js              ──►  jCustomer             /cxs/context.json — the request it makes anyway, now asking the properties
5  jCustomer           ──►  wem.js                context: profile properties, trackedConditions      (response)
6  formidable-jxp.js                              once wemLoaded AND formidable:ready: fills the mapped fields
                                                  flagged prefill — the visitor's input kept, an author's default replaced
```

Nothing personal is in the HTML: the page and the form fragment stay cached for everyone, and
one context request — the tracker's own — serves every prefilled field. The `push` is the client
script's, not the fragment's: jExperience creates `window.digitalDataOverrides` in the head and reads it
at `DOMContentLoaded`, after every deferred script has run, so the one script core hoists into the head
sees every block of the page and pushes their union once — nothing inline, nothing repeated per form,
the lesson of #330. jExperience documents this extension point through its own tests
(`wem.digitalDataOverrides.cy.ts`).

**Verified (2026-09-17).** Hydration cannot undo a prefill, because none happens before it: the
script writes only when both gates are open — the tracker's context loaded (`wemLoaded`, read as a
flag and through the tracker's own load callback, registered after the one that sets the flag) and the
island mounted (`formidable:ready`, and `form.noValidate` for a script that loads after the event). Every
input is uncontrolled, so React alone would have left a value; but the masked text and the bounded date
islands rewrite their input on mount, and a rule that depends on which island a field has is a rule that
breaks at the next island. A multi-valued property comes back as an array of strings; a single one as a
string, dates as ISO date-times, booleans as booleans.

**The touched guard reads what the visitor did, never the live state** (review of 2026-09-17, measured
on the rendered DOM). A single `<select>` has its first option selected by the browser while
`defaultSelected` stays false; a colour input is born `#000000` over an empty attribute; a field the
visitor typed in then cleared reads `value === defaultValue` again. So a control counts as touched when a
trusted `input` or `change` event reached it — the browser's own, never one a script dispatched, this one
included. What the author wrote as a default is no guard (2026-09-18): the profile's value replaces it,
and a profile with nothing to say leaves it, never blanking a choice. A date takes the calendar day of the
instant in the visitor's zone, not its UTC day. A hidden control is told as well as written
(`formidable:prefill`), because the range slider's named control is a controlled mirror.

**After the write, what the author asked** (`jExperiencePrefillThen`, 2026-09-18 — the two behaviours Forms'
prefill offers as `makeReadOnlyAfterPrefill` and `hideAfterPrefill`, as one choice). Read-only sets the
native attribute on a textual control; a select, a radio, a checkbox or a colour input has none (`readonly`
applies to the textual types only), and `disabled` would take the value out of the submission, so they get
a listener that puts the prefilled state back on every change and says the change again on what it put back
— an island's validation, registered at hydration, runs before the restore and would otherwise keep the
visitor's transient state — while the base stylesheet tones the field down and takes the pointer off those
controls (`prefill.css`, two variables). `aria-readonly` goes where the role supports it: the checkbox and
the select, and for radios the group — the `radiogroup`, since a bare `fieldset` is a `group`, which carries
it no better than a `radio` does. The four views that render same-named radios mark it (`fmdb:radio`, and
`fmdbext:switch` in its buttons mode, `fmdbext:rating`, `fmdbext:scale`); the script looks for
`[role="radiogroup"]` and falls back to the field's own `[data-fmdb-node-name]` wrapper, which every shape
has — a one-choice radio has no fieldset at all, and climbing to the nearest one would mark an author's
fieldset of unrelated fields. That fallback is a **placement, not an announcement**: the wrapper carries no
role, so assistive technology ignores `aria-readonly` there — what it buys is that the attribute does not
land on an author's unrelated fieldset, which is the bug it was added for. A colour input has no role to
carry it either. Hidden sets `display: none` on the field's wrapper, the value still submitted. Both write
`data-fmdb-prefilled` (`readonly` or `hidden`) on the wrapper, the styling hook. A written choice counts as
prefilled whether or not the DOM changed — a profile that merely confirms the author's default gets the
same `then` as one that differs (review). The range slider is the island's own business: its named control
is a hidden mirror the script writes without knowing what the slider accepts, so the script leaves a hidden
control alone and the island applies `then` for a value it took (`applyAfterPrefill`). Neither touches a
field the prefill left alone — the visitor has to be able to fill it — and a value the field's own validation
rejects is never hidden, since the visitor could neither see the error nor fix it. The conditional logic keeps
such a wrapper out of sight when a rule shows it again, while still enabling its controls and reading it as a
source: it is not hidden *by the logic*.

**The prefill runs again when the form goes back to what the page opened with.** A reset empties the
fields and a "new form" hands the visitor the same empty element, so a form the profile fills would come
back blank for a visitor it knows — and locked or hidden fields would stay locked over values that are
gone. The island says both in the same vocabulary as the rest (`formidable:reset`, `formidable:newForm`,
the form's UUID in the detail), and `api.prefillAgain` does three things for that form alone before
running the ordinary prefill: it gives back everything the previous run did — the locks, the marks, the
fields put out of sight — then forgets the mark that says the form was filled once, and what its controls
remember of having been touched, since what the visitor typed went with the reset. Giving back first is
what makes a replay safe: a second `lock()` over a field the first one locked would read the read-only
attribute as the author's and never hand it back. The gates are the same, so a page with no tracker or no
context does nothing, as before.

**An error is not a new form.** The island empties the form after an accepted submission, and only that
path says `formidable:newForm`; after a rejected one the form stays on screen with everything the visitor
typed, and its "try again" button says nothing — the prefill would otherwise write the profile's values
over the corrections they just made, since a replay forgets what the controls remember of being touched.

Two things a refusal and a reset must undo (review, 2026-09-18). The script writes the slider's mirror before
telling the island, and the mirror is what the form posts: when the island refuses the value — out of the
slider's bounds, or over an answer the visitor already gave — nothing re-renders, so the island puts the
mirror back itself (`restoreMirror`), or the form would carry a value the visitor never gave. And a form
reset empties the fields, so nothing is prefilled any more: the wrapper comes back in sight and loses
`data-fmdb-prefilled`, the lock is lifted, on both sides (`clearPrefillMarks` in the island; in the script
the form's undo list, run on the native reset and again before any replay). Without it a required field
hidden by the prefill would come back unanswered behind a `display: none` wrapper, blocking the submission
over an error the visitor cannot see.

### Submitting: through the pipeline, then to the profile

```
1  Visitor's browser   ──►  Formidable            POST fid · lang, as today
2  Formidable                                     pipeline steps 1-12: whitelist, validation, captcha, actions
3  Formidable          ──►  Visitor's browser     200 + jexperience: {formId, fields} — the accepted values, purged   (response)
4  Form island                                    dispatches formidable:submitted with the response
5  formidable-jxp.js                              shouldCollect(formId)? → wem.collectEvent(wem.buildFormEvent(formId) + fields)
6  wem.js              ──►  jCustomer             the form event, with the tracker's profile and session cookies
7  jCustomer                                      stores the event, applies the mapping rule → profile updated
```

The event carries what the pipeline accepted, minus what the author marked sensitive: undeclared
fields, rejected values, files and every field flagged sensitive never reach jCustomer. A field the
visitor left unanswered — a text left blank, a select left on its empty option, a group with nothing
ticked — is **absent** from the event, not sent empty: jCustomer writes whatever the event carries,
and an empty string is a value to it, so `alwaysSet` would erase the profile's value and
`setIfMissing` would write the empty string and then keep it for good (issue #339). An absent field
resolves to nothing and the action writes nothing, under either strategy. This is Forms' behaviour —
its result data leaves an empty answer out — and the tracker's own listener leaves an empty input out
of a plain form. A rejected submission (400) sends nothing, since the island only dispatches on a 200.

---

## The identifier

The form's identity in jCustomer is **its JCR UUID**: `target.itemId` of the event, `formId` of the
mapping rule, what a marketer types in a goal, and what the rendered `<form>` carries as `id` and
`name`.

- **Stable.** The UUID survives renames, moves and languages; Forms used a node name and lost its
  history on a rename.
- **Valid.** Unomi's item schema constrains `itemId` to `^(\w|[-_@\.]){0,60}$`: 36 characters,
  hexadecimal digits and dashes.
- **Nothing to store, nothing to show.** The UUID is what Jahia already gives every node: jContent
  and the Content Editor's technical information display it, GraphQL and the MCP server return it.
  No mixin, no property, no listener, no editor field: the first design's `formidable-jxp-<uuid>`
  property and its stamping pass were removed on 2026-09-15 (decision log). That is true of the CND
  and of the node; a rule already written **outside** Jahia by an earlier snapshot keeps the old
  identity until the form is republished — a matter for our own development instances only, since
  nothing released ever wrote such a rule, so it is not in the upgrade notes (HDU, 2026-09-17).
- **Carried by the markup.** elements renders `id` and `name` equal to the UUID, so everything in
  jExperience that reads a form from the page agrees with the rule and the event: the tracker's key
  is `name`, then `id`; the tag picker of the Form mappings screen reads `name|id`, first present
  wins; the same screen loads the live page and looks the form up by `form[name=…]`, then `#…`. A
  mapping created from jExperience's own screen therefore keys on the identifier the island sends.
  The title is not a candidate: read before `id` by all three, it would key a mapping on a label
  that changes with the language.
- **The tracker is kept off by two attributes**, rendered by elements on every form, jExperience
  module or not: `data-form-id` (its initial scan skips any form carrying one — the Jahia Forms
  convention) and `data-wem-observed="true"` (its observer of late forms skips those already
  marked). Without them the tracker would attach its own `submit` listener as soon as a goal or
  our own mapping rule names the form — its watch list is the `formId` of every
  `formEventCondition` of the context — and send the raw DOM fields before validation, then the
  island would send again. Both hooks were read in wem.min.js 4.2.1
  (`_registerListenersForTrackedConditions`, `_observeForms`); the one side effect sits in the Form
  mappings screen, which tries the Forms API for a form carrying `data-form-id`, gets a 404, logs
  it and extracts the fields from the page. Consequence: a Formidable form is never auto-tracked by
  jExperience; submissions reach jCustomer through this module only, with the values the pipeline
  accepted.
- **Readable in dashboards.** The event's target carries `properties: {name, path}` — Unomi's item
  schema allows a free `properties` object on the target, no schema extension needed — so a Kibana
  dashboard keys on `target.itemId` and labels with `target.properties.name`; a renamed form keeps
  its series.
- **Accessible name.** The form's title goes in `aria-label`: nothing inside the `<form>` repeats
  it, and a form landmark without a name is not announced as one.

---

## The send condition and the consent gates

One function of the client script, `shouldCollect(formId)`, holds every reason to send or not.
It is deliberately the single place where Formidable depends on the tracker's API, so that a
change on jExperience's side is a change of one function and its test.

```js
const shouldCollect = (formId) =>
  window.wem !== undefined                                    // tracker present: no consent manager blocked it
  && window.wemLoaded === true                                // callbacks executed — set in the fallback mode too
  && Boolean(wem.getLoadedContext()?.profileId)               // a context really loaded: no profile, nothing to bind to
  && window.digitalData?.wemInitConfig?.activateWem !== false // the visitor did not disable tracking in jExperience
  && !window.digitalData?.wemInitConfig?.disableTrackedConditionsListeners
  && wem.getFormNamesToWatch().includes(formId);              // the form is named by a rule of the context
```

Why each line:

- **`window.wem` and `wemLoaded`.** A consent manager that blocks the script leaves no tracker;
  a page whose context failed to load has no profile to bind the event to. Both are jExperience's
  own signals, read as is.
- **`activateWem`.** jExperience's per-visitor switch ("disable tracking", the
  `enableWemActionUrl` action): when a visitor turned tracking off, Formidable sends nothing and
  prefills nothing.
- **`disableTrackedConditionsListeners`.** An integrator's page-level choice that turns the
  tracker's automatic form, video and link tracking off. Formidable's collection is of that
  kind: it follows the flag, exactly as Forms does — the Forms bridge sends only when
  `getFormNamesToWatch()` names the form, and that list stays empty under the flag. Formidable
  never sets the flag itself: it is global to the page and would silence other modules.
- **`getFormNamesToWatch()`.** The `formId`s of the `trackedConditions` jCustomer returned for the
  page: goals and segments are rules in Unomi, and **so is the mapping rule this integration writes
  at publication**, so one list answers both "a marketer referenced this form" and "the author mapped
  a field of it" — the page declares nothing about its mappings, and there is no second list to keep
  in step (HDU, 2026-09-15). **Kept on purpose** (HDU, 2026-09-10): without it, a goal created in
  jExperience on a form nobody mapped would never count, and the marketer has no way to know why —
  Forms behaves as the list says, and marketers expect the same. Romain would rather see
  `trackedConditions` go; if jExperience removes or reworks them, this line is what changes, and the
  mappings the page would then have to declare are the fallback. Two facts to remember when it does:
  the list is filled inside the tracker's context callback, so it must be read at submission time and
  not at page load; and it is empty under `disableTrackedConditionsListeners`, which is the behaviour
  wanted anyway.

Prefill keeps the tracker's gates — `wem`, `wemLoaded`, a context with a profile, `activateWem` — and
drops the two about tracking: the watch list and `disableTrackedConditionsListeners`, since prefill reads
and tracks nothing. It adds one of its own, the island's readiness. A visitor who refused tracking is not
prefilled either.

---

## Data contracts

### The render filter's contributions

Emitted before the form markup, in live, by `FormJExperienceRenderFilter` when the site's pages
carry jExperience's tracker; cacheable, identical for every visitor. **Shipped in phase 3:**

```html
<script type="application/json" data-formidable-jxp="FORM-UUID">
  {"formId": "FORM-UUID", "name": "Contact form", "path": "/sites/mysite/contents/contact"}
</script>
<script src="/modules/formidable-jexperience-engine/javascript/formidable-jxp.js" defer></script>
```

The block carries three strings and the prefill pairs, no more: the identifier the event is keyed on,
the title and path its target properties read, and for each field the author asked to prefill the
profile property it reads and, when the author asked for something after the write, `then` (`readOnly` or `hidden`). **What the form
maps for sending is deliberately not in it** — the send decision reads the tracker's own watch list,
which a mapped form is in through the rule published for it, so declaring the mappings again would be
a second list to keep in step for nothing (HDU, 2026-09-15). The prefill pairs are the one thing the
context cannot say: which field a returned property belongs to. The block is
emitted for every form, and so is the script's `<jahia:resource>` declaration — the filter is called
once per form and has no page-level state to dedupe on. Core's `StaticAssetsFilter` does that part:
it hoists the declarations of the aggregated page into the `<head>` and keeps one per path, so a page
carrying several forms loads and runs the script once. The marker travels inside the cached fragment,
which is why it is written into the output rather than registered on the request — this filter sits inside
the fragment cache (priority 17, after core's `CacheFilter` at 16.5), so it runs on a cache miss only, and a
cached fragment replays nothing. The form is read in a session of the filter's own: a form
placed through a reference renders contextualised under it, and the render session hands that same
node back for the identifier (see the decision log), so the block would otherwise name the
reference and find no mapped field.

The pairs are read in the filter's own live session, from the JCR alone — a field carrying the mapping
mixin with a property, its prefill switch on, not marked sensitive — never from jCustomer: a render is
not the place for a network call, and a property the schema no longer offers simply comes back absent
from the context.

```html
<script type="application/json" data-formidable-jxp="FORM-UUID">
  {"formId": "FORM-UUID", "name": "Contact form", "path": "/sites/mysite/contents/contact",
   "prefill": {"firstName": {"property": "firstName"}, "email": {"property": "email", "then": "readOnly"}}}
</script>
```

No inline script comes with it. The client script, once in the head, gathers the `prefill` properties
of every block on the page and pushes them in one `digitalDataOverrides` entry before the tracker
reads the array (see "Rendering and prefill"). The property names are exactly the mapped ones, no
wildcard: the profile stays private to what the page's forms need.

### Submission request and response

| Element | Value | Status | Server handling |
|---|---|---|---|
| `?fid` | Form node UUID | existing | Validated as UUID, resolved in live (pipeline steps 2 and 4, see [Form submission flow](form-submission-flow.md)) |
| `?lang` | Language tag of the rendered form | existing | Locale of the submission |
| Cookies | `wem-profile-id`, `wem-session-id` | existing (jExperience) | **Not read by Formidable.** The tracker attaches them to the event it sends; Formidable never sees, stores or forwards a profile id |
| Response `jexperience` block | `{formId, fields}` on a 200 | new | Added by the `SubmissionResponseEnricher` of the jExperience module on a tracked site — jExperience among the site's modules **and** settings for it: the accepted values of the fields carrying `fmdbmix:profileMappableField`, minus those flagged sensitive: files, buttons, containers and every sensitive field never leave the server, as strings, multi-valued as arrays |

Nothing of the first design's `pid` parameter and `X-Formidable-Tracking` header remains: the
page identity comes from the tracker's own `buildSourcePage()`, in the browser that shows it.

### The `form` event

Built by `wem.buildFormEvent(formId)` — event type, scope, source page, profile and session come
from the tracker — then completed by the client script:

```json
{
  "eventType": "form",
  "scope": "mysite",
  "source": { "itemType": "page", "itemId": "PAGE-UUID", "scope": "mysite",
              "properties": { "pageInfo": { "pageID": "PAGE-UUID", "pagePath": "/sites/mysite/home/contact",
                                            "destinationURL": "https://www.example.com/contact-us", "referringURL": "https://www.example.com/" } } },
  "target": { "itemType": "form", "itemId": "FORM-UUID", "scope": "mysite",
              "properties": { "name": "Contact form", "path": "/sites/mysite/contents/contact" } },
  "flattenedProperties": {
    "fields": { "firstName": "Ada", "email": "ada@example.com", "topics": ["cdp", "forms"], "newsletter": "true" }
  }
}
```

- **Values** come from the `jexperience.fields` block of the 200 — never from the DOM. Every
  answered field the pipeline accepted, as strings, multi-valued as arrays; files, buttons,
  containers and every field the author marked **sensitive** never leave the server. A field left
  unanswered is **absent**, never an empty string or an empty array — blank values are dropped, and a
  field with nothing left is not in the block — so that the rule writes nothing for it. Unmapped
  fields are in it on purpose, as in Forms: a mapping made in jExperience's own Form mappings
  screen names a field this module need not know. Type conversion is the rule's job.
- **Source** is the page as the tracker sees it: identity, real URL, referrer — the same source
  jExperience puts in every page view, so marketing can test by URL.
- **Target properties** are the readable label of the identifier; Unomi's `FormSource` schema
  refers to the base item schema, whose `properties` is a free object.

### The mapping rule

```json
{
  "metadata": {
    "id": "formidable-form-mapping_mysite_FORM-UUID",
    "name": "Contact form", "description": "Formidable auto mapping",
    "scope": "mysite", "systemTags": ["formMappingRule"]
  },
  "priority": -1,
  "condition": { "type": "booleanCondition", "parameterValues": { "operator": "and", "subConditions": [
    { "type": "formEventCondition", "parameterValues": { "formId": "FORM-UUID" } },
    { "type": "booleanCondition", "parameterValues": { "operator": "or", "subConditions": [
      { "type": "sourceEventPropertyCondition", "parameterValues": { "scope": "mysite" } }
    ] } }
  ] } },
  "actions": [
    { "type": "setPropertyAction", "parameterValues": {
        "setPropertyName": "properties(firstName)", "setPropertyStrategy": "alwaysSet",
        "setPropertyValue": "eventProperty::flattenedProperties(fields)(firstName)" } },
    { "type": "setPropertyAction", "parameterValues": {
        "setPropertyName": "properties(interests)", "setPropertyStrategy": "setIfMissing",
        "setPropertyValueMultiple": "eventProperty::flattenedProperties(fields)(topics)" } }
  ]
}
```

- **Whole-site source condition** instead of one path per page hosting the form. A Formidable
  form is reusable content placed on several pages; the Forms bridge queried every display node
  to list paths, which is complexity without benefit here.
- **Value key by property type**, read from the property types endpoint: string →
  `setPropertyValue`, integer → `…Integer`, boolean → `…Boolean`, multivalued → `…Multiple`. The
  bridge never emitted the multivalued key; jExperience's own screen does. Date properties:
  confirm the Unomi 3 parameter before promising them.
- **Strategy per field**, `alwaysSet` by default or `setIfMissing`, the two values jExperience's
  screen offers.
- **Name** = the form's title, what the Form mappings screen shows next to the identifier.

### JCR definitions

```cnd
// formidable-engine — the marker: "this field can take part in a profile mapping"
[fmdbmix:profileMappableField] mixin

// formidable-elements and formidable-extended-inputs — every mappable field declares it as a supertype,
// exactly as it declares fmdbmix:element. Third-party field types opt in the same way.
[fmdb:inputText] > jnt:content, fmdbmix:element, fmdbmix:textField, fmdbmix:profileMappableField, ...
[fmdbext:switch]  > jnt:content, fmdbmix:element, fmdbmix:booleanField, fmdbmix:profileMappableField, ...

// formidable-jexperience-engine — one extension target, no list of field types, no dependency on the extended inputs.
// `extends` only, no supertype: carrying the mapping must not make a node "mappable" — the type declares that.
[fmdbmix:jExperienceProfileMapping] mixin
 extends = fmdbmix:profileMappableField
 itemtype = content
 - jExperienceProfileProperty (string, choicelist[formidableJExperienceProfileProperties]) indexed=no
 - jExperienceSetStrategy (string, choicelist[resourceBundle]) = 'alwaysSet' autocreated indexed=no < 'alwaysSet', 'setIfMissing'
 // the prefill lives in the mapping: it needs a mapped property to do anything, and the editor cannot show
 // one fieldset only when another is on. Its option's choicelist chains our initializer, which puts a message
 // in the dropdown while the switch is off — dependentProperties asks the list again the moment it is ticked
 - jExperiencePrefill (boolean) = false autocreated indexed=no
 - jExperiencePrefillThen (string, choicelist[resourceBundle,formidableJExperiencePrefillThen,dependentProperties='jExperiencePrefill,jExperiencePrefillThen']) = 'editable' autocreated indexed=no < 'editable', 'readOnly', 'hidden'

// formidable-jexperience-engine — the author's "this field is sensitive"; jmix:templateMixin is what
// drops the fieldset's enable switch, and the mapping's choicelist names the property in its
// dependentProperties, so ticking the box empties that dropdown without a save
[fmdbmix:jExperienceSensitiveField] > jmix:templateMixin mixin
 extends = fmdbmix:profileMappableField
 itemtype = content
 - jExperienceSensitive (boolean) = false autocreated indexed=no
```

This follows the two-family rule of [CND module ownership](cnd-module-ownership.md): marker
mixins the back end reads to know what a type *is*, owned by the engine and claimed by field
types as supertypes; property mixins that add what the author configures, owned by the feature
module and attached through a marker, never through a list of concrete field types. The marker
plays for mapping the role `fmdbmix:formElement` plays for the field list.

A mixin *can* extend another mixin: the Content Editor resolves `extends` with
`type.isNodeType(target)`, which is true for every supertype of the field type, mixins included
(jcontent, `EditorFormServiceImpl.getExtendMixins`). The same method only offers an extension
when the module that declares it is installed on the site, so the "jExperience" section appears
exactly on the sites where the jExperience module of Formidable is deployed.

### Type compatibility in the dropdown

Which fields are mappable at all is decided by the marker mixin; which properties a mappable
field sees is decided from Formidable's value-kind mixins, so a third-party field that opts into
`fmdbmix:numberField` is treated like the built-in number field. The same table drives the value
key the rule builder picks, so the dropdown and the rule never disagree.

| Field kind (mixin) | Built-in fields | Profile property types offered | Cardinality |
|---|---|---|---|
| `textField` | text, textarea | `string` | single |
| `emailField` | email | `email`, `string` | single, or multivalued when the field is `multiple` |
| `numberField` | number, range; extended rating, scale | `integer`, `long`, `float`, `double` | single |
| `booleanField` | extended consent, switch | `boolean` | single |
| `dateField`, `datetimeLocalField` | date, datetime | `date` | single |
| `colorField` | color | `string` | single |
| `choiceField` | radio, select, checkbox | `string` (option values are strings) | single for radio, single select and a one-choice checkbox; multivalued for multiple selects and checkbox groups (two choices or more, or a count the source cannot give) |
| no kind mixin | hidden | `string` | single |
| `fileField` | file | not mappable | — |

- **Cardinality must match.** A multivalued property is offered only to a multi-valued field and
  vice versa. Relaxing single field → multivalued property, which jCustomer accepts, is a
  possible later refinement.
- **Cardinality comes from the `multiple` property.** The built-in select and email inputs declare
  a `multiple` boolean; a third-party type adopts the convention by declaring a property of that
  name and is single-valued without it — the value-kind mixins themselves carry no cardinality.
  The dropdown follows the toggle as the author holds it, unsaved: the choicelist declares
  `dependentProperties='multiple'`, so the Content Editor asks the list again when the toggle
  changes (jcontent re-queries a choicelist only for the properties its selector options name,
  and ships the unsaved value in the initializer's context).
- **The checkbox follows its number of choices, as the view does**, and says so with a mixin:
  `fmdbmix:cardinalityFromChoices`, declared by the engine and carried by `fmdb:checkbox`. No type
  name is read here — a third-party field that renders one input per choice opts into the same rule
  by carrying the mixin, exactly as it opts into a value kind. The renderer draws one `<input type="checkbox">`, submitting one value, for exactly
  one choice and a group otherwise; the shape applies the same rule to the same count. The count
  comes from the engine's `ChoiceOptionsResolver` (its `api` package), which resolves the choices
  as the view does — the manual list aligned on the default language, or what the options source
  delivers — or, while the author edits, from the `options` list the editor holds unsaved
  (`dependentProperties` names `options` and `optionsMode`). A count the source cannot give, or a
  switch to a sourced mode not saved yet, is a group. When a change of choices moves a checkbox
  from one shape to the other, the stored mapping is not offered any more: the editor resets the
  select and the next save clears a mapping that would never be applied (HDU, 2026-09-14 — a mapping
  shown must be one that will happen).
- **Always removed**, whatever the type: properties flagged hidden, read-only or protected in
  their metadata, and those tagged `systemProfileProperties` or
  `hiddenFromFormMappingProperties`, the union of what the prototype, the Forms bridge and
  jExperience's own screen each exclude.
- **Where the stacks differ.** jExperience's Form mappings screen does not filter by type at all
  and lets jCustomer convert at rule time; the Forms bridge filters per input type with a table
  close to this one. Formidable follows the bridge's stance: a filtered dropdown is what makes
  the section usable for an author who does not know the profile schema.
- **Seen on jCustomer 3.0.0** (2026-09-11, default schema): `valueTypeId` ∈ {`string` ×24, `integer` ×5,
  `date` ×4, `email` ×1}; 7 read-only properties, 5 multivalued; `systemProfileProperties` on 5 and
  `hiddenFromFormMappingProperties` on 7. Still to confirm: the `setPropertyAction` parameter for dates.

### The editor section: a Content Editor form override

An `extends` mixin shows up in the Content Editor as a switch-gated fieldset in the main
section. To give the mapping its own tab, the module ships a form override that declares a
**jExperience** section, exactly the way the engine declares the **Logic** section for
`fmdbmix:formLogicElement`. Java module, so the file lives under `META-INF`, not `settings`:

```
formidable-jexperience-engine/src/main/resources/META-INF/jahia-content-editor-forms/forms/fmdbmix_jExperienceProfileMapping.json
```

```json
{
  "nodeType": "fmdbmix:jExperienceProfileMapping",
  "priority": 2.0,
  "sections": [
    {
      "name": "jexperience",
      "labelKey": "fmdb.section.jexperience",
      "rank": 1.15,
      "fieldSets": [
        {
          "name": "fmdbmix:jExperienceProfileMapping",
          "rank": 0.0,
          "fields": [
            { "name": "jExperienceProfileProperty" },
            { "name": "jExperienceSetStrategy" }
          ]
        }
      ]
    }
  ]
}
```

The prefill is the last two fields of that same fieldset: the author's "prefill this field", and what the
page does once the profile's value is in the field — leave it editable, make it read-only, hide it. The
profile's value replaces a default the author gave the field and a missing value leaves it; the "replace the
field's default value" option of the day before went with that decision (decision log, 2026-09-18).

They sit inside the mapping because a prefill needs a mapped property to do anything at all, and the editor
has no way to show one fieldset only when another is switched on: a mixin's own switch is the one conditional
display it offers, a fieldset `condition` is resolved against the saved node when the form is built, and
`extends` is matched against the primary node type, never against a mixin the node carries. Inside the
mapping the dependency is structural instead — no mapping, no prefill, and switching the mapping off takes
the prefill with it. What the editor still cannot do is grey out one field while the box beside it is
unticked, so the option's choicelist does it in words: `PrefillThenChoiceListInitializer` replaces the three
answers with one message ("switch the prefill on above…") while the switch is off, and the choicelist names
the switch in its `dependentProperties`, so the editor asks the list again the moment the box is ticked, with
no save in between. That entry carries the value the field already holds rather than an empty one — which is
what lets the option stay required while saying nothing.

That option is **required by the editor only**, and by the right file: `"mandatory": true` in
`META-INF/jahia-content-editor-forms/**fieldsets**/fmdbmix_jExperienceProfileMapping.json`, nothing in the
CND. The Content Editor keeps two static registries and merges only the fieldset one into a field's
definition — a `forms/` file places the fields in sections and what it says about one of them is dropped,
so the same flag there looks right and does nothing (measured: `mandatory: false` on the deployed instance,
`true` once the override was a `fieldsets/` file keyed by `name`). The CND stays as it was deployed: a
mandatory flag added to a type already registered is a MAJOR change to Jahia's `DefinitionsBundleChecker`,
which cancels the deployment of the module. The options-source settings of `formidable-elements` carry
theirs in a `fieldsets/` override for the same reason.

- **Rank.** Sections order by rank, and the ranks Formidable already uses are 1.10 for Logic and
  Responses, 1.20 Buttons, 1.30 Multi-step, 1.40 Style, 1.50 Validation messages. 1.15 gives
  jExperience a rank of its own and places it right after Logic on a field. Documenting the rank
  table and how to add a section is tracked in
  [formidable#311](https://github.com/Jahia/formidable/issues/311).
- **Labels.** `fmdb.section.jexperience=jExperience` plus the fieldset and property labels go in
  the module's own bundle, a Java bundle, so the French file escapes its accents as `\uXXXX`.
- **The switch stays.** The fieldset is left activatable, not `isAlwaysActivated`: switching it
  on is the author's "map this field to the visitor profile", and only fields carrying the mixin
  with a non-empty property take part in the rule and in prefill. Always-on would add the mixin
  to every mappable field saved on the site, which the
  [extension guide](../extension/how-to-extend-views-and-elements-from-third-party-module.md)
  accepts for a sample and flags as a deliberate choice for a product module.
- **No custom selector.** The standard choicelist selector renders the initializer's values, so
  no `fieldsets/` override is needed; the Logic section needs one only because its rules editor
  is a React selector of its own.
- **Two controls, in this order.** The sensitive checkbox first, rendered without a switch
  (`forms/fmdbmix_jExperienceSensitiveField.json`, rank -1, `isAlwaysActivated` on a
  `jmix:templateMixin` fieldset), then the mapping fieldset with its own switch. The flag gates the
  mapping, so it must be answerable before the mapping is switched on — which is why it is a
  property of a second mixin and not a field of the mapping's own. The mapping's dropdown names it
  in `dependentProperties`, so ticking the box replaces the properties with one message before any
  save; nothing greys out the mapping switch itself (see the decision log).
- **No section on the form itself.** The form's identity in jCustomer is its UUID, which the
  Content Editor's technical information and jContent already display; the author copies it into a
  goal from there. The first design stamped a `formidable-jxp-<uuid>` property on every form through
  a mixin, a listener and a start-up pass, and showed it read-only in a section of the form —
  removed 2026-09-15, see "The identifier".

## Security and trust model

The principle is the one Formidable already applies to the logic-state header: the client
provides what only the browser knows, the server establishes everything it can, and no client
indication has power over validation, storage or another visitor's data. In the browser-side
design the server's part is smaller and sharper: it decides **what** may reach jCustomer (the
accepted, purged values) and the tracker decides **for whom** (its own cookies) and **whether**
(its consent signals).

### What the server establishes alone

| Gate | Source of truth | Applies to |
|---|---|---|
| jExperience installed on the site **and** holding settings for it | `JExperienceSite.tracked` (`getInstalledModules`, `getContextServerStatus`) | render filter output, response block |
| jExperience holding settings for the site | `JExperienceSite.configured` | the mapping rule — its question is reaching jCustomer, not contributing to a page |
| jCustomer reachable | `isAvailable(siteKey)`, called from `MappingRuleSynchronizer` and `ProfilePropertyCatalog` only | the mapping rule, and the profile properties the editor offers — the two things that talk to jCustomer |
| The values that may leave | Pipeline steps 1 to 12: field whitelist, validation, actions succeeded; the mappable marker, then the author's sensitive flag | response block, hence the event |
| The mapping rule | Publication listener in a system session, admin client owned by jExperience | rule |

### What the browser establishes

| Gate | Source of truth | Applies to |
|---|---|---|
| Profile and session identity | The tracker's first-party cookies, attached by the tracker itself | event, prefill |
| Consent | No tracker (blocked script), `activateWem` off, context not loaded | event, prefill |
| The form is tracked | Mapped field (in the cached config) or `getFormNamesToWatch()` (jCustomer's rules) | event |
| Bot filtering | jExperience's user-agent check before it injects the tracker (`ContextActivatorFilter`): a bot gets no `wem.js`, hence no event and no prefill | event, prefill |

### Threats

| Threat | What it takes | Effect | Control |
|---|---|---|---|
| Send an event with values the pipeline rejected | Any HTTP client calling jCustomer's public collector | Possible today for anyone, with or without Formidable: the public collector accepts any `form` event bound to one's own profile. Formidable adds no surface: its script only ever sends the server's `fields` block | Out of Formidable's hands by design; property types on the jCustomer side |
| Forge a `jexperience.fields` block | Would require forging the server's 200 | None: the block is computed server-side from the validated parameters | Pipeline |
| Poison another visitor's profile through Formidable | The victim's profile cookie | No new path: Formidable never handles a profile id; the tracker binds the event to the cookies of the browser it runs in, as for every page view | Same as jExperience |
| Prefill leaking to another visitor | A shared cache serving one visitor's HTML to another | Impossible by construction: no profile value is ever in the HTML; prefill happens in the browser from the tracker's own context response | Design |
| Read the mapped profile properties of a visitor | Being that visitor's browser | The tracker only requests the properties named in the push (the mapped ones); a page without a mapped form requests nothing more than today | Named properties, never `*` |
| A double event (tracker + island) on a form referenced by a rule | A goal or a mapping rule naming the form: its `formId` is the form's DOM `id` and `name` | Prevented: elements renders `data-form-id` and `data-wem-observed="true"` on every form, the two attributes wem.min.js checks before attaching its listener — `data-form-id` in the initial scan of every version from 3.4.0 to 4.2.1, `data-wem-observed` in the form observer that exists from 3.7.1 on (no observer before it); the island is the only sender | The four attributes asserted by `tests/cypress/e2e/validation/49-form-element-attributes.cy.ts`; the absence of a `[WEM] Watching form` line was verified by hand on the local stack, the tracker needing a jCustomer CI has not got |

---

## Performance

- **Rendering**: the form fragment and the page stay in Jahia's cache as today; the filter adds
  two small script tags and a JSON block, identical for every visitor. No call to jCustomer from
  Jahia at render time.
- **Prefill**: zero extra request — the properties ride on the context request the tracker
  makes on every page anyway; only pages whose form has a prefill-enabled mapping name any
  property.
- **Submission**: the pipeline does one more thing, serialising the accepted fields into the 200
  (microseconds); the event is one request from the browser to jCustomer, after the visitor
  already has the answer.
- **Editing**: the property-types call is cached in the module, one minute per site — see below.

### The profile-property catalog: when jCustomer is called, and why there is a cache

**When the call happens.** The Content Editor builds the whole form definition of a node in one
request (`forms.editForm`, or `forms.createForm` for a field being created): every section, every
fieldset — the dynamic ones included, switched on or not — and every choicelist initializer is
evaluated then. So the `formidableJExperienceProfileProperties` initializer runs **each time an
author opens or creates a mappable field**, whether or not they ever unfold the jExperience section
or switch the mapping on. Nothing in the module can make it lazier: the editor only re-asks a
choicelist on its own (`forms.fieldConstraints`) when it depends on another field's value.

**What the call costs.** `GET /cxs/profiles/properties/targets/profiles` through jExperience's admin
client (HTTPS, authenticated): 10 to 17 ms and 19 KB against a jCustomer on the same machine
(2026-09-11, 36 property types); a whole `editForm` of a text field, initializer included, 90 to
130 ms. A jCustomer on another network — the usual production layout — adds its round trip and TLS,
typically 50 to 200 ms, on every field opening of every author.

**What the cache does.** `ProfilePropertyCatalog` is one OSGi service per Jahia node, so its memory
is **shared by every author of the instance**, keyed by site: at most one jCustomer call per site
per minute on the success path, however many people edit forms (an outage costs at most one call
per site per ten seconds, see below). A property created in jExperience therefore shows in
the dropdown at the first opening after the minute — the tooltip of the property field says so, in
the author's words. **One duration rules everything**: a list is served while it is under a minute
old; past that, the next opening reads jCustomer again, and a read that fails yields the
"jExperience is not connected" entry instead of a list that may no longer be true (the stale entry
is dropped, so a later success starts a fresh minute). **A failure is remembered for ten seconds**:
jExperience's admin client waits up to its configured timeout — 30 s by default — on a hung
jCustomer, and without that memory every opening of every mappable field by every author would
start a fresh call and wait on it while Jahia kept loading a jCustomer already in trouble; within
the ten seconds the entry reads unavailable without a call, then jCustomer is asked again, so a
recovery shows well within the tooltip's minute. The ten seconds count from the **end** of the failed
call: dated from its start, an entry written after a 30 s timeout would be born expired. What the minute buys is thus not CPU: it
keeps the remote round trip out of every field opening and keeps authors from being a source of
traffic on jCustomer's admin API. What it does not buy, by choice, is hiding an outage: an author
opening a field while jCustomer is down reads the message.

**What it does not do.** No invalidation from the UI: the list refreshes by expiry (or when the
module restarts). A shorter time to live would not change the worst case (one call per site per
period) and would only shorten the wait of the one author who just created a property; no cache
would give the exact state of jCustomer at each opening at the price above. Serving an expired
list through an outage was built first and removed: a bounded grace period needed a second
duration to explain, and a list that may be an hour old is worse than a message. Kept at one
minute, one rule (decisions of 2026-09-11).

---

## Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-09-09 | Standard `form` event; the prototype's custom event and its six JSON schemas are dropped | Standard rules, goals and the Form mappings screen only work on the standard event |
| 2026-09-09 | The event carries all accepted values, minus files and sensitive fields | The event is the trace of the submission, as with Forms |
| 2026-09-09 | Mapping and prefill configured per field: property dropdown, prefill toggle, strategy | Mirrors the per-field Forms node with its mapping-only variant |
| 2026-09-09 | A form-level mapping table is recorded as a later option | Needs a custom Content Editor selector; clearly more expensive than the per-field section |
| 2026-09-09 | A marker mixin in the engine, `fmdbmix:profileMappableField`, declared by every mappable field type; the jExperience mixin extends that single target instead of a list of field types | The engine-to-elements pattern already used for `fmdbmix:formElement`; removes the enumeration and the dependency on the extended inputs; verified against the Content Editor's `isNodeType` resolution of `extends` |
| 2026-09-09 | New branch `feat/jexperience-integration` from main; the prototype's reusable pieces are ported onto it, and `save2jCustomer` is deleted once the port is done | Four months of drift, the action SPI changed, the event is redesigned |
| 2026-09-10 | **Client-side design** (Romain): prefill through `digitalDataOverrides.push` and the tracker's context, event sent by the browser through `wem.collectEvent`. The server-side event (observer SPI, `pid`, tracking header) and the server-side prefill (render filter making the fragment uncacheable, `ProfilePrefillService`) are dropped | Server-side prefill wrote personal data into HTML that any cache in front of Jahia could serve to another visitor, and cost one jCustomer call per page view on the render path; the server-side event duplicated what the tracker already does with the visitor's own cookies. `digitalDataOverrides` is jExperience's documented extension point (its own Cypress tests exercise `wemInitConfig.requiredProfileProperties`) |
| 2026-09-10 | **The event carries the server's accepted values**, returned in the 200 by a generic `SubmissionResponseEnricher` SPI, not the DOM fields | Sending from the browser must not mean bypassing the pipeline: undeclared fields, rejected values, files and sensitive fields never reach jCustomer; a 400 sends nothing |
| 2026-09-10 | **No server-side probe of jCustomer's rules** (the first design's "is this form referenced here?", cached 60 s) (Romain) | The browser already holds the answer in the context the tracker loaded; recomputing it server-side costs a jCustomer round trip per submission for the same information |
| 2026-09-10 | **Tracked = mapped OR referenced in jCustomer**, the latter read from `wem.getFormNamesToWatch()`, kept on purpose; a form-level switch stays recorded, not built (HDU) | A goal or segment created in jExperience on a form nobody mapped must count, as with Forms; without the list the marketer has no way to know why it does not. Romain would rather see `trackedConditions` go: the dependency is confined to `shouldCollect()`, one function to change when jExperience reworks them |
| 2026-09-10 | **`disableTrackedConditionsListeners` is honoured**, and never set by Formidable (HDU) | It is the integrator's page-level "no automatic form tracking", possibly the outcome of a refusal; Forms goes silent under it (its watch list is empty) and Formidable does the same. Setting it would silence every other module on the page |
| 2026-09-10 | ~~**Identifier `formidable-jxp-<uuid>`**, never equal to the DOM `<form id>`; `target.properties.name`/`path` for readability; shown in the editor (HDU)~~ — superseded 2026-09-15, see below | The tracker attaches its own raw-fields listener to any `<form>` whose `id`/`name` matches a tracked `formId`: a distinct identifier is what makes the island the only sender, even once a marketer creates a goal. Unomi's `itemId` pattern allows it (51 chars, `[\w@.-]`); goals are typed by hand in jExperience, so the author must be able to copy it; dashboards keyed on an opaque id need the name as a label |
| 2026-09-10 | ~~`data-form-id` is not used as the opt-out~~ — superseded 2026-09-15, see below | Forms' convention, honoured by the tracker's initial scan only and ignored by jExperience's observer of late forms |
| 2026-09-11 | ~~The form identifier is a read-only property stamped by a listener, not a custom selector~~ — superseded 2026-09-15, see below | One read-only string does not justify a Module Federation bundle in the module; the Content Editor renders a `readOnly` field of the mixin; every form under `/sites` is stamped when the module starts (decided later that day, dd05aa5), the listener covers creation, copy, import and edit |
| 2026-09-11 | `fmdbmix:jExperienceProfileMapping` extends the marker without inheriting from it (the first draft wrote `> fmdbmix:profileMappableField` too) | Mappability is what a field *type* declares; the mapping is what an author configures. With the supertype, any node the mixin lands on — by API or import, a file field included — would pass every `isNodeType(marker)` check and the marker would stop meaning anything. `extends` alone is how every property mixin of the repository attaches to its target, and the Content Editor resolves it with `isNodeType`, so nothing needs the inheritance |
| 2026-09-11 | Field shape from the value-kind mixins; ~~`fmdb:checkbox` is the one type name read~~ | No mixin tells the checkbox group (always a list) from a radio group; every other cardinality comes from the `multiple` property. **Superseded 2026-09-16**: the distinction became `fmdbmix:cardinalityFromChoices`, so the shape inference reads no type name at all |
| 2026-09-11 | `choicelist[resourceBundle]` for the write strategy | Labels for `alwaysSet` / `setIfMissing` come from the module's bundle instead of raw values in the dropdown |
| 2026-09-11 | The profile-property catalog keeps its list one minute per site, shared by every author; an empty match and an unreachable jCustomer each yield one explanatory entry, pre-selected (HDU) | The editor evaluates the initializer at every opening of a mappable field, section unfolded or not; one minute keeps the remote round trip out of the editor at the cost of a one-minute delay after a property is created — said in the tooltip. Removing the cache was weighed and declined: same worst case. jCustomer ships no boolean property, so a blank dropdown had to explain itself |
| 2026-09-11 | The field's stored mapping is in the dropdown, flagged "kept", when the list lacks it — **narrowed 2026-09-14 to the outage case, see below** | jcontent's single select resets a value absent from its constraints: without this, opening a mapped field while jCustomer is down and saving anything would wipe the mapping silently. The author keeps it or picks another entry, knowingly |
| 2026-09-11 | One duration: an expired list is never served, a failed read past the minute gives the message (HDU) | The first design served the previous list through any outage; a bounded grace period would have needed a second duration to explain, and a list that may be an hour old misleads an author more than a message. What the author sees is under a minute old, or says why it is not |
| 2026-09-11 | Local stack: the test Jahia joins the jCustomer compose network with a fixed address, jExperience 4.2.1 is installed by jar upload | jCustomer trusts privileged calls by IP; the artifact is only on Nexus' internal group, so `installModule mvn:` is a silent no-op on the test container (kit: `~/Jahia/modules/Formidable/jexperience/README.md`) |
| 2026-09-14 | ~~**A copied form is re-stamped**: the listener compares the stored identifier with the one the node's own UUID gives, never trusts its presence (review of PR #324)~~ — superseded 2026-09-15, see below | The core's copy carries every mixin and property it does not forbid onto the new node, so a copy kept the source's identifier and two forms shared one jCustomer identity — indistinguishable in every goal, segment and dashboard, competing for the same mapping rule. Idempotence is kept: the right value means nothing to do |
| 2026-09-14 | ~~The start-up pass follows the engine's rules: `ISDESCENDANTNODE('/sites')`, one save per form with a `refresh(false)` on failure, observation scoped to `/sites` too~~ — superseded 2026-09-15, see below | Module-bundled nodes under `/modules` belong to their module; one unsavable form must not lose the whole pass. The engine's helpers are package-private, the two rules are cheap to reproduce |
| 2026-09-14 | ~~The pass writes the **default workspace only**, documented in the upgrade notes and the listener's Lifecycle note~~ — superseded 2026-09-15, see below | The identifier is derived from the UUID everywhere it is used at runtime, so live never reads the stored property; writing live would bring the UGC traps MigrationSessions exists for, for no reader. The one visible effect — every form flagged *modified* once — is what the upgrade page explains |
| 2026-09-14 | **A failed read of the profile properties is remembered for ten seconds** | Only the success path honoured "one call per site per minute": a hung jCustomer (30 s admin timeout) was paid by every author at every field opening. Ten seconds keeps an outage to one call per site per span and shows a recovery well within the tooltip's minute; the entry stays one rule — "unavailable, ask again after N seconds" |
| 2026-09-14 | Cardinality contract for third-party fields = a `multiple` boolean property (the select/email convention), written down; the choicelist declares `dependentProperties='multiple'` and reads the unsaved value from the context | The value-kind mixins carry no cardinality, so the convention is the contract phase 2's rule builder agrees with. Without the re-query an author switching **Multiple** on and mapping in the same session picked from the single-valued list and only saw the mismatch on reopening, as a "(kept)" entry that reads like an outage |
| 2026-09-14 | ~~The one-option checkbox is recorded as a known limit, not fixed~~ — superseded the same day, see below | ~~The renderer submits one value for a single option while the shape says list; the option count is unknown at edit time for a sourced list~~ |
| 2026-09-14 | **The checkbox's cardinality follows its number of choices, counted as the view counts them** (HDU): one choice = one value, otherwise a group; the count comes from a new engine API, `ChoiceOptionsResolver`, and from the editor's unsaved `options` on a re-query (`dependentProperties='multiple,options,optionsMode'`). No `multiple` property on the checkbox (HDU) | The view already decides single vs group on `parsedChoices.length === 1`; the shape must apply the very same rule to the very same count, or the dropdown and the event disagree. The view resolves through an engine service that is not exported, so the engine gains one small `api` interface rather than an exported implementation package. A sourced list is counted through the same resolver on the saved node; while a mode switch is unsaved the count is unknown, a group, until the save |
| 2026-09-14 | **The stored mapping is protected during an outage only, as the one entry of the list** (HDU): when jCustomer answers, the list is the truth and a stored mapping it lacks — cardinality or type no longer fitting the field, property gone from the schema — is not offered; the editor resets it and the next save clears it. When jCustomer cannot be asked, the list holds the stored value alone, labelled by its id, with a description saying the mapping is left unchanged because the properties cannot be listed | Showing "gender (current mapping, kept)" on a checkbox that became a group displayed a mapping phase 2 would never turn into a rule: a mapping shown must be one that will happen. The outage is the one case where the list says nothing about the mapping, so the protection against a silent wipe stays there and only there. Two entries during the outage (the mapping and the "not connected" message) let the author pick the message and wipe the mapping; one entry leaves nothing to pick. A true read-only is not reachable from an initializer: jcontent disables a select only when its list is empty, and an empty list also resets the value. Phase 2 applies the same rule at publication |
| 2026-09-14 | The kept entry's description carries no `:` and no `.`, by precaution (review of 0e531e3) | jcontent hands an entry's `description` to i18next's `t()` as if it were a key; `:` and `.` are its default namespace and key separators. Whether the editor's instance truncates a plain sentence at them has **not been observed** — the check is a screenshot with jCustomer stopped, still to do; the fix is safe either way. Only this message travels as a description: the "none" and "unavailable" messages are entry labels rendered verbatim, and keep their colon |
| 2026-09-14 | **Phase 2: the rule is built as the JSON map jCustomer stores**, not with Unomi's Java classes | The sync must compare what it would post with what jCustomer holds; maps compare directly on the owned parts (metadata id/name/description/scope/systemTags, priority, condition, actions), while jCustomer adds fields of its own on read. No new OSGi import, no JSON library at runtime: jExperience's admin client (de)serialises maps |
| 2026-09-14 | The stored rule is read with `GET /cxs/rules/{id}` | jCustomer answers 204 for an unknown id, which the admin client hands back as null; `/cxs/rules/query` with a `propertyCondition` answers 500 on the rules index (jExperience's own screen uses `sessionPropertyCondition`) |
| 2026-09-14 | The value parameter follows jExperience's Form mappings screen: multivalued → `setPropertyValueMultiple`, integer/long → `…Integer`, boolean → `…Boolean`, everything else → `setPropertyValue` | jCustomer 3's `setPropertyAction` declares exactly these value parameters (read from `/cxs/definitions/actions/setPropertyAction`); the form event carries strings, the profile schema converts |
| 2026-09-14 | A publication's events are coalesced: the listener asks for a synchronisation that runs 2 s after the last request for the form | A publication with subtree reaches live in several bursts, the form before its fields; the first burst read a checkbox with no option yet (a group) and would have written a rule from half a form. One synchronisation per publication, from the settled tree |
| 2026-09-14 | The mapped fields are read in the site's default language | Option values live in the default language (the elements' alignment rule); a translation with untranslated labels aligns to an empty list — counted there, a one-choice checkbox read as a group |
| 2026-09-14 | Resync on availability = a pending list retried every minute and drained in order, stopping at the first form still unreachable | jExperience exposes no availability event; a minute is the catalog's own horizon. A publication during an outage is never lost, and never retried faster than jCustomer can come back |
| 2026-09-14 | The reader applies the dropdown's rule at publication: a mapping the schema no longer offers, or that no longer fits the field's shape, is skipped and logged | The rule must never disagree with what the editor would offer; the author sees the mismatch as a reset select, the administrator as a warning line |
| 2026-09-14 | ~~Removals get a second, untyped live listener~~ — superseded the same day: one untyped listener anchored on `j:lastPublished`, see below | A typed listener never sees a deleted form leave live: Jahia resolves a removed node's types from the event's info, which an unpublication fills and a published deletion does not (the deletion of the whole subtree reached the typed listener as translation property changes only). Without a type filter, the cost is bounded by acting on the head of a removed subtree only — its parent still exists — so a deleted page costs one rule lookup, its children none |
| 2026-09-14 | **One untyped live listener, anchored on `j:lastPublished`** (review of #325) | Two cases escape a typed listener: a form deleted by a published deletion (Jahia resolves a removed node's types from the event's info, empty on that path) and a field whose jExperience section was switched off (the mixin is gone from the live node when the event's types are resolved, so the field's own publication passes no filter and the rule kept an action for an un-mapped field). Publication writes `j:lastPublished` on every node it publishes: keeping that one property event by name, plus removal heads, costs a climb per published node and one rule lookup per removed subtree — and covers both cases. Verified on the lab: un-mapping a field by removing its section and publishing that field alone deletes the rule |
| 2026-09-14 | Jahia language codes go through `LanguageCodeConverters.languageCodeToLocale` (review of #325) | They are `Locale.toString()` forms (`en_US`); `Locale.forLanguageTag` returns the root locale for every one with a country, and the rule would have been built in no language. The per-language fallback loop went with it: `getNodeByUUID` does no locale filtering, so it was dead code |
| 2026-09-14 | A site without a jExperience configuration is left alone; only a jCustomer that cannot be asked pends the form (review of #325) | `isAvailable` is false in both cases, but `getContextServerStatus(site)` is null only without a configuration. Without the distinction, every publication on every other site of the instance would have added a permanent entry to the pending list. A form that cannot be read (repository error) is dropped, not retried; the retry loop catches every error, since the scheduler drops a task that throws and every later retry with it |
| 2026-09-14 | The form's path is a SQL2 literal: quotes doubled (the rule of `JCRContentUtils.sqlEncode`, applied by hand because that class does not load outside Jahia) (review of #325) | An imported or mounted form with a quote in its path would have made the query invalid, silently, at every publication |
| 2026-09-14 | `long` is not a typed integer: only `integer` takes `setPropertyValueInteger`, as jExperience's screen and the Forms bridge do (review of #325) | The javadoc claims the screen's rule; the screen tests `integer` on strict equality |
| 2026-09-14 | Two documented differences from the rules jExperience's screen writes: the source condition is the whole site alone (the screen always adds the form's page path), and an integer property gets its typed parameter only (the screen writes typed and plain side by side) (review of #325) | A Formidable form is reusable content placed on any page, so a page path would be wrong; the typed parameter alone is what the Forms bridge writes. Whether the Form mappings screen writes such a rule back unchanged when a marketer saves it there is a manual check to make, once, before the shapes are called interchangeable |
| 2026-09-14 | ~~`fmdbmix:jExperienceForm` inherits `jmix:templateMixin`~~ — superseded 2026-09-15, see below | `isAlwaysActivated` forces the fieldset on but keeps its enable switch; only a `jmix:templateMixin` fieldset loses it (jcontent `EditorFormServiceImpl`). Switching the toggle off dropped the mixin and the value, which the listener then restored: confusing UI plus a spurious write. The core marker has no runtime semantics (only the legacy GWT editor read it) |
| 2026-09-14 | The bundle exports nothing; `analyze-only` with `failOnWarning`; `jahia-depends` names `formidable-elements` too | An exported implementation is a compatibility promise nobody asked for (`dependency-decisions.md`). ~~The phase-2 SPI gets its own `api` package.~~ — superseded 2026-09-15: `SubmissionResponseEnricher` landed in the **engine's** `api` package, since the servlet is what calls it. The publication listener and the render filter apply to `fmdb:form`, an elements type ~~which `fmdbmix:jExperienceForm` extends~~, and the declared dependencies must say so even though the node-type capability already carried the resolution |
| 2026-09-14 | The catalog dates an entry from the **end** of the call, not its start (second review) | `now` was read before the blocking fetch: after a 30 s admin timeout the failure entry was born 20 s expired and never served anyone, so the memory only covered instant failures. Re-reading the clock when storing is the whole fix; the test advances the clock inside the stubbed call |
| 2026-09-14 | The three CND clauses the Java relies on are pinned by a test reading the module's own CND | No test environment installs the module yet; `dependentProperties='multiple'`, `> jmix:templateMixin` and the `extends` to the marker each survived deletion with the suite green. A line-level reader cross-checked with the Java constants guards them until a Cypress spec does |
| 2026-09-15 | **The identifier is the form's UUID**, carried by the rendered `<form>` as `id` and `name`; the `formidable-jxp-<uuid>` property, its mixin `fmdbmix:jExperienceForm`, the editor field and the stamping listener are removed (HDU) | Reading wem.min.js 4.2.1 and the Form mappings screen showed that every reader takes `name` then `id`, and that two attributes keep the tracker off: a distinct identifier only protected against a tracker two attributes silence. The UUID needs no storage, no listener and no upgrade note, and jContent already shows it |
| 2026-09-15 | **`data-form-id` and `data-wem-observed="true"` are rendered by elements on every form**, jExperience module or not; the form's title is its `aria-label` | One attribute per code path of the tracker (initial scan, observer). Unconditional: a goal created on a site without the module must not make the tracker send raw, unvalidated DOM fields — a Formidable form is tracked through this module or not at all. The title is not a `name` candidate: read before `id` by the tracker, the picker and the page lookup, it would key a mapping on a label that changes with the language |
| 2026-09-15 | **The response block's `fields` are the accepted values of the profile-mappable fields** (`fmdbmix:profileMappableField`), minus the sensitive ones | The pipeline has no notion of a sensitive field, so the marker is the boundary that already exists: what jExperience may map is what jExperience receives — files, buttons and containers never. **Struck and restored the same day:** the rule was narrowed to the mapped fields only, then restored when that turned jExperience's own Form mappings screen into a dead end (the two rows below), and the author's per-field sensitive flag took over the job of keeping a value in |
| 2026-09-15 | **The render filter reads the form in a session of its own**, not the render session | A form placed through a reference renders as a node contextualised under it, whose path `…/theReference@/theForm` is no JCR path: the mapped-fields query matched nothing and the block named a place the mapping rule never mentions. Asking the render session for the identifier gives that same contextualised node back — observed in live, and the first fix, which trusted the session, shipped nothing (its unit test mocked the very lookup that fails). A session that never saw the reference resolves the identifier to the form itself |
| 2026-09-15 | The response block is **serialised inside each enricher's own guard**, not by the writer | The writer runs outside every guard: a null key or a NaN from one enricher threw there and turned an accepted submission into a 500. Building the JSON where the failure can still be attributed keeps the SPI's one promise — an enricher costs its own entries and nothing else |
| 2026-09-15 | The client script is emitted next to **every** form of a page and keeps one instance by itself | The filter has no page-level state to dedupe on, and a `<script defer>` fetched twice is one fetch; the script returns early when `window.formidableJxp` is already there |
| 2026-09-15 | ~~**Only a mapped field's value is in the response block**~~ — superseded the same day, see the two rows below | The reasoning held for data minimisation but broke a behaviour Forms has: both of jExperience's own paths send every field, so a mapping made in its Form mappings screen always receives a value there, and restricting ours turned that screen into a dead end |
| 2026-09-15 | **Back to every accepted value**, as jExperience's own paths do (HDU) | Verified in wem.min.js 4.2.1: the Forms bridge sends `resultData` whole and the plain-form listener every named input of `form.elements`. Sending less would make jExperience's Form mappings screen a dead end for our forms — a mapping made there on a field we do not map would receive nothing, silently. The marker still leaves out files, buttons and containers |
| 2026-09-15 | **A per-field sensitive flag** (`fmdbmix:jExperienceSensitiveField`, HDU): its value never leaves the site and it cannot be mapped | Data minimisation belongs to the author, field by field, not to a blanket rule: a national id or a free-text note is the author's call, and a node-type-level notion (a "password" type) would be both too coarse and too late. Four readers honour the one flag — the dropdown offers a single message saying why, the reader skips a mapping made before the box was ticked, the render filter leaves the field out of the page, and the enricher leaves its value out of the answer. The last is the one that matters: whatever the editor allowed, the value does not leave the server |
| 2026-09-15 | The flag lives in the **jExperience module**, and says only what it does there | [formidable#161](https://github.com/Jahia/formidable/issues/161) is a different concern — the use of a field's value inside the actions (HDU). An engine-level "sensitive field" would promise that a ticked field stays out of a notification email too, which nothing implements |
| 2026-09-15 | The switch of the mapping fieldset is **not** disabled when the flag is on | jcontent has no declarative way to grey out a fieldset on a sibling property's value; doing it would need a Module Federation bundle in the module, declined in phase 1 for the identifier. The dropdown with its single message, plus the four server-side guards, make the mapping impossible and say why |
| 2026-09-15 | **The sensitive flag holds from the save**, not from the next publication: the enricher treats a field as sensitive when either workspace says so (review) | Everything else in the integration describes the published form and follows it at the next publication, which is right for a mapping. A privacy stop is not of that kind: the submission is resolved in live, so reading live alone would leave an author who ticks the box on a form already collecting sending that value until someone publishes — with nothing in the editor saying the box is not armed. It costs one session of the default workspace per submitted form on a tracked site, and a failed reading keeps the published answer with a warning, since a transient error must not empty the block for every visitor |
| 2026-09-15 | **The page declares nothing about the form's mappings** (HDU): the send decision reads `getFormNamesToWatch()` alone, and the config block carries the identifier, the title and the path | A mapped form is already in that list, because the rule this integration publishes for it is a `formEventCondition` of the context, exactly like a goal or a segment — observed on the local stack, where a form appeared in the list only once its mapping rule existed. Declaring the mappings as well was a second list to keep in step for a case the first already covers. If `trackedConditions` go, as Romain would like, the mappings come back as the fallback; that is a future problem, not an over-engineered present one. Phase 4 puts the pairs back for its own reason: prefill needs them at page load, and unlike jExperience's Forms bridge — which reads them from the Angular form model — our browser has no model to read |
| 2026-09-15 | **The server-side gate asks two questions, not one** (HDU): jExperience must be among the site's modules **and** hold settings for it, before the render filter writes a block or the enricher answers | Only the pair means there is a tracker on the page, and without a tracker nothing reads what we write. jExperience puts both there itself: `ContextServerScriptFilter`, the filter that emits the tracker, is declared `applyOnSiteTemplateSets="jexperience"` (mod-wem-components.xml), which the core resolves as `getSite().getInstalledModules().contains(templateSet)` — so our first term is that filter's own condition, on the same site object — and that filter takes the tracker's URL from the settings. (`ContextActivatorFilter` reads the installed modules too, but only inside `shouldSetWemSessionId`, for the cookie.) Neither half stands alone: the settings are not a per-site answer — jExperience falls back to the platform's for a site that has none, so on a platform holding a global configuration every site claimed to be configured — and enablement alone says nothing about a jCustomer ever having been connected. Both halves are reads from memory, a list held by the site node and a map lookup, which is what keeps them affordable on every accepted submission and every render cache miss. The rule synchroniser keeps the settings question alone: reaching jCustomer is not contributing to a page |

| 2026-09-17 | **Prefill writes only when both the tracker's context and the island are there**, and reads both states as well as listening to both signals | Every input is uncontrolled, so React alone would leave a value written before hydration — but two islands (masked text, bounded date) rewrite their input on mount, and a rule that depends on which island a field has breaks at the next island. Waiting costs nothing: the context takes longer than hydration in practice. The tracker fires no DOM event, so its own callback registration is used, at a priority after the one that sets `wemLoaded` |
| 2026-09-17 | **Empty fields only; the author's default gives way only when the author says so** (`jExperiencePrefillOverridesDefault`, HDU) | Prefill is a suggestion, not an overwrite: a value the visitor typed is never replaced. A default the author gave the field is the author's word too, so replacing it is the author's call, one checkbox inside the prefill fieldset. The DOM says which is which: `defaultValue`, `defaultChecked`, a `selected` option |
| 2026-09-17 | **The prefill is a mixin of its own** (`fmdbmix:jExperiencePrefill`), not a boolean of the mapping (HDU) | Two checkboxes side by side — "prefill" and "prefill even over a default" — read as one question asked twice, and the second makes sense only once the first is ticked. jcontent's one declarative conditional display is the switch it gives a mixin that extends a type: the fieldset opens, the option appears. The property `jExperiencePrefillFromProfile` of phase 1, never released, goes |
| 2026-09-17 | **The block's fragment depends on every mappable field of the form** | Found on the instance: prefill switched on a field and published, and the page kept serving the block of the last cache miss, without that field, until the site cache was flushed — the filter reads the fields in a session of its own, which the fragment's dependency tracking does not see, and the form's node, the fragment's only dependency, is not what a mapping changes. Every mappable field, mapped or not, is registered, because mapping a field later is exactly the change that must refresh the block. Registering them was not enough: at its first priority (11) the filter sat outside the cache, below `AggregateFilter` (16.0), so it also ran on the pass where the aggregation stands a placeholder in for the form — its block was baked into the *parent's* fragment (the reference's, on the playground page), whose dependencies are not the fields, and what it added to the form's resource came after `CacheFilter` (16.5) had stored the fragment. Read in the cache itself: the field's dependency entry listed the field's fragment, never the form's, and the block sat in the reference's fragment. Priority 17, just inside the cache, puts block and fields in the form's own fragment |
| 2026-09-17 | **Touched and defaulted are read from events and attributes, not from the live state** (HDU's review, measured) | `value !== defaultValue` and `selected !== defaultSelected` trip on their own: a single select's first option, a colour input's `#000000`, a cleared field. A mapped select was never prefilled and the end-to-end proof, listing no select, was consistent with it. Trusted `input`/`change` events name what the visitor touched; `value`, `checked` and `selected` attributes name what the author wrote |
| 2026-09-17 | **A hidden control is told, not only written**: `formidable:prefill` with `{value, overridesDefault}`, and the range island applies the prefill's rules itself (HDU's review) | The slider's named control is a hidden mirror of React state; a write reaches the DOM and is reconciled back at the next render, and the prototype-setter trick defeats the change tracker, not controlled values. The event is the phase-3 pattern the other way round; the rules are in a pure function the island and the tests share. Range is the one built-in with a mirror |
| 2026-09-17 | **A fragment rendered without its block stays cached**, transient outage or not (HDU's review asked for the decision) | The transient case — jExperience restarting, the service reference unbound — heals itself: a module start flushes the output caches (measured on 8.2.4: 36 fragments before a bundle restart, 0 after), so the blockless fragments go when jExperience is back. The caught path is a failure that costs one warning and a form without its block until the next change or flush, against a session, a query and a warning per request while it lasts. The one case left is jExperience connected to a site after its pages were rendered: flush the site cache, as after any change the cache does not see |
| 2026-09-17 | **The prefill fieldset keeps extending the marker**, not the mapping mixin; the dropped switch is named in the log and the tooltip says what it needs (HDU's review) | jcontent offers a mixin's fieldset only through the primary type: `EditorFormServiceImpl.getExtendMixins(primaryNodeType, site)` walks `NodeTypeRegistry.getMixinExtensions()` and keeps an extension when `primaryNodeType.isNodeType(extended)` — a mixin added on the node is not a root. A fieldset extending the mapping mixin would never be offered. So the filter says, at debug level, which of the four conditions dropped a field whose prefill is on |
| 2026-09-17 | **The removal of the snapshot's prefill property is refused by the definitions check**, recorded here and not in the upgrade notes (HDU's review, then HDU: no instance outside ours ran such a snapshot) | Measured: with the earlier CND registered, `installOrUpgradeBundle` answers `install: []` and HTTP 200, and only the log says `Major change in definition … REMOVED, cancel module deployment`. Nothing released carries the property, so no migration and no administrator note; on one of our own instances the remedy is a deploy with the check bypassed, then the prefill switched on again |
| 2026-09-17 | **Every mapped shape is prefilled**, not text-like fields only as the roadmap first said (HDU) | The matrix is small — a string, a number, a date sliced to what the input takes, an option or a radio matched by value, a group or a multiple select matched by the values listed, a boolean checking a lone checkbox or a switch — and the playground carries every one of them. No server-side default property is needed for a choice field: the client matches option values |
| 2026-09-17 | **Nothing is injected per form; the hoisted script pushes the union once** (HDU, the lesson of #330) | jExperience creates `window.digitalDataOverrides` in the head, hands the array to the tracker at `wem.init()`, and reads it at `DOMContentLoaded`, after every deferred script — so the one script core hoists into the head sees every block of the page and pushes one entry; the tracker concatenates arrays, so jExperience's own `j:nodename` stays. The block carries data per form, which it already did |
| 2026-09-17 | **Consents are not read**: the tracker's presence and `activateWem` are the only gates (HDU) | jExperience's consent handling is out of date; whoever decides whether the tracker starts — a consent tool, the bot filter — decides for this integration too. `profile.consents` is not consulted, for sending or for prefilling |
| 2026-09-18 | **The profile wins over an author's default, and a missing value never blanks** (HDU): the `jExperiencePrefillOverridesDefault` option of the day before goes, the prefill fieldset keeps its switch alone | The 2026-09-17 rule — empty fields only, the default replaced only when the author ticks the override — read the contract the wrong way round. A default is what the author knows without knowing the visitor; the profile's value is what is known about this visitor: the fresher word wins whenever there is one, and there is nothing for the author to decide. What must never happen is the trap Forms' prefill fell into — a default blanked because the profile had nothing. The text, select and slider paths already wrote nothing then, but the choice path unchecked a default-checked option whenever the override was on and the profile named nothing the options carried; the rule is now "nothing named, nothing written" for every shape. A value the visitor typed before the context landed stays protected as before |
| 2026-09-18 | **What follows the write is one choice, not two flags** — `jExperiencePrefillThen`: editable (default), read-only, hidden (HDU: "comme dans Forms") | Forms' prefill offers `hideAfterPrefill` and `makeReadOnlyAfterPrefill` as two booleans, and a hidden field makes read-only moot: one choicelist cannot say both. It applies only to a field that was written — the profile had a value, the visitor had not typed — so a visitor the profile knows nothing about keeps a field to fill. Read-only has no native form for a select, a radio or a checkbox, and `disabled` would drop the value from the submission: `aria-readonly` plus a restore on change, the stylesheet taking the pointer off. Hidden is inline `display: none` with `data-fmdb-prefilled` on the wrapper, which the conditional logic reads so that a rule showing the field again does not undo it, without treating it as logic-hidden (that would disable its controls). A value that fails the field's validation is not hidden: the visitor could neither see nor fix the error. Review (HDU, same day): the range mirror is barred from constraint validation and the script cannot see the island's verdict, so the island applies `then` itself; a choice the profile merely confirms is prefilled all the same (`fillChoices` reported a write only on a DOM change, the other shapes on any value); `readonly` does nothing on a colour input, which joins the restored shapes; `aria-readonly` is the radiogroup's, not the radio's; the restore says the change again, since an island's own change listener runs first and validated the transient state. Round 2 (same day): the island takes its refused value back out of the mirror, which is what the form posts; a form reset undoes the prefill on both sides, since nothing is prefilled once the fields are empty; the group role goes on the four views that render same-named radios, with the field's wrapper as the script's fallback; and the option is mandatory in the editor form, not in the CND — a mandatory flag added to a registered type cancels the module's deployment |
| 2026-09-18 | **The prefill goes back inside the mapping** (HDU: the dependency between the two switches is invisible) — `jExperiencePrefill` + `jExperiencePrefillThen` as properties of `fmdbmix:jExperienceProfileMapping`, the mixin of 2026-09-17 removed | Two switches side by side said nothing of the fact that the second does nothing without the first; only the tooltip did. jcontent has no live conditional display to lean on: a mixin's own switch is the one it offers, a fieldset `condition` is evaluated against the saved node when the form is built (so it cannot follow a switch just flipped), and a mixin that `extends` another mixin never appears at all, since `getExtendMixins` matches the primary node type. Inside the mapping the dependency is structural: no mapping, no prefill, and switching the mapping off takes the prefill with it. The editor still cannot grey out one field while a box beside it is unticked, so the option's dropdown says it in words — `PrefillThenChoiceListInitializer` offers one message while the switch is off, carrying the value the field already holds so that a required dropdown changes nothing, and `dependentProperties` refills the list the moment the box is ticked. Nothing is migrated: no released version ever knew the mixin |
| 2026-09-16 | **Two engine markers replace the last two type names the Java read**: `fmdbmix:formRoot` on `fmdb:form`, `fmdbmix:cardinalityFromChoices` on `fmdb:checkbox` | The rule this integration already followed for a field — read the mixin, never the concrete type — now holds for the form and for the checkbox's cardinality. It closes the two gaps the ownership document listed, and it is what lets a module of its own offer a form type, or a field that renders one input per choice, and be treated like the built-in ones. Neither marker carries a property, so adding it to a deployed type adds no constraint; the deployment of the release that introduces them is forced past the definitions check |
| 2026-09-21 | **An unanswered field is absent from the event, never empty** (issue #339): the response block drops blank values and leaves out a field with nothing left | Found on the playground: a visitor who submitted the complete form once without a country held `countryName: ""` for good — the select's empty option travelled as `fields.country: ""`, jCustomer's `setPropertyAction` wrote it (an empty string is a value), and `setIfMissing` then found the property present at every later answer; under `alwaysSet` every blank submission would erase the profile's value instead. Three sources fixed the rule. jCustomer (`PropertyHelper.setProperty`): a null value returns before any strategy, "nothing to update", so an absent field is the one shape neither strategy writes. Forms (`populateAndSubmitResults`): an input goes into the result data only when its value is defined and not empty — a number, a boolean or a valid date excepted — so Forms never sends a blank, and the bridge sends that data whole. The tracker's own `_extractFormData` (wem.min.js 4.2.1): an empty text input is left out, an unchecked radio too; its single select still sends the empty option's `""` and its checkbox group an empty array, the same leak this fix closes on Formidable's side. The block already left out an absent name and an empty list; it now applies the pipeline's own definition of an answered field — at least one non-blank value, the rule of its required-field and hidden-field checks — to what it sends: blanks dropped, the rest in order, nothing left means no entry. Fixed on the server, where the block is the contract and the values never come from the DOM |

## Open questions

| Question | Owner | Status |
|---|---|---|
| Hydration vs prefill ordering: the client script waits for `wemLoaded` and `formidable:ready`; confirm React does not reset the values, and the shape of multi-valued `profileProperties` | dev | **resolved 2026-09-17**: both gates, both states read (see "Rendering and prefill"); a multi-valued property is an array of strings |
| Honour profile consents (`profile.consents`) before sending and before prefilling, on top of the tracker-level gates | dev | **closed 2026-09-17, not handled**: the tracker's gates are the consent signals; jExperience's consent handling is out of date (HDU) |
| Extended inputs (consent, switch, rating, scale): covered by the marker mixin, no dependency; their prefill rendering | dev | **resolved 2026-09-17**: written by their shape like any field — a switch or consent as a lone checkbox from a boolean, a rating or scale as a number |
| Prefill for choice fields, which have no default-value property today | dev | **resolved 2026-09-17**: no default property needed, the client matches the option values the profile names |
| Date profile properties: which `setPropertyAction` parameter Unomi 3 expects | dev | **answered 2026-09-14**: jCustomer 3 offers `setPropertyValue`, `…Integer`, `…Boolean`, `…Multiple` only (plus current date/timestamp flags) — a date, float or double value travels through `setPropertyValue` as the string the form submits, as jExperience's own screen does; whether jCustomer converts it is to verify with a real submission in phase 3 |
| A local jCustomer for the dev loop and CI (docker compose jexperience + jcustomer + elasticsearch) — the phases' proofs are Cypress against it | dev | **dev loop done** 2026-09-11 (jCustomer 3.0.0 + jExperience 4.2.1 next to the test Jahia, kit on the developer's machine); the CI compose profile is still to add |
| Upstream the generic profile-properties choicelist into jExperience | dev, jExperience team | proposal |
| A one-option checkbox submits a scalar while its dropdown offered multivalued properties only | HDU | **resolved 2026-09-14**: the shape follows the choice count as the view does, through the engine's `ChoiceOptionsResolver` |
| If jExperience removes or reworks `trackedConditions`: replace the `getFormNamesToWatch()` line of `shouldCollect()` by whatever replaces it | dev | when it happens |

## Roadmap

| Phase | Deliverable | Proof |
|---|---|---|
| 1 | Module skeleton recalibrated on jExperience 4.2.1 with open OSGi ranges, added to the root pom. Marker mixin in the engine and on the field types, editor section (its copyable identifier field was removed 2026-09-15, see "The identifier"), ported choicelist initializer with cache, strategy and failure handling. FR bundle with escaped accents, prototype's harness file dropped | **Shipped 2026-09-11, review fixes 2026-09-14 (PR #324).** 55 JUnit tests in the module (shape inference on nodes and types, the checkbox's choice count asked for checkboxes only, property filter, catalog cache, failure paths and failure memory dated from the end of the call, initializer through the editor's context, the CND clauses the Java relies on; the identifier and listener tests went with the identifier on 2026-09-15) and 5 in the engine for `ChoiceOptionsResolver`; deployed on the test instance next to jExperience 4.2.1: the section lists 18 properties on a text field |
| 2 | Mapping rule sync: rule builder on the form's UUID, publication listener, diff, delete on unpublish/removal, resync on availability | **Shipped 2026-09-14** (branch `feat/jexperience-phase2-rule-sync`). Golden JSON test of the rule, synchronizer tests (create, unchanged, update, delete, pending and retry, coalescing), listener and reader tests; on the local stack: publishing a mapped form creates the rule in jCustomer, republishing it unchanged writes nothing, unpublishing it or publishing its deletion deletes the rule, republishing recreates it, un-mapping a field by switching its section off and publishing that field alone deletes the rule too; a `form` event sent through the public `context.json` with the profile cookie — what the phase-3 script will do through the tracker — set the mapped profile property (`processedEvents: 1`). Cypress through the proxy waits for a jCustomer in CI |
| 3 | Engine `SubmissionResponseEnricher` SPI and the elements' `formidable:submitted` event; render filter (config block, script); client script with `shouldCollect()` and the event | **Shipped 2026-09-15.** 360 engine and 105 module unit tests, the response guards mutation-proven. End to end on the local stack: the block renders with the form's own path, a submission answers `jexperience {formId, fields}`, and in the browser the tracker's initial scan and observer attach nothing (no `[WEM] Watching form` although the form is in `getFormNamesToWatch`), one `eventcollector` call per submission, the event stored with the bare UUID as `itemId` and the form's name and path as target properties, and a proof rule setting the mapped profile property. Prefill stays phase 4 |
| 4 | Prefill of every mapped shape from the context's profile properties, the profile's value replacing an author's default (since 2026-09-18; empty fields only at first), and the author's choice of what follows the write — editable, read-only, hidden (2026-09-18); `formidable:ready` on the island | **Shipped 2026-09-17 (PR #336)**: on the local instance, a visit with a known visitor's `context-profile-id` cookie fills the simple form's four fields — the phone behind its logic included — and, on the complete form, the date island (the visitor's calendar day), the gender radio, the country select and the kids number, whose author default of 1 gives way to the profile's value; a field the visitor typed in and cleared before the context landed stays empty; all of it once `wemLoaded` and `formidable:ready` are there, one `digitalDataOverrides` push for the page; a visitor jCustomer does not know gets empty fields; the HTML is the same for both. The `formidable:ready` contract is asserted in Cypress (spec 49), the range island's prefill rules in vitest |
| 5 | Phase 2 items from the open questions as decided: choice fields, extended inputs, consents | Per item |
| 6 | Changelog entry, submission-flow doc updated with the response block, [formidable#153](https://github.com/Jahia/formidable/issues/153)'s jCustomer question answered | Review |

## Sources

- Apache Unomi tracker 1.5.0 (`apache-unomi-tracker`, the tracker jExperience embeds):
  `startTracker(digitalDataOverrides)`, `_registerListenersForTrackedConditions`,
  `_formSubmitEventListener`, `buildFormEvent`, `getFormNamesToWatch`,
  `disableTrackedConditionsListeners`, `requiredProfileProperties`.
- Jahia/jexperience, main: `live-mode/wem.js` (loader, `_formFactorySubmitEventListener`,
  `_observeForms`, `wemLoaded`), `tests/cypress/e2e/live/wem.digitalDataOverrides.cy.ts` and its
  fixture, `ContextServerService`, `form-mapping.service.js`, `goals/goal-formGoal*`,
  `ContextActivatorFilter` (`isContextEnabled`: no tracker for a bot, `deviceClassIsUnauthorized`).
- Jahia/jexperience-forms-bridge, main: `JExperienceMapping.java`, `mfffPrefill.service.jsp`
  (prefill by a second context request), `definitions.cnd`.
- Jahia/forms-core, main: `formDefinition.formView.jsp` (`data-form-id`), `ffCallbackService.js`.
- Apache Unomi JSON schemas: `events/form/form.json`, `form.source.json`, `items/item.json`
  (`itemId` pattern, free `properties`), `form.flattenedProperties.fields.json`.
- Jahia/formidable: `servlet/FormSubmitServlet.java` (the 200 body), `servlet/FormSubmissionPipeline.java`,
  `captcha/CaptchaRenderFilter.java`, `permissions/FormPublicationAclSyncListener.java`,
  `Form.client.tsx`, `useFormSubmission.ts`; the `save2jCustomer` prototype branch.
- jcontent: `EditorFormServiceImpl.getExtendMixins`.
