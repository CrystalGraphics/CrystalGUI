package com.crystalgui.text.lang;

import com.crystalgui.fs.CgPath;
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
 * <h3>{@code member} is a position that does not exist yet</h3>
 *
 * <p>A class with no attached source has no coordinates at all until it has been <b>decompiled</b>, and
 * decompiling to answer a hover that may never be acted on would put hundreds of milliseconds behind
 * every one — see {@code JavaSignatures.declarationWithoutSource}. So such a site names the type, carries
 * {@code (0,0)}, and records <em>which member was asked for</em>; whoever produces the text is then the
 * one that can say where in it that member is. Null everywhere else, including for every site that
 * already knows its own position.</p>
 *
 * @param resource where it is declared, or null for the document that was asked
 * @param start    first character of the declaration's name
 * @param end      one past its last character
 * @param member   the member to find once the text exists, or null when {@code start} is already right
 */
public record DeclarationSite(@Nullable Resource resource, TextPoint start, TextPoint end,
                              @Nullable String member) {

    /** A site whose position is already known — everything but the sourceless-classpath case. */
    public DeclarationSite(@Nullable Resource resource, TextPoint start, TextPoint end) {
        this(resource, start, end, null);
    }

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

    /**
     * A declaration in a file the WORKSPACE holds — another project source.
     *
     * <p>The counterpart to {@link #inLibrary}, and it takes a {@link String} path for exactly the same
     * reason: the only caller is an engine, {@code com.crystalgui.fs} is not parent-first on the band
     * loader, and a child-side class that built the path type itself would build the band's own copy of
     * it. Composing it here — in a class the host loads — keeps that type out of the engine's constant
     * pool entirely.</p>
     *
     * <p>Without this a project file's declaration could only be described as a library, so Ctrl+B on a
     * type declared two files away opened the <b>decompiler</b> on a {@code .class} that does not exist,
     * and the viewer came up empty. Everything worked; the site simply said the wrong thing about where
     * the type lives.</p>
     *
     * @param workspacePath the declaring file's path, as {@code ProjectSources.pathOf} reports it
     */
    @Nullable
    public static DeclarationSite inProject(String workspacePath, TextPoint start, TextPoint end) {
        if (workspacePath == null || workspacePath.isEmpty()) return null;
        try {
            return new DeclarationSite(Resource.of(CgPath.parse(workspacePath)), start, end);
        } catch (RuntimeException notAPath) {
            // A path this workspace cannot spell is not worth failing a hover over -- the caller's
            // fallback is the library-shaped site it had before this existed.
            return null;
        }
    }

    /**
     * A member of a library type whose source nobody has — the type, and the member to look for.
     *
     * <p>The position is {@code (0,0)} because none exists: this is the class that will be decompiled,
     * and its members have no line numbers until it has been. Naming the member is what lets the reader
     * land on it rather than at the top of the file. @see #member</p>
     */
    @Nullable
    public static DeclarationSite inLibraryMember(String topLevelBinaryName, String member) {
        if (topLevelBinaryName == null || topLevelBinaryName.isEmpty()) return null;
        return new DeclarationSite(Resource.of(Resource.SCHEME_LIBRARY, topLevelBinaryName),
                new TextPoint(0, 0), new TextPoint(0, 0),
                member == null || member.isEmpty() ? null : member);
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
