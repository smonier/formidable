# How to enrich the submission response

For the developer of a Jahia module that needs the browser to learn something once a form was
accepted: an identifier the page's scripts wait for, values a tracker should send, a token. The
engine's submit servlet answers `{"success": true}` on an accepted submission; a
`SubmissionResponseEnricher` adds entries to that body.

## When it runs

After the pipeline accepted the submission and **every action succeeded**. A rejected submission
(`4xx`, `5xx`) is never enriched: its body carries `success: false` and the error code only. The
form island then dispatches a bubbling `formidable:submitted` DOM event on the `<form>` element,
with `{formId, response}` as detail — `response` being the parsed body, your entries included —
so a script that knows nothing of the island can act on your block.

## Step 1: Implement the service

```java
@Component(service = SubmissionResponseEnricher.class, immediate = true)
public class MyEnricher implements SubmissionResponseEnricher {

    @Override
    public Map<String, Object> enrich(AcceptedSubmission submission) {
        if (!appliesTo(submission.siteKey())) {
            return Map.of();                       // nothing to add: an empty map, never null
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("formId", submission.formNode().getIdentifier());
        block.put("fields", submission.parameters());
        return Map.of("mymodule", block);          // one top-level key, named after your module
    }
}
```

Register it as an OSGi service (the `@Component` above does); the servlet tracks every
`SubmissionResponseEnricher` dynamically, no configuration needed. Two things your module declares,
the same two a [`FormAction`](how-to-create-form-action.md) needs:

```xml
<properties>
  <!-- deploy time: your module starts after the engine, whose SPI it implements -->
  <jahia-depends>formidable-engine</jahia-depends>
</properties>
```

```xml
<!-- compile and resolution time, in the bnd instructions -->
<Import-Package>org.jahia.modules.formidable.engine.api;version="[0.5,1)",*</Import-Package>
```

## Step 2: Answer only for the submissions that concern you

**The servlet asks every registered enricher, for every accepted submission of the platform.** It
does not know which module cares about which form: that question is yours to answer, and answering
it is the difference between an enricher and a module that puts its key in every form's response.

Nothing in the SPI decides it for you, and there is no configuration to fill in — the gate is a line
of your own at the top of `enrich`, returning an empty map when the submission is not yours. The two
enrichers in this repository gate on different things, because they are not asking the same question:

| Enricher | Its question | Why that one |
|---|---|---|
| `SubmissionEventEnricher` (jExperience integration) | does this site run jExperience *and* hold settings for it? | only the pair means there is a tracker on the page, and without a tracker nothing reads the block |
| `SampleResponseEnricher` (samples module) | does this form carry my mixin? | a module must not add its block to a form nobody pointed at it |

A site, a node type, a mixin the author adds, an OSGi configuration — whatever your module already
knows. What matters is that the answer is cheap: it runs on every accepted submission of the
platform, inside the request.

## Step 3: Understand the input you receive

`AcceptedSubmission` is a record:

| Accessor | Value |
|---|---|
| `formNode()` | The form, read in the **live** workspace in the submission's locale |
| `siteKey()` | The key of the form's site |
| `locale()` | The locale of the submission (the `lang` parameter of the request) |
| `parameters()` | The accepted values by field name — declared, validated, non-file fields, each value as the submitter sent it. A deep snapshot, immutable down to its lists: sorting or clearing one throws, and would otherwise change what the next enrichers read |

Files never reach an enricher: they belong to the actions.

## Step 4: Respect the rules of the body

- **Keys are top-level.** Name yours after your module. `success`, `errorCode`, `actionsCompleted`,
  `actionsTotal` and `messages` belong to the servlet: an enricher writing one of them is logged and that key
  ignored, the rest of its entries kept. A key **another enricher already wrote** is refused the same
  way, the first writer keeping it — which is what makes naming the key after your module a
  requirement rather than a convention: a collision costs the second module its block, silently
  except for a line in the server log, and the binding order of two OSGi services is not yours to
  predict.
- **Values are plain Java.** `Map`, `Collection`, `String`, `Number`, `Boolean`, nested as needed;
  they are serialised as JSON. No JCR node, no exception, nothing that is not data.
- **Never fail the submission.** The actions ran; an exception thrown by an enricher is logged and
  its entries left out, the `200` stands. That holds for every value `org.json` can refuse. It does
  not hold for a structure that contains itself, whose serialisation ends in a `StackOverflowError`
  no `catch` on the way out is meant to swallow: do not return one. Keep the work light: it runs in the request, before the
  visitor sees the success message.
- **Nothing personal that the page could not already know.** The body goes to the browser that
  submitted the form, and only there; still, return what the page needs, not the whole
  submission, when a subset does.

## Related example in this repository

[`SampleResponseEnricher`](../../jahia-test-module/formidable-test-module-samples-java/src/main/java/org/jahia/test/modules/formidable/samples/enricher/SampleResponseEnricher.java)
in the samples module is this page in runnable form, and the one to copy: it shows the gate a real
enricher needs. An enricher is asked for **every** accepted submission of the platform, so one that
answers unconditionally puts its key in every form's response; the sample answers only for a form
carrying its own mixin, which the author adds. Its Cypress spec
(`tests/cypress/e2e/security/47-response-enricher.cy.ts`) asserts both halves.

`formidable-jexperience-engine`'s `SubmissionEventEnricher` is the other example: it returns
`jexperience: {formId, fields}` — the form's UUID and the accepted values of the fields the visitor
answered, minus the ones the author marked sensitive — for the client script that sends the form event through
jExperience's tracker; see [jExperience integration](../architecture/jexperience-integration.md),
"Submitting".
