package com.crystalgui.ui.dom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.testsupport.UiDocumentTestBase;

/**
 * <b>A subscription declared once is held for exactly as long as the node is in a tree.</b>
 *
 * <p>The failure this closes is total and silent. {@code disconnected()} drops what a node holds — it
 * must, or a connection outlives its node — so a subscription made in a constructor is gone the first
 * time the node leaves the tree, and nothing remakes it. Nodes leave for ordinary reasons: a dock hiding
 * a panel, a layout rebuilding, a pane being retargeted. Every subscription goes at once, so it presents
 * as the widget dying rather than as one feature breaking, and it stays dead until the process restarts.
 * Four widgets in this repository had it, and one had grown its own copy of this mechanism.</p>
 *
 * @see UINode#whileConnected
 */
public class SubscriptionsFollowTheTreeTest extends UiDocumentTestBase {

    /** A node that declares what it follows in its constructor and never thinks about it again. */
    private static final class Follower extends UIElement {
        int heard;
        int attached;

        Follower(Signal.Action signal) {
            super(Name.of("follower"));
            whileConnected(() -> signal.connect(() -> heard++));
            onConnected(() -> attached++);
        }
    }

    @Test
    public void aDeclaredSubscriptionIsLiveWhileAttached() {
        Signal.Action signal = new Signal.Action();
        Follower node = new Follower(signal);
        document.append(node);
        document.update(W, H);

        signal.emit();
        assertEquals(1, node.heard);
    }

    /** Detached is deaf — the whole point of dropping them. */
    @Test
    public void andIsDroppedOnTheWayOut() {
        Signal.Action signal = new Signal.Action();
        Follower node = new Follower(signal);
        document.append(node);
        document.update(W, H);

        node.removeSelf();
        document.update(W, H);
        signal.emit();
        assertEquals("a detached node is still listening", 0, node.heard);
    }

    /**
     * <b>And it comes back.</b> This is the one that was broken everywhere: a node put back into the
     * tree used to be permanently deaf, and only in cases nobody tests.
     */
    @Test
    public void andIsRemadeOnTheWayBackIn() {
        Signal.Action signal = new Signal.Action();
        Follower node = new Follower(signal);
        document.append(node);
        document.update(W, H);
        node.removeSelf();
        document.update(W, H);
        document.append(node);
        document.update(W, H);

        signal.emit();
        assertEquals("the subscription was not remade", 1, node.heard);
    }

    /** Once per attach, and never twice for one — a duplicate would double every event. */
    @Test
    public void oneSubscriptionPerAttach() {
        Signal.Action signal = new Signal.Action();
        Follower node = new Follower(signal);
        for (int i = 0; i < 3; i++) {
            document.append(node);
            document.update(W, H);
            node.removeSelf();
            document.update(W, H);
        }
        document.append(node);
        document.update(W, H);

        signal.emit();
        assertEquals("the node heard it more than once", 1, node.heard);
        assertEquals("onConnected ran once per attach", 4, node.attached);
    }

    /** Declared after it is already in a tree, which a binder building into a live panel does. */
    @Test
    public void declaringItLateSubscribesOnTheSpot() {
        Signal.Action signal = new Signal.Action();
        Late node = new Late();
        document.append(node);
        document.update(W, H);

        node.follow(signal);
        signal.emit();
        assertEquals(1, node.heard);
    }

    private static final class Late extends UIElement {
        int heard;

        Late() {
            super(Name.of("late"));
        }

        void follow(Signal.Action signal) {
            whileConnected(() -> signal.connect(() -> heard++));
        }
    }

    /**
     * <b>A subclass that forgets {@code super.connected()} keeps its subscriptions.</b>
     *
     * <p>Twelve of the forty-three overrides in this repository do not call it, so running the
     * machinery inside {@code connected()} would have re-created the very trap it closes — silently,
     * and only for those twelve. It runs from the attach itself instead.</p>
     */
    @Test
    public void aForgottenSuperCallCostsNothing() {
        Signal.Action signal = new Signal.Action();
        Forgetful node = new Forgetful(signal);
        document.append(node);
        document.update(W, H);

        signal.emit();
        assertEquals("the subscription was lost to a missing super.connected()", 1, node.heard);
        assertNotNull(node.box());
    }

    private static final class Forgetful extends UIElement {
        int heard;

        Forgetful(Signal.Action signal) {
            super(Name.of("forgetful"));
            whileConnected(() -> signal.connect(() -> heard++));
        }

        @Override
        protected void connected() {
            // Deliberately no super call.
        }
    }
}
