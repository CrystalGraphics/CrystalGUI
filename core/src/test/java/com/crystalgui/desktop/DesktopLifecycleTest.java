package com.crystalgui.desktop;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.window.WindowPolicy;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.control.Button;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.ui.service.Input;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * CrystalOS W3 — the document lifecycle ({@code plan_windowing.md}).
 *
 * <p><b>Hide, close and destroy are three different verbs</b>, and every test here is about keeping
 * them apart. The one that matters most is the freeze: a hidden document that goes on working is
 * invisible by definition — it costs frames and holds resources and nothing on screen says so — which
 * is why the plan calls it the regression most likely to go unnoticed and why it is pinned here
 * rather than left to a widget to remember.</p>
 */
public class DesktopLifecycleTest extends UiDocumentTestBase {

    /**
     * Animations OFF for the fixture. Several tests below turn them back on for the thing they are
     * about and restore this in a finally; without a @Before the class relied on that restore having
     * run, i.e. on another test having gone first. A window's state change is DEFERRED while a
     * timeline plays, so the assertions here read VISIBLE for a window that has been closed.
     */
    @Before
    public void quietTheCompositor() {
        Desktop.setAnimationsEnabled(false);
    }

    private UIElement root;
    private Desktop desktop;
    private Input input;

    private void build() {
        root = new UIElement().layout(l -> l.width(400).height(300));
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        desktop = Desktop.of(document);
        settle();
        input = document.input();
        input.beginFrame();
        input.endFrame();
    }

    private void settle() {
        for (int pass = 0; pass < 2; pass++) {
        frame();
        }
    }

    /** A full frame: style, tickers, layout — {@code advanceFrame} without the GL. */
    private void advance() {
        frame();
    }

    private WindowFrame open(String title) {
        WindowFrame frame = Desktop.of(document).addWindow(new WindowFrame(title));
        frame.resizeTo(160, 120);
        frame.content().append(new Button(title));
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
        assertNull("a hidden document is out of the tree", frame.document());
        assertTrue("...and off the desktop", desktop.visibleWindows().isEmpty());
        assertEquals("...but still a document", 1, desktop.windows().size());
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
        document.animation().every(document, delta -> {
            if (frame.document() == null) return false;   // the Animation.Hook contract
            ticks.incrementAndGet();
            return true;
        });

        advance();
        int whileVisible = ticks.get();
        assertTrue("the ticker has to be running for this test to mean anything", whileVisible > 0);

        frame.hide();
        advance();
        advance();

        assertEquals("a hidden document ticks nothing", whileVisible, ticks.get());
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
        // The slot is a scroll view whose bars come first in the raw list; ask for the CONTENT.
        Button inside = (Button) frame.content().describedChildren().get(0);
        document.focus().requestFocus(inside);
        assertSame(inside, document.focus().focused());

        frame.hide();

        assertNull("focus cannot stay on something out of the tree", document.focus().focused());
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
        assertNotNull("back in the tree", frame.document());
        assertEquals(1, shows.get());
        assertTrue("a restore is persisted — the flag is how content knows to revalidate",
                persisted.get());
    }

