package com.crystalgui.text.lang;

import javax.annotation.Nullable;

/**
 * An answer, and the document version it describes.
 *
 * <h3>Why every asynchronous answer carries one</h3>
 *
 * <p>A language engine is asked a question about a document and answers some milliseconds later, by which
 * time the document has usually moved. Without a version stamp the answer is indistinguishable from a
 * current one: offsets still parse, ranges are still in bounds, and the result is confidently about text
 * that is no longer there. That failure is silent — it is why {@code TextBuffer.version()} exists at all
 * (see the version spine in {@code plan/lang-stack.md} §8), and this type is what carries it across the seam.</p>
 *
 * <h3>The consumer picks the staleness policy, not the producer</h3>
 *
 * <p>There is no single right answer to "the version moved, now what", so this type deliberately does not
 * choose. Three policies are in use and each is correct for its consumer:</p>
 *
 * <ul>
 *   <li><b>Discard</b> — a hover or a go-to-definition. The user asked about a specific character; if the
 *       document changed under the request, the answer is about a different character and showing it is
 *       worse than showing nothing.</li>
 *   <li><b>Keep, adjusted</b> — diagnostics. They are inherently one compile behind (see
 *       {@link com.crystalgui.text.diagnostic.Diagnostic}, which stores rows rather than offsets for
 *       exactly this reason) and a squiggle that vanishes on every keystroke is worse than one that lags.</li>
 *   <li><b>Keep, per line</b> — semantic tokens. A line the edit did not touch still has the right colours,
 *       so dropping the whole set on any edit makes the file flicker to lexer colouring as you type.</li>
 * </ul>
 *
 * <p>So the rule is: a producer stamps, a consumer compares. {@link #isFresh} is the comparison; nothing
 * here does it for you.</p>
 *
 * @param version the document version this was computed against — {@link com.crystalgui.text.TextBuffer#version()}
 * @param value   the answer, or null for "there is genuinely nothing here"
 */
public record Versioned<T>(long version, @Nullable T value) {

    public static <T> Versioned<T> of(long version, @Nullable T value) {
        return new Versioned<>(version, value);
    }

    /** No answer — the offset names nothing, or the engine cannot say. Distinct from a failed request. */
    public static <T> Versioned<T> none(long version) {
        return new Versioned<>(version, null);
    }

    /** Whether this still describes {@code currentVersion}. */
    public boolean isFresh(long currentVersion) {
        return version == currentVersion;
    }

    public boolean isPresent() {
        return value != null;
    }

    /** The value, or {@code fallback} when absent. */
    public T orElse(T fallback) {
        return value == null ? fallback : value;
    }
}
