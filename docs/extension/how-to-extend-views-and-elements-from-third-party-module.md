# How to Extend Formidable Views and Add New Form Elements from a Third-Party Module

This guide explains how to extend Formidable rendering from another Jahia module.

It covers three different cases:

1. adding a new view for an existing Formidable node type
2. adding a new custom form element type
3. adding a contributor setting to a built-in field, shown in that field's own editor form

The first two cases do not follow the same rendering contract.

## Module dependencies

A third-party module that extends Formidable rendering should depend on:

- `formidable-elements`
- `formidable-engine`

Reason:

- `formidable-elements` provides the rendered node types and default views
- `formidable-engine` owns runtime mixins such as `fmdbmix:formElement` and `fmdbmix:formContainer`

How to declare it — the DEPLOY-time dependency, not (only) a build one; without it your
CND cannot resolve those mixins and the module fails to register its definitions:

- Java module: `<jahia-depends>formidable-elements,formidable-engine</jahia-depends>`
  in the pom's `<properties>`
- JS module: `"module-dependencies": "default,formidable-elements=0.5,formidable-engine=0.5"`
  in the package.json `jahia` section

This is exactly what `formidable-extended-inputs` declares; pin the versions like it does, to the
release you build against.

In practice, external CND definitions often reuse both layers, directly or indirectly.

## Two rendering contracts

There are two important categories of rendered nodes:

### 1. `fmdbmix:formContainer`

A form container is a node that renders child form nodes.

Current examples in Formidable:

- `fmdb:fieldList`
- `fmdb:fieldset`
- `fmdb:step`

Containers must not render their children with a plain manual loop unless they deliberately reimplement Formidable's logic contract.

They should delegate child rendering to the built-in `hidden.logic` view on `fmdbmix:formContainer`.

### 2. `fmdbmix:element`

A form element is a leaf field such as a text input, checkbox, select, or textarea.

Leaf elements do not render child nodes, so they do not need the `hidden.logic` indirection.

They can render their own HTML directly.

## Why containers must use `hidden.logic`

Formidable executes conditional visibility logic at render boundaries, not inside every individual container implementation.

The shared container view:

- wraps each child with `LogicAwareRender`
- serializes logic metadata into `data-fmdb-*` attributes
- applies initial hidden state when a node has logic attached
- preserves explicit `j:view` overrides on child nodes
- applies step-specific fallback behavior for multi-step forms
- hides later steps on first render when the form uses step navigation

That is why `fmdb:form` itself now delegates the rendering of its `fields` child node through `hidden.logic`, and why `fmdb:fieldset` and `fmdb:step` do the same.

If an external container bypasses this shared view, the usual regressions are:

- conditional logic does not hide the child field on first render
- step fallback view resolution is lost
- multi-step initial visibility becomes inconsistent
- child-level `j:view` overrides are easier to break accidentally

## Case 1: add a new view for an existing container

Example: add a `twoColumns` view for `fmdb:fieldset`.

The key point is this:

- your custom container view can control layout
- but child rendering should still go through `hidden.logic`

Example:

```tsx
import {
  AddResources,
  buildModuleFileUrl,
  jahiaComponent,
  Render,
} from "@jahia/javascript-modules-library";
import classes from "./twoColumns.module.css";

interface FieldsetProps {
  "jcr:title"?: string;
}

jahiaComponent(
  {
    componentType: "view",
    nodeType: "fmdb:fieldset",
    name: "twoColumns",
    displayName: "Fieldset - Two columns",
  },
  ({ "jcr:title": title }: FieldsetProps, { currentNode, currentResource }) => {
    // Forward showLogicHidden: jContent's inspection previews pass it down so
    // logic-hidden fields stay visible there — a container that drops it makes
    // its children's conditional fields invisible in the preview drawer.
    const showLogicHidden =
      currentResource.getModuleParams().get("showLogicHidden")?.toString() === "true";

    return (
      <>
        <AddResources type="css" resources={buildModuleFileUrl("dist/assets/style.css")} />
        <fieldset>
          {title && <legend>{title}</legend>}

          <Render
            node={currentNode}
            view="hidden.logic"
            readOnly
            parameters={{
              className: classes.grid,
              childClassName: classes.item,
              ...(showLogicHidden ? { showLogicHidden: "true" } : {}),
            }}
          />
        </fieldset>
      </>
    );
  },
);
```

