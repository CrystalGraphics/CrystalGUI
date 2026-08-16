package com.crystalgui.language.engine.bridge;

import java.util.List;

/**
 * What the <b>Java</b> engine can say about a source file — the analysis half of the Java bridge.
 *
 * <h3>The request is Java's; the answer is everybody's</h3>
 *
 * <p>{@link #analyze} names a class, a classpath and a release level, because that is what a Java compiler
 * needs to resolve a file and nothing less would do. A JavaScript engine needs none of them and will
 * declare its own request. What both hand back is an {@link Analysis} — the language-neutral answer that
 * every consumer above the bridge is written against — so the request shape is the only thing a second
 * engine has to write for itself.</p>
 *
 * <h3>Why this returns an {@link Analysis} rather than a pile of lists</h3>
 *
 * <p>Resolving a name needs the whole resolved AST, and an AST cannot cross the classloader boundary:
 * it is made of engine types the host must never see. Materialising everything a caller <em>might</em>
 * ask — every symbol at every offset — is absurd. So the analysis stays on the engine's side, behind an
 * interface the host can hold: {@link Analysis} is declared in the shared package (one identity) and
 * implemented over there (holding the AST). Each query is a call across the gap that answers from a
 * tree the host never sees.</p>
 */
public interface SourceAnalyzer {

    /**
     * Parses and resolves one source file.
     *
     * @param className    the fully qualified name the source declares
     * @param source       the source text, exactly as the engine should see it
     * @param classpath    what to resolve against
     * @param releaseLevel the Java level to analyse at
     * @param version      the document version this describes, carried through to every answer
     */
    Analysis analyze(String className, String source, List<String> classpath, int releaseLevel,
                     long version);

    /**
     * The Java engine's analysis — the general {@link com.crystalgui.language.engine.bridge.Analysis},
     * under the name it had before the answer was split from the request.
     *
     * <p>Kept as the type {@link #analyze} returns so nothing that held one has to change; it adds
     * nothing, and a consumer should ask for the general type unless it genuinely needs to know the
     * engine was Java's.</p>
     */
    interface Analysis extends com.crystalgui.language.engine.bridge.Analysis {
    }
}
