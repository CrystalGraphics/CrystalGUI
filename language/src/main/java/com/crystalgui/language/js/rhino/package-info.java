/**
 * The half the <b>band loader</b> defines — everything that may name Rhino, and nothing that may name us.
 *
 * <p>Membership is by loader, not by subject: a class is here because it is defined on the far side of
 * the bridge. Six of the twenty import no Rhino at all and are here because every one of their callers
 * does — putting them on the host side would define a second copy of each, on both sides, of a type the
 * two sides then exchange.</p>
 *
 * <p><b>A class here may name only JDK types, {@code com.crystalgui.text.*} and
 * {@code language.engine.bridge}.</b> Not {@code language.run}, not {@code language.java}, not
 * {@code language.map} — each of those compiles and each quietly duplicates itself.</p>
 *
 * <p>At this root: {@code RhinoSourceAnalyzer}, the entry point and the engine's side of the analysis
 * bridge; {@code RhinoScopes}, read straight out of Rhino's own parse rather than re-derived;
 * {@code RhinoSemanticTokens}, the colours a grammar cannot produce; {@code RhinoProblemPolicy}, what
 * this engine reports and how it should read; and three that exist because the bands disagree —
 * {@code RhinoTokens} (a {@code Token} constant is an inlined {@code int} and the bands renumbered the
 * set), {@code RhinoThread} (every entry runs with the engine loader on the thread, or Rhino never finds
 * its regular-expression engine), and {@code JsKeywords} (measured from the running band, because the
 * grammar parses more than the engine accepts).</p>
 */
package com.crystalgui.language.js.rhino;
