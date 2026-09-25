# Field actions

> Decided with the developer on 2026-09-21 (issue #341), from the draft of 2026-09-11. The engine half is
> shipped by the pull request that adds this page; the library helpers, the editor zone and the built-in
> email check follow (see "Roadmap").

## Overview

A **field action** is a piece of content attached to one form field that judges the field's candidate value
against something the browser cannot know — an external service (an email deliverability provider, a postal
address service, a customer directory), a repository lookup, a business rule. It runs **server-side, at the
visitor's request while the form is being filled** (on leaving the field, or at submission, as the contributor
set), and **again when the submission is parsed**, before any form action runs. Its refusal reaches the visitor
as a message anchored on the field, in the visitor's language, written by the contributor.

It is the form action's twin, one level down:

| | Form action | Field action |
|---|---|---|
| Attached to | the form (`actions` list) | one field (`actions` list under the field) |
| Runs | after the submission is accepted, once | before it is accepted — at blur or submit from the browser, and in the pipeline |
| Side effects | expected (store, email, forward) | forbidden — a field action only answers |
| Declared by | a node type taking `fmdbmix:formAction` | a node type taking `fmdbmix:fieldAction` |
| Implemented in | Java (`FormAction` OSGi service) | Java (`FieldAction` OSGi service) or **JavaScript** (a `hidden.execute` server view) |
| Contributor UI | the actions zone under the form, Content Editor for each action | a switch in the field's editor form, a zone under the field, Content Editor for each action |

Everything a third-party module needs to add its own field-action type is what it needs today to add an
action type — a CND, a view or a service, resource bundles.

## The problem it solves

Formidable validates the shape of a value (required, pattern, length, bounds, type semantics) in the
browser and again in the pipeline (`FieldValidator`, step 9). Nothing let a project say "and this email
must actually exist" or "this customer number must be known to our CRM". The only hook was a custom form
action, and an action is the wrong tool: it runs after the submission is accepted, behind the store and
the emails, with no way to tell the visitor *which* value was wrong — the form shows its global error. A
refusal there is a stored, emailed, then refused submission.

## Architecture

```
 browser                        formidable-engine (Java, generic)                     the action's code
 ───────                        ─────────────────────────────────                     ─────────────────
 blur / submit ──POST──▶ FieldActionServlet  /modules/formidable-engine/field-action
   {field, value, trigger}   │ security-filter scope formidable-field-action (origin: hosted)
                             │ value length cap · rate limit per client
                             │ fid → form read in the VISITOR's live session (readable? a form? members-only → FMDB-009)
                             │ field found BY NAME under it (FieldActionCollector, walk cached per form and locale)
                             ▼
                        FieldActionDispatcher ── for each action of the field, in list order ──┐
                             │  1. a Java FieldAction registered for the node type?  ──────────┼─▶ execute(node, request)
                             │  2. else render the node's `hidden.execute` view      ──────────┼─▶ hidden.execute.server.tsx
                             │     (RenderService, expiration=0, request attribute)           │      server.osgi.getService(FieldActionGateway)
                             │  verdict cache (action id + locale + trimmed value)  ◀────────┘      credential from .cfg, never in JS or JCR
                             ▼
 ◀──── JSON {verdict: accept | advice | reject, messages: [{level, html, field}]}

 submit ──POST──▶ FormSubmitServlet → pipeline step 11b runFieldActions → the same dispatcher, the same cache
                                                     → FMDB-015 + messages[] on a blocking refusal
```

**The server runs the action, twice, from one code.** The browser never runs a field action itself: it
asks. On blur or submit of a field carrying actions, the page calls the pre-check endpoint, which runs the
field's actions and answers a verdict and the messages; the page shows them under the field. Then the
pipeline runs the *blocking* actions again at submission — the pre-check is a courtesy for the visitor,
the pipeline is the authority, and a browser that skipped the pre-check meets the same actions there. The
shared verdict cache makes the honest browser's second run free. Three reasons hold this shape: the
provider's credential never leaves the server; a value the browser was told was fine is checked again
where the data is accepted; and a JavaScript module cannot expose an endpoint of its own.

