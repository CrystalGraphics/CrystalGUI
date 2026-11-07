package com.crystalgui.text.syntax;

import com.crystalgui.core.provider.Providers;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.pattern.FilePatternMap;
import com.crystalgui.fs.Resource;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.lang.LanguageServices;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Which language a file is written in: its {@link Language}, a {@link SyntaxTokenizer} per document, and the engine's
 * services where one is installed.
 *
 * <pre>{@code
 * Entry entry = LanguageRegistry.forFileName("Shader.frag");          // GLSL; PLAIN when nothing claims it
 * SyntaxTokenizer tokens = entry.newTokenizer();                       // one per document
 * LanguageServices services = entry.newServices(buffer, resource);    // null with no engine
 *
 * LanguageRegistry.registerNames(cmake, "CMakeLists.txt");             // exact name, then extension, then glob
 * LanguageRegistry.registerExtensions(cmake, "cmake");
 * }</pre>
 *
 * <ul>
 *   <li>The first read runs every {@link LanguageKinds} service on the classpath, so a module's languages arrive by
 *       its jar being present. {@link #bootstrap()} runs it early, on the thread that owns the UI.</li>
 *   <li>Process-global: a rule registered anywhere is seen everywhere, tests included. Prefer a name or an extension
 *       to a glob; a bare {@code *} claims every unknown file.</li>
 *   <li>A tokenizer comes from a supplier because a tree-sitter one holds a parse tree per document.</li>
 * </ul>
 */
public final class LanguageRegistry {

    /** What a file is: its language, its tokenizers, and its engine's services, null where it has none. */
    public record Entry(Language language, Supplier<SyntaxTokenizer> tokenizer,
                        @Nullable LanguageServices.Factory services,
                        @Nullable Supplier<SyntaxTokenizer> staticTokenizer) {

        /** A language with colouring and no engine. */
        public Entry(Language language, Supplier<SyntaxTokenizer> tokenizer) {
            this(language, tokenizer, null, null);
        }

        /** A language with its engine and no separate static tokenizer. */
        public Entry(Language language, Supplier<SyntaxTokenizer> tokenizer,
                     @Nullable LanguageServices.Factory services) {
            this(language, tokenizer, services, null);
        }

        /** A tokenizer for one document: exactly what was registered, composed with nothing. */
        public SyntaxTokenizer newTokenizer() {
            return tokenizer.get();
        }

        /**
         * A tokenizer that answers on the calling thread, for text that is not a document (a doc comment's code
         * sample). A document's tree-sitter tokenizer parses on a worker and answers nothing at first. Defaults to
         * {@link #newTokenizer}.
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
     * A language gained services it lacked (an engine band downloaded after the workbench came up): whoever owns open
     * documents attaches services to the ones that have none. Emit on the UI thread, since a listener reaches the
     * widget tree; from a job, hop through {@code JobScheduler.onDone} first.
     */
    public static final Signal.Action onCapabilityChanged = new Signal.Action();

    /** Announces that a language now offers services it did not. @see #onCapabilityChanged */
    public static void capabilityChanged() {
        onCapabilityChanged.emit();
    }

    private static final FilePatternMap<Entry> RULES = new FilePatternMap<>();

    // The same entries by language name, for a caller holding a Language and no file name
    private static final Map<String, Entry> BY_LANGUAGE = new HashMap<>();

    static {
        Entry java = new Entry(Language.JAVA, KeywordTokenizer::java);
        registerExtensions(java, "java");

        // CrystalGraphics' own sources are .shader, .vert and .frag
        Entry glsl = new Entry(Language.GLSL, KeywordTokenizer::glsl);
        registerExtensions(glsl, "glsl", "vert", "frag", "geom", "tesc", "tese", "comp", "shader");

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

    /** The entry for a language by its name, never null: {@link #PLAIN} when nothing claims it. */
    public static synchronized Entry forLanguage(@Nullable Language language) {
        bootstrap();
        if (language == null) return PLAIN;
        Entry entry = BY_LANGUAGE.get(language.name());
        return entry == null ? PLAIN : entry;
    }

    /** The language for a file name or path, never null. */
    public static synchronized Entry forFileName(@Nullable String fileName) {
        bootstrap();
        Entry entry = RULES.get(fileName);
        return entry == null ? PLAIN : entry;
    }

    /** Whether anything claims this file. */
    public static synchronized boolean isKnown(@Nullable String fileName) {
        bootstrap();
        return RULES.get(fileName) != null;
    }

    /** Every registered pattern, in registration order, as {@code KIND:pattern}. */
    public static synchronized List<String> rules() {
        bootstrap();
        return RULES.patterns();
    }

    private static final List<String> CONTRIBUTORS = new ArrayList<>();

    private static boolean bootstrapped;

    /**
     * Runs every {@link LanguageKinds} service on the classpath, once; every read calls it.
     *
     * <ul>
     *   <li>A question about a file triggers it, never class loading: a dedicated server that classifies nothing
     *       loads no grammar or native.</li>
     *   <li>Services load through this class's loader, not the context one, which LaunchWrapper leaves elsewhere.</li>
     * </ul>
     */
    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        // Before the loop: a service reads the registry back while registering
        bootstrapped = true;
        Providers.forEach(LanguageKinds.class, LanguageRegistry.class.getClassLoader(), kinds -> {
            try {
                kinds.register();
                CONTRIBUTORS.add(kinds.getClass().getName());
                CrystalGuiCore.LOGGER.info("[cgui] languages contributed by {}", kinds.getClass().getName());
            } catch (RuntimeException | LinkageError failed) {
                CrystalGuiCore.LOGGER.error("[cgui] the language contributor {} failed; its languages are "
                        + "absent on this host", kinds.getClass().getName(), failed);
            }
        }, broken -> CrystalGuiCore.LOGGER.error("[cgui] a LanguageKinds service could not be loaded; its "
                + "languages are absent on this host", broken));
    }

    /** Which contributors ran, by class name, in discovery order. */
    public static synchronized List<String> contributors() {
        bootstrap();
        return List.copyOf(CONTRIBUTORS);
    }

    /** What has run so far, without triggering discovery. For tests. */
    public static synchronized List<String> contributorsSoFarForTesting() {
        return List.copyOf(CONTRIBUTORS);
    }

    /** Forgets that discovery ran, keeping the rules: re-registering replaces them. For tests. */
    public static synchronized void resetBootstrapForTesting() {
        bootstrapped = false;
        CONTRIBUTORS.clear();
    }
}
