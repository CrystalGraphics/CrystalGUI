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
 *
 * <p>{@code JsCompatibility} is the band question pointed the other way: not what <em>this</em> engine
 * takes but what an <b>older</b> one refuses, so a modder on Java 17 is told which lines a 1.7.10 player
 * cannot load. It reads text over located nodes rather than calling accessors, for the reason
 * {@code RhinoTokens} exists.</p>
 *
 * <p><b>{@code JsImports} is the one class here that BOTH loaders define, deliberately.</b> The grammar
 * has to blank the same statements or tree-sitter mis-colours the whole file, and a bytecode scan
 * forbids {@code language.js} from naming {@code language.grammar} — so the reference runs the other
 * way and there are two copies in the process. Safe only because it is stateless and hands over nothing
 * but {@code String}s; the moment {@code Scanned} or {@code Imported} crossed the bridge the two copies
 * would stop being assignable, which is exactly what the rule above is about.</p>
 */
package com.crystalgui.language.js.rhino;
