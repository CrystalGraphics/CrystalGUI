package com.crystalgui.language.grammar;


import com.crystalgui.core.async.JobScheduler;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.LanguageRegistry;

/**
 * Puts this module's real parsers in front of {@code core}'s built-in lexers.
 *
 * <h3>Why a host has to ask</h3>
 * <p>{@code core} registers {@code KeywordTokenizer} for every language it knows, because it must work
 * with no native libraries at all — a dedicated server builds and edits documents with no GL and no
 * {@code .so}. This module cannot register itself from a static initializer for the same reason it is a
 * separate module: merely being on the classpath should not load a native.</p>
 *
 * <p><b>The cost of not calling this is invisible, which is why it is worth a class of its own.</b> The
 * editor still highlights — the lexer colours keywords, strings, numbers and comments perfectly well —
 * so nothing looks broken. What is missing is everything a word list cannot see: the lexer calls any
 * identifier before a {@code (} a "function", so a constructor, an enum constant, a declaration and a
 * call are one colour, and no scheme can separate them. That reads as a palette that does not match its
 * reference, and the palette is not the problem. It cost a full round of scheme tuning to notice.</p>
 */
public final class TreeSitterLanguages {

    private TreeSitterLanguages() {
    }

    /**
     * Registers every grammar this module ships, reparsing on the shared scheduler.
     *
     * <p>Idempotent — {@link LanguageRegistry#registerExtensions} replaces a rule for the same
     * extension, so calling this twice leaves one registration rather than two.</p>
     */
    public static boolean register() {
        return register(JobScheduler.shared());
    }

    /**
     * @param scheduler where reparses run, or {@code null} to parse on the calling thread
     * @return whether the native loaded and the grammars are now in front of core's lexers
     */
    public static boolean register(JobScheduler scheduler) {
        try {
            // Built once here purely to fail fast: if the native will not load on this platform, we must
            // leave core's lexer in place rather than registering a supplier that throws on first open.
            TreeSitterTokenizer probe = Grammar.JAVA.newTokenizer(scheduler);
            probe.close();
        } catch (Throwable nativeUnavailable) {
            // Not an error. The fork ships natives for five platform/arch pairs and this is one of the
            // others, which is exactly the case the built-in lexer exists to cover.
            System.err.println("[crystalgui] tree-sitter unavailable here; keeping the built-in lexer: "
                    + nativeUnavailable);
            return false;
        }
        // AND SAY SO WHEN IT WORKS. This probe is the only thing that actually loads the JNI library --
        // everything below merely records suppliers -- so it is the one place that knows. A host used to
        // repeat it reflectively for exactly that reason, which is a duplicate of these six lines and was
        // documented as necessary because "register() is LAZY". It has not been lazy since the probe was
        // added. Colouring from a word list and colouring from a parse tree also look alike on most
        // lines, so a silent success is indistinguishable from a silent miss until somebody notices a
        // type and a call sharing a colour -- the same rule the engine bands are held to.
        System.err.println("[crystalgui] tree-sitter grammars are live - "
                + Grammar.values().length + " languages parsing");

        // ONE LOOP OVER THE TABLE. This was six near-identical blocks, each restating a language's
        // extensions and its Language beside a factory that also knew them -- so adding XML meant getting
        // the same six facts right in two files. They live on Grammar now, and a seventh language is a row.
        for (Grammar grammar : Grammar.values()) {
            // ANY SERVICES ALREADY REGISTERED ARE CARRIED OVER, which is what makes this and
            // `JavaLanguage.register()` safe to call in either order. Building a bare Entry here
            // discarded them, so registering the engine FIRST silently threw it away: the editor
            // coloured perfectly, Problems stayed empty for Java, and every identifier took one colour
            // -- which reads as the engine not being built rather than as an entry being replaced.
            // JavaLanguage already reads-then-writes for the same reason and says order does not matter;
            // this is the half that made that true.
            String probeName = "any." + grammar.extensions().get(0);
            LanguageRegistry.Entry current = LanguageRegistry.forFileName(probeName);
            LanguageRegistry.registerExtensions(
                    new LanguageRegistry.Entry(grammar.language(),
                            () -> grammar.newTokenizer(scheduler), current.services()),
                    grammar.extensions().toArray(new String[0]));
        }
        return true;
    }
}
