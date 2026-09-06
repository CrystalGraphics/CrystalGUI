/**
 * The services over the two trees: input, focus, motion, lifecycle, dismissal.
 *
 * <p><b>Each is a service the document owns rather than a method on a node</b>, and that is this
 * package's membership rule. One 962-line input handler became five objects with one job each, and a
 * live interaction became a mode pushed onto a stack rather than another {@code if} at the top of the
 * key handler. plan/engine-audit.md §12.4; plan/engine-core.md 5.5.</p>
 *
 * <ul>
 *   <li>{@link com.crystalgui.ui.service.Input} — the platform sink. Hit-tests the box tree and
 *   dispatches over the composed <em>node</em> tree with per-listener retargeting, holding a
 *   {@link com.crystalgui.ui.service.InputMode} stack instead of hard-coded rungs;
 *   {@link com.crystalgui.ui.service.Drag} is one of those modes. Propagation is the DOM's:
 *   {@code stopPropagation} ends the walk and the same node's remaining listeners still run.</li>
 *   <li>{@link com.crystalgui.ui.service.Focus} — one owner, one traversal, and ONE inertness
 *   predicate over navigation scopes (document, shadow tree, dialog, window), with
 *   {@code delegatesFocus} and modality as scope properties.</li>
 *   <li>{@link com.crystalgui.ui.service.Animation} — timelines whose clock is the host's DELTA, so
 *   "the clock starts on the first tick" is structural rather than remembered. Per-frame hooks are
 *   OWNED by a node and stop when it leaves the tree; {@code afterLayout} is for anything positioned
 *   from measured geometry, because an ordinary hook runs BEFORE this frame's layout.</li>
 *   <li>{@link com.crystalgui.ui.service.Lifecycle} — freeze / thaw / destroy. A frozen subtree keeps
 *   its scroll, its text and its listeners; boxes are dropped and hooks stopped, and the node tree is
 *   untouched, which is the whole difference from detaching it.</li>
 *   <li>{@link com.crystalgui.ui.service.Dismiss} — the popover stack, light dismiss, close watchers
 *   and Escape. The popover stack and the close-watcher stack are separate on purpose: a modal is
 *   Escape-closable and not light-dismissable, and one list gets one of them wrong.</li>
 * </ul>
 *
 * <p><b>{@link com.crystalgui.ui.service.AnchoredPlacement} is the one member that is not a
 * service</b> — a final class of static methods, and the most-consumed helper in the engine at fifteen
 * packages. It is here because every popup needs it and nothing else in {@code ui} is a better home,
 * not because it fits the rule above. Named rather than quietly tolerated, so the rule stays worth
 * something.</p>
 *
 * <h3>What governs this package</h3>
 *
 * <p>It may name {@code ui.dom}, {@code ui.box}, the event types, the platform input SPI and
 * {@code core}. It may not name a widget, the desktop or the workbench —
 * {@code LayeringTest.aLayerNamesNothingAboveIt} asserts it, and it is what stops the service layer
 * learning about a {@code WindowFrame}. {@code ModeStackTest} additionally reads {@code Input}'s
 * constant pool to prove the service names no gesture: drag, the window switcher and keyboard move are
 * push order, not branches.</p>
 */
package com.crystalgui.ui.service;
