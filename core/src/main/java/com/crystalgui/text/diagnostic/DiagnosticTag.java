package com.crystalgui.text.diagnostic;

/**
 * Extra meaning about a diagnostic that changes how it is <b>drawn</b> rather than how bad it is — the
 * Language Server Protocol's {@code DiagnosticTag}, and VS Code's {@code MarkerTag}.
 *
 * <h3>Why this is not a severity</h3>
 *
 * <p>"This variable is never read" is not a lesser warning; it is a different <em>kind</em> of statement.
 * Severity answers "how much should this worry you", and these answer "what does the text itself look
 * like now" — unused code is faded out, deprecated code is struck through, and both keep whatever severity
 * their producer gave them. Folding them into the severity ladder would force a choice between showing a
 * squiggle you cannot act on and losing the rendering entirely.</p>
 *
 * <p>A shader graph has an obvious producer for the first: the emitter already knows which properties
 * become uniforms that nothing samples, and which nodes are disconnected from the master.</p>
 */
public enum DiagnosticTag {

    /** Unreachable or unused — rendered faded rather than underlined. */
    UNNECESSARY,

    /** Still works, should not be used — rendered struck through. */
    DEPRECATED
}
