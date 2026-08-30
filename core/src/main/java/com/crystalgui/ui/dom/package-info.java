/**
 * The seam, and the node tree that implements it natively.
 *
 * <h3>Two things live here, on purpose</h3>
 *
 * <p><b>The seam</b> — {@link com.crystalgui.ui.dom.TreeSource}, {@link com.crystalgui.ui.dom.TreeObserver},
 * {@link com.crystalgui.ui.dom.NodeContract} — is the tree contract the mirror is written against
 * (plan_ui_rewrite.md §0): stable identity with a lifecycle, light-tree iteration, an edit-script
 * observer, a contract per node kind. {@link com.crystalgui.ui.dom.ElementTreeSource} is the OLD
 * engine's implementation over {@code UIElement}, and it stays until M6 ends.</p>
 *
 * <p><b>The node tree</b> — {@code Node}, {@code Document}, {@code ShadowTree}, {@code Slot} and their
 * source — is the NEW engine's implementation, arriving with plan_m5.md 5.1. A node is identity,
 * attributes, children, a shadow tree and events, and nothing else: no geometry, no layout id, no
 * world matrix, no scroll offset, no network field. What is about being on screen belongs to a
 * {@code Box} in {@code ui.box}; what is about style belongs to the style pass.</p>
 *
 * <h3>The strangler line</h3>
 *
 * <p>The node tree may name the seam, {@code com.crystalgui.core.*}, {@code ui.contract} and the
 * event types once they are host-agnostic — and may not name {@code UIElement}, {@code UIWindow}, a
 * widget, or anything in {@code ui.input}, {@code ui.elements} or {@code ui.shadow}. The old engine
 * may name the seam and {@code ElementTreeSource} and nothing else here. {@code EngineBoundaryTest}
 * scans the compiled classes for both directions; it is the rule, this comment is its description.</p>
 */
package com.crystalgui.ui.dom;
