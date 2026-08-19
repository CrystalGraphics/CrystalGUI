package com.crystalgui.text.diagnostic;

import java.util.List;

/**
 * A second opinion about a document, from something that is not a language engine.
 *
 * <h3>Why this exists when {@code LanguageServices} already does</h3>
 *
 * <p>{@code LanguageServices} is a <em>language's</em> engine: it colours, resolves, completes and
 * reports, and a document has one. A shader compiler is none of those things. It answers exactly one
 * question — <em>would this compile</em> — and it is not the GLSL language's engine in any sense that
 * would let it answer the rest: it cannot tell a parameter from a local, and a {@code .glsl} include
 * fragment is not a translation unit it can be given at all.</p>
 *
 * <p>So this is deliberately the smallest possible producer, and everything downstream is already
 * generic over who produced a diagnostic: the squiggles, the Problems panel, the status-bar count and
 * the decoration tracking, which hangs off {@link DiagnosticSet#onChanged} rather than off any one
 * engine's push precisely so a second producer needs no wiring.</p>
 *
 * <h3>Why {@code core/} may hold it</h3>
 *
 * <p>Because it names nothing. A shader compiler lives in CrystalGraphics, which {@code core/} may reach
 * only inside a paint method — the headless rule, and the one {@code headlessTest} exists to enforce. An
 * interface taking a {@link String} and answering {@link Diagnostic}s crosses that boundary without
 * mentioning it, exactly as the language SPIs do: whoever has the compiler implements this, and a
 * dedicated server simply has no implementation.</p>
 */
@FunctionalInterface
public interface SourceChecker {

    /**
     * What is wrong with {@code source}, or an empty list.
     *
     * <p><b>May be slow and may be called off the UI thread.</b> A GLSL check is a parse, and a real one
     * is a driver round-trip — slower than a Java compile and with a wider window for the document to
     * move on underneath it, which is why {@link CheckedDocument} carries the version rather than
     * announcing whatever comes back.</p>
     *
     * <p>Never throws for bad input: a file that does not compile is the ordinary case here, and it is
     * reported rather than raised. An implementation that lets its compiler's exception escape turns a
     * squiggle into a crash on a keystroke.</p>
     *
     * @param name what to call this source in a message — the file's own name
     */
    List<Diagnostic> check(String name, String source);
}
