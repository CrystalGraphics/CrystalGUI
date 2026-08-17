package com.crystalgui.language.engine.bridge;

import java.util.List;
import java.util.function.Predicate;

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
     * Parses one script, against what the last run of it left in scope.
     *
     * <p>The live scope is the top resolution tier — what a value <em>is</em> outranks what the author
     * said and what the syntax suggests — and it is per <b>document</b>, because it is the result of
     * running that document. So it arrives with the request rather than being set on the analyser, which
     * is one object shared by every open file.</p>
     */
    default Analysis analyze(String sourceName, String source, long version,
                             LiveScopeSnapshot liveScope) {
        return analyze(sourceName, source, version);
    }

    /**
     * The keywords this engine accepts — what a completion list in open code may offer.
     *
     * <p>A question about the <em>engine</em>, not about the language, which is why it is asked here rather
     * than answered from a table on the host side. The grammar parses modern JavaScript and the engine is
     * what refuses it, differently per band: {@code class} and {@code async} are refused by every Rhino we
     * ship, and offering a keyword that cannot run is worse than omitting it — a completion row is a
     * promise that accepting it produces something the engine will take.</p>
     */
    default List<String> keywords() {
        return List.of();
    }

    /**
     * Lends this analyser the <b>Java</b> engine, so a Java type reached from JavaScript is answered by
     * the resolver that answers for Java.
     *
     * <p>{@code new java.util.ArrayList()}, {@code Java.type("a.b.C")}, a JSDoc {@code {java.util.List}} —
     * for every one of those, "what members does it have" is a question the Java engine already answers
     * better than reflection can: generic substitution, accessibility, and the binding keys
     * {@code AttachedSources} needs to quote a signature out of {@code src.zip}. Asking it means a member
     * list reached from JavaScript is <em>the same list</em> a {@code .java} file would have shown, which
     * is the whole point of the interop tier.</p>
     *
     * <p>Optional, and its absence is not a failure: a build that ships Rhino without ECJ falls back to
     * reflection over the host loader, which is also exactly what Rhino itself will do at call time — so
     * the fallback shows what the script can really call, just with less detail.</p>
     *
     * <p>Called once at registration. It is a lend rather than an ownership transfer: the Java engine is
     * the Java language's to open and to close, and this analyser must never close it.</p>
     */
    default void useJavaEngine(SourceAnalyzer java, List<String> classpath, int releaseLevel) {
    }

    /**
     * Restricts which Java classes resolution and completion may describe.
     *
     * <p>A {@code Predicate<String>} rather than the host's policy object, for the reason every other
     * crossing here is a JDK type: the child may not see {@code language.run}. Passed to the <b>analyser</b>
     * as well as to the executor because the two must agree — a class absent from the completion list and
     * callable at run time, or offered and then refused, is a worse failure than either restriction alone.</p>
     */
    default void restrictTo(Predicate<String> allowsClass) {
    }

    /**
     * Installs the mapping the member lists are shown through.
     *
     * <p>The <b>other direction</b> from the executor's: a member list read off a class is full of runtime
     * names, and showing those would teach an author to write them — at which point the executor's
     * translation has nothing to translate. Both halves or neither; a completion list offering
     * {@code func_147439_a} beside a runtime that only accepts {@code getBlock} is an editor working against
     * its user.</p>
     */
    default void useMemberNames(MemberNameMapper mapper) {
    }

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
