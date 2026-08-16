package com.crystalgui.language.engine.bridge;

/**
 * What the <b>JavaScript</b> engine can say about a source file — the analysis half of the JS bridge.
 *
 * <h3>The request is JavaScript's; the answer is everybody's</h3>
 *
 * <p>Compare {@link SourceAnalyzer}, whose {@code analyze} names a class, a classpath and a release
 * level because that is what a Java compiler needs to resolve a file. This one names a source and a
 * version and nothing else — there is no classpath to resolve against, no compilation unit to be the
 * public type of, and no language level beyond the one the band's Rhino was built with. That difference
 * <em>is</em> the reason the two requests are separate interfaces while the answer is one: everything
 * above the bridge consumes {@link Analysis} and never asks which engine produced it.</p>
 *
 * <h3>Parsed in IDE mode, always</h3>
 *
 * <p>Rhino's parser has a recovery mode — {@code CompilerEnvirons.setRecoverFromErrors},
 * {@code setIdeMode}, {@code setRecordingComments}, {@code setRecordingLocalJsDocComments} — that
 * returns a tree <b>for broken source</b>, collects every problem instead of throwing on the first, and
 * attaches each doc comment to the declaration it precedes. Everything M10 offers rests on that: a file
 * is broken most of the time it is being typed in, and an analyser that only answers for well-formed
 * input answers exactly when it is not needed. Same argument {@code Resolver} already makes about ECJ's
 * binding recovery.</p>
 */
public interface JsSourceAnalyzer {

    /**
     * Parses one script.
     *
     * @param sourceName what the engine should call this source in a message or a stack frame — the
     *                   file's own name ({@code Main.js}), which is what a runtime error's frame will
     *                   carry and therefore what the console links against
     * @param source     the text, exactly as the engine should see it. <b>Never wrapped</b>: JavaScript
     *                   has no compilation-unit shape to satisfy, so unlike Java there is no prelude and
     *                   every offset in the answer is an offset into the document
     * @param version    the document version this describes, carried through to every answer
     */
    Analysis analyze(String sourceName, String source, long version);

    /**
     * The JavaScript engine's analysis — the general
     * {@link com.crystalgui.language.engine.bridge.Analysis}, plus what only a JS parse can offer.
     *
     * <p>Kept as the type {@link #analyze} returns for the same reason
     * {@link SourceAnalyzer.Analysis} is: a consumer should ask for the general type unless it genuinely
     * needs to know the engine was Rhino's.</p>
     */
    interface Analysis extends com.crystalgui.language.engine.bridge.Analysis {
    }
}
