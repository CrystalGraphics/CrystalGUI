package com.crystalgui.text.syntax;

import com.crystalgui.fs.FilePatternMap;

import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Which language a file is written in — the file-name-to-{@link Language} map.
 *
 * <h3>Why a registry rather than a switch at each call site</h3>
 *
 * <p>Three things need this answer and none of them is a good owner of it: the editor that opens a
 * document, a Problems panel deciding how to render a row, and anything that eventually compiles the
 * thing. A switch in a harness scene answers only for that scene, and the second copy is where the two
 * start disagreeing about whether {@code .frag} is GLSL.</p>
 *
 * <h3>Names, not just extensions</h3>
 *
 * <p>This matched on the extension alone, which cannot express most of what a real project contains:
 * {@code Dockerfile}, {@code Makefile}, {@code CMakeLists.txt} and {@code .gitignore} have no useful
 * extension, and the last one's whole name <em>is</em> an extension. Every editor that does this properly
 * matches on a pattern — VS Code's {@code contributes.languages} takes {@code extensions},
 * {@code filenames} and {@code filenamePatterns} together; IntelliJ's {@code FileType} takes exact names
 * and patterns beside extensions.</p>
 *
 * <p>So a rule is one of three shapes, and they are consulted <b>most specific first</b>: an exact file
 * name, then an extension, then a glob. That order is what lets {@code CMakeLists.txt} be CMake while
 * {@code .txt} stays plain — the reverse order would make every rule a race with whichever was registered
 * last.</p>
 *
 * <h3>{@link Language} and {@link SyntaxTokenizer} stay separate, and are paired only here</h3>
 *
 * <p>They answer different questions — one is comment syntax and bracket pairs, the other only knows how
 * to colour — and {@code TextEditor} keeps them as two fields for that reason. But "what is this file?"
 * has a single answer, so the pairing has to exist somewhere; here is the one place that does not force
 * either type to learn about the other.</p>
 *
 * <h3>Tokenizers come from a {@link Supplier}, one per document</h3>
 *
 * <p>{@link KeywordTokenizer} is stateless and could safely be shared, but the interface is explicitly
 * built for implementations that are not: {@code SyntaxTokenizer.edited} exists so a tree-sitter backend
 * can hold a parse tree per document and update it in place. Handing out a shared instance would work
 * today and silently corrupt every open file the moment such a backend is registered — one document's
 * edits applied to another's tree. A supplier costs nothing and closes that door now.</p>
 *
 * <h3>It is process-global, and that is a trap worth naming</h3>
 *
 * <p>Registration is static, because what language a file is written in is a fact about the world
 * rather than per-window state — the same argument {@code ElementRegistry} makes about tags. The
 * cost is that a registration made anywhere is visible everywhere, including across tests in one
 * JVM: a rule registered as a bare {@code *} by one test made every unknown name resolve to it in
 * every other. Register narrowly, and prefer a name or an extension to a glob.</p>
 *
 * <h3>Nothing here is required</h3>
 *
 * <p>An unrecognised name resolves to {@link Language#PLAIN} and {@link SyntaxTokenizer#NONE}, which is
 * the honest answer rather than a guess: a file the editor cannot classify still opens, still edits, and
 * simply offers no colouring or bracket pairs. {@code core/} deliberately knows nothing about tree-sitter
 * or natives, so the built-ins are the keyword lexers that ship with it.</p>
 */
public final class LanguageRegistry {

    /** What a file is, as far as the editor is concerned. */
    public record Entry(Language language, Supplier<SyntaxTokenizer> tokenizer) {

        public SyntaxTokenizer newTokenizer() {
            return tokenizer.get();
        }
    }

    /** Plain text: opens and edits, colours nothing, pairs nothing. */
    public static final Entry PLAIN = new Entry(Language.PLAIN, () -> SyntaxTokenizer.NONE);

    private static final FilePatternMap<Entry> RULES = new FilePatternMap<>();

    static {
        Entry java = new Entry(Language.JAVA, KeywordTokenizer::java);
        registerExtensions(java, "java");

        // Every extension a GLSL file is written under in practice. `.glsl` is the generic one; the stage
        // suffixes are what a driver's own toolchain and every shader mod use, and CrystalGraphics'
        // shipped assets use `.shader`, `.vert` and `.frag` -- so leaving those out would mean the
        // engine's own sources opened as plain text.
        Entry glsl = new Entry(Language.GLSL, KeywordTokenizer::glsl);
        registerExtensions(glsl, "glsl", "vert", "frag", "geom", "tesc", "tese", "comp", "shader");
    }

    private LanguageRegistry() {
    }

    /** Registers {@code entry} for each extension, with or without the leading dot, case-insensitively. */
    public static synchronized void registerExtensions(Entry entry, String... extensions) {
        RULES.putExtensions(entry, extensions);
    }

    /** Registers {@code entry} for each <b>exact</b> file name — {@code Dockerfile}, {@code .gitignore}. */
    public static synchronized void registerNames(Entry entry, String... fileNames) {
        RULES.putNames(entry, fileNames);
    }

    /** Registers {@code entry} for each glob over the whole file name — {@code *.test.js}. */
    public static synchronized void registerGlobs(Entry entry, String... globs) {
        RULES.putGlobs(entry, globs);
    }

    /** The language for a file name or path, never null. */
    public static synchronized Entry forFileName(@Nullable String fileName) {
        Entry entry = RULES.get(fileName);
        return entry == null ? PLAIN : entry;
    }

    /** True when something has claimed this file — for a caller that wants to say so rather than silently
     * fall back to plain text. */
    public static synchronized boolean isKnown(@Nullable String fileName) {
        return RULES.get(fileName) != null;
    }

    /** Every registered pattern, in registration order, as {@code KIND:pattern}. */
    public static synchronized List<String> rules() {
        return RULES.patterns();
    }
}
