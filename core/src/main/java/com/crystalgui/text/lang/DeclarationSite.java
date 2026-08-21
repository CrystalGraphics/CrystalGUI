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

    /**
     * A declaration in a type the workspace does not contain — a JDK or library class.
     *
     * <h3>It takes a NAME, and builds the {@link Resource} itself, and that is the whole point</h3>
     *
     * <p>The only caller is an engine, and an engine is loaded by {@code EngineClassLoader}, which is
     * <b>child-first over everything except the JDK, the bridge package and
     * {@code com.crystalgui.text.*}</b>. This class is in that last one, so it is the host's. {@code
     * Resource} is in {@code com.crystalgui.fs} and is not — so a child-side class that called
     * {@code Resource.of(...)} itself would resolve {@code Resource} through the band's loader.</p>
     *
     * <p>Whether that finds a second copy depends on what is on the band's URLs, and <b>that differs
     * between development and production</b>: {@code EngineHost} adds its own code source, which in
     * Gradle is {@code language/build/classes} — no {@code com.crystalgui.fs} in it, so delegation falls
     * to the parent and one copy exists — and under LaunchWrapper is <b>the whole mod jar</b>, which
     * contains it. So the band would define its own {@code Resource}, and handing one to this
     * constructor fails with the confusing shape: {@code Resource cannot be cast to Resource}. Every
     * test and every harness run would pass; only a shipped jar would break.</p>
     *
     * <p>Taking the name as a {@link String} moves the resolution inside a method whose own class is
     * host-loaded, so {@code Resource} is looked up there and the engine's constant pool never mentions
     * it. The same shape as {@code TypeBytes} and every other crossing: compose host-side, cross with
     * JDK types only.</p>
     *
     * @param topLevelBinaryName the declaring compilation unit's top-level type — {@code java.util.Map}
     *                           for {@code Map.Entry}, since a source archive is keyed by unit
     */
    public static DeclarationSite inLibrary(String topLevelBinaryName, TextPoint start, TextPoint end) {
        if (topLevelBinaryName == null || topLevelBinaryName.isEmpty()) return null;
        return new DeclarationSite(
                Resource.of(Resource.SCHEME_LIBRARY, topLevelBinaryName), start, end);
    }

    /** Whether this points into a type the workspace does not contain. @see #inLibrary */
    public boolean isLibrary() {
        return resource != null && Resource.SCHEME_LIBRARY.equals(resource.scheme());
    }

    /** Whether this is in the document the question was asked about. */
    public boolean isSameDocument() {
        return resource == null;
    }
}
