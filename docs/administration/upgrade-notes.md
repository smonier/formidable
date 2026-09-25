# Upgrade notes

Manual steps required when upgrading between specific versions. Most upgrades
are in-place module installs; only the transitions listed here need attention.

## Supported upgrade paths

| From | To | How |
|---|---|---|
| 0.4.x | 0.5.0 | In-place module install. The 0.5.0 wave of startup migrations renames the prefixed properties at the first start (see below). Verified on a real 0.4.0 instance (2026-09-10) |
| 0.3.x | 0.4.0 | The reinstall procedure below (formidable-elements changed identity), then the 0.4.0 wave of startup migrations runs at the first start. Verified on a real 0.3.0 instance (2026-09-02) |
| 0.3.x | 0.5.0 directly | The same reinstall procedure, with the 0.5.0 artifacts: 0.5.0 still ships the 0.4.0 wave of startup migrations, so both waves run when formidable-elements deploys (step 3). Verified on a real 0.3.0 instance (2026-09-10): the engine upload is refused by the definitions check as in step 1 (bypass it), the engine-first start logs the expected `Could not migrate node` errors, the elements deploy migrates every field in both workspaces, published forms render and accept submissions, republication works |

0.6 removes both waves of startup migrations: from 0.6 on, **0.5.x is the minimum upgrade
source**, and the precondition is an outcome, not a version number — **both waves must have
run** on the instance. Starting the 0.5.x engine alone is not enough: the 0.5.0 wave only
completes when `formidable-elements` 0.5.x deploys (elements 0.4.x only requires engine 0.4,
which engine 0.5.x satisfies, so an instance can run that pair indefinitely without renaming a
property), and 0.6 drops the migration together with the prefixed definitions it reads. The
proof an administrator can look for is the engine log line
`Renamed the prefixed mixin properties of N field(s)` (see "How to check" below).

## 0.4.x → 0.5.0: the configuration file is deployed with the module

**Automatic on most installations — check your settings if the module was configured through
the Felix console.**

### What changes

The module now ships its configuration file. At the first start of 0.5.0, Jahia copies it to
`karaf/etc/org.jahia.modules.formidable.cfg` — unless a file with that name exists — and
fileinstall loads it. Every setting is in it, commented, at its default. From now on the file is
the one place the module is configured from: edit it, or use the provisioning API; fileinstall
applies a change without a restart. The Felix console is no longer a good tool for it: it
rewrites the whole file in a typed syntax the file format does not read back.

### Who is affected

- **Configured through the provisioning API** (`editConfiguration`) before 0.5: not affected.
  The API wrote `karaf/etc/org.jahia.modules.formidable.cfg` itself, so the deployed file is not
  copied over it and your values stay. You do not get the commented file; the defaults it
  documents are in the module's documentation.
- **Configured through the Felix console** (or directly in ConfigAdmin) before 0.5: those
  settings lived in no file, and the deployed file replaces them with its defaults. On a running
  server the module detects the switch and writes the settings back into the file — the log
  shows `carried over into the file:` with their names. The detection is a race the module can
  lose: fileinstall loads the copied file a second or two after Jahia copies it, and when the
  module starts after that, the first configuration it sees already comes from the file, exactly
  like a fresh install. That is the usual case after an upgrade done while Jahia was stopped, and
  possible on a running server. A warning at startup then points at the file, created or changed moments before.
- **Never configured**: nothing to do.

### How to check

