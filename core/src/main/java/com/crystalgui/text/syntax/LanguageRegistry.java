package com.crystalgui.text.syntax;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Which language a file name is written in — the extension-to-{@link Language} map.
 *
 * <h3>Why a registry rather than a switch at each call site</h3>
 *
 * <p>Three things need this answer and none of them is a good owner of it: the editor that opens a
 * document, a Problems panel deciding how to render a row, and anything that eventually compiles the
 * thing. A switch in a harness scene answers only for that scene, and the second copy is where the two
 * start disagreeing about whether {@code .frag} is GLSL.</p>
 *
 * <h3>{@link Language} and {@link SyntaxTokenizer} stay separate, and are paired only here</h3>
 *
 * <p>They answer different questions — one is comment syntax, bracket pairs and indent triggers, the
 * other only knows how to colour — and {@code TextEditor} keeps them as two fields for that reason. But
 * "what is this file?" has a single answer, so the pairing has to exist somewhere; here is the one place
 * that does not force either type to learn about the other.</p>
 *
 * <h3>Tokenizers come from a {@link Supplier}, one per document</h3>
 *
 * <p>{@link KeywordTokenizer} is stateless and could safely be shared, but the interface is explicitly
 * built for implementations that are not: {@code SyntaxTokenizer.edited} exists so a tree-sitter backend
 * can hold a parse tree per document and update it in place. Handing out a shared instance would work
 * today and silently corrupt every open file the moment such a backend is registered — one document's
 * edits applied to another's tree. A supplier costs nothing and closes that door now.</p>
 *
 * <h3>Nothing here is required</h3>
 *
 * <p>An unknown extension resolves to {@link Language#PLAIN} and {@link SyntaxTokenizer#NONE}, which is
 * the honest answer rather than a guess: a file the editor cannot classify still opens, still edits, and
 * simply offers no colouring or bracket pairs. {@code core/} deliberately knows nothing about tree-sitter
 * or natives, so the built-ins are the two keyword lexers that ship with it.</p>
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

    /** Insertion-ordered so {@link #extensions()} reads predictably in a settings screen. */
    private static final Map<String, Entry> BY_EXTENSION = new LinkedHashMap<>();

    static {
        Entry java = new Entry(Language.java(), KeywordTokenizer::java);
        register(java, "java");

        // Every extension a GLSL file is written under in practice. `.glsl` is the generic one; the stage
        // suffixes are what a driver's own toolchain and every shader mod use, and CrystalGraphics'
        // shipped assets use `.shader`, `.vert` and `.frag` -- so leaving those out would mean the
        // engine's own sources opened as plain text.
        Entry glsl = new Entry(Language.glsl(), KeywordTokenizer::glsl);
        register(glsl, "glsl", "vert", "frag", "geom", "tesc", "tese", "comp", "shader");
    }

    private LanguageRegistry() {
    }

    /** Registers {@code entry} for each extension, without the dot, case-insensitively. */
    public static synchronized void register(Entry entry, String... extensions) {
        if (entry == null) throw new IllegalArgumentException("A language entry must not be null");
        for (String extension : extensions) {
            if (extension == null || extension.isEmpty()) continue;
            BY_EXTENSION.put(normalise(extension), entry);
        }
    }

    /**
     * The language for a file name or path, never null.
     *
     * <p>Takes the extension after the <b>last</b> dot, so {@code Main.java} and {@code lib/util.test.java}
     * agree. A name with no dot, or a trailing dot, is plain.</p>
     */
    public static synchronized Entry forFileName(@Nullable String fileName) {
        String extension = extensionOf(fileName);
        if (extension == null) return PLAIN;
        return BY_EXTENSION.getOrDefault(extension, PLAIN);
    }

    /** True when this extension has been registered — for a caller that wants to say so rather than
     * silently fall back. */
    public static synchronized boolean isKnown(@Nullable String fileName) {
        String extension = extensionOf(fileName);
        return extension != null && BY_EXTENSION.containsKey(extension);
    }

    /** Every registered extension, in registration order. */
    public static synchronized Set<String> extensions() {
        return Set.copyOf(BY_EXTENSION.keySet());
    }

    @Nullable
    private static String extensionOf(@Nullable String fileName) {
        if (fileName == null) return null;
        // The last SEPARATOR first, so a dot in a directory name cannot be read as the file's extension:
        // "my.project/README" has no extension at all.
        int slash = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        String name = slash >= 0 ? fileName.substring(slash + 1) : fileName;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return normalise(name.substring(dot + 1));
    }

    private static String normalise(String extension) {
        String trimmed = extension.trim();
        if (trimmed.startsWith(".")) trimmed = trimmed.substring(1);
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