What this does:

- your view owns the wrapper markup and layout
- `hidden.logic` still owns the rendering contract for child nodes
- the `showLogicHidden` forwarding keeps your children inspectable in jContent's
  preview drawer (the parameter only travels if every container on the way relays it)

## Case 2: add a new custom container type

If your module introduces a new container type, that type should opt into the same contract by using `fmdbmix:formContainer`.

Example CND:

```cnd
<jnt = 'http://www.jahia.org/jahia/nt/1.0'>
<fmdbmix = 'http://www.jahia.org/jahia/fmdb/mix/1.0'>
<mymod = 'http://www.example.com/jahia/mymod/nt/1.0'>

[mymod:panel] > jnt:content, fmdbmix:formElement, fmdbmix:formContainer, fmdbmix:nonSubmittable
 + * (fmdbmix:element) = fmdbmix:element
 + * (fmdbmix:formContent) = fmdbmix:formContent
```

Its view should follow the same pattern as `fieldset`:

```tsx
import { jahiaComponent, Render } from "@jahia/javascript-modules-library";

jahiaComponent(
  {
    componentType: "view",
    nodeType: "mymod:panel",
    name: "default",
  },
  (_props, { currentNode, currentResource }) => {
    const showLogicHidden =
      currentResource.getModuleParams().get("showLogicHidden")?.toString() === "true";

    return (
      <section className="my-panel">
        <Render
          node={currentNode}
          view="hidden.logic"
          readOnly
          parameters={{
            className: "my-panel-content",
            childClassName: "my-panel-item",
            ...(showLogicHidden ? { showLogicHidden: "true" } : {}),
          }}
        />
      </section>
    );
  },
);
```

## Case 3: add a new leaf form element

For a leaf field, there is nothing special to do for `hidden.logic`.

Why:

- a leaf field does not render children
- when that field participates in conditional logic, it is the parent container that wraps it through `LogicAwareRender`
- the field view itself should stay focused on its own markup

In most cases, a custom leaf field should extend `fmdbmix:element`.

Example CND:

```cnd
<jnt = 'http://www.jahia.org/jahia/nt/1.0'>
<fmdbmix = 'http://www.jahia.org/jahia/fmdb/mix/1.0'>
<mymod = 'http://www.example.com/jahia/mymod/nt/1.0'>

[mymod:inputTextLike] > jnt:content, fmdbmix:element
 - required (boolean) = false
 - placeholder (string) i18n indexed=no
 - defaultValue (string) i18n indexed=no
```

Example server view:

```tsx
import { jahiaComponent } from "@jahia/javascript-modules-library";

interface InputTextLikeProps {
  "jcr:title"?: string;
  placeholder?: string;
  defaultValue?: string;
  required?: boolean;
}

jahiaComponent(
  {
    componentType: "view",
    nodeType: "mymod:inputTextLike",
    name: "default",
  },
  (
    {
      "jcr:title": label,
      placeholder,
      defaultValue,
      required,
    }: InputTextLikeProps,
    { currentNode },
  ) => {
    const inputId = `input-${currentNode.getIdentifier()}`;
    const inputName = currentNode.getName();

    return (
      <div className="fmdb-form-group">
        {label && (
          <label htmlFor={inputId} className="fmdb-form-label">
            {label}
          </label>
        )}

        <input
          type="text"
          id={inputId}
          name={inputName}
          className="fmdb-form-control"
          placeholder={placeholder}
          defaultValue={defaultValue}
          required={required}
        />
      </div>
    );
  },
);
```

This is intentionally similar to Formidable's built-in `fmdb:inputText`.

## Case 4: add a contributor setting to a built-in field, in the field's own form

