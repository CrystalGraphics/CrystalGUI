/**
 * The JavaScript engine — Rhino behind every {@code .js} document, and behind Run.
 *
 * <h2>Which side of the bridge a class is on, and how to tell</h2>
 *
 * <p>This package spans the one boundary that matters in this module: some of it is loaded by the
 * <b>host</b>, and some of it by the <b>band loader</b> that has Rhino on it. Get that wrong and the
 * failure is a {@code NoClassDefFoundError} on a class that plainly exists, or — worse — a second copy of
 * a class quietly defined on the other side, so two objects of "the same" type are not assignable.</p>
 *
 * <p>The rule is mechanical: <b>a class that imports {@code org.mozilla.javascript} is child-side.</b>
 * Nothing else is. The naming convention was meant to say so and no longer does, which is why the table
 * is here rather than in a reader's head:</p>
 *
 * <table>
 *   <caption>Where each class is loaded</caption>
 *   <tr><th>Child (band loader — may name Rhino)</th><th>Host (may name {@code language.run}, {@code language.java})</th></tr>
 *   <tr><td>{@code RhinoSourceAnalyzer}, {@code RhinoExecutor}</td><td>{@code JsLanguage}, {@code JsLanguageServices}</td></tr>
 *   <tr><td>{@code RhinoScopes}, {@code RhinoSemanticTokens}, {@code RhinoProblemPolicy}</td><td>{@code JsHost}</td></tr>
 *   <tr><td>{@code RhinoResolution}, {@code RhinoInference}, {@code RhinoJsDoc}, {@code RhinoTokens}</td><td>{@code JsCompletionProvider}</td></tr>
 *   <tr><td>{@code RhinoGlobals}, {@code RhinoConsoleFormat}, {@code RhinoRemapping}, {@code RhinoThread}</td><td>{@code RhinoOrigin}, {@code RhinoStackFrameFilter} — named for whose <em>format</em> they carry, not for what they import</td></tr>
 *   <tr><td><b>{@code JsQuickFixes}, {@code JsRewrites}</b> — {@code Js} prefix, child-side: they walk the AST</td><td>{@code JsSignatures}, {@code JsTypeRef}, {@code JsKeywords}, {@code LineIndex}, {@code InteropResolver}, {@code JsLoaders} — no Rhino import, but constructed from the child side and therefore loaded there in practice</td></tr>
 * </table>
 *
 * <h2>What may cross</h2>
 *
 * <p>Only JDK types, {@code com.crystalgui.text.*}, and the bridge interfaces in
 * {@code language.engine.bridge} — the three things {@code EngineClassLoader.PARENT_FIRST} shares. That is
 * why the console arrives at the executor as two {@link java.util.function.Consumer}s rather than as a
 * {@code RunConsole}, why the sandbox is a {@link java.util.function.Predicate} rather than a
 * {@code ScriptPolicy}, and why {@code MemberNameMapper} deals in strings rather than in a
 * {@code MappingSet}.</p>
 *
 * <p><b>A child-side class must never import {@code language.java} or {@code language.run}.</b> It
 * compiles and it appears to work: the band loader is child-first over {@code com.crystalgui.language.*}
 * and carries our own class files, so it simply defines its own copy. {@code SimilarNames} was imported
 * that way for a release — harmless, being stateless, and exactly the precedent that is not worth having.
 * It now lives in {@code com.crystalgui.text}, which is shared.</p>
 *
 * <h2>Where the rest is written down</h2>
 *
 * <p>{@code plan_m10.md} — §2 for what this engine offers against the Java one and at what fidelity, §3
 * for the loader argument in full, and §12a for the review that produced this table.</p>
 */
package com.crystalgui.language.js;
