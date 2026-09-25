package org.jahia.modules.formidable.engine.api;

/**
 * What a field action answers: a verdict, and a word for the logs. Never the text the visitor reads — that
 * is the contributor's, on the node.
 *
 * @param verdict {@link Verdict#ACCEPT}, {@link Verdict#REJECT} or {@link Verdict#UNAVAILABLE}
 * @param detail  a machine word explaining the verdict ({@code "undeliverable"}, {@code "provider 503"}…),
 *                for the logs and nothing else; may be {@code null}
 */
public record FieldActionResult(Verdict verdict, String detail) {

    /** The three answers a check can give. */
    public enum Verdict {
        /** The value passes. */
        ACCEPT,
        /** The value is refused: the contributor's message is shown, and the submission is blocked or warned as the contributor set. */
        REJECT,
        /** The check could not run — the service behind it did not answer, the action failed: the contributor's {@code whenUnavailable} decides. */
        UNAVAILABLE
    }

    public FieldActionResult {
        if (verdict == null) {
            throw new IllegalArgumentException("a field action result needs a verdict");
        }
    }

    public static FieldActionResult accept() {
        return new FieldActionResult(Verdict.ACCEPT, null);
    }

    public static FieldActionResult reject(String detail) {
        return new FieldActionResult(Verdict.REJECT, detail);
    }

    public static FieldActionResult unavailable(String detail) {
        return new FieldActionResult(Verdict.UNAVAILABLE, detail);
    }
}