Open `karaf/etc/org.jahia.modules.formidable.cfg` after the upgrade. If your instance had CAPTCHA
keys, forward targets, option sources or upload limits and the file shows them at their
defaults, re-enter them in the file (or through the provisioning API). The log has the trace
either way: `carried over into the file:` when the module did it, `Gave up carrying … over` when
it tried and could not (the file's values are then in force), the startup warning when the file
was loaded before the module started.

## 0.4.x → 0.5.0: the mixin properties lose their `fmdb:` prefix, migrated at startup

**Who is affected**: every site with forms created or edited under 0.4.0 whose choice
fields take their options from the options-source mixins (`optionsMode`, `options`,
`optionsSourceKey`, `optionsRootCategory`, `optionsRootNode`, `optionsNodeType`), whose
select fields carry an empty-option label (`optionsEmptyLabel`), or whose date and datetime
fields carry bound modes (`minBoundMode`, `maxBoundMode`, `minRelativeAmount`,
`minRelativeUnit`, `maxRelativeAmount`, `maxRelativeUnit`). In 0.4.0 those thirteen
properties were named with an `fmdb:` prefix (`fmdb:options`, `fmdb:minBoundMode`,
`fmdb:optionsEmptyLabel`…) — the only prefixed properties of the model, where `fieldKey`,
`logics`, `msg*`, `min` and `max` never had one. Since 0.5.0 they bear the unprefixed
names above ([#310](https://github.com/Jahia/formidable/issues/310)).

**What happens at startup**: `MixinPropertyNamesMigration` rewrites every prefixed property
still present on a field under its unprefixed name — value and type kept, the translated
option list and empty-option label on each `j:translation_*` subnode — and removes the
prefixed one, in the default and live workspaces, through a system session Jahia does not
mistake for user-generated content. A field edited in the editor between an import and
the restart keeps its edited value: the prefixed one is dropped, never copied over a newer
value. It runs at engine activation and again on an elements redeploy, and is a no-op once
no prefixed property remains. The thirteen prefixed definitions stay in the CND for this
release, hidden, and the JCR-level `mandatory` of the four options-source settings
(`optionsSourceKey`, `optionsRootCategory`, `optionsRootNode`, `optionsNodeType`) and the four
relative offsets is lifted for this release (the editor still requires them) — both so that an
export taken from 0.4.0, whose fields carry only the prefixed names, is still accepted by the
import, and because Jahia refuses to deploy a module that adds a mandatory property to a
deployed type ("Major change in definition"), which would have blocked this very upgrade; **after importing such an export, restart the engine** (or
redeploy `formidable-elements`) so the migration renames what the import brought in —
until then those fields render without their options and bounds. The deprecated
definitions and the migration leave in 0.6, and the `mandatory` flags come back with them.

**How to check**: `jahia.log` reports
`[MixinPropertyNamesMigration] Renamed the prefixed mixin properties of N field(s) in workspace 'default'`
(then `'live'`). If the engine was upgraded before the elements, the engine start reports instead
`N field(s) … wait for the formidable-elements (re)deploy` — the field types still carry their 0.4.0
definitions — and the rename happens when the elements module is deployed. A field then reads back
under the new names:

```graphql
{
  jcr(workspace: LIVE) {
    nodeByPath(path: "/sites/<site>/contents/<form>/fields/<field>") {
      optionsMode: property(name: "optionsMode") { value }
      minBoundMode: property(name: "minBoundMode") { value }
      old: property(name: "fmdb:optionsMode") { value }   # null once migrated
    }
  }
}
```

**What to do in your own code**: a template set, a third-party module or an integration
that reads these properties — a view through `getNodeProps`, a GraphQL query, a JCR-SQL2
condition, a Content Editor override, a label key such as
`fmdbmix_dateBounds.fmdb_minBoundMode` or `fmdb_select.fmdb_optionsEmptyLabel` — must drop the
prefix (`minBoundMode`, `fmdbmix_dateBounds.minBoundMode`, `fmdb_select.optionsEmptyLabel`). The rendered markup and the submission payload do not
change.

## 0.3.0 (and earlier) → 0.4.0: formidable-elements must be reinstalled

**Manual procedure required** — this is the only step of the 0.4.0 upgrade
that is not automatic; see [Upgrade procedure](#upgrade-procedure) below.

### Symptom

Uploading formidable-elements 0.4.0 on an instance that already runs 0.3.0 (or
any earlier version) fails in **Administration > Server > Modules and
Extensions > Modules** with:

> Module upload failed because another module formidable-elements exists.

### Cause

PR [#139](https://github.com/Jahia/formidable/pull/139) changed the Maven
group id of formidable-elements from the JavaScript-module scaffolding
placeholder to the official Jahia namespace, so the module can be published on
the Jahia App Store (which also requires the `required-version` metadata added
in the same PR):

| | group id (`Jahia-GroupId` manifest header) |
|---|---|
| ≤ 0.3.0 | `org.example.javascript` (default generated by javascript-modules-engine) |
| ≥ 0.4.0 | `org.jahia.modules.javascript` (set via `jahia.maven.groupId` in `package.json`) |

Jahia identifies a module by the *group id + module id* pair. On upload,
`ModuleManagementFlowHandler` calls
`JahiaTemplateManagerService.differentModuleWithSameIdExists(id, groupId)` and
rejects the package when a module with the same id but a different group id is
already registered — the new package is treated as a *different* module
claiming an existing name, not as a new version.

formidable-engine is not affected: PR #139 only added a `jahia-depends`
property to its `pom.xml`, its Maven group id is unchanged.

### Upgrade procedure

1. Upgrade formidable-engine to 0.4.0 **first**. The element types
   (`fmdb:select`, `fmdb:radio`, `fmdb:checkbox`...) resolve the engine mixins
   they inherit from at the moment their own module registers: elements
   installed against an older engine keep that older view of the shared
   properties until they are reinstalled, so the engine must always carry the
   new definitions before the elements register against them. Since 0.4.0 the
   ordering is enforced: formidable-elements declares
   `Jahia-Depends: formidable-engine=0.4`, so it refuses to start until an
   engine at 0.4 or later is running (and formidable-extended-inputs requires
   both at 0.4).

   **Untick "Validate module definitions" for this upload too**: 0.4.0 removes
   three submission properties (ipAddress, userAgent, submitterUsername) that
   no version since 0.2.0 has written, so the validation rejects the upload as
   a major definition change (verified against a 0.3-era instance):

   > Module upload failed: Major change in definition :
   > [nodeTypeName=fmdb:formSubmission,type=MAJOR,
   > propDefDiffs=[[itemName=ipAddress,type=MAJOR,operation=REMOVED],...]],
   > cancel module deployment

   Expected here for the same reason as in step 3. Versions 0.2.0 and later
   never wrote these properties, but an instance that ran 0.1.x may still
   store values on old submissions: the upgrade leaves those values in place
   (0.2.0's changelog documents them as kept), they simply no longer have a
   definition — a content-integrity scan may flag them on such an instance.
2. In **Administration > Server > Modules and Extensions > Modules**, stop and
   uninstall formidable-elements 0.3.0 (or earlier). **Do not tick the option
   to delete the module content when uninstalling**: that choice erases every
   form and submission stored in the JCR. Left unticked, forms and their
   submissions are not affected; forms simply stop rendering while the module
   is absent.
3. Install formidable-elements 0.4.0 (then any extension module, such as
   formidable-extended-inputs, in the same movement). **Untick "Validate
   module definitions" before uploading**: the previous module's definitions
   are still registered in the JCR and 0.4.0 removes properties from them
   (they moved to engine mixins), so the validation rejects the upload as a
   major definition change:

   > Module upload failed: Major change in definition :
   > [nodeTypeName=fmdb:inputDatetimeLocal,type=MAJOR,...
   > propDefDiffs=[[itemName=min,type=MAJOR,operation=REMOVED],...]],
   > cancel module deployment

   This is expected here — the startup migrations take over for the existing
   content, so bypassing the check is safe for this upgrade.

   The engine log may also show `Could not migrate node ...` errors dating from
   step 1: the engine started while the element types still carried their old
   definitions. They are expected and recovered — the migrations that write
   through the element types (choice options, date bounds, list titles) re-run
   by themselves when formidable-elements is deployed; the translation fieldKey
   cleanup does not need to, its writes never involve those types. (The platform fires
   the same redeploy event when a module *stops*, so uninstalling the elements
   in step 2 re-runs the migrations too — some of that noise dates from step 2;
   the migrations are keyed on content state, so those runs are no-ops.)
4. **Verify formidable-elements is enabled on every site that uses it** (site
   settings, or **jContent > site > Modules**): uninstalling the old module in
   step 2 removed it from the sites' installed modules, and installing the new
   one does not put it back — until then the module's views are not served for
   those sites, and published forms render a "Module error" box instead.
   **0.4.0 repairs this automatically**: when formidable-elements deploys, the
   engine re-enables it on every site that holds forms but lost the module
   (`Re-enabled formidable-elements on site …` in the log) — once per site: a
   later deliberate deactivation is respected. This step is a verification, and
   the manual fallback if a site was missed — re-enabling by hand works
   immediately; a server restart does not.

   While you are there, check **formidable-engine** is enabled on the site too
   if its users need the **Form Results** panel: rendering and submissions work
   without it, the results panel does not. The engine's identity is unchanged
   by this upgrade, so a site that had it keeps it — this only concerns sites
   where it was never enabled.
5. **Reload jContent in every browser tab that was open during the upgrade**
   (a full page reload, not just closing the editor) before editing a form.
   The form editor's client code ships with the modules: a tab that kept the
   previous bundle shows fields with empty or missing settings — typically a
   conditional-logic rule whose value column is blank although the rule is
   stored — and nothing is wrong with the content.
6. Check that forms render again on the site.

This is a one-time migration: from 0.4.0 on, the group id is stable and later
versions install in place as usual — but the ordering rule of step 1 holds for
every upgrade that changes the engine definitions.

### If you installed in the wrong order (elements before engine)

Observed on a real replay: the elements module's definitions fail to parse while
the engine is still on 0.3 (`Error parsing definitions for DX OSGi bundle
formidable-elements` in the log), and the editor looks broken — wrong form
structure, Content Editor panels not applying. This **recovers on its own** the
moment engine 0.4 starts: the elements definitions are re-parsed against the new
engine mixins, and the content migrations run at engine activation (they are
keyed on content state, so nothing is lost or done twice).

Two things remain true whatever the order:

- **step 4 still applies** — the automatic re-enable runs when the elements
  module deploys, and the verification (plus the manual fallback) remains
  yours: a site missing the module renders broken forms and a broken editor
  even though the definitions and the migrated content are fine;
- if the editor still looks wrong after step 4, restart the formidable-elements
  bundle once (its definitions then re-register against the running engine).

## 0.3.0 (and earlier) → 0.4.0: choice-field options are migrated at startup

**Nothing to do — the migration is fully automatic.**
`ChoiceOptionsContentMigration` runs at engine startup, in both workspaces,
moves the legacy values as-is and stamps the manual mode — published forms
keep rendering without a republish. The migration is keyed on content state
(a legacy property is present), so re-running it is a no-op. The migrated
fields do show up as *modified* (pending publication) in jContent afterwards;
see [the publication flag note](#migrated-content-shows-as-modified-in-jcontent).

0.3 allowed a language to translate the option VALUES themselves (`rouge`/`vert`
facing `red`/`green`); 0.4 shares one value set across languages, so such a
list converges the first time the field is saved: the language's values become
the default language's, and its labels **ride along by position** when the two
lists have the same size and no value in common — the shape of a translated
0.3 list. A language whose list genuinely differs (fewer rows, mixed values)
falls back to blank labels awaiting re-translation, and the editor shows the
raw value wherever a label is blank. Any conditional-logic rule that compared
one of these values follows the same realignment in that save, so it keeps
matching submissions.

These divergent-list behaviours are confined to migrated fields by a one-shot
marker the migration stamps and the first realignment clears: native 0.4
content — where a contributor may legitimately replace every option value in
one edit — is never label-paired or rule-remapped on the shape alone.

### What changes

PR [#193](https://github.com/Jahia/formidable/pull/193) unified the per-type
option properties (`options` on `fmdb:select`, `choices` on `fmdb:radio` /
`fmdb:checkbox`) into the single `fmdb:options` property (renamed `options` in 0.5.0, see
above) carried by the `fmdbmix:manualOptions` mixin, as part of the options-source feature.

### The one case needing attention: importing a 0.3-era export

A form export produced by 0.3.0 or earlier and imported into an instance
already running 0.4.0 re-creates the legacy storage *after* the startup
migration ran: the imported choice fields render empty option lists.

Restart the server (or the formidable-engine bundle) to re-run the migration,
or re-enter the options in the editor.

### Source-based option settings became mandatory — the manual list did not

0.4.0 marks the settings of the source-based option modes as mandatory: the
source key for a declared source, the root category for category options, the
root content and content type for content options. The manually typed option
list is deliberately NOT mandatory — the constraint actually moved the other
way: 0.3.0 declared the radio and checkbox `choices` list mandatory, and 0.4.0
lifts that, so a choice field can now be saved with an empty list (it renders
an empty field until options are entered). Migrated fields all carry their
options and satisfy the source-mode constraints as-is.

## 0.3.0 (and earlier) → 0.4.0: date bounds become bound modes, migrated at startup

**Nothing to do — the migration is fully automatic.**
`DateBoundsContentMigration` runs at engine startup, in both workspaces, and
stamps every field carrying a fixed bound with the `date` mode and its mixin —
the stored values stay in place, and published forms keep rendering without a
republish. The migration is keyed on content state (a fixed value without a
mode), so re-running it is a no-op. The migrated fields do show up as
*modified* (pending publication) in jContent afterwards; see
[the publication flag note](#migrated-content-shows-as-modified-in-jcontent).

### What changes

The fixed `min`/`max` properties of date and datetime-local fields moved from
the field types into the `fmdbmix:fixedMinDate`/`fmdbmix:fixedMaxDate` (and
datetime) dynamic-fieldset mixins, driven by the new `fmdb:minBoundMode` /
`fmdb:maxBoundMode` properties (renamed `minBoundMode` / `maxBoundMode` in 0.5.0, see
above; `none`, `date`, `today` — the day the visitor
submits the form — or `relative`, that day shifted by a signed offset). In the
editor each bound is now a dropdown, and the calendar (or the offset fields)
only appears for the choice that needs it.

Programmatic creation is affected, though: a script or integration (GraphQL,
JCR API) that used to write a plain `min`/`max` date property on these field
types now hits a constraint violation, because the property definition lives in
the fixed-bound mixin. Such writers must add the matching mixin
(`fmdbmix:fixedMinDate` and friends) and set the bound mode to `date` alongside
the value — exactly what the editor and the migration produce.

As with every upgrade that changes the engine definitions, install the new
formidable-engine before the new formidable-elements.

### The one case needing attention: importing a 0.3-era export

Like for the choice options above: a form export produced by 0.3.0 or earlier
and imported into an instance where the migration already ran keeps its fixed
bounds **enforced at validation time** (the submission pipeline reads the stored
values on the underlying node), but the rendered date pickers are unconstrained
and the editor shows the bound dropdowns as "none" until the migration runs
again. Restart the server (or the formidable-engine bundle) to re-run it.

## Migrated content shows as *modified* in jContent

Every startup migration below writes into **both** workspaces, so the live
site never waits for a publication — but the default-workspace nodes are
modified *after* their last publication, and jContent truthfully flags the
migrated fields and lists as *modified* (pending publication).

For the choice-options and date-bounds migrations both workspaces carry
identical migrated values (verified byte-for-byte): publishing the flagged
content changes nothing in live and only clears the flag. The list-titles
migration is the one exception — in live it only titles the languages already
published there, so publishing a flagged list ALSO pushes the default titles
of the not-yet-published languages; that is a real (if minor) change to live,
and publishing remains the contributor's decision.

## Startup migrations

The engine carries one-shot content migrations that run at every module start
(`@Activate`) **and re-run whenever formidable-elements is (re)deployed**
(`ElementsRedeployRetriggeredMigration`) — on the engine-first upgrade path the
engine-activation run fails against the previous element definitions, and the
elements-redeploy run is the one that does the work. They run on both
workspaces, keyed on the content state and idempotent.
They come in two waves. The 0.4.x wave exists for instances upgrading from 0.3.x
content. It **stays in 0.5.0** (decided 2026-09-10) so that a 0.3.x instance can upgrade
to 0.5.0 directly, without a stop at 0.4.0; it leaves in 0.6 together with the 0.5.0 wave
(`MixinPropertyNamesMigration`, for 0.4.x content) and the deprecated definitions that
wave reads. From 0.6 on, 0.5.x is the minimum upgrade source: every instance has then run
both waves at least once. Each class carries a `Lifecycle:` note in its Javadoc pointing
here.

Every workspace pass goes through `MigrationSessions`. The **live pass runs with
JCR observation switched off**: Jahia records a direct live write on a published
node as user-generated content (`UGCListener` stamps `jmix:liveProperties` and
lists the properties in `j:liveProperties`), after which every publication skips
those properties for good — a migrated field would never take a later publication
into account (#281). A live pass that rewrote anything (or failed, since it may
already have saved some nodes) flushes the output caches itself, cluster-wide,
since the cache invalidation does not see the change either; the flush is
best-effort and never fails the migration. The Cypress specs exercising a
migration assert the invariant with `expectNoLiveOwnedProperty`, translation
subnodes included.

An instance upgraded from 0.3 with a **0.4.0 development build older than #282**
carries that marker on its migrated fields, and the migrations never revisit a
migrated node: clear `jmix:liveProperties` from them in live, or upgrade again
from a 0.3 restore. No released version is concerned.

| Class (`org.jahia.modules.formidable.engine.migration`) | Introduced | Leaves in | What it rewrites |
|---|---|---|---|
| `ChoiceOptionsContentMigration` | 0.4.0 (#193) | 0.6 | Legacy `options`/`choices` of choice fields → `fmdb:options` (`options` since 0.5.0) + manual mode |
| `DateBoundsContentMigration` | 0.4.0 (#202) | 0.6 | Fixed date/datetime bounds without a bound mode → mode `date` + fixed-bound mixins |
| `TranslationFieldKeyCleanup` | 0.4.0 (#215) | 0.6 | Stray `fieldKey` on `j:translation_*` subnodes of form elements |
| `ListTitlesContentMigration` | 0.4.x (#231) | 0.6 | Missing `jcr:title` on a form's `fields`/`actions` lists → the type's default label, per site language (in live, published languages only) |
| `MixinPropertyNamesMigration` | 0.5.0 (#312) | 0.6 | The thirteen `fmdb:`-prefixed properties (options source, date bounds, the select's empty-option label) → unprefixed names, translations included; the deprecated definitions it reads leave with it |

Removal checklist: delete the class and its unit test, drop the Cypress spec that
restarts the engine to exercise it, and remove the row above. When the last row
goes, also delete `ElementsRedeployRetriggeredMigration`, `MigrationSessions`,
`ElementsSiteReactivation` and their tests. The two marker mixins of the engine's CND
are a different matter: a declaration that disappears while nodes still carry it leaves
those nodes with an unknown mixin, and nothing flags it at deploy time — the definitions
check diffs only the types present in the new CND.

- `fmdbmix:migratedChoiceOptions` clears itself: `ManualOptionsLanguageSync` removes it the
  first time a field's languages realign, so its declaration goes once no field carries it.
- `fmdbmix:elementsReactivated` is stamped once per site healed on a 0.3.x path and removed
  nowhere — that permanence is what makes the healing one-shot — so every such site still
  carries it in 0.6. Keep its (empty) declaration in the CND and delete only the code that
  stamps and reads it; or ship a one-shot that strips it from the sites first, and drop the
  declaration one version later.
