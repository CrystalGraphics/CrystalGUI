/**
 * The box tree — everything about a node that is about being on screen.
 *
 * <p>A {@link com.crystalgui.ui.box.Box} exists for every composed-tree node whose computed
 * {@code display} is not {@code none}, in composed order, and owns the geometry a node deliberately
 * does not: the layout engine's node and its style, the layout result, the scroll offset, the
 * transform and its origin, opacity, z-index, the paint order of its children, and the world matrices
 * — composed by {@link com.crystalgui.ui.box.BoxTree} when layout completes, never written by paint.
 * plan_engine_core_audit.md §12.3; plan_m5.md 5.3.</p>
 *
 * <h3>Hosting is one operation, and four features are it</h3>
 *
 * <p>A box has a <b>host</b>: normally the composed parent; the top layer for a promoted box; an
 * owner's overlay box for an owned dialog; a frame's content box for a torn-out fragment; a second box
 * drawing the same subtree for a thumbnail. Promotion, owned attachment, tear-out and previews are the
 * same mechanism. Hosting also decides out-of-flow — being placed somewhere other than where you sit
 * in the tree is what out-of-flow <em>means</em> — which is why {@code BoxStyle} is told, rather than
 * a promoted popup being expected to remember to set {@code position: absolute}.</p>
 *
 * <h3>One pass, and no feedback into it</h3>
 *
 * <p>Layout runs once. Nothing here writes style, so a pass that wants to move something writes on the
 * NEXT layout — which is why an unplaced popup is laid out off-screen rather than drawn at its
 * containing block's corner. {@link com.crystalgui.ui.box.Measurable} is the inversion that makes it
 * possible: the engine <em>asks</em> a node for a size instead of a node pushing one back, and Taffy
 * asks for min-content as well as max-content, so a text leaf's minimum is not its whole line.</p>
 *
 * <p>{@code Box.hitTest} inverts exactly the matrix the painter will use, so a click lands on what
 * will be drawn with no paint having happened — {@code HitTestBeforePaintTest} is the assertion.
 * {@code Box.x()} is <b>parent-relative</b>, unlike the old engine's accumulated cache, so subtracting
 * two boxes' offsets is only meaningful when they share a parent; {@code centreIn} and {@code originIn}
 * are the conversion, and they carry the intervening transforms and scrolls that a subtraction never
 * did.</p>
 *
 * <p>{@link com.crystalgui.ui.box.BoxPainter} draws every box in its own space with the pose set from
 * {@code localToWorld}, through {@code com.crystalgui.render} — the paint context, the drawables, the
 * layer FBOs — which is the backend and stays as it is. This package is the tree that records into it.
 * {@link com.crystalgui.ui.box.BoxStyle} is the only place the project's layout defaults are stated,
 * and they diverge from CSS on purpose: see the table in {@code AGENTS.md}.</p>
 *
 * <h3>What governs this package</h3>
 *
 * <p>It may name {@code ui.dom}, the style pass's {@code ComputedStyle}, {@code render}, {@code taffy}
 * and {@code core}. It may not name a widget, the desktop or the workbench —
 * {@code LayeringTest.aLayerNamesNothingAboveIt} asserts it. And it <b>writes nothing into the
 * cascade</b>: {@code BoxStyle} READS a {@code ComputedStyle}, and where the old engine pushed geometry
 * back at {@code IMPORTANT} origin in 117 places, this one either asks or writes a compositor override
 * that is withdrawn with a {@code null}. {@code EngineBoundaryTest.theNewEngineWritesNothingIntoTheCascade}
 * is the rule; this comment is its description.</p>
 */
package com.crystalgui.ui.box;
