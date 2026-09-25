package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FieldActionRequest;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.RenderException;
import org.jahia.services.render.RenderService;
import org.jahia.services.render.Resource;
import org.json.JSONObject;

import javax.jcr.RepositoryException;
import java.util.regex.Pattern;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The runtime {@link ViewRenderer}: the platform's own way from Java into a JavaScript module. A Jahia JavaScript
 * module cannot expose an HTTP endpoint and the JavaScript modules engine exports none of its packages, so the one
 * sanctioned path is {@link RenderService#render}: the field-action node is rendered with the view named
 * {@value #VIEW}, the request travels in the {@value #REQUEST_ATTRIBUTE} attribute — request attributes are already
 * how the engine talks to the JavaScript views — and the view answers a small JSON body. Both the view's own
 * {@code cache.expiration=0} and the {@code expiration} attribute set here keep a verdict about one value out of
 * the fragment cache, where it would answer every visitor with the first one's result.
 *
 * <p>What the chain adds around that body is removed here: the platform's {@code URLFilter} wraps every fragment
 * it handles in {@code <!-- jahia:temp value="URLParserStart…" -->} markers, and the filter that normally takes
 * them out again ({@code StaticAssetsFilter}) does not run on a {@code module} configuration. The dispatcher's
 * reader requires the whole body to be one JSON object, so those two comments alone would make every
 * JavaScript-written field action unavailable. They are stripped here with the pattern the platform strips them
 * with elsewhere; anything else around the object stays, and is the view's contract violation to fix.</p>
 */
final class RenderServiceViewRenderer implements ViewRenderer {

    /**
     * The platform's render call itself, and the one seam the tests replace: without it nothing could assert that
     * what comes back goes through {@link #body} — the step that removes the chain's own markers, and the one whose
     * absence made every JavaScript-written field action answer UNAVAILABLE.
     */
    @FunctionalInterface
    interface Fragment {
        String of(JCRNodeWrapper actionNode, HttpServletRequest req, HttpServletResponse resp)
                throws RenderException, RepositoryException;
    }

    /** The view a field-action type registers to be written in JavaScript. */
    static final String VIEW = "hidden.execute";
    /** The request attribute carrying the {@link FieldActionRequest} as JSON while the view renders. */
    static final String REQUEST_ATTRIBUTE = "formidable.fieldAction";
    private static final String EXPIRATION_ATTRIBUTE = "expiration";

    private final Fragment fragment;

    RenderServiceViewRenderer() {
        this(RenderServiceViewRenderer::renderThroughPlatform);
    }

    RenderServiceViewRenderer(Fragment fragment) {
        this.fragment = fragment;
    }
    /**
     * The comments Jahia's {@code URLFilter} wraps a fragment in, as the platform's own cleanup matches them:
     * {@code StaticAssetsFilter.CLEANUP_REGEXP = Pattern.compile("<!-- jahia:temp [^>]*-->")} (jahia-impl 8.2.4).
     * Copied rather than called: that filter pulls half the rendering stack in with it, for one regular expression.
     */
    private static final Pattern TEMP_TAG = Pattern.compile("<!-- jahia:temp [^>]*-->");

    @Override
    public String render(JCRNodeWrapper actionNode, FieldActionRequest request, HttpServletRequest req, HttpServletResponse resp)
            throws RenderException, RepositoryException {
        if (req == null || resp == null) {
            return null;
        }
        Object previousPayload = req.getAttribute(REQUEST_ATTRIBUTE);
        Object previousExpiration = req.getAttribute(EXPIRATION_ATTRIBUTE);
        req.setAttribute(REQUEST_ATTRIBUTE, payload(request).toString());
        req.setAttribute(EXPIRATION_ATTRIBUTE, "0");
        try {
            return body(fragment.of(actionNode, req, resp));
        } finally {
            restore(req, REQUEST_ATTRIBUTE, previousPayload);
            restore(req, EXPIRATION_ATTRIBUTE, previousExpiration);
        }
    }

    /**
     * The platform's own render of the view, resource and context and all — resolved at each call, since the
     * dispatcher is built before the render service is necessarily there. Everything here is platform glue, which
     * is why it sits behind the seam rather than in {@link #render}: what a unit test has to hold is that whatever
     * comes back goes through {@link #body}, and a {@code Resource} cannot be built without half the render stack.
     */
    private static String renderThroughPlatform(JCRNodeWrapper actionNode, HttpServletRequest req, HttpServletResponse resp)
            throws RenderException, RepositoryException {
        Resource resource = new Resource(actionNode, "html", VIEW, Resource.CONFIGURATION_MODULE);
        RenderContext context = new RenderContext(req, resp, JCRSessionFactory.getInstance().getCurrentUser());
        context.setSite(actionNode.getResolveSite());
        context.setWorkspace(actionNode.getSession().getWorkspace().getName());
        context.setMainResource(resource);
        context.setServletPath("/cms/render");
        return RenderService.getInstance().render(resource, context);
    }

    /** The view's body: what the render chain produced, without the platform's own temp markers. */
    static String body(String rendered) {
        return rendered == null ? null : TEMP_TAG.matcher(rendered).replaceAll("").trim();
    }

    static JSONObject payload(FieldActionRequest request) {
        JSONObject payload = new JSONObject();
        payload.put("formId", request.formId());
        payload.put("fieldName", request.fieldName());
        payload.put("value", request.value() == null ? "" : request.value());
        payload.put("locale", request.locale() == null ? "" : request.locale().toLanguageTag());
        return payload;
    }

    private static void restore(HttpServletRequest req, String name, Object previous) {
        if (previous == null) {
            req.removeAttribute(name);
        } else {
            req.setAttribute(name, previous);
        }
    }
}