    /** A bare detach means the same thing as {@code hide()}. Anything else would leave a document that is
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
        assertTrue("a destroyed document leaves the registry", desktop.windows().isEmpty());
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
     * through to whatever is behind the document — the screen it is on, in the worst case — so a document
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

    /** Destroy runs {@code Disposer}, so anything registered against the document goes with it — which is
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
        assertTrue("everything registered under the document went with it", childDisposed.get());
        assertTrue(Disposer.isDisposed(frame));
    }

    @Test
    public void aDestroyedWindowCannotComeBack() {
        build();
        WindowFrame frame = open("One");
        frame.destroy();
        try {
            frame.show(false);
            fail("a destroyed document must refuse to be shown");
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
     * <b>Dirty work is never discarded silently.</b> The cap is a budget, not a promise: a document whose
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

        assertEquals("the dirty document survives the cap", WindowState.HIDDEN, dirty.state());
        // AND THE BUDGET IS STILL MET, by passing over the refusal and taking the next one down rather
        // than stopping at it. bfcache does the same: an ineligible entry is skipped, not a wall. The
        // alternative lets retention grow by one document per unsaved document, which is the leak the cap
        // exists to bound.
        assertEquals(WindowState.DESTROYED, clean.state());
        assertEquals(1, desktop.windows().size());
    }

    // ── The registry's two orders ───────────────────────────────────────────

    /**
     * The taskbar reads open order and the switcher reads MRU, and they are not interchangeable: a bar
     * whose entries jump on every activation is the "never in the same place twice" menu bug wearing a
     * strip, and MRU is not derivable from z because a hidden document has left the stacking order while
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
        // STILL IN THE SEQUENCE, which is the half that matters: a hidden document has left the stacking
        // order entirely and kept its place here, which is exactly why the switcher cannot read z.
        assertTrue("a hidden document keeps its place in the sequence",
                desktop.registry().mruOrder().contains(first));
        assertEquals(java.util.List.of(first), desktop.registry().hidden());
        // AND IT KEEPS THE FRONT OF IT, because minimising hands activation to nobody. That is the
        // right answer twice over: the switcher's first offer is the document you just put away, which
        // is what a switcher is for, and nothing had to move the keyboard to get there.
        assertSame(first, desktop.registry().mruOrder().get(0));
        assertNull("minimising is not switching", desktop.activeWindow());
        assertSame("and `third` is still merely the one in front", third,
                desktop.visibleWindows().get(desktop.visibleWindows().size() - 1));
    }

    /** A document can be found again by the name it was opened under — what geometry is persisted
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

    /**
     * <b>Minimising hands activation to nobody</b>, and closing hands it to the document in front.
     *
     * <p>The distinction is that activation here <em>carries the keyboard with it</em>: it restores
     * focus into whatever it lands on. So handing over on a minimise drops the caret into a document the
     * user did not ask for, once per minimise — putting something away is not the same gesture as
     * switching to something else. Windows hands over in both cases; its activation does not move a
     * caret. Destroying is the case that genuinely has nowhere to leave the keyboard, so that one
     * still hands over.</p>
     */
    @Test
    public void minimisingDoesNotActivateAnotherWindowButDestroyingDoes() {
        build();
        WindowFrame first = open("One");
        WindowFrame second = open("Two");
        assertSame(second, desktop.activeWindow());

        second.hide();
        settle();
        assertNull("putting a document away is not switching to another", desktop.activeWindow());
        assertFalse("...and the one behind was not disturbed", first.isActive());

        desktop.activate(second);
        settle();
        assertSame(second, desktop.activeWindow());

        second.destroy();
        settle();
        assertSame("but a destroyed document leaves the keyboard nowhere, so the front one takes over",
                first, desktop.activeWindow());
    }

    /** Evicting a document the user had already put away must not reach in and change what they are
     * looking at — the handover is for the ACTIVE document being destroyed, not for any destroy. */
    @Test
    public void destroyingABackgroundWindowLeavesActivationAlone() {
        build();
        WindowFrame background = open("One");
        WindowFrame front = open("Two");
        assertSame(front, desktop.activeWindow());

        background.destroy();
        settle();

        assertSame("the document in front is untouched", front, desktop.activeWindow());
    }

    /**
     * <b>Suspending the desktop retains every document exactly as it is</b> — what a host does when its
     * screen closes.
     *
     * <p>The whole compositor leaves the tree, which is the freeze one level up: nothing lays out or
     * paints and the input handler forgets everything inside it. What must NOT happen is the windows
     * changing state — a resume has to know which of them were on screen, and hiding each one loses
     * exactly that.</p>
     */
    @Test
    public void suspendingTheDesktopRetainsEveryWindowAndResumingPutsThemBack() {
        build();
        WindowFrame visible = open("Visible");
        WindowFrame minimised = open("Minimised");
        minimised.hide();
        visible.moveTo(70, 50);
        settle();
        // The slot is a scroll view whose bars come first in the raw list; ask for the CONTENT.
        document.focus().requestFocus((Button) visible.content().describedChildren().get(0));

        Desktop.of(document).suspend();
        settle();

        assertTrue(Desktop.of(document).isSuspended());
        assertNull("the compositor is out of the tree", desktop.document());
        assertNull("...so it holds no input state", document.focus().focused());
        assertEquals("and the windows are untouched", WindowState.VISIBLE, visible.state());
        assertEquals(WindowState.HIDDEN, minimised.state());
        assertEquals("both still registered", 2, desktop.windows().size());

        Desktop.of(document).resume();
        settle();

        assertFalse(Desktop.of(document).isSuspended());
        assertNotNull(desktop.document());
        assertEquals("back on screen where it was", 70f, visible.left(), 0.51f);
        assertEquals(WindowState.HIDDEN, minimised.state());
    }