**Why a view is the JavaScript entry point.** A Jahia JavaScript module cannot expose an HTTP endpoint,
and the JavaScript modules engine exports none of its packages (its manifest lists 166 exported packages,
none under `org.jahia.modules.javascript`, measured on `javascript-modules-engine 1.3.0-SNAPSHOT`), so a
Java module cannot link against `GraalVMEngine` to call a registered function. What Java *can* do with a
JavaScript module is what the platform does: render one of its views through `RenderService.render`. A
view named `hidden.execute` registered on the field-action type is therefore the contract: the engine
renders it with the candidate value in a request attribute, the view answers a small JSON body. Request
attributes are already how the engine talks to the JavaScript views (`CaptchaRenderFilter`), and
`server.osgi.getService(<class name>)` already how the views call back into Java. The registry-based SDK
that would have offered a direct call (javascript-modules#686) was closed unmerged; should one ship, the
dispatcher gains a second lookup and the view stays valid.

## JCR definitions

The split follows [CND module ownership](cnd-module-ownership.md): **markers the engine keys on live in
the engine and carry no property; what the contributor configures is a property mixin attached through
a marker with `extends`; concrete types live with the code that implements them.**

### `formidable-engine` — `META-INF/definitions.cnd`

```cnd
// Marker: what the node IS. Concrete field-action types take it as a supertype, whatever module
// declares them, exactly as action types take fmdbmix:formAction. A field action answers; it never writes.
[fmdbmix:fieldAction] mixin

// What the contributor sets on every field action, whatever its type — attached through the marker with
// `extends`, so a third-party type gets it with nothing to opt into.
[fmdbmix:fieldActionFeedback] mixin
 extends = fmdbmix:fieldAction
 - rejectionMessage (string, richtext[…]) = resourceBundle('fmdbmix_fieldActionFeedback.rejectionMessage.default') autocreated i18n indexed=no
 - trigger (string, choicelist[resourceBundle]) = 'blur' autocreated indexed=no < 'blur', 'submit'
 - severity (string, choicelist[resourceBundle]) = 'block' autocreated indexed=no < 'block', 'warn'
 - whenUnavailable (string, choicelist[resourceBundle]) = 'accept' autocreated indexed=no < 'accept', 'reject'

// The per-field list, fmdb:actionList in miniature. Orderable: the first blocking refusal wins.
[fmdb:fieldActionList] > jnt:content, jmix:list, mix:title, jmix:systemNameReadonly
 orderable
 - jcr:title (string) = resourceBundle('fmdb_fieldActionList') autocreated i18n
 + * (fmdbmix:fieldAction) = fmdbmix:fieldAction version

// The switch in the field's own Content Editor form — "this field has actions".
[fmdbmix:fieldActions] mixin
 extends = fmdbmix:formElement
 + actions (fmdb:fieldActionList) = fmdb:fieldActionList autocreated
```

Same shape as the form actions — one marker, one list, one property mixin — plus the switch, and the same
precedents, line for line: `fmdb:actionList`'s `+ * (fmdbmix:formAction) = fmdbmix:formAction version` for
the list; `fmdbmix:fixedMinDate … extends = fmdbmix:dateBounds` and
`fmdbmix:jExperienceProfileMapping … extends = fmdbmix:profileMappableField` for a mixin attached to a
marker with `extends`, which is also what gives this one its switch. The child under the field is named `actions`, as it is under the form: the
same word for the same object, the parent saying which.

**The switch.** Turning the fieldset on adds `fmdbmix:fieldActions` to the field on save, and adding a
mixin autocreates the child nodes it declares (Jackrabbit's `AddMixinOperation` walks the autocreated
node definitions): the list appears. Turning it off removes the mixin, and removing a mixin deletes the
child nodes it defines: the list and every action in it go — the switch's label says so.
`fmdbmix:formElement` itself is untouched.

**Why the mixin does not take `jmix:dynamicFieldset`**, although the fieldset is dynamic. That supertype
extends `jmix:templateMixin`, and the Content Editor gives no enable switch to a `jmix:templateMixin`:
`hasEnableSwitch = !nodeType.isNodeType("jmix:templateMixin")`, and a fieldset with neither a switch nor
one visible field is dropped from the form altogether (`EditorFormServiceImpl`, the two lines that close
the fieldset loop). This mixin carries **no property** — it only autocreates the list — so with that
supertype the editor rendered nothing at all and the feature could not be turned on by a contributor.
What makes a fieldset dynamic there is `extends`, nothing else. The options fieldsets keep
`jmix:dynamicFieldset` for the opposite reason: a choicelist activates them through the `addMixin` wiring
of their overrides, so they must not offer a switch of their own.

**The four settings** are the contributor's calls, per form, hence properties, not facts of the type:
when the browser asks (`blur` for a cheap check, `submit` for a paid one — the pipeline runs the blocking
ones again regardless), whether a refusal blocks the submission or only warns, and what an unanswered
check means (`accept`: a provider outage never blocks visitors; `reject`: a form that must not pass
unchecked). A node saved outside the editor may lack the mixin or a property: the engine reads the CND
defaults (`ResolvedFieldAction`).

### A field-action type — in the module that implements it

The concrete type is declared next to its code, like a field type next to its view. The engine never
names the concrete type: it reaches it through the markers.

```cnd
// A module of its own: the type takes the marker, carries its own settings, and either a Java service
// or a hidden.execute view bound to it judges the value. The four feedback settings arrive through
// `extends`, unasked.
[myco:crmLookupAction] > jnt:content, fmdbmix:fieldAction, mix:title
 - jcr:title (string) = resourceBundle('myco_crmLookupAction') autocreated i18n
 - providerId (string, choicelist[formidableFieldActionProviders]) mandatory indexed=no
```

The `jcr:title` line is what fills the action's title with the type's own label when a contributor
creates one, as every built-in form action does — without it the card and the content tree show a bare
system name.

**Ship an icon too**, at `src/main/resources/icons/myco_crmLookupAction.png`, 16×16. The platform's
fallback does not help here, whatever it looks like: `JCRContentUtils.getIcon` walks `getSupertypes()`
in order and takes the first one with a file, and `nt:base` — which ships one — comes long before
`fmdbmix:fieldAction` in that list. Measured: a sample type carrying the marker and no icon resolved to
`/modules/assets/icons/nt_base`, the generic sheet, and resolved to its own glyph the moment the file
existed. The engine's `icons/fmdbmix_fieldAction.png` is therefore the marker's own drawing, not a
fallback for the types that take it; the samples module ships the same drawing under its type's name,
which is the shape to copy.

The samples module ships one: `fmdbsample:blockedWordsAction` (a `words` list, a value containing one is
refused), implemented by `BlockedWordsFieldAction` in Java — the shape to copy. It is a sample: it is installed
by the test provisioning manifest and reaches no product installation.

### The samples' second one — `fmdbsample:emailDeliverabilityAction`

**The engine ships no concrete field-action type at all.** It provides the mechanism — the markers, the list, the
dispatcher, the endpoint, the gateway — and a project decides which checks its forms deserve. What the samples
module ships is the shape to copy, and the second sample is the check a real project is most likely to want: does
the domain of the address exist? It asks the domain name system about the **domain** — never the address, so the
visitor's identity is not handed to a resolver — and it accepts as soon as a mail exchanger or an address record
answers. It needs no provider, no credential, no account and no configuration, which is what makes it a good
sample: it runs out of the box, in the playground and in the test suite.

**It refuses one thing only: a domain the resolver says does not exist.** That is the mistyped domain, which is
the case worth catching and the one that can be proved. Anything else is an unavailable check, which the
contributor's `whenUnavailable` decides. Two measurements are behind that rule, both made against the running
instance rather than assumed:

- asking for `MX`, `A` and `AAAA` **in one query** answers `DNS error` on resolvers that answer each of the
  three separately, so the lookup asks one question at a time;
- **Docker's embedded resolver returns nothing at all to a mail-exchanger question**, and most Jahia
  installations run behind one. An empty answer therefore proves nothing, and a first cut that read it as
  "this domain takes no mail" refused every address on such a host, `ada@jahia.com` included.

What it does not do is prove the mailbox exists, or even that the domain accepts mail. The help text says so,
and that is what a provider behind `FieldActionGateway` is for.

It carries one unit test in the samples module, which is the other half of what a module copying it inherits:
the seam is the lookup itself, so its rules are tested without a test of the network.

## Execution

### A field action in Java

```java
@Component(service = FieldAction.class)
public class CrmLookupAction implements FieldAction {
    @Override public String getNodeType() { return "myco:crmLookupAction"; }

    @Override
    public FieldActionResult execute(JCRNodeWrapper actionNode, FieldActionRequest request) {
        String providerId = actionNode.getProperty("providerId").getString();
        FieldActionGateway.Response response = gateway.post(providerId, "customers/lookup",
                "{\"number\":" + JSONObject.quote(request.value()) + "}");            // IOException → the engine reads it as unavailable
        if (response.status() != 200) return FieldActionResult.unavailable("provider " + response.status());
        return new JSONObject(response.body()).optBoolean("known") ? FieldActionResult.accept() : FieldActionResult.reject("unknown");
    }
}
```

`org.jahia.modules.formidable.engine.api` exports the contract: `FieldAction` (`getNodeType()`,
`execute(actionNode, request)`), `FieldActionRequest` (form UUID, field name, value, locale),
`FieldActionResult` (`accept()`, `reject(detail)`, `unavailable(detail)`) and `FieldActionGateway`. The
action node is read in `live` in a system session; an exception escaping `execute` counts as unavailable
and is logged.

### A field action in JavaScript — the `hidden.execute` view

```tsx
jahiaComponent(
  { componentType: "view", nodeType: "myco:crmLookupAction", name: "hidden.execute",
    properties: { "cache.expiration": "0" } },                              // never cached: a verdict is about one value
  ({ providerId }, { renderContext }) => {
    const raw = renderContext.getRequest().getAttribute("formidable.fieldAction");   // null on a direct URL hit: the view is not an endpoint
    if (!raw) return null;
    const request = JSON.parse(raw) as { formId: string; fieldName: string; value: string; locale: string };
    const gateway = server.osgi.getService("org.jahia.modules.formidable.engine.api.FieldActionGateway");
    const response = gateway.post(providerId, "customers/lookup", JSON.stringify({ number: request.value }));
    if (response.status() !== 200) return `{"verdict":"unavailable","detail":"provider ${response.status()}"}`;
    return JSON.parse(response.body()).known ? `{"verdict":"accept"}` : `{"verdict":"reject","detail":"unknown"}`;
  },
);
```

- **Input**: the request attribute `formidable.fieldAction`, a JSON object `{formId, fieldName, value,
  locale}` the engine sets before rendering; absent on a direct hit of the view through the render servlet,
  which is why the view answers nothing then. The value never travels in a URL.
- **Output**: the view's body is one JSON object `{"verdict": "accept" | "reject" | "unavailable",
  "detail"?: string}` **and nothing else** — the output, trimmed, must be exactly that object, once the
  platform's own wrapping is off it. Jahia's `URLFilter` applies on a `module` configuration and wraps every
  fragment in `<!-- jahia:temp value="URLParserStart…" -->` markers; `StaticAssetsFilter`, which removes them
  again, does not run on that configuration, so `RenderServiceViewRenderer` strips them with the platform's
  own pattern before the reader sees the body. Without that step the strict reader would answer UNAVAILABLE
  for **every** JavaScript-written action, which the CND default then accepts. Nothing
  before it, nothing after it: not a comment, not a debug line, never the candidate value. A reader that
  took the widest span between braces would let a view echoing the value hand the parser a string the
  visitor partly controls, and one `{` in the value would then retire the check, silently, onto the
  `whenUnavailable` default; the strict reader fails on every value instead, deterministically, so the
  author sees it at the first test. Anything but the one object is read as unavailable; the output itself
  reaches the logs at DEBUG only. The **visitor-facing text is not the view's business**: the engine reads
  the contributor's `rejectionMessage` on the node in the visitor's locale, interpolates and escapes it.
- **Caching**: the view declares `cache.expiration=0` and the engine sets the `expiration` request
  attribute to `0` before rendering, which has priority over the view (`AggregateCacheFilter`); the cache
  layer stores nothing whose expiration is not `> 0`. Belt and braces, because a cached verdict keyed on
  the node would answer every visitor with the first one's result.
- **Side effects**: none. The engine renders the view in the visitor's request, the node read in `live`.

`@jahia/formidable-library` will carry `readFieldActionRequest(renderContext)` and `fieldActionResult`
helpers for this contract (roadmap, step 2); the raw attribute and the JSON string above are the contract
itself.

### The dispatcher

`FieldActionDispatcher` runs a field's actions in list order. The trigger filter (a blur pre-check runs
the blur actions, a submit runs them all) and the pipeline's `blockingOnly` rule (a warning action had
its say at the pre-check) decide whether an action runs; a Java `FieldAction` registered for the node
type is executed, failing that the view is rendered and its verdict parsed. `ACCEPT` moves on. `REJECT`
becomes one `FieldActionMessage` — level `error` when the action blocks, `warning` otherwise; the
contributor's `rejectionMessage` in the visitor's locale, `${value}` interpolated through
`TemplateInterpolator` with `FieldEscaper.html`, the rich text itself trusted as the form's responses are;
the field name, and — for the logs and the tests only, never in a response — the action's id and type. It
ends the run when the action blocks, since the first blocking refusal wins. `UNAVAILABLE` is what the
contributor's `whenUnavailable` says, and **its `detail` reaches DEBUG only**: that string is the action's
or the view's own words — a provider's answer, an exception message — and may quote what the visitor typed.

**`${value}` is the only name a rejection message may use**, and it is the only one the editor's help text
offers. The browser asks about one field at a time, so the other fields' values are not there at the
pre-check: interpolating them at submission only would render one message two ways, complete once and full
of holes the other time. Both entry points pass the same thing, and any other name resolves to nothing.

The **verdict cache** (`VerdictCache`) keeps `ACCEPT` and `REJECT` per action id, **locale** and trimmed
value for `fieldActionVerdictCacheTtlSeconds`, bounded at ten thousand entries; `UNAVAILABLE` is a
moment's truth and is not kept. The locale is in the key because it is in the request an action judges: an
accept obtained with `?lang=<a locale where the check passes>` must not serve the submission in another.
`FieldActionRuntime`, one OSGi component, holds the registered Java actions, the verdict cache, the
endpoint's rate limiter, the cache of the forms' declared actions (below) and the dispatcher built on them,
and both servlets reference it — so the verdict the pre-check gave is the one the pipeline finds.

### The endpoint — `POST /modules/formidable-engine/field-action?fid=<form UUID>&lang=<lang>`

Body `{"field": "<field node name>", "value": "<candidate>", "trigger": "blur" | "submit"}` — one value
per call; a multi-valued control asks once per value — answer `{"verdict": "accept" | "advice" | "reject",
"messages": [{"level": "error" | "warning", "html": "…", "field": "…"}]}` — `advice` when only warnings
came back; a message never names the action node or its type. Registered as
`FormSubmitServlet` is (HTTP whiteboard, `alias=/formidable-engine/field-action`), gated as it is: its own
security-filter scope `formidable-field-action` (`auto_apply: origin: hosted`), one more pattern in the
CSRFGuard whitelist, `PermissionService.hasPermission({api: "formidable-field-action"})` before anything
is read. Then, in order:

1. `fieldActionRateLimitPerMinute = 0` switches the endpoint off — a 404 without a code; the field actions
   then run at submission only.
2. `fid` UUID-validated, `lang` a valid language tag — `FMDB-002` as the pipeline answers.
3. The body read up to the value cap plus what the syntax may add, refused unread beyond — `FMDB-003`; a
   body that is not a JSON object, or a `field` that is not a node name, `FMDB-002`; a `value` longer than
   `fieldActionMaxValueLength`, `FMDB-003`.
4. Rate limit per client address (`fieldActionRateLimitPerMinute`, default 30) — `FMDB-016`, 429. The
   endpoint is an open door to a possibly paid service for anyone on the site; the limit and the cache are
   what make it affordable.
5. The form resolved in `live` by its UUID **in the visitor's own session**, exactly as the pipeline's
   step 4 does: a form the caller cannot read — unpublished, on a page they may not see, on another site
   they have no access to — is not found, and so is an identifier that is not a form's, with the same
   code so the answer discloses nothing about what the UUID points at. The form's UUID is public (the
   rendered form carries it in its submit URL); what the visitor may read is not. `FMDB-004`.
6. A guest on a form carrying `fmdbmix:authenticatedOnlyForm` is refused, as the pipeline's step 6 refuses
   their submission — `FMDB-009`, 401. Without it, the public fid of a members-only form would let a
   logged-out caller run every action of the form.
7. The field found **by node name under the form** (`FieldActionCollector.collect`) — never
   `getNodeByIdentifier` on a client-supplied field id. The walk of the form's subtree runs in a system
   session, as the pipeline's metadata collector runs it, and is kept per form and locale for sixty seconds
   (`FieldActionsCache`, bounded at a thousand forms): thirty pre-checks a minute on one form cost one walk,
   and a contributor's change reaches the pre-check within the minute — the pipeline, the authority, walks
   the form on every submission. No such field with actions: `FMDB-004`.
8. A blank value is accepted without running anything — an unanswered field says nothing to check, and
   the pipeline skips it too. Otherwise the dispatcher runs the field's actions for the declared trigger.

A form carrying `fmdbmix:captchaProtectedForm` is **not** captcha-gated at the pre-check: a captcha token
is single-use and is spent by the submission, which the pipeline verifies at its step 7 before step 11b
runs anything again. The pre-check of such a form is bounded by the rate limit, the value cap and the
verdict cache only, which is what "an open door to a possibly paid service" means: the administrator who
finds that bound too loose for a given provider switches the pre-check off (`fieldActionRateLimitPerMinute=0`)
and the field actions run at submission only, behind the captcha. A per-action "submission only" setting
is listed under "Open questions".

### The pipeline — step 11b `runFieldActions`

Between `validateRequired` (11) and `dispatchActions` (12): for every field whose metadata carries
actions (`FormFieldMetadataCollector.Result.fieldActions`, read in the same walk as the constraints, so
the repository is read once), whose submitted value is not blank and that the logic evaluator does not
hold hidden, the dispatcher runs the **blocking** actions with every trigger — on **every non-blank value**
of the field, in order, since every one of them is stored and sent on (a checkbox group, or two fields
sharing a name, which the pipeline accumulates under one name).

**A field whose actions all warn is not judged here at all** — `blockingOnly` would skip every one of them —
so nothing it carries costs anything, and nothing it carries is counted against the bound below.

**The bound.** A field name can be submitted any number of times: the parser appends one entry per part and
only the request size limits them, so one submission could ask for thousands of provider calls. What is counted
is the list about to be judged: `answeredValues` removes the repeats of one answer before anything counts or
runs them, so a hundred repeats are one call and a hundred different answers are a hundred.

The verdict cache is **not** what makes that true, and a reader who believes it is will write the old loop
again. It never stores an `UNAVAILABLE` — which is exactly what a provider being down answers — and stores
nothing at all when `fieldActionVerdictCacheTtlSeconds` is zero. Counting one thing and running another is
what let a body repeating a single value under the request size limit run hundreds of thousands of serial
outbound calls. Past `fieldActionMaxValuesPerField` the submission is refused with `FMDB-017`, and the response
carries a `messages` entry naming the field. **Nothing reads it yet**: the browser side is stage 2 of the
roadmap, and until it ships the visitor sees the form's own error message with the code. The entry is what
will let the page point at the field.
The check runs over **the whole submission before any action runs**: were it inside the loop, the provider of
whichever field the metadata happened to yield first would already have been called, and billed, for another
field to cancel the submission a moment later. The first refusal is
`SubmissionException(FMDB_015, 422)` carrying the messages, which the servlet writes in a `messages`
array next to `errorCode`, which the browser will anchor on the field exactly as it will the pre-check's
(stage 2; today the response carries the entry and no client reads it).
`messages` joins the servlet's reserved keys: an enricher cannot take it. Warning actions do not run here:
they warned.

The step runs on the dispatcher the submit servlet hands over from `FieldActionRuntime`, a mandatory
static reference: OSGi does not activate the servlet until the runtime is bound, so a submission never
meets a half-started engine. Should the dispatcher be missing all the same, the step logs a warning that
the form's field actions **did not run** — a different fact from "this form has no checks", which is
silent — and the submission goes on.

## Providers, configuration and secrets — the `FieldActionGateway`

A provider's credential goes neither in the JavaScript nor in the repository. As forward targets are
declared, providers are declared in `org.jahia.modules.formidable.cfg`:

```
# --- FIELD ACTIONS ---
# Each entry: id|Label|https://base-url|Credential-Header-Name|credential (the last two together, or neither)
fieldActionProviders=
fieldActionHttpConnectTimeoutSeconds=5
fieldActionHttpRequestTimeoutSeconds=10
fieldActionVerdictCacheTtlSeconds=300      # 0 disables the cache
fieldActionRateLimitPerMinute=30           # 0 disables the endpoint
fieldActionMaxValueLength=512
fieldActionMaxValuesPerField=50            # DISTINCT values of ONE field judged at submission
```

`FormidableConfigService` parses them (`FieldActionProvider`, whose `toString` masks the credential;
`FieldActionSettings`, everything the field actions read, in one piece), the HTTPS-only rule of the
forward targets applied to the base URL. `FormidableFieldActionProvidersInitializer` feeds the
`formidableFieldActionProviders` choicelist (label shown, id stored). `FieldActionGatewayImpl` is the
exported service: it resolves the provider, refuses a path that is absolute, carries a scheme, climbs with
`..` or holds a control character, appends it to the base URL (a base without a trailing slash keeps its
last segment), injects the credential header, applies the timeouts, caps the response body at a million
characters, and never puts the credential in a log line or in the object it returns.

## Security and trust

- **The endpoint is the only entry**, with the submission's posture: `origin: hosted` scope, CSRFGuard
  whitelist, the form read in the visitor's own `live` session (what the caller cannot read is not found),
  a guest refused on a members-only form (`FMDB-009`). Only the walk of a form the visitor has read, and
  the action nodes themselves, are read in a system session — the submitter has no reason to have read
  access to the action nodes. A `hidden.execute` view is renderable through the platform's render servlet
  by anyone who can read the node — which is why the view answers nothing without the engine's request
  attribute, which no URL can set.
- **The captcha is not re-checked at the pre-check** (a token is single-use); the pre-check of a
  captcha-protected form is bounded by the rate limit, the cap and the cache, or switched off.
- **The credential never leaves the engine**: `.cfg` → gateway; the JavaScript sees a provider *id*; the
  repository stores a provider *id*.
- **SSRF**: the gateway only ever calls the configured base URLs; the path is relative-only, on the same
  host and scheme.
- **Abuse of a paid API**: rate limit per client on the pre-check, a cap on the distinct values judged per
  field at submission (`fieldActionMaxValuesPerField`, `FMDB-017` past it, checked over the whole submission
  before anything runs), verdict cache, blocking actions only in the pipeline, `0` to switch the endpoint
  off. The submission path needs its own bound because it judges every value of a field, and nothing else
  limits how many values one field name may carry.
- **The visitor's value never reaches a log line above DEBUG**, whichever way it arrives: an action's
  `detail`, a view's output, or the message of a throwable — `NumberFormatException: For input string: "…"`
  carries it by construction, so the exception's type is logged and the throwable itself goes to DEBUG.
- **Information disclosure**: the visitor gets the contributor's message and a verdict, never the provider's
  response, the action node's identifier or its node type; `detail` stays in the logs. The candidate value
  is never in a URL, nor in a log line at INFO — a view's output, which may echo it in breach of the
  contract, is logged at DEBUG only.
- **The view's output is the verdict object and nothing else**: a lenient reader would let an echoed value
  retire the check onto the `whenUnavailable` default; the strict one fails deterministically.
- **The verdict cache is keyed on the locale** as well as the action and the value: a pre-check in a locale
  where the check passes cannot seed the accept the submission finds in another.
- **The pipeline is the authority, over every non-blank value of every answered, visible field.** The
  pre-check is a courtesy; the submit-time re-check is what protects the data — a browser that skips the
  pre-check meets the same actions. Without a dispatcher the step says so in the logs rather than pass in
  silence.

## Performance

A blur-time call costs one servlet hit, one read of the form in the visitor's session, one action run — a
provider call at most; the walk of the published form that finds the fields runs once a minute per form
and locale (`FieldActionsCache`), not once a call. The verdict cache makes submit free for values already
checked in the same locale; the rate limit bounds a hostile client. The pipeline re-check adds one provider
call per blocking action and non-blank value never pre-checked.

## Decision log

| Date | Decision | Why |
|---|---|---|
| 2026-09-11 | **View, not direct polyglot call** for a JavaScript action | The JS engine exports no package, so `GraalVMEngine.doWithContext` is unreachable without reflection against a private API; the render service is the platform's sanctioned way in, and it gives the view the `server` helpers for free |
| 2026-09-11 | **Verdict + contributor message, not view-authored text** | One place for the visitor's words (the node, i18n, in the Content Editor), one escaping path (the engine), and an action whose author never touches copy |
| 2026-09-11 | **One marker, one property mixin — not a marker per behaviour** | Whether a refusal blocks and what an outage means are the contributor's calls, per form, so they are properties with the safe defaults (`block`, `accept`), not facts of the type. An earlier draft had nine value-kind markers and two behaviour markers: dropped |
| 2026-09-11 | **A dynamic-fieldset switch, not a create-on-first-add list** (HDU) | The feature is turned on in the field's own form, where every other setting of the field is, and the list exists exactly when the switch is on — `autocreated` on the mixin does the creation, the platform's `removeMixin` the deletion. The alternative, a hidden `actions` child on `fmdbmix:formElement` created by the zone's first click, touched the engine marker and left the feature discoverable only from the Page Builder |
| 2026-09-11 | **Re-run in the pipeline, not a signed proof from the pre-check** | A proof token is more machinery and a replay surface; a cached re-check is one lookup |
| 2026-09-21 | **The vocabulary is `fieldAction`, mirroring `formAction`** (HDU) — `fmdbmix:fieldAction`, `fmdb:fieldActionList`, `fmdbmix:fieldActions`, `fmdbmix:fieldActionFeedback`, `FieldAction`, the child named `actions` | More generic than "validator" — a field action may one day do more than validate — and it reads beside the form actions without a second word. It also dissolves the collision with the engine's `FieldValidator` class, the format validation of step 9: nothing new is called a validator |
| 2026-09-21 | **The view is named `hidden.execute`**, not `hidden.validate` (HDU) | The action's code, whatever it does |
| 2026-09-21 | **The server runs the action, twice, from one code** (HDU asked whether the front proxies the back: yes) | The browser only asks; the pre-check endpoint and the pipeline's step 11b share the dispatcher and the verdict cache. Three reasons: the credential stays server-side, the pipeline is the authority, a JavaScript module has no endpoint of its own |
| 2026-09-21 | **Field actions stand alone; `messages[]` is a servlet key** written by the pipeline for both outcomes, the enricher SPI unchanged | The draft made the "action outcomes" design (per-action visitor messages, gating actions) a prerequisite, its `messages` array being the substrate. The refusal needs only the array: the field actions add it, minimal, and action outcomes will write into it. The response-enrichment SPI shipped meanwhile covers accepted submissions only and runs after the actions — a rejection's messages are the pipeline's, not an enrichment's, so `messages` is reserved like `success` and `errorCode` |
| 2026-09-21 | **No dependency on the TypeScript form-actions registry** (#164) | Its SDK (javascript-modules#686) was closed unmerged on 2026-07-22; no registry entry point exists or is scheduled. The view is the entry point, not a workaround |
| 2026-09-21 | **The bundle's default message never falls back to the server's locale** | `ResourceBundle` would serve a French server's `_fr` bundle to an English visitor when no `_en` file exists; the lookup uses the no-fallback control so the base bundle answers. Found by the unit test on a French machine |
| 2026-09-22 | **The endpoint reads the form as the pipeline does** — visitor session, `FMDB-004` for what the caller cannot read, `FMDB-009` for a guest on a members-only form (review of #344) | The first cut resolved the form in a system session, so any published form on the platform, members-only pages included, had its actions runnable by anyone holding the public fid; and no authentication check existed while the pipeline had one. The pipeline's posture, step for step, is the only defensible one |
| 2026-09-22 | **The view's output is exactly one JSON object** — no tolerance for surrounding markup (review of #344) | The lenient reader took the widest span between braces: a view echoing the value let a `{` in the value make the output unparseable, hence unavailable, hence accepted by the CND default. Strict parsing fails on every value, deterministically, where the author sees it |
| 2026-09-22 | **Every non-blank value is judged; the locale is in the cache key; the response names no action node** (review of #344) | The authority must cover what is stored: all values, not the first. A cached accept in one locale must not answer another, since the locale is part of the request. A node UUID and a vendor namespace in the response disclose the checks behind a form the caller may not read |
| 2026-09-22 | **The engine ships no concrete field-action type** (HDU: « je préfère l'avoir en sample et ne pas fournir d'action field par défaut ») | The email domain check was written as a built-in of the engine and moved to the samples module before it shipped. The engine owns the mechanism; which checks a form deserves is a project's decision, and a built-in would have made one for every installation. The samples module is where a third party reads the shape to copy, and it is installed by the test provisioning manifest only |
| 2026-09-22 | **An action judges one value per call**, a multi-valued field one value at a time (HDU, asked whether the values should arrive as an array) | They already are an array where they are parsed; what carries one value is what an action receives. Three reasons to keep it: the pre-check has only one value to offer, since the browser asks while the visitor fills the form and not once it is complete; the verdict cache is keyed by value, which is what lets it be shared between the two entry points and between visitors, where a whole-set key would share nothing; and a third-party action stays "one value in, one verdict out", the same code serving a text field and a group of checkboxes. The cost is one provider call per value, which `fieldActionMaxValuesPerField` bounds |
| 2026-09-22 | **The rendered view's body is stripped of the platform's `jahia:temp` markers** before the strict reader sees it (review of #344) | `URLFilter` wraps a `module`-configuration fragment and `StaticAssetsFilter`, which unwraps it, does not run there. The lenient reader survived it by accident; the strict one would have answered UNAVAILABLE for every JavaScript action, accepted by the CND default. The pattern is copied from `StaticAssetsFilter` rather than the class called: that class drags the rendering stack into the tests for one regular expression |
| 2026-09-22 | **A rejection message interpolates `${value}` and nothing else** (review of #344) | The pre-check knows one field, the submission knows them all; interpolating the others would render the same message complete at submission and full of holes at blur. One contract for both, enforced by the code rather than by advice |
| 2026-09-22 | **The values judged per field are capped** (`fieldActionMaxValuesPerField`, default 50; reviews of #344) | "Every value is judged" turned one submission into one provider call per value, and nothing bounds how many values a field name carries. Distinct values miss the verdict cache and evict everyone else's. Three corrections followed in the same wave: the count is of **distinct** values, since that is what a provider call costs; the check covers **the whole submission before anything runs**, so no field is billed for another to cancel it; and a field whose actions only warn is neither judged nor counted, since `blockingOnly` skips it anyway. The default is above a plausible option count — an "interests" group with thirty boxes is an ordinary form — and the refusal has its own code with a message naming the field, rather than a bare size error the visitor cannot act on |
| 2026-09-22 | **`fmdbmix:fieldActions` drops the `jmix:dynamicFieldset` supertype** (found on the local instance: the switch was nowhere in the Content Editor) | `jmix:dynamicFieldset` extends `jmix:templateMixin`, which the editor reads as "no enable switch"; with no property of its own the fieldset was then not rendered at all, so the feature had no way in. `extends = fmdbmix:formElement` alone makes it dynamic AND switchable. Measured on `forms.editForm`: `visible: false, hasEnableSwitch: false` before, both true after |
| 2026-09-22 | **The walk of the form is cached per form and locale for sixty seconds**, not keyed off the form's `jcr:lastModified` (review of #344) | A change to an action or a field touches that node's `jcr:lastModified`, not the form root's: the core `LastModifiedListener` writes the first node up the hierarchy that carries `mix:lastModified`, which a `jnt:content` action node is itself (jahia-impl 8.2.4 sources, `updateLastModifiedProperties`), so the root's date would serve stale actions after a republish. A short TTL is exact within the minute and needs no invalidation; the pipeline walks fresh every time |

## Open questions

- Credentials as `${env:…}` references in `.cfg`: depends on the platform's configuration interpolation
  being enabled; to be verified, otherwise the file holds the literal.
- Whether the pre-check should also run for a field hidden by conditional logic at the moment of the call
  (today: yes, the browser decides what it asks about; the pipeline skips hidden fields).
- A `warn` refusal from a `submit`-triggered action: shown once and the submission proceeds, or a confirm
  step? v1: shown, proceeds.
- **A rule about the whole set of a multi-valued field has no home**: "at most three topics", "these two
  options cannot be picked together". An action is handed one value and never its siblings, on purpose (see
  the decision log). Such a check would need either a kind of its own or a second value in the request
  carrying the field's other values — and, for the pre-check, a browser that sends the set rather than the
  one value it just changed. Not built, and not implied by anything shipped.
- A captcha-protected form's pre-check is an uncaptcha'd oracle onto the provider, bounded by the rate limit
  only. A per-action "at submission only" setting (no pre-check, the pipeline alone runs it, behind the
  captcha) would let a contributor close it for one paid check without switching the endpoint off for the
  whole platform. Not built: the trigger `submit` still pre-checks, on purpose, so the visitor sees the
  refusal before the page reloads.

## Roadmap

1. **Engine** — CND, `FieldAction` and `FieldActionGateway` API, configuration keys and provider list,
   dispatcher, endpoint, pipeline step 11b, `FMDB-015`/`FMDB-016`, `messages[]`, unit tests; the samples
   module's `fmdbsample:blockedWordsAction`. **Shipped 2026-09-21** (this page's pull request).
2. **Library** — `readFieldActionRequest`, `fieldActionResult`, `fieldActionAttributes` (spreads
   `data-fmdb-field-action="blur" | "submit"` on the control), `useFieldActions` (blur/submit → endpoint →
   `setCustomValidity` + the field-error rendering, `settleFieldActions(form)` before the XHR).
3. **Elements** — the zone under the field in edit mode (`FieldActionList` and `FieldAction`
   `hidden.authoring` views, replicas of the actions zone, `AddContentButtons` offering every deployed
   type through `ActionSummaryService.describeType`), the form island wiring, the styling hooks
   `fmdb-form-warning` and `fmdb-field-action-pending` in `docs/styling/`.
4. **Samples and the JavaScript path** — a sample action written as a `hidden.execute` view, which is the only
   thing that will exercise the render chain end to end (the wrapping the strict reader trips on was found by
   reading the platform, not by running it); a Cypress spec on the sample actions against the endpoint and the
   pipeline; the extension how-to case "Adding a field action type"; the `.cfg` keys in `docs/administration/`.
   The two Java samples — blocked words and the email domain check — **shipped**.

## Sources

- `formidable-engine/…/api/FieldAction.java`, `FieldActionRequest.java`, `FieldActionResult.java`,
  `FieldActionGateway.java`; `…/actions/field/` (`FieldActionDispatcher`, `FieldActionCollector`,
  `FieldActionRuntime`, `FieldActionServlet`, `FieldActionGatewayImpl`, `RenderServiceViewRenderer`,
  `ResolvedFieldAction`, `VerdictCache`, `FieldActionsCache`, `RateLimiter`); `servlet/FormSubmissionPipeline.java` (step 11b),
  `servlet/FormFieldMetadataCollector.java` (`Result.fieldActions`), `servlet/FormSubmitServlet.java`
  (`messages`, `RESERVED_KEYS`); `config/FormidableConfig.java`, `config/FormidableConfigService.java`
  (`FieldActionProvider`, `FieldActionSettings`); `choicelist/FormidableFieldActionProvidersInitializer.java`;
  `META-INF/definitions.cnd`, `META-INF/configurations/org.jahia.modules.formidable.cfg`,
  `org.jahia.bundles.api.authorization-formidable-engine.yml`, `org.jahia.modules.jahiacsrfguard-formidable.cfg`.
- `jahia-test-module/formidable-test-module-samples-java/…/actions/field/BlockedWordsFieldAction.java` and
  `…/actions/field/EmailDeliverabilityFieldAction.java`, their CND, their labels and their icons.
- [Form submission flow](form-submission-flow.md), [CND module ownership](cnd-module-ownership.md),
  [Custom validation](custom-validation.md), the extension guide's action case, issue #341.
- Platform: `RenderService.render(Resource, RenderContext)`, `AggregateCacheFilter` (expiration lookup
  order: request attribute, node, view), `CacheFilter` (caches only `expiration > 0`),
  `javascript-modules-engine 1.3.0-SNAPSHOT` manifest (no own package exported).