Sometimes the field types are fine and only one rendering choice is missing — the sample
module `formidable-test-module-samples-tsx` lets the contributor place a field's help text
above the field (the built-in rendering), below it, or in both places. Three pieces, none of
them in Formidable itself:

**1. A mixin extending the built-in types** carries the setting. `extends` (not a supertype)
is what a third-party CND can do to types it does not own, and it takes a list. `helpText` is
declared by each concrete field type, not by `fmdbmix:element`, so the list names every type
that has one:

```cnd
[fmdbsamplemix:helpTextPosition] mixin
 extends = fmdb:inputText, fmdb:inputEmail, fmdb:textarea, fmdb:inputNumber, fmdb:inputRange, fmdb:inputDate, fmdb:inputDatetimeLocal, fmdb:inputColor, fmdb:inputFile, fmdb:checkbox, fmdb:radio, fmdb:select
 itemtype = content
 - helpTextPosition (string, choicelist[resourceBundle]) = 'up' autocreated indexed=no < 'up', 'down', 'both'
```

Every type named must exist when your module deploys — the sample leaves out the optional
types of `formidable-extended-inputs` because it does not depend on that module. The value
labels come from your module's bundle, keyed `fmdbsamplemix_helpTextPosition.helpTextPosition.<value>`.

**2. A form override puts the setting where the contributor expects it.** Left alone, an
`extends` mixin shows up in the Content Editor as a fieldset of its own, behind an enable
switch. One override, on the mixin itself
(`settings/jahia-content-editor-forms/forms/fmdbsamplemix_helpTextPosition.json`), moves the
field into the edited field's own fieldset — `<main>` — right under Help text, and keeps the
mixin always activated so the value is saved without a switch to flip:

```json
{
  "nodeType": "fmdbsamplemix:helpTextPosition",
  "priority": 2.0,
  "sections": [
    {
      "name": "content",
      "fieldSets": [
        { "name": "<main>", "fields": [{ "name": "helpTextPosition", "rank": 1.4 }] },
        { "name": "fmdbsamplemix:helpTextPosition", "isAlwaysActivated": true, "hide": true }
      ]
    }
  ]
}
```

How it reads: `<main>` in an override means the fieldset of the type being edited, whichever
form declares the override — so this single file serves the twelve types the mixin extends.
Any field of the form can be pulled into `<main>` this way, whichever fieldset declared it.
The mixin's own fieldset, emptied by the move, would still show as a bare switch: `hide`
takes it off the screen while the editor keeps tracking it, so the mixin is still added on save.
The fields of `<main>` are ranked 1, 2, 3… in declaration order and `helpText` is the first
declared property of every field type (title and system name sit before it with lower ranks),
so 1.4 lands right after Help text — before Required, and before the options mode the choice
fields already place at 1.5. With the mixin always activated, every extended field saved in
the editor gets it — acceptable for a sample, a deliberate choice for a product module.

**3. A view honouring the setting.** Two ways to ship it. A view registered on the built-in
type under a *new name* is opt-in: the contributor picks it in the View chooser
(`fmdb:inputText` is renderable, like every `jnt:content`), as with the fieldset samples. A
`default` view registered with a `priority` above Formidable's — which registers its views at
the default priority, 0 — *takes over*: on every site where your module is enabled, every text
input renders through it, nothing to pick, and the built-in view is no longer reachable there.
The sample takes over (`src/components/Input/Text/default.server.tsx`, `priority: 1`), so the
setting is honoured wherever it is set:

```tsx
jahiaComponent(
  { componentType: "view", nodeType: "fmdb:inputText", name: "default", priority: 1 },
  (props, { currentNode }) => { /* … */ },
);
```

