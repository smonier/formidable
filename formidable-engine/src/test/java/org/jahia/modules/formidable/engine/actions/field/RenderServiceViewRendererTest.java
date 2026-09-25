package org.jahia.modules.formidable.engine.actions.field;

import org.jahia.modules.formidable.engine.api.FieldActionRequest;
import org.json.JSONObject;
import org.jahia.services.content.JCRNodeWrapper;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * What the render chain hands back, and what the dispatcher is allowed to see of it.
 */
class RenderServiceViewRendererTest {

    /** The markers Jahia's URLFilter puts around every fragment it handles; nothing strips them on a module render. */
    private static final String START = "<!-- jahia:temp value=\"URLParserStart6f3a1c2b\" -->";
    private static final String END = "<!-- jahia:temp value=\"URLParserEnd6f3a1c2b\" -->";

    @Test
    void theViewsBodyLosesThePlatformsTempMarkersSoTheStrictReaderSeesTheObjectAlone() {
        // Verifies the one thing standing between a JavaScript field action and its verdict: the render chain wraps
        // the fragment in jahia:temp comments (URLFilter applies on a module configuration, StaticAssetsFilter, which
        // would remove them, does not), and the dispatcher's reader requires the whole body to be one JSON object.
        // Unstripped, every such action would answer UNAVAILABLE — accepted by the CND default, silently.
        String verdict = "{\"verdict\":\"reject\",\"detail\":\"undeliverable\"}";

        String body = RenderServiceViewRenderer.body(START + verdict + END);

        assertEquals(verdict, body);
        assertEquals(FieldActionResultVerdict.REJECT, verdictOf(body));
    }

    @Test
    void anEmptyRenderAndAnUnwrappedOneComeBackUnharmed() {
        // Verifies the two neighbours of the case above: a view that rendered nothing stays nothing, and a body the
        // chain did not wrap is handed over as it is — the stripping must not be a second lenient reader.
        assertEquals("{\"verdict\":\"accept\"}", RenderServiceViewRenderer.body("  {\"verdict\":\"accept\"}\n"));
        assertEquals("", RenderServiceViewRenderer.body(START + END));
        assertEquals(null, RenderServiceViewRenderer.body(null));
    }

    @Test
    void whatRenderReturnsHasGoneThroughTheStripping() throws Exception {
        // Verifies the ROUTING, which the two tests above cannot see: they call body() directly, so dropping it from
        // render() leaves them green while every JavaScript-written field action goes back to answering UNAVAILABLE.
        // The render call itself is the seam; the node, the site and the session are the least a Resource needs.
        String wrapped = START + "{\"verdict\":\"accept\"}" + END;

        RenderServiceViewRenderer renderer = new RenderServiceViewRenderer((node, req, resp) -> wrapped);
        String body = renderer.render(mock(JCRNodeWrapper.class),
                new FieldActionRequest("f1", "email", "ada@example.com", Locale.ENGLISH),
                mock(HttpServletRequest.class), mock(HttpServletResponse.class));

        assertEquals("{\"verdict\":\"accept\"}", body);
    }

    @Test
    void thePayloadCarriesTheRequestTheViewReads() {
        // Verifies the other half of the contract: what the engine puts in the request attribute for the view.
        JSONObject payload = RenderServiceViewRenderer.payload(new FieldActionRequest("f1", "email", "ada@example.com", Locale.FRENCH));

        assertEquals("f1", payload.getString("formId"));
        assertEquals("email", payload.getString("fieldName"));
        assertEquals("ada@example.com", payload.getString("value"));
        assertEquals("fr", payload.getString("locale"));
    }

    /** The dispatcher's own reader, to prove the two ends meet. */
    private static FieldActionResultVerdict verdictOf(String body) {
        return switch (FieldActionDispatcher.parse(body).verdict()) {
            case REJECT -> FieldActionResultVerdict.REJECT;
            case ACCEPT -> FieldActionResultVerdict.ACCEPT;
            case UNAVAILABLE -> FieldActionResultVerdict.UNAVAILABLE;
        };
    }

    private enum FieldActionResultVerdict { ACCEPT, REJECT, UNAVAILABLE }
}
