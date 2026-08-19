/**
 * The semantic layer's contracts — interfaces only, no engine, no natives.
 *
 * <h2>What lives here and what may not</h2>
 *
 * <p>{@code core/} has to load on a dedicated server: no GL, no native libraries, and certainly no
 * 15MB compiler. So this package is the <b>whole footprint</b> of the language stack inside
 * {@code core/} — the types an engine implements and the editor consumes, and nothing that could
 * drag an engine in behind it. ECJ, Rhino and the tree-sitter natives all live in {@code language/},
 * which depends on this and is never depended upon.</p>
 *
 * <p>{@code core:headlessTest} is the proof, exactly as it is for GL: it runs with the engines absent,
 * so anything here that reached one would fail there rather than in production.</p>
 *
 * <h2>The two async shapes, and why there are two</h2>
 *
 * <p>Deciding this once is most of what this package is for, because the wrong shape is invisible until
 * it is load-bearing:</p>
 *
 * <ul>
 *   <li><b>Push, with an invalidation range</b> — {@link com.crystalgui.text.lang.SemanticTokenProvider}.
 *       Continuous background analysis nobody asked for. The consumer is told that answers changed and
 *       pulls synchronously per row from a cache, which is the shape
 *       {@link com.crystalgui.text.syntax.SyntaxTokenizer} already has and the per-row cache is built on.</li>
 *   <li><b>Request, with a callback that may never fire</b> — {@link com.crystalgui.text.lang.Resolver}
 *       and {@link com.crystalgui.text.lang.CompletionProvider}. A user-initiated question about a caret
 *       position that stops being interesting the moment it moves.</li>
 * </ul>
 *
 * <p>LSP splits these the same way and for the same reason — {@code publishDiagnostics} is a
 * notification, {@code hover} is a request — which is worth knowing before anyone tries to unify them.</p>
 *
 * <h2>Three types that are deliberately not here</h2>
 *
 * <ul>
 *   <li><b>{@code Diagnostic}</b> — already exists, fully LSP-shaped, in
 *       {@link com.crystalgui.text.diagnostic}, with a per-owner
 *       {@link com.crystalgui.text.diagnostic.DiagnosticSet} that is exactly what independent engines
 *       need. An engine publishes into the document's set; nothing is mirrored here.</li>
 *   <li><b>A text-edit type</b> — {@link com.crystalgui.text.Change} is already LSP's {@code TextEdit}:
 *       a range and a replacement, in offsets against the document being edited.</li>
 *   <li><b>A completion-item kind</b> — {@link com.crystalgui.text.lang.SymbolKind} covers both, and a
 *       near-identical second enum would need a mapping table between them that nobody would keep
 *       current.</li>
 * </ul>
 *
 * <p>Each was checked before it was written. The rule that produced all three: <b>an SPI that duplicates
 * a type the codebase already has is worse than one that reuses it</b>, because the two drift and no
 * caller can tell which is authoritative.</p>
 *
 * @see com.crystalgui.text.lang.LanguageServices the per-document façade everything is reached through
 */
package com.crystalgui.text.lang;
