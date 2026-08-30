package com.crystalgui.ui.event;

/**
 * What an event is dispatched to and what a listener is attached to.
 *
 * <p>A marker, deliberately: the event types carry a target and a listener receives the thing it was
 * attached to, and neither needs to know what that thing can do. {@code UIElement} implements it for
 * the old engine and the node tree's {@code Node} for the new one (plan_m5.md 5.1, D5.6), which is
 * what lets one set of event types and one listener group serve both. A handler that needs the
 * element casts, and says so.</p>
 */
public interface EventTarget {
}
