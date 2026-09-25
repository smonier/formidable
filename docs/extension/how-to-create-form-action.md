# How to Create a `FormAction`

This is the single guide for adding a server-side Formidable action, whether the action lives inside `formidable-engine` or in another Jahia module.

Relevant runtime files:

- `formidable-engine/src/main/java/org/jahia/modules/formidable/engine/api/FormAction.java`
- `formidable-engine/src/main/java/org/jahia/modules/formidable/engine/api/FormActionException.java`
- `formidable-engine/src/main/java/org/jahia/modules/formidable/engine/api/FmdbNodeType.java`, `FmdbMixin.java`, `FmdbProperty.java`, `FmdbNodeName.java` — the names of the content model, to name qualified rather than to retype (see `docs/architecture/cnd-module-ownership.md`)
- `formidable-engine/src/main/java/org/jahia/modules/formidable/engine/servlet/FormSubmissionPipeline.java`
- `formidable-engine/src/main/java/org/jahia/modules/formidable/engine/servlet/FormSubmitServlet.java`
- `formidable-engine/src/main/resources/META-INF/definitions.cnd`

## Runtime model

At submission time:

1. `FormSubmitServlet` receives the POST request.
2. `FormSubmissionPipeline` resolves the submitted form and its configured action nodes.
3. Each action node is matched to the first OSGi service implementing `FormAction` whose `getNodeType()` matches the node primary type.
4. `execute(...)` is called.

That means a new action always needs:

- a JCR node type extending `fmdbmix:formAction`
- a Java OSGi component implementing `FormAction`
- authoring support so contributors can configure the action on a form

## Where to define it

If the action belongs to another Jahia module:

- declare the node type in that module's own `definitions.cnd`
- add a Maven dependency on `formidable-engine` (compile-time: the SPI)
- make sure the bundle imports `org.jahia.modules.formidable.engine.api`
- declare the DEPLOY-time dependency too — without it the CND cannot resolve
  `fmdbmix:formAction` and the module fails to register its definitions:
  - Java module: `<jahia-depends>formidable-engine</jahia-depends>` in the pom's
    `<properties>`
  - JS module: `"module-dependencies": "default,formidable-engine=0.5"` in the
    package.json `jahia` section (pin the version, as `formidable-extended-inputs` does)

If the action belongs to this repository, the same rules apply, except the node type and handler live directly in `formidable-engine`.

No Formidable source change is required for external actions as long as the SPI contract is respected.

## Step 1: Declare the action node type

```cnd
<jnt = 'http://www.jahia.org/jahia/nt/1.0'>
<fmdbmix = 'http://www.jahia.org/jahia/fmdb/mix/1.0'>
<mymod = 'http://www.example.com/jahia/mymod/nt/1.0'>

[mymod:webhookAction] > jnt:content, fmdbmix:formAction, mix:title
 - endpointId (string) indexed=no
```

Rules:

- the type must extend `fmdbmix:formAction`
- `jnt:content` is the normal base type
- `mix:title` is recommended for author usability
- if the action **never writes to the repository**, also extend `fmdbmix:readOnlyCompatibleAction`
  so forms using it keep working while the platform is in read-only maintenance mode. Without
  this mixin the action is presumed to write, and its forms are blocked with `FMDB-014` during
  maintenance (see `docs/architecture/form-submission-flow.md`, *Read-only maintenance mode*). Only declare
  it if the action genuinely performs no JCR write — a lying declaration is caught at runtime
  by the repository's own read-only rejection, which also surfaces as `FMDB-014`.

## Step 2: Implement the Java handler

Example:

```java
package org.example.jahia.modules.mymod.actions;

import org.jahia.modules.formidable.engine.api.FormAction;
import org.jahia.modules.formidable.engine.api.FormActionException;
import org.jahia.modules.formidable.engine.api.SubmittedFile;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.osgi.service.component.annotations.Component;

import javax.jcr.RepositoryException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

@Component(service = FormAction.class)
public class WebhookFormAction implements FormAction {

    private static final Logger logger = LoggerFactory.getLogger(WebhookFormAction.class);

    @Override
    public String getNodeType() {
        return "mymod:webhookAction";
    }

    @Override
    public void execute(
            JCRNodeWrapper actionNode,
            HttpServletRequest req,
            JCRSessionWrapper session,
            Map<String, List<String>> parameters,
            List<SubmittedFile> files
    ) throws FormActionException {
        try {
            String endpointId = actionNode.getProperty("endpointId").getString();

            logger.info("Forwarding submission to target '{}' with {} field(s).", endpointId, parameters.size());
        } catch (RepositoryException e) {
            throw new FormActionException(
                    "Could not read the action configuration.",
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    e
            );
        }
    }
}
```

## Step 3: Match `getNodeType()` exactly

This is the most important rule.

If your node type is:

```cnd
[mymod:webhookAction] > jnt:content, fmdbmix:formAction, mix:title
```

then your handler must return:

```java
@Override
public String getNodeType() {
    return "mymod:webhookAction";
}
```

