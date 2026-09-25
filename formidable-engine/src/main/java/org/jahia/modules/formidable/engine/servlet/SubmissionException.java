package org.jahia.modules.formidable.engine.servlet;

import org.jahia.modules.formidable.engine.actions.field.FieldActionMessage;

import java.util.List;

/**
 * Thrown by any step in {@link FormSubmissionPipeline} when the submission must be rejected.
 * Carries an {@link ErrorCode} (returned to the client) and an internal message (logged only) — and, for a
 * refusal by a field action, the messages the visitor reads, which the response carries in {@code messages}.
 */
final class SubmissionException extends Exception {

    final ErrorCode errorCode;
    final int actionsCompleted;
    final int actionsTotal;
    /** 0 = none: the error code's own status applies. */
    private final int httpStatusOverride;
    /** What the visitor is told, anchored on fields; empty for every rejection but a field action's. */
    private final transient List<FieldActionMessage> messages;

    SubmissionException(ErrorCode errorCode, String internalMessage) {
        this(errorCode, internalMessage, -1, -1, null);
    }

    /** A field action's refusal: FMDB-015 with the messages the browser anchors on the refused field. */
    SubmissionException(ErrorCode errorCode, String internalMessage, List<FieldActionMessage> messages) {
        super(internalMessage);
        this.errorCode = errorCode;
        this.actionsCompleted = -1;
        this.actionsTotal = -1;
        this.httpStatusOverride = 0;
        this.messages = messages == null ? List.of() : List.copyOf(messages);
    }

    SubmissionException(ErrorCode errorCode, String internalMessage, Throwable cause) {
        this(errorCode, internalMessage, -1, -1, cause);
    }

    SubmissionException(ErrorCode errorCode, String internalMessage, int actionsCompleted, int actionsTotal) {
        this(errorCode, internalMessage, actionsCompleted, actionsTotal, null);
    }

    SubmissionException(
            ErrorCode errorCode,
            String internalMessage,
            int actionsCompleted,
            int actionsTotal,
            Throwable cause
    ) {
        this(errorCode, internalMessage, actionsCompleted, actionsTotal, cause, 0);
    }

    /**
     * @param httpStatusOverride response status to use instead of the error code's own —
     *                           the status a {@link org.jahia.modules.formidable.engine.api.FormActionException}
     *                           chose, which the SPI promises is forwarded to the client
     */
    SubmissionException(
            ErrorCode errorCode,
            String internalMessage,
            int actionsCompleted,
            int actionsTotal,
            Throwable cause,
            int httpStatusOverride
    ) {
        super(internalMessage, cause);
        this.errorCode = errorCode;
        this.actionsCompleted = actionsCompleted;
        this.actionsTotal = actionsTotal;
        this.httpStatusOverride = httpStatusOverride;
        this.messages = List.of();
    }

    int httpStatus() {
        return httpStatusOverride > 0 ? httpStatusOverride : errorCode.httpStatus;
    }

    /** The messages the response carries; empty for every rejection but a field action's. */
    List<FieldActionMessage> messages() {
        return messages;
    }

    boolean hasActionProgress() {
        return actionsCompleted >= 0 && actionsTotal > 0;
    }
}
