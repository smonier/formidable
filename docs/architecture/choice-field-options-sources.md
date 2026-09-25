# Choice field options sources

Choice fields (`fmdb:select`, `fmdb:radio`, `fmdb:checkbox`) can fill their option
list from an **options source** instead of manually typed options. The options are
resolved live, at render time, in the language of the rendered form. Three source
kinds exist:

- **declared sources**: curated Jahia choicelist initializers, declared by the
  administrator in the module configuration;
- **categories**: the contributor picks a root category, the options are the
  categories directly underneath — no configuration involved, since categories
  are already contributor-curated content governed by JCR permissions;
- **contents**: the contributor picks a root node and a content type, the options
  are the descendants of that root carrying that type — same
  no-configuration-involved reasoning as categories, for regular editorial
  content.

## Storage model

The mode lives on the engine-owned mixin pair (industrial's mediaSource pattern):

- `fmdbmix:optionsSource` carries the `optionsMode` switch (`manual` default);
- `fmdbmix:manualOptions` carries the unified `options` property
  (i18n, multiple, JSON-encoded `{"value","label","selected"}` strings);
- `fmdbmix:sourcedOptions` carries only `optionsSourceKey`;
- `fmdbmix:categoryOptions` carries only `optionsRootCategory`, a
  weakreference to a `jnt:category` node picked with the category picker;
- `fmdbmix:contentOptions` carries `optionsRootNode` (a weakreference to
  the picked root, editorial picker) and `optionsNodeType` (the content
  type to list, as a qualified node type name).

In all three non-manual modes nothing else is stored — the option list never
materializes in the JCR.

The Content Editor switches the four options `jmix:dynamicFieldset` mixins (manual,
sourced, category, content — one per mode) through the `addMixin` wiring declared in the
fieldset JSON overrides of formidable-elements.

### Manual options across languages

An option's **value** is its identity: submissions store it, conditional logic
rules match it, and the forged-value validation checks it. It must therefore be
one single set across languages — only the **label** is editorial content that
translates (the default selection is form behavior, and travels with the value).
Since `options` is an i18n property, three rules keep the languages
coherent.

**In the editor, options are authored in the site's default language.** Outside
it a row locks its value and its default selection, and the row controls (add,
remove, reorder) hide: an added row could never receive a value there, and any
structural change would only be reverted by the re-alignment. Only the label is
editable. Options arriving in another language through the API or an import
still **seed the default language at save**, so such content self-heals instead
of being erased by a later main-language edit.

**On every save of the options, the server feeds every site language**
(`ManualOptionsLanguageSync`): each language is given the default language's
values, order and count, its `j:translation_*` subnode created when it has none.
A language keeps its own label for a value it already carries — same-value
entries pair positionally, so duplicated (or still-empty) values never collapse
onto one translation — and **labels are never copied from the default
language**: an entry nobody has translated is stored with an empty label. A
copied label cannot be told apart from a translated one, by the contributor
scanning the list or by a translation tool, and it would have to be erased
before it could be typed over. Content that diverged before this guard existed
is re-aligned the next time its field is saved — never at startup: the
legacy-options migration completes before the sync listener registers.

Feeding a language nobody translated **departs from the Jahia norm**, where
starting a translation is the contributor's gesture and never a server-side side
effect. This field leaves no room for that gesture: the value is the identity, so
it cannot be typed outside the default language, a row added there saves
valueless, and the only remaining way to bring the list into a language is
Content Editor's language copy — which copies the **whole node** and overwrites
every other field's hand-made translation. Creating the subnode unasked is the
lesser evil: the rows are then always on screen, values and switches read-only,
labels empty and ready to contribute.

**At render time, an untranslated entry follows the site's own rule for
untranslated content** — the *Replace untranslated content with the default
language content* setting (`j:mixLanguage`, read through
`JCRSiteNode.isMixLanguagesActive`). `ManualOptionsDisplayService` applies it per
entry, not per field, so a half-translated list always renders its translated
labels:

| Stored in the rendered language | Replacing ON | Replacing OFF |
|---|---|---|
| a label | that label | that label |
| an empty label | the default language's label | the entry is not rendered |

A form must never offer a blank choice, which rules out rendering the empty
label as it stands; and when the site asks for untranslated content to stay
invisible, an untranslated choice is exactly that. A field nobody translated
therefore offers nothing at all while replacing is off — the same verdict the
site pronounces on any other untranslated content.

Three consequences worth knowing:

- a form **renders** the default language's values, order and default
  selections, carrying the rendered language's own labels
  (`ManualOptionsDisplayService`). Rendering the stored translation verbatim
  would be unsafe rather than merely stale: publication is per language, so live
  can hold a translation at an older generation than the default language, and a
  visitor would then be offered values the validation rejects as forged;
- the forged-value validation reads the allowed values **from the default
  language**, so its verdict never depends on a translation that has not been
  re-aligned yet;
- the two above are deliberately **asymmetric**, and it is the one place where
  this design is not airtight. Withholding an untranslated entry narrows what a
  visitor can *pick*; it does not narrow what the server *accepts*, since the
  allowed set is read from the default language. An entry hidden from a French
  form is still accepted if it is submitted by hand. This is tolerated: the
  alternative — deriving the allowed set from the rendered language — is what
  makes a published-generation mismatch reject legitimate submissions, which is
  a far worse failure. Treat the hiding as presentation, never as an access
  control.

**An option whose value is deliberately empty** — the historical way of starting
a select on a blank entry — cannot have its label translated. Outside the default
language the editor reads an empty value as "nothing to translate here" and shows
a pointer to the default language where the label input would be. The stored
label survives untouched (same-value entries pair positionally, so the
re-alignment keeps it); it simply cannot be edited there.
`optionsEmptyLabel` is the supported way to start a select empty, and being
an ordinary i18n property it translates normally.

Why `options` is **not mandatory**: options are authored in one language
only, while a mandatory i18n property is validated in **every** language a
contributor merely visits. During a field's creation, opening another language
made the whole form unsavable — the visited language failed the required
validation on a list the editor deliberately forbids authoring there — and no
editor-side workaround exists that does not write content on the contributor's
behalf (which falsifies the per-language change tracking). Coherence and
presence are owned by the save-time sync instead: a saved default language feeds
every other one. The trade-off is that a choice field *can* be saved with no
options at all; it then simply renders nothing selectable until its options are
authored in the default language.

Why the storage stays i18n: one JSON entry carries the value together with its
**translatable label**, so a non-i18n property could not hold the labels.
Splitting values and labels into parallel properties (values shared, labels
i18n, index-aligned) was weighed and rejected: the index alignment is exactly
the fragility this guard removes, for a much larger migration and editor
rework. The identity is enforced by the sync plus the default-language reads
above instead.

## Declaring sources (administrator)

Sources are declared in `org.jahia.modules.formidable.cfg`, one per line:

```properties
optionsSources=countries|Countries|country\n\
  tv|TV screens|fmdbSampleStaticList|plasma,oled,led
optionsSourcesCacheTtlSeconds=300
```

Each entry has the form `id|Label|initializerKey` or `id|Label|initializerKey|param`:

| Segment | Role |
|---|---|
| `id` | Stable identifier stored in JCR (`optionsSourceKey`) |
| `Label` | Shown to contributors in the source picker |
| `initializerKey` | Key of the Jahia choicelist initializer to evaluate (e.g. `country`) |
| `param` | Optional parameter string handed to the initializer |

The label is either a literal, or a resource-bundle key of the form
`<module>:<resource.key>` (for example
`formidable-test-module-samples-java:sample.optionsSource.tv`) resolved
server-side against that module's Java resource bundle; an unresolvable key falls
back to the raw label, so a misconfiguration stays visible. The resolution
language is the one the Content Editor hands to choicelist initializers: the
**UI language** since jcontent PR #2570 (2026-07-20) — the same language that
resolves the neighboring editor labels — and the edited content language on
older jcontent versions. A literal containing a colon (e.g. `Type: TV`) is not
mistaken for a key — the key form is strictly `module:key` without spaces.

Only declared sources are exposed to contributors — never the raw platform-wide
initializer list, most of which is context-dependent and meaningless as a form
options source. An empty `optionsSources` disables sourced options entirely
(fail-safe default).

In the Content Editor, the source is picked with a standard Jahia choicelist
fed by the `formidableOptionsSources` initializer — no custom selector involved.

### Which initializers qualify as a source

The resolver evaluates the initializer **outside any rendering context**:
`getChoiceListValues(null, param, null, locale, emptyContext)` — no property
definition, no context node — and caches the result per **(source, language)**
for the TTL. That cache is shared by every caller: all users, all workspaces,
edit and live alike. Two rules follow:

1. **The initializer must not require Content Editor context.** Most core
   initializers resolve against the property definition or a context node and
   either throw or return an empty list here (verified on Jahia 8.2.4:
   `users`, `templates`, `templatesNode`, `resourceBundle`, `sort`,
   `componenttypes`, `nodetypeproperties`, `propertyValues`, `menus`,
   `moduleImage`, `siteLanguage`, `flag`, `script`, …).
2. **The initializer must not read JCR content through the ambient session.**
   Core initializers that touch content use
   `JCRSessionFactory.getCurrentUserSession()`, which is the **default
   workspace** regardless of the rendered workspace. Combined with the shared
   cache, this is an information leak: an editor's session primes the cache
   with default-workspace values — including **unpublished content** — and live
   visitors are served that list until the TTL expires.

The core `nodes` initializer is the canonical counter-example and must **never
be declared as a source**, even though it superficially fits (it accepts a
`/path;type` parameter and lists nodes): it reads the default workspace through
the current user session (verified against the 8.2.4 bytecode), its values are
UUIDs (opaque in results, broken by a site re-import), its labels are system
names (not localized titles), and site placeholders in its parameter resolve
against the platform's *default site*, not the current one. Anything shaped
like "list this content" belongs to **category mode** — whose resolution goes
through the field's own session and leaks nothing by construction — not to a
declared source.

In practice, on a stock Jahia the only core initializer worth declaring is
**`country`** (ISO country list, localized labels, no JCR involved). The
engine's `formidableMimeTypes` is safe for the same reason.

### Initializer parameters

The parameter is **one opaque string**: everything after the third `|` is passed
verbatim to the initializer (the entry is split with a limit, so the parameter may
itself contain `|`). The Jahia initializer API
(`getChoiceListValues(epd, param, values, locale, context)`) receives a single
string — there is **no platform-wide separator for multiple parameters**; each
initializer defines its own convention. The core `nodes` initializer uses `;`
(`/path;jnt:type`), `;` is the recommended convention for heterogeneous parameters
(for example `code;depth` contracts), and the sample `fmdbSampleStaticList` splits
its single parameter on commas to get a homogeneous value list. This is the
same behavior as the CND `choicelist[initializer='param']` syntax, where the quotes
also carry a single string. (Not to be confused with the comma in
`choicelist[init1,init2]`, which separates chained initializers, not parameters.)

## Category mode

The options of a category-mode field are the categories **directly under** the
picked root: the category name is the submitted value, its localized title the
displayed label. The weakreference resolves in the workspace of the caller, so a
live form only shows **published** categories, and an unpublished or deleted root
category degrades like any failing source (see below). Category options are not
TTL-cached: the read is in-JVM, backed by Jahia's JCR caches, and the field
fragment depends on the root category's subtree, so a category published,
renamed or removed under it refreshes the already-rendered page.

## Content mode

The options of a content-mode field are the **descendants** of the picked root
node that carry the configured content type — the whole subtree, not only direct
children. The submitted value is the content's **path relative to the root**
(`paris`, `europe/berlin`): unique by construction, readable in the results, and
stable across site re-imports (unlike a UUID). The displayed label is the
content's localized displayable name.

### What the contributor configures

The Content Editor asks for two things:

1. **Root node** — picked with the editorial picker, stored as a weakreference.
2. **Content type** — a dropdown listing the **distinct primary types of the
   contributable contents actually present under the picked root** (contents
   carrying `jmix:droppableContent` or `jmix:editorialContent`; technical
   subnodes such as permissions or translations carry neither and never
   surface, and `fmdb:*` form elements are excluded). The list is re-resolved
   whenever the root changes (standard jcontent dependent-properties
   mechanism), so everything offered resolves and everything resolvable is
   offered. The discovery scan is bounded (500 contents per facet): past that
   bound a type present only deeper in the tree may be missing from the
   dropdown — never wrongly added — and a stored type stays selectable.

Both fields are standard Jahia selectors (editorial picker and choicelist) —
no custom editor code is involved.

### Resolution rules

The resolution runs a JCR query through the **field's own session**, so it
follows the caller's workspace and language: a live form only lists
**published, visitor-readable** content, in the language of the rendered page.
Content options are not TTL-cached (the read is in-JVM, backed by Jahia's JCR
caches), and the field fragment depends on the root's subtree: publishing a new
content under the root, or renaming or unpublishing one, refreshes the
already-rendered page.

An unreadable root (deleted, or no longer published) degrades like any failing
source: inline error on the field, form blocked only if the field is required
(see below).

### The result cap

`optionsQueryMaxResults` (module configuration, default **100**) bounds how many
options a content-mode field may resolve. Above the cap the field **fails
explicitly like a failing source** — the visitor never gets a silently
truncated list, because a missing option is a data-integrity issue, not a
cosmetic one. The content-type dropdown forewarns the same situation: a type
whose contents already exceed the cap stays offered (a stored value must remain
selectable) but its label carries a localized warning, so contributors re-scope
their root (or ask an administrator to raise the cap) before publishing.

Mind one operational nuance when changing the cap (or any module
configuration): the new value reaches the services immediately, but live pages
**already rendered keep serving their cached HTML fragments** until a change
under the picked root flushes them or the fragment cache expires — a
configuration is not a node, so no cache dependency can follow it. Flush the
site cache to see a configuration change on an already-visited page.

### Publication follows the reference — mind what lives under the root

Jahia publication includes referenced content: **publishing the form publishes
the picked root node and its whole subtree along**, including contents nobody
ever published explicitly. This is standard Jahia behavior for references (the
same mechanism publishes an image referenced by a page), but a content-mode
field references a *container*, so the ripple effect is the full option
universe of the field.

Consequences and guidance:

- **Point the root at a dedicated folder** whose entire content is meant to be
  public (the options of a live form are, by definition, public data). Avoid
  mixed working folders where drafts sit next to publishable content.
- **Mark work-in-progress content as WIP**: WIP content is excluded from every
  publication, including the one triggered through the form's reference
  (verified on Jahia 8.2.4: a WIP-flagged text under the picked root stays out
  of live when the form is published). WIP is the platform's contract for
  "not ready" — an unpublished-but-not-WIP content offers no such guarantee.
- Remember that a content published this way becomes readable in live (as an
  option of the field, and through APIs subject to live permissions) even when
  it has no navigable page.

## Resolution, cache and failures

`FormidableOptionsSourceService.resolve(sourceKey, languageTag)` (engine) evaluates
the initializer and answers in the manual-options JSON format, so the rendering
code cannot tell sourced and manual options apart. Server views call it in-process
(`server.osgi.getService`). Results are cached in memory per (source, language) for
`optionsSourcesCacheTtlSeconds` (default 300); a configuration change takes effect
immediately (cache entries remember the source definition they were resolved from),
and failures are never cached.

When a source cannot deliver (unknown key, initializer missing or failing), the
field renders an inline error instead of an empty list:

- **required field**: the form is not submittable (the submit button is disabled);
- **optional field**: the form stays usable without it.

Submitted values are validated server-side against the re-resolved list, exactly
like manual options, with no tolerance: a non-empty value absent from the list is
rejected (FMDB-010), and when the source cannot be resolved at validation time a
non-empty value is rejected as unverifiable. An empty or absent value follows the
field's `required` flag, like any other field.

## Writing a source initializer

Any module can contribute one by registering a `ModuleChoiceListInitializer` OSGi
service with a fixed key. `SampleStaticListInitializer`
(formidable-test-module-samples-java) is a complete parameterized example of the
recommended shape: it serves a **static list** whose values come from the
initializer parameter (comma-separated) and whose labels come from the module's
resource bundle in the requested locale — no Content Editor context, no JCR read,
so the result is safely cacheable across users and workspaces. Return localized
labels using the `locale` argument; blank values are dropped by the resolver, and
a blank label falls back to the value.

Mind the rules of the previous section: a source initializer runs outside any
rendering session and its result is cached across users and workspaces. Compute
options from non-JCR data (code, configuration, resource bundles, external
systems) — anything whose universe is the same for every visitor. Content that
lives in the repository is what **category mode** and **content mode** are for:
their resolution runs through the caller's session, so publication and
permissions apply per request. If a source initializer reading the repository is
truly unavoidable, open an **explicit** system session on a deliberately chosen
workspace — never rely on `getCurrentUserSession()` — and only expose
admin-curated, non-sensitive trees: whatever is resolved is served identically
to every visitor.
