/**
 * ECJ, driven — the compiler and the DOM, turned into this codebase's vocabulary.
 *
 * <p>Everything here is <b>child-side</b>: it names {@code org.eclipse.jdt} directly, so only the band
 * loader can define it. What leaves this package leaves as a {@code bridge.Analysis} — a
 * {@code SyntaxToken}, a {@code Diagnostic}, a {@code SymbolInfo} — never as a JDT type.</p>
 *
 * <p>The split inside is between <em>running</em> the compiler ({@code EcjScriptCompiler}), <em>reading</em>
 * the DOM it produces ({@code EcjSourceAnalyzer}), and the two policy questions that have to be answered
 * the same way by both: how this band's ECJ is configured ({@code EcjOptions}), and which of its
 * problems are worth reporting and how they should read ({@code EcjProblemPolicy}).</p>
 *
 * <p>{@code ProblemSpans} is here rather than in {@code .fix} because ECJ's span and the span worth
 * underlining are not always the same, and both the squiggle and the quick-fix router have to agree
 * about which one they mean. {@code SourcePackages} is here for the same reason: the analyser and the
 * compiler both have to tell ECJ what unit they are handing it, and they must say the same thing.</p>
 */
package com.crystalgui.language.java.ecj;