If the string does not match exactly, the action will never be executed.

## Step 4: Understand the input you receive

Inside `execute(...)`:

- `actionNode` contains the current action configuration stored in JCR
- `parameters` contains the validated text fields as `Map<String, List<String>>`
- `req` is the current servlet request
- `session` is the current JCR session

Actions run in the submission pipeline, outside the Jahia page-rendering flow. No `RenderContext` is available in this SPI.

Example for reading action configuration:

```java
String targetId = actionNode.getProperty("targetId").getString();
```

Example for reading submitted values:

```java
List<String> emailValues = parameters.get("email");
String email = (emailValues != null && !emailValues.isEmpty()) ? emailValues.get(0) : "";
```

## Step 5: Access uploaded files if needed

If your action needs uploaded files, read them from the `files` argument passed by the submission pipeline:

```java
for (SubmittedFile file : files) {
    logger.debug("Received uploaded file '{}' ({})", file.originalName(), file.mimeType());
}
```

The SPI-level file contract exposes:

- `fieldName`
- `originalName`
- `mimeType`
- `data`

## Step 6: Fail correctly with `FormActionException`

Throw `FormActionException` when the action must stop the pipeline and return an error to the client.

Examples:

```java
throw FormActionException.badRequest("Missing required field 'email'.");
```

```java
throw FormActionException.serverError("MailService is unavailable.");
```

```java
throw new FormActionException("Forward target returned an error.", 502);
```

> **Caveat — Jahia MailService:** `MailService.sendMessage()` queues the message through
> an Apache Camel route. SMTP delivery failures on the asynchronous delivery path are
> logged by Jahia/Camel and do not propagate back to the caller. A `try/catch` around
> `sendMessage()` only handles synchronous failures raised while invoking the mail
> service. To guard against a missing SMTP configuration, check `mailService.isEnabled()`
> before calling `sendMessage()`.

## Step 7: Add authoring support

The runtime code is not enough by itself. Contributors still need to be able to create and configure the action node.

At minimum, authors must be able to:

1. create a node of your action type
2. edit its properties
3. create or place it under the form's `actions` child node (`fmdb:actionList`)

For an external action, this authoring support also belongs in that module.

## Security guidance

A custom action is trusted server-side code. Do not assume it is safe just because Formidable already parsed the form.

Recommended practices:

- validate action configuration read from JCR
- prefer logical identifiers over raw URLs or secrets stored in content
- keep outbound targets in operator-managed configuration when possible
- use strict allowlists for external integrations
- do not log sensitive submitted values
- return generic error messages for user-facing failures

## Troubleshooting

If your action does not run, check:

1. the class is annotated with `@Component(service = FormAction.class)`
2. the OSGi component is active
3. the node type extends `fmdbmix:formAction`
4. `getNodeType()` exactly matches the primary node type
5. the action node is actually configured on the form
6. the module imports the Formidable action API package correctly
7. no earlier action in the pipeline already failed

Useful runtime symptom:

- if the pipeline logs that no handler exists for your action type, the node was found but no active `FormAction` service matched it

## Related examples in this repository

Use these implementations as references:

- `formidable-engine/src/main/java/org/jahia/modules/formidable/engine/actions/form/email/SendEmailNotificationFormAction.java`
- `formidable-engine/src/main/java/org/jahia/modules/formidable/engine/actions/form/forward/ForwardSubmissionFormAction.java`
- `formidable-engine/src/main/java/org/jahia/modules/formidable/engine/actions/form/storage/SaveToJcrFormAction.java`

## What the Page Builder shows about your action

A form placed on a page lists its actions in an authoring zone (edit mode only), one card per
action: the type icon, the title, the action's *key parameter* and, smaller, the type description.
All of it comes from your declarations, nothing to register, and all of it is resolved the way
the Content Editor resolves it — in the editor's UI language, through the platform:

- **icon**: `icons/<type>.png` in your module, the one the Content Editor shows; a type shipping
  none gets a supertype's, `fmdbmix:formAction`'s in the end, as everywhere else in jContent;
- **description**: the `<type>.ui.tooltip` key of your resource bundle — the bundle your module
  declares, whatever its name and encoding, with a site's own rewording of the key honoured first,
  as in the editor;
- **key parameter**: the first property your type declares in its own CND after `jcr:title` — a
  re-declaration of a property inherited from one of your mixins counts, a property merely
  inherited does not — that a contributor reads at a glance: a single string edited as a small
  text or a `choicelist` (shown by its label). Long texts, flags, numbers, references and
  multi-valued properties are skipped, and so is a type that declares nothing telling (Save to
  JCR). Order your CND so that the property that tells two instances of your action apart comes
  first.

To shape your own card instead, register a `hidden.authoring` view on your action type in a
JavaScript module: Formidable's card view sits below the default priority, so yours wins at the
default one (the engine picks among the candidate views of a node — its type's and its mixins' —
by priority first, then by module name). A view in a Java module cannot take the card over:
JavaScript views are resolved ahead of JSP ones.

