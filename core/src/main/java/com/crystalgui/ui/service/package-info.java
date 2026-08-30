/**
 * The services over the two trees: input, focus, motion, lifecycle.
 *
 * <p>Each is a service the document owns rather than a method on a node. Input hit-tests the box tree
 * and dispatches on the composed node tree with retargeting, holding a mode stack instead of
 * hard-coded rungs. Focus is one algorithm over navigation scopes — document, shadow tree, dialog,
 * window — with {@code delegatesFocus} and modality as a scope property. Motion is one timeline
 * writing box properties, with transitions as its cascade-facing client. Lifecycle freezes a retained
 * subtree in place — boxes dropped, hooks stopped, node tree untouched — instead of detaching it.
 * plan_engine_core_audit.md §12.4; plan_m5.md 5.5.</p>
 *
 * <p>May name {@code ui.dom}, {@code ui.box}, the event types, the platform input SPI and
 * {@code core}. May not name {@code UIElement}, {@code UIWindow}, {@code UIInputHandler} or a widget —
 * {@code EngineBoundaryTest} asserts it.</p>
 */
package com.crystalgui.ui.service;
