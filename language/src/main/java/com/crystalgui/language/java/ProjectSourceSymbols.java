package com.crystalgui.language.java;

import com.crystalgui.fs.Resource;
import com.crystalgui.fs.ResourceContentProvider;
import com.crystalgui.fs.ResourceRegistry;
import com.crystalgui.text.lang.ProjectSourcesRegistry;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.text.lang.SymbolKind;
import com.crystalgui.text.lang.SymbolModifier;

import javax.annotation.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * What a project {@code .java} file DECLARES — a class, an interface, a final enum.
 *
 * <h3>The same door a library tab already knocks on</h3>
 *
 * <p>{@code ResourceContentProvider.symbolOf} exists and {@code LibrarySources} answers it for
 * {@code library://} — which is why a {@code FlexDirection.class} tab draws an enum glyph and hovers
 * "Final enum". Nothing was registered for {@code project://}, so the identical question about the
 * author\'s own file had no one to ask, and every {@code .java} row in the tree drew the file-type icon.
 * This is that provider, not a second mechanism: the caller is unchanged, the vocabulary is
 * {@code SymbolIcon}\'s, and the tooltip comes from {@code SymbolIcon.describe} exactly as the tab\'s does.</p>
 *
 * <h3>A scan, where the library side runs a probe compile</h3>
 *
 * <p>Deliberately different, because the callers are. A tab asks about one type and can afford
 * {@code Analysis.describe}; a tree asks about every file on screen, on every refresh, and fifty probe
 * compiles to choose fifty pictures is not a trade worth making. The question an icon needs is far
 * smaller than the one an analysis answers: read the declaration the author wrote.</p>
 *
 * <p>The consequence is stated rather than hidden — this reports what the SOURCE says. A file whose
 * declaration does not compile still declares something and this will say so, where an analysis would
 * report nothing. For an icon that is the better answer: a broken file is exactly when you want the tree
 * to still look like itself.</p>
 *
 * <h3>A scanner, and the three things that make one wrong</h3>
 *
 * <p>Every mistake available here is a token read out of context, so the scan skips what it must:
 * <b>comments</b>, because {@code // a final class} is prose and a javadoc block is full of the words
 * being looked for; <b>string and character literals</b>, for the same reason one step nastier, since
 * {@code "class"} inside a header comment's example code is indistinguishable from the real thing to a
 * plain {@code indexOf}; and <b>annotations</b>, because {@code @interface} is a declaration while
 * {@code @Override} is not, and the two differ by one character in the same position.</p>
 *
 * <p>{@code record} is the one CONTEXTUAL keyword and gets the same treatment {@code SourceHeaders}
 * documents: it is a declaration only when a NAME follows it. {@code void f(Object record)} is ordinary
 * Java and {@code String.class} puts a hard keyword where no declaration is — believed unconditionally,
 * a scanner reports the wrong kind for a file that never mentioned records.</p>
 *
 * <h3>The first top-level type, not the public one</h3>
 *
 * <p>They are the same thing in a well-formed file, and when they are not the file does not compile —
 * so agreeing with the compiler costs nothing and disagreeing would need the scan to know which name
 * matches the file's, which is a second fact to get wrong. A file with no type at all (a
 * {@code package-info.java}, or one still being typed) answers null and keeps its file-type icon.</p>
 */
public final class ProjectSourceSymbols implements ResourceContentProvider {

    /** Registers this for {@code project://}. Idempotent — the registry holds one provider per scheme. */
    public static void register() {
        ResourceRegistry.register(Resource.SCHEME_PROJECT, new ProjectSourceSymbols());
    }

    /**
     * Never asked for: this provider exists for {@link #symbolOf} alone.
     *
     * <p>A project file\'s BYTES come from the workspace client, which is a network round trip on a
     * remote workspace and has an owner already. Answering here would be a second way to read a file,
     * with its own idea of what is current.</p>
     */
    @Override
    public byte[] read(Resource resource) {
        return null;
    }

    /**
     * What the file declares, or null — for a file outside a source root, one nobody has read yet, or
     * one that declares nothing.
     *
     * <p>Text through {@code ProjectSources}, so an UNSAVED edit is what the icon reflects. {@code
     * sourceOf} answers null for a file nobody has open and schedules a read rather than blocking, which
     * is right here for the reason it is right in an analysis: this runs while painting. The row keeps
     * its file-type icon and gets the glyph on the refresh after the text lands.</p>
     */
    @Override
    @Nullable
    public SymbolInfo symbolOf(Resource resource) {
        if (resource == null) return null;
        String path = resource.path();
        if (path == null || !path.endsWith(".java")) return null;
        // NULL FOR ANYTHING OUTSIDE A SOURCE ROOT, which `nameOf` answers by construction -- a `.java`
        // file in a scratch directory is a file, not a declaration the project makes.
        String qualifiedName = ProjectSourcesRegistry.view().nameOf(resource.toString());
        if (qualifiedName == null) return null;

        String source = ProjectSourcesRegistry.view().sourceOf(qualifiedName);
        if (source == null || source.isEmpty()) return null;
        return declaredIn(source, qualifiedName);
    }

    /** The scan. Package-private so a test can drive it without a workspace. */
    @Nullable
    static SymbolInfo declaredIn(String source, String qualifiedName) {
        if (source == null || source.isEmpty()) return null;

        Set<SymbolModifier> modifiers = EnumSet.noneOf(SymbolModifier.class);
        int at = 0;
        int length = source.length();
        while (at < length) {
            char here = source.charAt(at);

            // ── The things a keyword must not be read out of ────────────────────────────────────
            if (here == '/' && at + 1 < length && source.charAt(at + 1) == '/') {
                while (at < length && source.charAt(at) != '\n') at++;
                continue;
            }
            if (here == '/' && at + 1 < length && source.charAt(at + 1) == '*') {
                int end = source.indexOf("*/", at + 2);
                at = end < 0 ? length : end + 2;
                continue;
            }
            if (here == '"' || here == '\'') {
                at = pastLiteral(source, at, here);
                continue;
            }
            // AN ANNOTATION, SKIPPED WHOLE -- except `@interface`, which is a declaration. Read as a
            // bare `@` plus a name, `@Override` would leave `interface`-shaped text for the word scan
            // below to find, and every annotated class in the project would report as an interface.
            if (here == '@') {
                if (source.startsWith("@interface", at)) {
                    return symbol(SymbolKind.ANNOTATION, modifiers, qualifiedName);
                }
                at++;
                while (at < length && Character.isJavaIdentifierPart(source.charAt(at))) at++;
                continue;
            }
            if (!Character.isJavaIdentifierStart(here)) {
                at++;
                continue;
            }

            int start = at;
            while (at < length && Character.isJavaIdentifierPart(source.charAt(at))) at++;
            String word = source.substring(start, at);

            switch (word) {
                case "abstract":
                    modifiers.add(SymbolModifier.ABSTRACT);
                    continue;
                case "final":
                    modifiers.add(SymbolModifier.FINAL);
                    continue;
                case "static":
                    modifiers.add(SymbolModifier.STATIC);
                    continue;
                case "class":
                    return symbol(SymbolKind.CLASS, modifiers, qualifiedName);
                case "interface":
                    return symbol(SymbolKind.INTERFACE, modifiers, qualifiedName);
                case "enum":
                    return symbol(SymbolKind.ENUM, modifiers, qualifiedName);
                case "record":
                    // CONTEXTUAL. `record` is a declaration only when a name follows it; everywhere else
                    // it is an ordinary identifier and always has been.
                    if (nameFollows(source, at)) return symbol(SymbolKind.RECORD, modifiers, qualifiedName);
                    continue;
                case "package":
                case "import":
                    // SKIPPED WHOLE, to its terminator. Clearing the modifier set on the way IN does not
                    // work: `import static java.util.List.of;` puts `static` AFTER the word that was
                    // supposed to guard against it, so the next class in the file reports as static.
                    int semicolon = source.indexOf(';', at);
                    at = semicolon < 0 ? length : semicolon + 1;
                    modifiers.clear();
                    continue;
                default:
                    continue;
            }
        }
        return null;
    }

    /**
     * The answer, in the shape every other {@code symbolOf} gives one.
     *
     * <p>Name and container filled from the QUALIFIED NAME rather than left blank: a {@code SymbolInfo}
     * with an empty name falls through to the documentation popup\'s assembled renderer, which is the
     * trap the invariants table records twice. Nothing else is invented — no signature, no type — because
     * a scan does not know them and a field filled by guessing is worse than an absent one.</p>
     */
    private static SymbolInfo symbol(SymbolKind kind, Set<SymbolModifier> modifiers,
                                     @Nullable String qualifiedName) {
        String name = qualifiedName == null ? "" : simpleNameOf(qualifiedName);
        String container = qualifiedName == null ? null : containerOf(qualifiedName);
        return new SymbolInfo(name, kind, null, container, null, modifiers, null);
    }

    private static String simpleNameOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    @Nullable
    private static String containerOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? null : qualifiedName.substring(0, lastDot);
    }

    /** Past a string or character literal, escapes honoured. Unterminated runs to the end, as it must. */
    private static int pastLiteral(String source, int at, char quote) {
        int cursor = at + 1;
        while (cursor < source.length()) {
            char here = source.charAt(cursor);
            if (here == '\\') {
                cursor += 2;
                continue;
            }
            if (here == quote) return cursor + 1;
            // A raw newline ends an unterminated literal rather than eating the rest of the file --
            // a half-typed string is the ordinary state of a file being edited.
            if (here == '\n') return cursor + 1;
            cursor++;
        }
        return source.length();
    }

    /** Whether an identifier starts at the next non-space character. @see #topLevelOf */
    private static boolean nameFollows(String source, int at) {
        int cursor = at;
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
        return cursor < source.length() && Character.isJavaIdentifierStart(source.charAt(cursor));
    }
}
