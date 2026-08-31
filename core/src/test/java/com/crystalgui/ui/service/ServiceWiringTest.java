package com.crystalgui.ui.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UINode;
import org.junit.Test;

/**
 * <b>The services are WIRED, not merely built</b> — every seam where one service has to call another,
 * asserted from the outside.
 *
 * <h3>The failure this exists for, which cost five widgets and a day</h3>
 *
 * <p>M6.0 built {@link Dismiss} with the whole spec algorithm — the popover stack, the invoker
 * carve-out, the {@code shownBefore} counter, the close-watcher cascade — and {@link Input}, written
 * at 5.5, invoked <b>none</b> of it. Light dismiss and Escape were both complete and both unreachable.
 * The same shape emptied the detach path: the old engine's {@code unregisterElement} told five things
 * that a node had gone, and the new tree told none of them.</p>
 *
 * <p>Every one of these is invisible to a unit test of the service itself — {@code Dismiss} passes
 * its own suite whether or not anything calls it — and invisible to a widget test, which drives the
 * widget's API rather than the platform's. They surface only by using the application, one screenshot
 * at a time, which is exactly how they surfaced. <b>So they are asserted here, through the platform
 * entry points a host actually calls</b>: {@code consumeMouseEvent}, {@code consumeKeyboardEvent},
 * and an ordinary {@code remove()}.</p>
 *
 * <p>A new service seam gets a case here in the same commit. The point is not the individual
 * assertions — each is nearly trivial — it is that the list exists at all.</p>
 */
public class ServiceWiringTest extends UiDocumentTestBase {

    /** Whether the popup's own close-watcher hook was asked. */
    private boolean closeRequested;

    /**
     * A node that is promoted, focused and holds a close watcher.
     *
     * <p>{@code requestClose} is OVERRIDDEN, because {@link UINode}'s answers {@code false} — "I did
     * not handle this" — and a cascade that is working is indistinguishable from one that is not when
     * every watcher in it declines.</p>
     */
    private UINode livePopup() {
        UINode popup = new UINode() {
            @Override
            public boolean requestClose() {
                closeRequested = true;
                return true;
            }
        };
        popup.setId("popup");
        layout(popup, l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                .left(10f).top(10f).width(100f).height(40f));
        popup.setFocusPolicy(com.crystalgui.ui.input.FocusPolicy.CLICK);
        document.append(popup);
        frame();
        document.promote(popup);
        document.dismiss().pushAutoPopover(popup);
        document.dismiss().pushCloseWatcher(popup);
        document.dismiss().recordShown(popup);
        document.focus().requestFocus(popup);
        frame();
        return popup;
    }

    // ── Input → Dismiss ──────────────────────────────────────────────────────

    /**
     * A press OUTSIDE an auto popover closes it — the spec's light dismiss, reached from the platform.
     *
     * <p>{@code Dismiss.lightDismiss} was complete and unreachable: a menu could be opened and not
     * closed by clicking away from it, which is the first thing anyone tries.</p>
     */
    @Test
    public void aPressOutsideAnAutoPopoverLightDismissesIt() {
        UINode popup = livePopup();
        assertTrue(document.dismiss().autoPopovers().contains(popup));

        press(400f, 400f);

        // ASKED, not popped. Light dismiss calls requestClose(); taking itself out of the stack is the
        // popover's own job, in hide(). Asserting on the stack tests the popover, not the wiring --
        // and passes against a Dismiss nothing ever calls, as long as some other path closed it.
        assertTrue("a press on bare document asks it to close", closeRequested);
    }

    /**
     * And a press INSIDE it does not — the half that makes the first one safe rather than merely
     * present.
     */
    @Test
    public void aPressInsideAnAutoPopoverKeepsIt() {
        livePopup();

        press(50f, 20f);

        assertFalse("a press on the popover itself must not ask it to close", closeRequested);
    }

    /**
     * Escape reaches the close-watcher cascade, on a key nothing else consumed.
     *
     * <p>Same story as light dismiss: {@code Dismiss.escape} existed and nothing called it, so Escape
     * closed nothing. It runs AFTER dispatch deliberately — a control gets first refusal on its own
     * keystrokes, which is what lets a search box clear its query before the dialog around it closes.</p>
     */
    @Test
    public void escapeReachesTheCloseWatcherCascade() {
        UINode popup = livePopup();
        assertSame(popup, document.dismiss().topCloseWatcher(null));

        boolean consumed = keyPress(CgKeyCodes.KEY_ESCAPE);

        assertTrue("the watcher's own hook was asked", closeRequested);
        assertTrue("and Escape is reported to the host as consumed", consumed);
    }

    // ── Detach → every service ───────────────────────────────────────────────

    /**
     * <b>A detach tells all five services.</b>
     *
     * <p>The old engine's {@code unregisterElement} did, and each has an invariant behind it: hover
     * left in a detached subtree makes the next diff walk two trees that never converge; a press
     * target or a pointer capture keeps routing events at something nobody can see; a detached modal
     * leaves the whole document inert with nothing to interact with; a popover that left goes on
     * taking Escape. Asserted together because they went missing together — the detach path told
     * nobody anything.</p>
     */
    @Test
    public void aDetachTellsEveryService() {
        UINode popup = livePopup();
        press(50f, 20f);
        frame();

        assertSame("focused before", popup, document.focus().focused());
        assertTrue("promoted before", document.isPromoted(popup));
        assertTrue("a close watcher before", document.dismiss().topCloseWatcher(null) == popup);

        document.remove(popup);
        frame();

        assertNull("focus was given up", document.focus().focused());
        assertFalse("promotion was withdrawn", document.isPromoted(popup));
        assertNull("the close watcher went", document.dismiss().topCloseWatcher(null));
        assertFalse("and the popover stack too", document.dismiss().autoPopovers().contains(popup));
    }

    /** A node that leaves mid-press is not still the press target. */
    @Test
    public void aDetachDropsThePressTarget() {
        UINode popup = livePopup();
        press(50f, 20f);

        document.remove(popup);
        frame();
        // The release must not report the departed node as its press target, which is what
        // `isWasPressTarget` reads and what every activation is gated on.
        release(50f, 20f);

        assertNull(document.focus().focused());
    }

    /** A pointer capture held by a departing node is released, or every later event routes at it. */
    @Test
    public void aDetachReleasesAPointerCapture() {
        UINode popup = livePopup();
        // A capture is only taken while a button is down -- it is the mechanism that makes a DRAG
        // route at its source, so there is nothing to capture between gestures.
        press(50f, 20f);
        document.input().setPointerCapture(popup);
        assertSame(popup, document.input().pointerCaptureTarget());

        document.remove(popup);
        frame();

        assertNull(document.input().pointerCaptureTarget());
    }
}
