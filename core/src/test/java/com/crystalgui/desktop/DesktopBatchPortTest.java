package com.crystalgui.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.window.WindowState;
import com.crystalgui.desktop.motion.WindowAnimator;
import com.crystalgui.desktop.taskbar.Taskbar;
import com.crystalgui.desktop.window.SnapZones;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.desktop.window.WindowRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.dom.UINode;
import org.joml.Vector2f;
import com.crystalgui.widget.dnd.Resizer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The 6.6 batch on the new engine — the compositor, the window, the strip.
 *
 * <h3>What is asserted, and why these things</h3>
 *
 * <p>Not the widgets' features, which the old engine's ~40 desktop test files already cover and which
 * move with them at 6.9. This is the port's own risk list, and every case below is one the batch's
 * plan named in advance or one a defect was found at:</p>
 *
 * <ul>
 *   <li><b>The compositor takes no space until a window is open</b> — this codebase's most-repeated
 *       failure, and it moved from an IMPORTANT write to a class plus a sheet rule, so the thing that
 *       could break is now "the class is not applied" rather than "the write is wrong".</li>
 *   <li><b>{@code Box.x()} is parent-relative</b>, which killed the graph's wires at 6.4 and reaches
 *       four sites here.</li>
 *   <li><b>A drag callback's coordinates are relative to the SOURCE</b> — the inverse of what
 *       {@code WindowMove}'s own comments said, in three places.</li>
 *   <li><b>Ownership replaces the one-way ticker</b>, which is what makes the freeze real.</li>
 * </ul>
 */
public class DesktopBatchPortTest extends UiDocumentTestBase {

    private Desktop desktop;
    private boolean animationsWere;

    /**
     * <b>Animations OFF, and it is not squeamishness.</b>
     *
     * <p>The standing rule, paid for again on this batch's first run: a window opens with a timeline
     * that scales it from a sliver, and a live transform is a REAL transform — hit-testing walks the
     * same matrices the paint does. Pressing a window's edge mid-animation therefore misses it by an
     * amount that depends on the machine, and the failure reads as "the resize handles are not there"
     * rather than as an animation still playing. Measured here as {@code scale(0.06, 0.10)} at
     * {@code opacity 0.05} on the frame after two ticks.</p>
     */
    @Before
    public void stillTheWindows() {
        animationsWere = WindowAnimator.isEnabled();
        Desktop.setAnimationsEnabled(false);
    }

    @After
    public void restoreAnimations() {
        Desktop.setAnimationsEnabled(animationsWere);
    }

    private Desktop desktop() {
        if (desktop == null) {
            withDefaultStyles();
            desktop = Desktop.of(document);
        }
        return desktop;
    }

    private WindowFrame open(String title) {
        WindowFrame frame = new WindowFrame(title);
        desktop().addWindow(frame);
        frame(); // place, size and settle
        frame();
        return frame;
    }

    // ── The compositor's own presence ────────────────────────────────────────

    /**
     * A desktop that has never held a window takes up nothing.
     *
     * <p>The engine's most-repeated failure, and the one with no symptom on screen: a transparent
     * full-size element over the application hit-tests across the whole surface and eats every click
     * that lands on background, so a UI that never opened a window simply stops responding.</p>
     */
    @Test
    public void anEmptyCompositorTakesNoSpace() {
        Desktop d = desktop();
        frame();
        Box box = d.box();
        assertNotNull("the desktop has no box at all", box);
        assertEquals("an empty desktop claimed width", 0f, box.width(), 0.01f);
        assertEquals("an empty desktop claimed height", 0f, box.height(), 0.01f);
        assertFalse("the live class is on an empty desktop", d.hasClass(Desktop.LIVE_CLASS));
    }

    /**
     * ...and gives the surface back when the last window goes.
     *
     * <p>The mirror case, which is the one that bites later: a desktop used once and then emptied
     * would otherwise keep the application dead.</p>
     */
    @Test
    public void theSurfaceComesBackWhenTheLastWindowIsDestroyed() {
        Desktop d = desktop();
        WindowFrame frame = open("One");
        assertTrue("a live desktop is not marked live", d.hasClass(Desktop.LIVE_CLASS));
        assertTrue("a live desktop claimed no width", d.box().width() > 0f);

        frame.destroy();
        frame();
        assertFalse("the class survived the last window", d.hasClass(Desktop.LIVE_CLASS));
        assertEquals("the surface was not given back", 0f, d.box().width(), 0.01f);
    }

