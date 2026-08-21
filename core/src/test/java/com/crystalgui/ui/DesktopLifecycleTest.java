package com.crystalgui.ui;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.desktop.Desktop;
import com.crystalgui.ui.elements.desktop.WindowFrame;
import com.crystalgui.ui.elements.desktop.WindowPolicy;
import com.crystalgui.ui.elements.desktop.WindowState;
import com.crystalgui.ui.input.UIInputHandler;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * CrystalOS W3 — the window lifecycle ({@code plan_windowing.md}).
 *
 * <p><b>Hide, close and destroy are three different verbs</b>, and every test here is about keeping
 * them apart. The one that matters most is the freeze: a hidden window that goes on working is
 * invisible by definition — it costs frames and holds resources and nothing on screen says so — which
 * is why the plan calls it the regression most likely to go unnoticed and why it is pinned here
 * rather than left to a widget to remember.</p>
 */
public class DesktopLifecycleTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;
    private Desktop desktop;
    private UIInputHandler input;

    private void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        desktop = window.desktop();
        settle();
        input = window.getInputHandler();
        input.beginFrame();
        input.endFrame();
    }

    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
            window.getStyleEngine().calculateStyle(0.016f);
            window.calculateLayout();
        }
    }

    /** A full frame: style, tickers, layout — {@code advanceFrame} without the GL. */
    private void advance() {
        window.updateWithoutPainting();
    }

    private WindowFrame open(String title) {
        WindowFrame frame = window.openWindow(new WindowFrame(title));
        frame.resizeTo(160, 120);
        frame.content().addChild(new Button(title));
        settle();
        return frame;
    }

    // ── Hide is detach, and the freeze falls out of it ──────────────────────

    @Test
    public void hidingDetachesAndRetains() {
        build();
        WindowFrame frame = open("One");

        frame.hide();
        settle();

        assertEquals(WindowState.HIDDEN, frame.state());
        assertNull("a hidden window is out of the tree", frame.getAttachedWindow());
        assertTrue("...and off the desktop", desktop.visibleWindows().isEmpty());
        assertEquals("...but still a window", 1, desktop.windows().size());
        assertSame(frame, desktop.windows().get(0));
    }

    /**
     * <b>The regression most likely to be invisible.</b> A ticker is the one thing detachment does not
     * stop by itself — registration is one-way by design — so the contract is that a ticker whose
     * element has left the tree returns false, and this is what holds it.
     */
    @Test
    public void aHiddenWindowFiresNoTickers() {
        build();
        WindowFrame frame = open("One");
        AtomicInteger ticks = new AtomicInteger();
        window.registerTicker(delta -> {
            if (frame.getAttachedWindow() == null) return false;   // the UIFrameTicker contract
            ticks.incrementAndGet();
            return true;
        });

        advance();
        int whileVisible = ticks.get();
        assertTrue("the ticker has to be running for this test to mean anything", whileVisible > 0);

        frame.hide();
        advance();
        advance();

        assertEquals("a hidden window ticks nothing", whileVisible, ticks.get());
    }

    /**
     * Hiding drops every input reference in the subtree — hover, the press target, pointer capture, a
     * drag anchored inside. It comes free from {@code onRemoved} recursing, which is exactly why it is
     * worth a test: nothing in {@code hide()} does it, so a future change to how hiding detaches would
     * take it away silently.
     */
    @Test
    public void hidingClearsInputStateInsideTheWindow() {
        build();
        WindowFrame frame = open("One");
        Button inside = (Button) frame.content().getChildren().get(0);
        input.requestFocus(inside);
        assertSame(inside, input.getFocusedElement());

        frame.hide();

        assertNull("focus cannot stay on something out of the tree", input.getFocusedElement());
    }

    @Test
    public void showingPutsItBackAndSaysItWasRestored() {
        build();
        WindowFrame frame = open("One");
        AtomicBoolean persisted = new AtomicBoolean();
        AtomicInteger shows = new AtomicInteger();
        frame.onShown.connect(value -> {
            shows.incrementAndGet();
            persisted.set(value);
        });

        frame.hide();
        frame.show(true);
        settle();

        assertEquals(WindowState.VISIBLE, frame.state());
        assertNotNull("back in the tree", frame.getAttachedWindow());
        assertEquals(1, shows.get());
        assertTrue("a restore is persisted — the flag is how content knows to revalidate",
                persisted.get());
    }

    /** A bare detach means the same thing as {@code hide()}. Anything else would leave a window that is
     * out of the tree and still claims to be visible. */
    @Test
    public void removingAWindowByHandIsHiding() {
        build();
        WindowFrame frame = open("One");

        frame.removeSelf();

        assertEquals(WindowState.HIDDEN, frame.state());
        assertEquals("still retained", 1, desktop.windows().size());
    }

    // ── Close is a request, routed through the policy ───────────────────────

    @Test
    public void closeDestroysByDefault() {
        build();
        WindowFrame frame = open("One");

        assertTrue(frame.requestClose());

        assertEquals(WindowState.DESTROYED, frame.state());
        assertTrue("a destroyed window leaves the registry", desktop.windows().isEmpty());
    }

    @Test
    public void closeHidesWhenThePolicySaysSo() {
        build();
        WindowFrame frame = open("One").setPolicy(WindowPolicy.HIDE_ON_CLOSE);

        assertTrue(frame.requestClose());

        assertEquals(WindowState.HIDDEN, frame.state());
        assertEquals("retained, and its way back is the taskbar", 1, desktop.windows().size());
    }

    /**
     * A guard that refuses still <b>handles</b> the request. Answering false would let Escape fall
     * through to whatever is behind the window — the screen it is on, in the worst case — so a window
     * that declined to close would close the application instead.
     */
    @Test
    public void aDiscardGuardCanRefuseACloseAndStillHandleIt() {
        build();
        WindowFrame frame = open("One");
        frame.setDiscardGuard(() -> false);

        assertTrue("refusing is handling", frame.requestClose());
        assertEquals(WindowState.VISIBLE, frame.state());
    }

    /** Destroy runs {@code Disposer}, so anything registered against the window goes with it — which is
     * what makes eviction safe to automate. */
    @Test
    public void destroyDisposesWhatIsRegisteredAgainstTheWindow() {
        build();
        WindowFrame frame = open("One");
        AtomicBoolean childDisposed = new AtomicBoolean();
        Disposable child = () -> childDisposed.set(true);
        Disposer.register(frame, child);

        frame.destroy();

        assertEquals(WindowState.DESTROYED, frame.state());
        assertTrue("everything registered under the window went with it", childDisposed.get());
        assertTrue(Disposer.isDisposed(frame));
    }

    @Test
    public void aDestroyedWindowCannotComeBack() {
        build();
        WindowFrame frame = open("One");
        frame.destroy();
        try {
            frame.show(false);
            fail("a destroyed window must refuse to be shown");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("destroyed"));
        }
    }

    // ── Bounded retention ───────────────────────────────────────────────────

    /**
     * Retention is bounded from day one. An unbounded retained set is a slow leak that only shows up in
     * long sessions — the shape of bug a player finds and a test never does.
     */
    @Test
    public void hidingPastTheCapEvictsTheLeastRecentlyUsed() {
        build();
        desktop.registry().setHiddenCap(2);

        WindowFrame first = open("One");
        WindowFrame second = open("Two");
        WindowFrame third = open("Three");
        // Activated most-recent-last, so `first` is the least recently used of the three.
        desktop.activate(first);
        desktop.activate(second);
        desktop.activate(third);

        first.hide();
        second.hide();
        third.hide();
        settle();

        assertEquals("the least recently used one was discarded", WindowState.DESTROYED, first.state());
        assertEquals(WindowState.HIDDEN, second.state());
        assertEquals(WindowState.HIDDEN, third.state());
        assertEquals(2, desktop.windows().size());
    }

    /**
     * <b>Dirty work is never discarded silently.</b> The cap is a budget, not a promise: a window whose
     * content refuses is kept even over the cap, because the alternative — throwing away unsaved work
     * while nobody is looking — is the failure this whole lifecycle exists to prevent.
     */
    @Test
    public void evictionSkipsAWindowWhoseContentRefuses() {
        build();
        desktop.registry().setHiddenCap(1);

        WindowFrame dirty = open("Dirty");
        dirty.setDiscardGuard(() -> false);
        WindowFrame clean = open("Clean");
        desktop.activate(dirty);
        desktop.activate(clean);

        dirty.hide();
        clean.hide();
        settle();

        assertEquals("the dirty window survives the cap", WindowState.HIDDEN, dirty.state());
        // AND THE BUDGET IS STILL MET, by passing over the refusal and taking the next one down rather
        // than stopping at it. bfcache does the same: an ineligible entry is skipped, not a wall. The
        // alternative lets retention grow by one window per unsaved document, which is the leak the cap
        // exists to bound.
        assertEquals(WindowState.DESTROYED, clean.state());
        assertEquals(1, desktop.windows().size());
    }

    // ── The registry's two orders ───────────────────────────────────────────

    /**
     * The taskbar reads open order and the switcher reads MRU, and they are not interchangeable: a bar
     * whose entries jump on every activation is the "never in the same place twice" menu bug wearing a
     * strip, and MRU is not derivable from z because a hidden window has left the stacking order while
     * keeping its place in the sequence.
     */
    @Test
    public void theRegistryKeepsOpenOrderAndMruSeparately() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");
        WindowFrame third = open("Three");

        desktop.activate(first);

        assertEquals("open order is stable", java.util.List.of(first, second, third),
                desktop.registry().windows());
        assertSame("most recently activated first", first, desktop.registry().mruOrder().get(0));

        first.hide();
        // STILL IN THE SEQUENCE, which is the half that matters: a hidden window has left the stacking
        // order entirely and kept its place here, which is exactly why the switcher cannot read z.
        assertTrue("a hidden window keeps its place in the sequence",
                desktop.registry().mruOrder().contains(first));
        assertEquals(java.util.List.of(first), desktop.registry().hidden());
        // It is no longer the FRONT of it, and that is right rather than incidental: hiding the active
        // window hands activation to the next one down, and being activated is what moves a window to
        // the front of the MRU. A test that demanded otherwise would be demanding that closing the
        // front window leave the switcher offering it back first.
        //
        // `third`, not `second` -- the window that takes over is the one in FRONT, which is a z-order
        // question and not a list-order one. Opening raises, so the last one opened is on top.
        assertSame(third, desktop.registry().mruOrder().get(0));
        assertSame(third, desktop.activeWindow());
    }

    /** A window can be found again by the name it was opened under — what geometry is persisted
     * against (W12) and what "reopen what I had" looks up. */
    @Test
    public void aWindowCanBeFoundByItsKey() {
        build();
        WindowFrame frame = open("One").setKey("editor:main");

        assertSame(frame, desktop.registry().byKey("editor:main"));
        assertNull(desktop.registry().byKey("nothing"));
        assertNull("anonymous windows never match", desktop.registry().byKey(null));

        frame.destroy();
        assertNull("and a destroyed one is gone from the registry", desktop.registry().byKey("editor:main"));
    }

    /** Activating a hidden window brings it back — which is what a taskbar entry (W4) and the switcher
     * (W10) both mean by "activate". */
    @Test
    public void activatingAHiddenWindowRestoresIt() {
        build();
        WindowFrame frame = open("One");
        frame.hide();
        settle();

        desktop.activate(frame);
        settle();

        assertEquals(WindowState.VISIBLE, frame.state());
        assertNotNull(frame.getAttachedWindow());
        assertSame(frame, desktop.activeWindow());
    }

    /** The minimise button hides and never destroys, whatever the policy says — the two controls mean
     * different things and a window manager that conflated them would lose work. */
    @Test
    public void theMinimizeButtonHidesEvenUnderADestroyingPolicy() {
        build();
        WindowFrame frame = open("One");
        assertEquals(WindowPolicy.DESTROY_ON_CLOSE, frame.policy());

        frame.minimizeButton().onPressed.emit();

        assertEquals(WindowState.HIDDEN, frame.state());
    }
}
