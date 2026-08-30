/**
 * The box tree — everything about a node that is about being on screen.
 *
 * <p>A {@code Box} is created for every composed-tree node whose computed {@code display} is not
 * {@code none}, in composed order, and owns what the old {@code UIElement} used to own about
 * geometry: the layout engine's node and its style, the layout result, the scroll offset, the
 * transform and its origin, opacity, z-index, the paint order of its children, and the world
 * matrices — computed from the box tree when layout completes, never written by paint. It has a
 * <b>host</b>: normally the composed parent; the root for a promoted box; an owner's overlay box for
 * an owned dialog; a frame's content box for a torn-out fragment; a second box drawing the same
 * subtree for a thumbnail. Promotion, owned attachment, tear-out and previews are one operation.
 * plan_engine_core_audit.md §12.3; plan_m5.md 5.3.</p>
 *
 * <p>It draws through {@code com.crystalgui.render} — the paint context, the drawables, the layer
 * FBOs — which is the backend and stays as it is. This package is the tree that records into it.</p>
 *
 * <p>May name {@code ui.dom}, the style pass's {@code ComputedStyle}, {@code render}, {@code taffy}
 * and {@code core}. May not name {@code UIElement}, {@code UIWindow}, {@code TopLayer} or a widget —
 * {@code EngineBoundaryTest} asserts it.</p>
 */
package com.crystalgui.ui.box;
