package com.crystalgui.text.lang;

import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextPoint;

import javax.annotation.Nullable;

/**
 * Where a symbol is declared — what go-to-definition jumps to.
 *
 * <h3>Rows, not offsets, and a resource that may be null</h3>
 *
 * <p>Positions are {@link TextPoint} for the same reason
 * {@link com.crystalgui.text.diagnostic.Diagnostic}'s are: the answer is computed against a snapshot and
 * consumed against the live document, and an offset that is one edit stale points confidently at innocent
 * text while a row that is stale is obviously so. That file's note is the long version.</p>
 *
 * <p>{@code resource == null} means <b>this document</b>. It is a real case rather than a missing value —
 * a local, a parameter, a field of the class being edited, and every symbol a script declares about itself
 * are all declared here — and it is by far the common one. Spelling it as null keeps a same-document jump
 * from needing to know its own identity, which nothing at this layer does.</p>
 *
 * <p>{@link com.crystalgui.text.diagnostic.RelatedInformation} makes the opposite choice and says so:
 * it carries no resource at all, because a diagnostic belongs to one document and nothing could open
 * another. That is still true of diagnostics and is not true here — a Java symbol usually resolves into a
 * type the script does not contain, so the field has to exist even while the only consumer that can act on
 * it is a same-document jump.</p>
 *
 * @param resource where it is declared, or null for the document that was asked
 * @param start    first character of the declaration's name
 * @param end      one past its last character
 */
public record DeclarationSite(@Nullable Resource resource, TextPoint start, TextPoint end) {

    public DeclarationSite {
        if (start == null || end == null) {
            throw new IllegalArgumentException("a declaration site needs a range");
        }
        if (end.compareTo(start) < 0) {
            TextPoint swap = start;
            start = end;
            end = swap;
        }
    }

    /** A declaration in the document that was asked. */
    public static DeclarationSite here(TextPoint start, TextPoint end) {
        return new DeclarationSite(null, start, end);
    }

    /** Whether this is in the document the question was asked about. */
    public boolean isSameDocument() {
        return resource == null;
    }
}
