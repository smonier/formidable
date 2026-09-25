package org.jahia.modules.formidable.engine.api;

import org.jahia.services.content.JCRNodeWrapper;

/**
 * A field action written in Java: the check of one field's candidate value, bound to a field-action node
 * type the way {@link FormAction} binds a form-action type — register the implementation as an OSGi
 * service, and {@link #getNodeType()} names the concrete type declared in your CND, a type that takes
 * {@code fmdbmix:fieldAction} as a supertype.
 *
 * <p>The engine runs it twice from one node: at the visitor's request while the form is being filled
 * (the {@code field-action} endpoint, on blur or at submit as the contributor set), and again in the
 * submission pipeline before any form action, for the actions whose refusal blocks. The same code, the
 * same verdict cache. A field action written in JavaScript is a server view named {@code hidden.execute}
 * on the same node type instead; the engine looks a Java service up first and renders the view when none
 * is registered.</p>
 *
 * <p>A field action <strong>answers</strong>: it returns a {@link FieldActionResult} and writes nothing.
 * The words the visitor reads are not its business either — the contributor writes them on the node
 * ({@code rejectionMessage}), the engine interpolates and escapes them; the result's {@code detail} is a
 * word for the logs. An exception escaping {@link #execute} counts as {@link FieldActionResult#unavailable}
 * and is logged: a provider outage is the contributor's call ({@code whenUnavailable}), never a stack trace
 * for the visitor. A call to an external service goes through {@link FieldActionGateway}, so that its
 * credential stays in the engine's configuration.</p>
 *
 * <p>See {@code docs/architecture/field-actions.md}.</p>
 */
public interface FieldAction {

    /** The node type this action implements, for example {@code myco:crmLookupAction}. */
    String getNodeType();

    /**
     * Judges the candidate value.
     *
     * @param actionNode the field-action node, read in {@code live} in a system session: its own properties
     *                   (a provider id, a threshold…) are what the contributor configured
     * @param request    the value under judgement and where it comes from
     * @return the verdict; never {@code null}
     */
    FieldActionResult execute(JCRNodeWrapper actionNode, FieldActionRequest request);
}
