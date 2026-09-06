/**
 * The JavaScript engine — Rhino behind every {@code .js} document, and behind Run.
 *
 * <h2>The boundary is the directory structure, and that is the point</h2>
 *
 * <p>This package spans the one boundary that matters in this module: some of it is loaded by the
 * <b>host</b>, and some by the <b>band loader</b> that has Rhino on it. Get it wrong and the failure is
 * a {@code NoClassDefFoundError} on a class that plainly exists, or — worse — a second copy of a class
 * quietly defined on the other side, so two objects of "the same" type are not assignable.</p>
 *
 * <table>
 *   <caption>Two packages, one rule each</caption>
 *   <tr><td>{@link com.crystalgui.language.js.host}</td>
 *       <td>May name {@code language.run} and {@code language.java}. <b>May not name Rhino.</b></td></tr>
 *   <tr><td>{@link com.crystalgui.language.js.rhino}</td>
 *       <td>May name Rhino. <b>May name only JDK types, {@code com.crystalgui.text.*} and
 *       {@code language.engine.bridge} besides.</b></td></tr>
 * </table>
 *
 * <h2>Why a directory here and not in {@code language.java}</h2>
 *
 * <p>Because here it cannot be read off the file. Fourteen of the twenty child-side classes import
 * {@code org.mozilla.javascript} and say so; the other six — {@code JsSignatures}, {@code JsTypeRef},
 * {@code JsKeywords}, {@code LineIndex}, {@code InteropResolver}, {@code JsLoaders} — import neither
 * Rhino nor anything of ours, and are child-side only because <em>every one of their callers is</em>.
 * That fact lives in no file, which is exactly the kind of fact that rots: this was a table in this
 * comment for a release, and the naming convention it documented had already stopped being true
 * ({@code JsQuickFixes} is child-side; {@code RhinoOrigin} is host-side).</p>
 *
 * <p>{@code language.java} splits on what a class is <em>for</em> instead, because there the answer is
 * mechanical — a class that imports {@code org.eclipse.jdt} is child-side, and that is thirty-six of
 * its fifty.</p>
 *
 * <h2>What may cross</h2>
 *
 * <p>Only the three things {@code EngineClassLoader.PARENT_FIRST} shares. That is why the console
 * arrives at the executor as two {@link java.util.function.Consumer}s rather than as a
 * {@code RunConsole}, why the sandbox is a {@link java.util.function.Predicate} rather than a
 * {@code ScriptPolicy}, and why {@code MemberNameMapper} deals in strings rather than in a
 * {@code MappingSet}.</p>
 *
 * <p>A child-side class that names {@code language.java} or {@code language.run} <b>compiles and
 * appears to work</b>: the band loader is child-first over {@code com.crystalgui.language.*} and carries
 * our own class files, so it simply defines its own copy. {@code SimilarNames} was imported that way for
 * a release — harmless, being stateless, and exactly the precedent not worth having. It now lives in
 * {@code com.crystalgui.text}, which is genuinely shared.</p>
 *
 * <h2>Where the rest is written down</h2>
 *
 * <p>{@code plan/lang-javascript.md} — §2 for what this engine offers against the Java one and at what fidelity, §3
 * for the loader argument in full, and §12a for the review that produced this split.</p>
 */
package com.crystalgui.language.js;
