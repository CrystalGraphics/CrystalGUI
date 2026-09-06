package com.crystalgui.ui.event;


/**
 * Close-request events — the web's {@code CloseWatcher} surface.
 *
 * <p>Only {@link Cancel} exists, and only because it is <b>cancelable</b>: that is the one thing a
 * {@code Signal} cannot express, and this engine's rule is that Signals carry plain notifications while
 * events carry things that propagate or can be prevented. The matching "it closed" notification is
 * {@code Dialog.onClosed}, an ordinary {@code Signal.Action} — adding a second spelling of it here would
 * be two mechanisms for one fact.</p>
 */
public abstract class CloseEvent extends UIEvent {

    protected CloseEvent(EventTarget target, boolean bubbles) {
        super(target, bubbles);
    }

    /**
     * The user asked to dismiss this element (Escape). Calling {@link #preventDefault()} keeps it open.
     *
     * <p><b>Does not bubble</b>, matching the spec's {@code cancel} event — a close request is about one
     * specific element, and letting it bubble would let an outer dialog cancel an inner one's dismissal.
     * </p>
     */
    public static class Cancel extends CloseEvent {
        public Cancel(EventTarget target) {
            super(target, false);
        }
    }
}