Taking a default view over means owning the whole built-in contract of the field — the HTML
conventions below, the validation-message attributes, and for the text input everything a mask
stands for: the `pattern`, the formatted default and the formatting while typing. None of it is
rewritten: the sample depends on `@jahia/formidable-library`, the package Formidable's own views
are built on (see [HTML conventions for custom fields](#html-conventions-for-custom-fields)), and
imports `HelpText`, `validationDataAttributes`, `maskToPattern` and `applyMask` in the view,
`useMask` in an island of its own (`Text.client.tsx`) for the live mask. The view reads
`helpTextPosition` (absent until the node was saved with the mixin — default to the built-in
placement). One accessibility point
when the help is shown twice: the control describes a single block (`aria-describedby` →
`help-<nodeId>`); the repeat after the field has no id and `aria-hidden="true"`, so a screen
reader hears the help once.

How the engine picks among several views of the same name: every view registered on the
node's primary type *or on any of its supertypes and mixins* is a candidate, and the candidates
are ordered by **priority (highest first), then the module's display name (alphabetical), then
the view key** — the type a view is registered on carries no weight. So a `default` view
registered on the mixin would win or lose against Formidable's on the name of your module,
which is not a rule to build on; **priority is the only deterministic lever**, on the mixin as on
the type. The sample registers its view on `fmdb:inputText` for readability — the setting is
honoured by the type that has the help text — and relies on the priority alone.

## HTML conventions for custom fields

External form fields should follow the same conventions as built-in fields:

- use `currentNode.getName()` as the HTML `name`
- use `input-${currentNode.getIdentifier()}` as the HTML `id`
- keep Formidable CSS hooks such as `fmdb-form-group`, `fmdb-form-label`, and `fmdb-form-control` when possible

Those conventions keep the field compatible with:

- form submission naming
- built-in styling
- Cypress selectors
- label/input linkage

Two more contracts matter for help texts and inline validation errors:

- the help block is a `div.fmdb-form-help` with id `help-<nodeId>`, referenced by the
  control through `aria-describedby`;
- custom validation messages are emitted as `data-fmdb-msg-*` attributes on the control
  (see `docs/architecture/custom-validation.md` for the full attribute table).

Both contracts, and the input-mask behaviour of the text input, are published on npm as
[`@jahia/formidable-library`](https://www.npmjs.com/package/@jahia/formidable-library) — the
package the modules of this repository themselves are built on. Depend on it rather than copying
the markup: `HelpText` and `helpTextId` render the help block, `validationDataAttributes` emits
the attributes from the mixin props, `maskToPattern` and `applyMask` give a masked field its
`pattern` and its formatted default, `useMask` its formatting while typing (in an island of your
module). A change of contract then surfaces as a type error when your module builds, not as a
silent drift. Its [README](../../packages/formidable/README.md) lists the API; take the
version matching the Formidable release you target — the package follows Formidable's version
numbers.

```sh
yarn add @jahia/formidable-library
```

The samples module of this repository declares the same dependency as `"workspace:*"`, which links
the monorepo's own sources: that value is for this repository only — in your module, the command
above (its [README](../../jahia-test-module/formidable-test-module-samples-tsx/README.md) spells out
what to change when copying it).

## When to add more engine mixins

`fmdbmix:element` is enough for a simple text-like field.

If your field also needs engine-side behavior, add the matching semantic mixin from `formidable-engine`.

Examples:

- `fmdbmix:emailField`
- `fmdbmix:fileField`
- `fmdbmix:dateField`
- `fmdbmix:datetimeLocalField`
- `fmdbmix:colorField`
- `fmdbmix:choiceField`
- `fmdbmix:numberField`
- `fmdbmix:booleanField`
- `fmdbmix:textField`

This lets the submission pipeline react to semantics instead of hard-coding your concrete node type name.

One more marker is not a value kind: `fmdbmix:profileMappableField` says the field can be mapped to a
jCustomer profile property. Declare it on every field whose value a profile could hold (never on a file
field): when `formidable-jexperience-engine` is deployed, the field gets the **jExperience** section of
the editor, with the profile properties matching its value kind and cardinality
(`docs/architecture/jexperience-integration.md`). Cardinality follows a `multiple` boolean property, the
convention of the built-in select and email inputs: declare one to be offered multivalued profile
properties while it is on; without it your field is single-valued (the checkbox type is the one exception,
read by name: its cardinality follows its number of choices, as the view renders it).

## Make your field a conditional-logic source

Any field type can let contributors build "show/hide other fields based on my value"
rules. Opting in is CND-only — no JavaScript registration is needed.

### 1. Declare the value kind in your CND

Add the engine semantic mixin matching the kind of values your field produces:

| Mixin | Value kind | Operators offered in the rules editor |
|---|---|---|
| `fmdbmix:choiceField` | choice list | is one of / is not one of |
| `fmdbmix:dateField` | date | before / after / on / between |
| `fmdbmix:numberField` | number | = / ≠ / < / ≤ / > / ≥ / between (numeric comparison) |
| `fmdbmix:booleanField` | boolean (on/off) | is true / is false |
| `fmdbmix:textField` | free text | is filled / is empty / equals / contains |

Example — a rating field (number) and a switch field (boolean):

```cnd
[mymod:rating] > jnt:content, fmdbmix:element, fmdbmix:numberField
 - minValue (double) = 1
 - maxValue (double) = 5

[mymod:switch] > jnt:content, fmdbmix:element, fmdbmix:booleanField
```

The number bounds MUST be named `minValue` / `maxValue`: they are the properties the
server reads to enforce the range at validation time (this is what the shipped
`fmdbext:rating` uses). A `min`/`max` pair is only read on date fields — on a number
field it would leave the range enforced in the browser alone, and a forged submission
outside it would be accepted.

The rules editor discovers eligible sources through these mixins (the
`FORM_TREE_BY_PATH` query checks `isNodeType`), so your field appears in the
source dropdown of every later field, with the operators of its kind.

### 2. Render native named controls (usually nothing to do)

At runtime the browser reads the field's current value generically: every named
form control (`input`, `select`, `textarea`) inside the field's logic wrapper
contributes its value — selected options for selects, checked values for
radios/checkboxes, the raw value otherwise. A rating rendered as
`<input type="number" name={...}>` or radios, and a switch rendered as a single
checkbox, work with zero client code.

Semantics to know:

- **boolean**: a single checkable control reports its checked state; the field
  should submit `"true"` when on and either nothing (native checkbox behavior)
  or an explicit `"false"` (e.g. a yes/no radio pair) when off. Both evaluators
  treat any other non-empty submitted value as on, and empty or `"false"` as off;
  server-side validation accepts `"true"`/`"false"` (case-insensitive) and `"on"`
  (the browser default for a checkbox without a `value` attribute) — anything
  else is rejected at submission.
- **choice**: the editor reads the choice list from the `options` property
  declared by `fmdbmix:manualOptions` (same JSON-encoded `{value, label}` entries
  as the built-in select/radio/checkbox); apply that mixin to your field nodes —
  there is no registration API for a custom choice property (the editor's source
  descriptors are a fixed map inside the engine's admin bundle).
- **text**: emptiness is whitespace-blankness on both evaluators (a value of
  spaces counts as empty); `equals` is an exact match on the raw submitted
  value and `contains` a substring match, both case-sensitive, and both require
  a non-empty expected value (use *is empty* to match the empty case — an empty
  text input submits `""` server-side but exposes no value browser-side, so an
  empty expected value would make the two evaluators disagree).

### 3. Exotic widgets: the `data-fmdb-logic-value` escape hatch

If your widget is not built on native named controls (canvas slider, custom
web component…), expose its current value on any element inside the field —
the runtime reads it instead of probing controls:

```html
<div data-fmdb-logic-value="4">…custom widget…</div>
<!-- multi-value widgets use a JSON array -->
<div data-fmdb-logic-value='["a","b"]'>…</div>
<!-- boolean widgets expose "true" / "false" -->
<div data-fmdb-logic-value="true">…</div>
```

Keep the attribute up to date, and dispatch a bubbling `change` (or `input`)
event on the form after updating it: conditional logic re-evaluates on those
events.

## Rule of thumb

If your custom view renders child form nodes, treat it as a `formContainer` and delegate child rendering to `hidden.logic`.

If your custom view renders a single field and no child form nodes, treat it as a leaf `formElement` and render the HTML directly.