    /**
     * A HIDDEN window still counts — retention is what "hidden" means here.
     *
     * <p>The counter-assertion to the one above, and it is not a formality: a rule written as "size to
     * the visible windows" passes every test that only ever destroys, and collapses the desktop the
     * first time somebody minimises their only window.</p>
     */
    @Test
    public void aHiddenWindowKeepsTheSurface() {
        Desktop d = desktop();
        WindowFrame frame = open("One");
        frame.hide();
        frame();
        assertEquals("a hidden window is not HIDDEN", WindowState.HIDDEN, frame.state());
        assertTrue("hiding the only window collapsed the desktop", d.hasClass(Desktop.LIVE_CLASS));
        assertTrue("the work area went to zero", d.box().width() > 0f);
    }

    // ── The compositor is reached through the compositor ─────────────────────

    /**
     * {@code Desktop.of} builds at most one, and {@code ifPresent} never builds.
     *
     * <p>The inversion the port makes: {@code ui.dom} may not name a compositor, so the compositor
     * names the document. {@code ifPresent} is the non-building read a command's {@code enabledWhen}
     * uses — routing one through the building accessor would grow a desktop on an application that has
     * never opened a window, which is exactly what the zero-size rule exists to keep harmless.</p>
     */
    @Test
    public void theCompositorIsFoundOrBuiltButNeverTwice() {
        assertNull("ifPresent built one", Desktop.ifPresent(document));
        Desktop first = Desktop.of(document);
        assertSame("of() built a second", first, Desktop.of(document));
        assertSame("ifPresent found a different one", first, Desktop.ifPresent(document));
        assertNull("a null document is not an error", Desktop.ifPresent(null));
    }

    // ── Box.x() is parent-relative ───────────────────────────────────────────

    /**
     * A frame's own box is already in the work area's space — no subtraction.
     *
     * <p>The M6.4 rule, reaching {@code restore()}, {@code livePreviewRect} and the minimise fallback.
     * The old accessor accumulated through every ancestor, so a site that subtracted the area's origin
     * was correct; here that counts the desktop's chrome twice, and is wrong by however much of it
     * there happens to be. <b>The taskbar is what makes this test able to fail</b>: without a strip
     * below the work area the layer sits at the desktop's own origin and the two readings agree.</p>
     */
    @Test
    public void aFrameIsPositionedInTheWorkAreasOwnSpace() {
        Desktop d = desktop();
        WindowFrame frame = open("One");
        frame.moveTo(120f, 90f);
        frame();

        Box self = frame.box();
        Box area = d.windowLayer().box();
        assertNotNull(self);
        assertNotNull(area);
        assertEquals("left() and the box disagree", frame.left(), self.x(), 0.01f);
        assertEquals("top() and the box disagree", frame.top(), self.y(), 0.01f);

        // AND A DEEPER NODE IS THE CONTROL. The work area happens to sit at the document's origin --
        // the taskbar is laid out BELOW it -- so at depth one the two readings agree and this test
        // could not fail. A taskbar entry is three levels down and its own x is an offset inside the
        // strip's centred row, which is nowhere near its position on screen: that difference is exactly
        // what `WindowAnimator.toward` gets wrong if it subtracts two boxes' offsets, and it is what
        // sent every wire in the graph to one short segment at 6.4.
        UINode entry = d.taskbar().entryFor(frame);
        assertNotNull("no taskbar entry to measure against", entry);
        Box entryBox = entry.box();
        assertNotNull(entryBox);
        assertNotEquals("an entry's own offset equals its screen position -- the fixture is flat",
                entryBox.worldX(), entryBox.x(), 1f);

        // ...and the conversion is what closes the gap. Through the work area's inverse, which also
        // carries every transform and scroll between the two -- a subtraction never did.
        Vector2f inArea = Box.originIn(entryBox, area);
        assertEquals("the entry did not convert into the work area's space",
                entryBox.worldX(), inArea.x, 0.5f);
    }

    /** The work area's own top-left is the origin of its own space, which is what the fallback reads. */
    @Test
    public void theWorkAreaIsItsOwnOrigin() {
        Desktop d = desktop();
        open("One");
        Box area = d.windowLayer().box();
        assertNotNull(area);
        assertEquals("the layer is offset within itself", 0f, area.x(), 0.01f);
        assertEquals("the layer is offset within itself", 0f, area.y(), 0.01f);
        assertTrue("the work area has no height", area.height() > 0f);
    }

    // ── The window's parts ───────────────────────────────────────────────────

    /**
     * The eight resize handles exist, and are the frame's own children.
     *
     * <p>{@code UIResizer} was built by the cascade — a property listener grew and dropped internal
     * children as {@code resize:} changed — and that cannot be done here: a style change that ADDS
     * nodes is a structural change made from inside the style pass. So a window installs them once.
     * The sheet is unchanged, and the classes are what it targets.</p>
     */
    @Test
    public void aWindowHasItsEightResizeHandles() {
        WindowFrame frame = open("One");
        int handles = 0;
        for (UINode child : frame.children()) {
            if (child.hasClass(Resizer.RESIZER_CLASS)) handles++;
        }
        assertEquals("a window did not get all eight handles", 8, handles);
    }

