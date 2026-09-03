package com.crystalgui.text.syntax;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.pattern.FilePatternMap;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.LanguageServices;

import java.util.HashMap;
import java.util.Map;
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

    /**
     * What a file is, as far as the editor is concerned.
     *
     * <h3>Three things, because "what is this file" has one answer</h3>
     *
     * <p>{@code services} joins the pair for the reason the pair exists at all: a switch that answers
     * "which language" in one place and "which engine" in another is two switches that will disagree
     * about {@code .frag}. It is <b>nullable</b>, and null is the ordinary case — five of the six shipped
     * grammars have no engine behind them, a dedicated server has none at all, and an editor with none
     * behaves exactly as it does today. See {@link LanguageServices} on why that absence is the whole
     * feature flag.</p>
     */
    public record Entry(Language language, Supplier<SyntaxTokenizer> tokenizer,
                        @Nullable LanguageServices.Factory services,
                        @Nullable Supplier<SyntaxTokenizer> staticTokenizer) {

        /** A language with colouring but no engine — every entry until M5 lands one. */
        public Entry(Language language, Supplier<SyntaxTokenizer> tokenizer) {
            this(language, tokenizer, null, null);
        }

        /** The pair every registration used before a static tokenizer could be named. */
        public Entry(Language language, Supplier<SyntaxTokenizer> tokenizer,
                     @Nullable LanguageServices.Factory services) {
            this(language, tokenizer, services, null);
        }

        /**
         * A tokenizer for this language — <b>exactly what was registered</b>, composed with nothing.
         *
         * <p>{@link DocComments#refining} briefly happened here, and it was wrong in a way worth naming:
         * a registry accessor that returns something other than what was put in it is invisible at every
         * call site. {@code language/} registers a tree-sitter tokenizer and would have received a wrapper
         * it never asked for, with no way to get the raw one back and nothing at either end saying so.
         * Composition belongs to whoever is building a pipeline, not to the lookup they build it from.</p>
         */
        public SyntaxTokenizer newTokenizer() {
            return tokenizer.get();
        }

        /**
         * A tokenizer for text that is <b>not a document</b> — a code sample in a doc comment, a snippet
         * in a tooltip — which must answer on the calling thread.
         *
         * <h3>Why the ordinary one cannot serve</h3>
         *
         * <p>{@link #newTokenizer} hands back what a DOCUMENT wants, and for a tree-sitter backend that
         * means a scheduler: the first parse of a real file is far past a frame budget, so it goes to a
         * worker and the query answers nothing until it lands. That is right for a file, whose view is
         * told to ask again — and wrong for a caller with one string and no second chance, which gets an
         * empty list and colours nothing.</p>
         *
         * <p>It was exactly that: {@code static.tokenize 527 chars -> 0 tokens}. Every {@code <pre>} block
         * in every doc comment was drawn as plain text, and cost ~19ms per block to arrive at it, because
         * building the tokenizer compiles the grammar's whole {@code highlights.scm} natively.</p>
         *
         * <p>Defaults to {@link #newTokenizer} for a registration that names no static one — which is
         * correct for a synchronous backend, where the two are the same thing.</p>
         */
        public SyntaxTokenizer newStaticTokenizer() {
            return staticTokenizer == null ? tokenizer.get() : staticTokenizer.get();
        }

        /** The same entry with a synchronous tokenizer named. @see #newStaticTokenizer */
        public Entry withStaticTokenizer(@Nullable Supplier<SyntaxTokenizer> factory) {
            return new Entry(language, tokenizer, services, factory);
        }

        /** Services for one document, or null when this language has no engine. */
        @Nullable
        public LanguageServices newServices(TextBuffer buffer, @Nullable Resource resource) {
            return services == null ? null : services.create(buffer, resource);
        }

        /** The same entry with an engine behind it — how a language module upgrades a registration. */
        public Entry withServices(@Nullable LanguageServices.Factory factory) {
            return new Entry(language, tokenizer, factory, staticTokenizer);
        }
    }

    /** Plain text: opens and edits, colours nothing, pairs nothing. */
    public static final Entry PLAIN = new Entry(Language.PLAIN, () -> SyntaxTokenizer.NONE);

    /**
     * <b>A language can answer more than it could a moment ago</b> — fired when that becomes true.
     *
     * <h3>What it is for</h3>
     *
     * <p>A language module's services are a feature flag: absent means the editor colours and does not
     * analyse. That absence is not always permanent — an engine band can arrive by download after the
     * workbench is up, and {@code JavaLanguage} deliberately retries its resolve rather than caching the
     * first failure. But a document that was opened while there was nothing to attach <b>keeps its
     * nothing</b>: services are attached once, when the document is created, so an editor already on
     * screen stayed dark until it was closed and reopened.</p>
     *
     * <p>So the capability change has to be announced. Whoever owns open documents fills in the ones that
     * have none; nothing already attached is touched, because a live services object holds a compile
     * result about text that has not changed.</p>
     *
     * <h3>Emitted on the caller's thread, and that is a constraint on the emitter</h3>
     *
     * <p>A listener here reaches the widget tree — attaching services subscribes to signals a widget
     * reads — so an emit from a worker would put the cascade on a background thread, which corrupts
     * {@code StyleEngine}'s dirty-match set with an exception naming nothing related. The one emitter
     * today fires from a document opening, which is the UI thread by construction. Anything emitting
     * from a job must hop through {@code JobScheduler.onDone} first.</p>
     */
    public static final Signal.Action onCapabilityChanged = new Signal.Action();

    /** Announces that a language now offers services it did not. @see #onCapabilityChanged */
    public static void capabilityChanged() {
        onCapabilityChanged.emit();
    }

    private static final FilePatternMap<Entry> RULES = new FilePatternMap<>();

    /**
     * The same entries, keyed by language name — for a caller that knows <b>what</b> rather than
     * <b>which file</b>.
     *
     * <p>Every rule here is a file pattern, which is right for the question the editor asks ("I am
     * opening {@code Foo.java}"). It is the wrong question for a caller holding a {@link Language}
     * already: a code sample inside a rendered doc comment is Java because the document is, and there is
     * no file name to invent for it. Deriving one — {@code "x.java"} — works and is a lie the next reader
     * has to decode.</p>
     *
     * <p>Written by the same three registration methods, so an entry replaced later (the tree-sitter
     * tokenizers overwrite the keyword ones when {@code language/} is present) is replaced in both.</p>
     */
    private static final Map<String, Entry> BY_LANGUAGE = new HashMap<>();

    static {
        Entry java = new Entry(Language.JAVA, KeywordTokenizer::java);
        registerExtensions(java, "java");

        // Every extension a GLSL file is written under in practice. `.glsl` is the generic one; the stage
        // suffixes are what a driver's own toolchain and every shader mod use, and CrystalGraphics'
        // shipped assets use `.shader`, `.vert` and `.frag` -- so leaving those out would mean the
        // engine's own sources opened as plain text.
        Entry glsl = new Entry(Language.GLSL, KeywordTokenizer::glsl);
        registerExtensions(glsl, "glsl", "vert", "frag", "geom", "tesc", "tese", "comp", "shader");

        // `.mjs` and `.cjs` beside `.js`, matching the three the JS grammar already claims -- a registry
        // that knew fewer would open one of them as plain text with the grammar sitting right there.
        Entry javascript = new Entry(Language.JAVASCRIPT, KeywordTokenizer::javascript);
        registerExtensions(javascript, "js", "mjs", "cjs");
    }

    private LanguageRegistry() {
    }

    /** Registers {@code entry} for each extension, with or without the leading dot, case-insensitively. */
    public static synchronized void registerExtensions(Entry entry, String... extensions) {
        RULES.putExtensions(entry, extensions);
        remember(entry);
    }

    /** Registers {@code entry} for each <b>exact</b> file name — {@code Dockerfile}, {@code .gitignore}. */
    public static synchronized void registerNames(Entry entry, String... fileNames) {
        RULES.putNames(entry, fileNames);
        remember(entry);
    }

    /** Registers {@code entry} for each glob over the whole file name — {@code *.test.js}. */
    public static synchronized void registerGlobs(Entry entry, String... globs) {
        RULES.putGlobs(entry, globs);
        remember(entry);
    }

    private static void remember(Entry entry) {
        BY_LANGUAGE.put(entry.language().name(), entry);
    }

    /**
     * The entry for a language, never null — {@link #PLAIN} when nothing has claimed it.
     *
     * <p>Keyed on the language's <b>name</b> rather than on the record's identity, because
     * {@code Language.JAVA} the constant and the {@code Language} a registered entry holds need not be the
     * same object: {@code language/} re-registers Java with a tree-sitter tokenizer and builds its own
     * value. The name is what both agree on.</p>
     */
    public static synchronized Entry forLanguage(@Nullable Language language) {
        if (language == null) return PLAIN;
        Entry entry = BY_LANGUAGE.get(language.name());
        return entry == null ? PLAIN : entry;
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