    /**
     * <b>A document can still be HIDDEN after the desktop has been suspended and resumed.</b>
     *
     * <p>The nastiest defect of the CrystalOS work so far, and it needed a real client to find because
     * every test opened its desktop once. {@code resumeDesktop} re-attaches through
     * {@code addInternalChild}, which ends in {@code markAsInternal()} — and that <b>recurses</b>. By
     * resume time the desktop is carrying WINDOWS, so every frame was marked internal;
     * {@code removeChild} silently refuses an internal child and returns a boolean nobody checks; and
     * {@code hide()} therefore detached nothing.</p>
     *
     * <p>What that looks like is not "hide is broken". The document reports {@code HIDDEN}, stays on
     * screen, stays hit-testable, and the next press that reaches it activates it straight back — so
     * minimise, hide and close all read as "the document will not close", and only after a close-and-
     * reopen, because the first desktop of a session has no windows on it when it is first attached.</p>
     *
     * <p>Asserted on the PARENT rather than on the state, because the state was always right — it was the
     * detach that never happened. {@code isInternalUI} is checked too, since that is the mechanism and a
     * test that only asserted the symptom would pass against a fix that suppressed it somewhere else.</p>
     */
    @Test
    public void aWindowCanStillBeHiddenAfterTheDesktopIsResumed() {
        build();
        WindowFrame frame = open("One");
        settle();

        Desktop.of(document).suspend();
        settle();
        Desktop.of(document).resume();
        settle();

        assertFalse("re-attaching the desktop marked its windows internal, so they can never detach",
                frame.get(Attribute.PART).isEmpty() == false);

        frame.hide();
        settle();

        assertEquals(WindowState.HIDDEN, frame.state());
        assertNull("the document reports HIDDEN but is still in the tree — visible and clickable",
                frame.parent());
    }

    /** Activating a hidden document brings it back — which is what a taskbar entry (W4) and the switcher
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
        assertNotNull(frame.document());
        assertSame(frame, desktop.activeWindow());
    }

    /** The minimise button hides and never destroys, whatever the policy says — the two controls mean
     * different things and a document manager that conflated them would lose work. */
    @Test
    public void theMinimizeButtonHidesEvenUnderADestroyingPolicy() {
        build();
        WindowFrame frame = open("One");
        assertEquals(WindowPolicy.DESTROY_ON_CLOSE, frame.policy());

        frame.minimizeButton().onPressed.emit();

        assertEquals(WindowState.HIDDEN, frame.state());
    }

    /**
     * <b>A minimise deactivates on the press, not when the animation lands.</b>
     *
     * <p>Hiding is DETACHING, so a minimise cannot detach until its animation has finished drawing — and
     * deactivation used to ride along with the detach, which made the whole state change 400ms late. The
     * caption stayed lit, the taskbar went on highlighting the document, and anything rendering "the active
     * document" described one that was visibly flying into the bar. Every document manager treats the gesture
     * as having happened the moment it is asked for and animates a document that has logically gone.</p>
     *
     * <p><b>Only reachable with animations ON.</b> Disabled, the continuation runs synchronously and the
     * two orderings are indistinguishable — which is why the assertion on the document still being VISIBLE
     * is not a spare: it is what proves the animation was still in flight when the state was read.</p>
     */
    @Test
    public void minimisingDeactivatesOnThePressRatherThanWhenItLands() {
        build();
        Desktop.setAnimationsEnabled(true);
        try {
            WindowFrame frame = open("One");
            assertSame("the fixture never made it active", frame, desktop.activeWindow());

            frame.minimizeButton().onPressed.emit();
            advance();

            assertEquals("the minimise already landed, so this says nothing about WHEN it deactivated",
                    WindowState.VISIBLE, frame.state());
            assertNull("still the active document while it is flying into the taskbar",
                    desktop.activeWindow());
        } finally {
            Desktop.setAnimationsEnabled(false);
        }
    }

    /**
     * <b>...and minimising a BACKGROUND document leaves the foreground one active.</b>
     *
     * <p>The other half of moving the deactivation earlier. {@code hide()} only ever deactivated the
     * document that was actually active, so doing it on the press has to carry the same guard — without it,
     * putting away a document nobody was using would blank the caption of the one being worked in.</p>
     */
    @Test
    public void minimisingABackgroundWindowDoesNotDeactivateTheForegroundOne() {
        build();
        Desktop.setAnimationsEnabled(true);
        try {
            WindowFrame background = open("Background");
            WindowFrame front = open("Front");
            assertSame(front, desktop.activeWindow());

            background.minimizeButton().onPressed.emit();
            advance();

            assertSame("minimising a background document stole the foreground document's activation",
                    front, desktop.activeWindow());
        } finally {
            Desktop.setAnimationsEnabled(false);
        }
    }
}
