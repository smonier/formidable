package org.jahia.modules.formidable.engine.actions.field;

import org.json.JSONObject;

import java.util.Locale;

/**
 * What the visitor is told about one field action's refusal, as the response carries it — in the {@code messages}
 * array of the pre-check answer and of a refused submission alike, so the browser anchors both the same way.
 *
 * <p>The response carries the level, the message and the field, nothing else: the action's node identifier and
 * node type stay in the logs and the tests, since a caller who cannot read the form must learn neither which
 * nodes stand behind it nor whose checks they are.</p>
 *
 * @param level      {@link Level#ERROR} for a refusal that blocks, {@link Level#WARNING} for one that only warns
 * @param html       the contributor's message, interpolated and escaped server-side: ready to render
 * @param field      the field's node name, what the browser anchors the message on
 * @param actionId   the action node's UUID — for the logs and the tests, never in the response
 * @param actionType the action's node type — for the logs and the tests, never in the response
 */
public record FieldActionMessage(Level level, String html, String field, String actionId, String actionType) {

    /** How the browser shows the message. */
    public enum Level {
        ERROR, WARNING;

        /** The JSON value: lower case, as the page's CSS hooks read it. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The response's view of the message: {@code level}, {@code html}, {@code field}. */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("level", level.key());
        json.put("html", html == null ? "" : html);
        json.put("field", field);
        return json;
    }
}