    /**
     * A window's own edges belong to its handles — press an element's CENTRE.
     *
     * <p>The standing rule, and it is what tells us the handles are hit-testable and in front. It
     * presents as an activation bug otherwise: the press lands, focus walks up to the frame, and the
     * control four pixels away never sees it.</p>
     */
    @Test
    public void aPressOnTheFramesEdgeHitsAResizeHandle() {
        WindowFrame frame = open("One");
        frame.moveTo(100f, 100f);
        frame.resizeTo(300f, 200f);
        frame();
        frame();

        Box box = frame.box();
        assertNotNull(box);
        UINode edge = hit(box.worldX() + 1f, box.worldY() + box.height() / 2f);
        assertNotNull("nothing at all is on the window's left edge", edge);
        assertTrue("the window's left edge is not a resize handle: " + edge.classes(),
                edge.hasClass(Resizer.RESIZER_CLASS));
    }

    /** The overlay slot takes no space until something is showing on it — the desktop's rule, one level down. */
    @Test
    public void theOverlaySlotIsSizedOnlyWhileOccupied() {
        WindowFrame frame = open("One");
        UINode overlays = null;
        for (UINode child : frame.children()) {
            if (child.hasClass(WindowFrame.OVERLAY_CLASS)) overlays = child;
        }
        assertNotNull("the frame has no overlay slot", overlays);
        assertFalse("an empty slot claims to be occupied",
                overlays.hasClass(WindowFrame.OVERLAY_OCCUPIED_CLASS));
        Box box = overlays.box();
        assertNotNull(box);
        assertEquals("an empty overlay slot covers the window", 0f, box.width(), 0.01f);
    }

    // ── Ownership replaces the one-way ticker ────────────────────────────────

    /**
     * Hiding a window drops the per-frame hooks its subtree owned.
     *
     * <p>The freeze contract, made structural. The old engine registered a ticker one-way and stopped
     * it only by having the ticker return false — so the one thing that carried on running in a hidden
     * window was a ticker, the "hidden editor that keeps compiling". Ownership answers it without the
     * ticker having to notice anything.</p>
     */
    @Test
    public void hidingAWindowDropsWhatItsSubtreeOwned() {
        WindowFrame frame = open("One");
        boolean[] ran = {false};
        document.animation().every(frame, delta -> {
            ran[0] = true;
            return true;
        });
        frame();
        assertTrue("the hook never ran while the window was up", ran[0]);

        frame.hide();
        frame();
        ran[0] = false;
        frame();
        assertFalse("a hidden window's hook is still running", ran[0]);
    }

    // ── The registry is one list, in two orders ──────────────────────────────

    /**
     * Open order and MRU order are both kept, and neither is derivable from the other.
     *
     * <p>The taskbar reads the first and the switcher the second; a hidden window has left the
     * stacking order while keeping its place in the sequence.</p>
     */
    @Test
    public void theRegistryKeepsBothOrders() {
        Desktop d = desktop();
        WindowFrame first = open("First");
        WindowFrame second = open("Second");
        d.activate(first);
        frame();

        WindowRegistry registry = d.registry();
        assertEquals("open order is not insertion order",
                java.util.List.of(first, second), registry.windows());
        assertSame("the most recently activated is not first in MRU",
                first, registry.mruOrder().get(0));
    }

    // ── The strip ────────────────────────────────────────────────────────────

    /** A taskbar entry exists per window, and answers for its OWN window rather than the active one. */
    @Test
    public void aTaskbarEntryAnswersForItsOwnWindow() {
        Desktop d = desktop();
        WindowFrame first = open("First");
        WindowFrame second = open("Second");
        d.activate(second);
        frame();

        Taskbar taskbar = d.taskbar();
        assertNotNull(taskbar);
        UINode entry = taskbar.entryFor(first);
        assertNotNull("no entry for the background window", entry);
        // THE ENTRY, not the desktop, is what a jump list resolves through: a taskbar entry is NOT
        // inside the window it stands for, so without its own answer the walk reaches the desktop and
        // gets the ACTIVE window -- which closes the wrong thing.
        assertSame("an entry resolved to the active window instead of its own",
                first, DataContext.from(entry).get(WindowFrame.WINDOW_FRAME));
    }

    /** Hiding the strip re-flows the work area — fullscreen needs no geometry of its own. */
    @Test
    public void hidingTheStripGivesItsRowToTheWorkArea() {
        Desktop d = desktop();
        open("One");
        float withBar = d.windowLayer().box().height();

        d.taskbar().setBarVisible(false);
        frame();
        float withoutBar = d.windowLayer().box().height();

        assertTrue("hiding the strip did not grow the work area", withoutBar > withBar);
    }

    // ── The snap zones read the POINTER ──────────────────────────────────────

    /**
     * A zone is read from where the hand is, never from the window's edge.
     *
     * <p>Asserted on {@link SnapZones} directly because that is where the rule lives; the conversion
     * into this space is {@code WindowMove}'s, and it inverted at M6.1 — a drag callback's coordinates
     * are now an offset within the CAPTION, so the caption's position is added rather than the area's
     * origin subtracted.</p>
     */
    @Test
    public void aSnapZoneIsReadFromThePointer() {
        assertSame("the left band is not at the left edge",
                SnapZones.Zone.LEFT, SnapZones.forPoint(2f, 300f, 800f, 600f));
        assertNull("the middle of the work area is a zone", SnapZones.forPoint(400f, 300f, 800f, 600f));
        assertNotSame("the right band answered LEFT",
                SnapZones.Zone.LEFT, SnapZones.forPoint(798f, 300f, 800f, 600f));
    }

    // ── The mirror, and the animation channel ────────────────────────────────

    /**
     * A thumbnail lays its window out a SECOND time and leaves the original's matrices alone.
     *
     * <p>The nine {@code mirrored} sites are deletions rather than conversions. The old engine drew the
     * subtree twice against one cached {@code localToWorld} per element, so the copy overwrote the
     * original's idea of where it lived and the real window stopped being clickable where it was drawn
     * — which is why {@code CgUiPaintContext.mirrored} was a counter rather than a boolean. A mirror
     * has boxes of its own, so the node is never told it is drawn twice.</p>
     *
     * <p><b>The assertion is on the ORIGINAL's geometry, not the copy's.</b> A mirror that produced no
     * boxes at all would satisfy "the window is still where it was"; asserting the mirror root exists
     * AND the original is unmoved is what separates the two.</p>
     */
    @Test
    public void aMirrorIsASecondLayoutAndDisturbsNothing() {
        WindowFrame frame = open("One");
        frame.moveTo(60f, 40f);
        frame();
        Box self = frame.box();
        assertNotNull(self);
        float wasWorldX = self.worldX();
        float wasWorldY = self.worldY();

        UINode host = sized("host", 120f, 90f);
        document.append(host);
        frame();
        Box hostBox = host.box();
        assertNotNull(hostBox);

        Box mirror = document.boxes().mirror(frame, hostBox);
        frame();
        assertNotNull("mirroring produced no box", mirror);
        assertNotSame("the mirror IS the original's box", self, mirror);
        assertTrue("the mirror has no size", mirror.width() > 0f);

        Box after = frame.box();
        assertNotNull(after);
        assertEquals("drawing a window twice moved the original", wasWorldX, after.worldX(), 0.01f);
        assertEquals("drawing a window twice moved the original", wasWorldY, after.worldY(), 0.01f);

        document.boxes().unmirror(mirror);
        frame();
        assertEquals("unmirroring moved the original", wasWorldX, frame.box().worldX(), 0.01f);
    }

    /**
     * A window animation writes the BOX, and withdraws rather than leaving a resting value.
     *
     * <p>The compositor channel {@code Box.setTransform}/{@code setOpacity} exist for. The old engine
     * had none and wrote ANIMATION-origin slots, which meant the animation also had to tell
     * {@code transform}'s own property listener that the value had moved — the slot write bypasses
     * {@code putCandidate}, so nothing else was told and the whole thing ran invisibly.</p>
     *
     * <p><b>Withdrawal is the half worth asserting.</b> An INLINE cleanup value written to end one
     * animation permanently outranks the class used to start the next: a window that had once been
     * maximised could never animate closed again, which is one of the four ways the cascade-driven
     * version failed silently.</p>
     */
    @Test
    public void anOpenAnimationWritesTheBoxAndThenLetsGo() {
        Desktop.setAnimationsEnabled(true);
        Desktop d = desktop();
        WindowFrame frame = new WindowFrame("One");
        d.addWindow(frame);
        frame();
        frame();

        Box box = frame.box();
        assertNotNull(box);
        assertTrue("the open animation is not playing", frame.isAnimating());
        assertNotSame("the box carries no compositor transform mid-animation",
                UITransform.IDENTITY, box.transform());
        assertTrue("the box carries no compositor opacity mid-animation", box.opacity() < 1f);

        // CANCEL, never a wait: a timeline advances on wall time a test loop cannot step, and every
        // ending here runs through the same teardown. @see WindowAnimation#cancel
        frame.cancelAnimation();
        frame();
        Box settled = frame.box();
        assertNotNull(settled);
        assertEquals("the animation left an opacity behind", 1f, settled.opacity(), 0.001f);
    }
}
