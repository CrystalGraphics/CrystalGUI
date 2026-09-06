package com.crystalgui.desktop;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.ui.input.keymap.KeyStroke;
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
import com.crystalgui.style.property.visual.transform.Transform;
import org.joml.Vector2f;
import com.crystalgui.widget.dnd.Resizer;
import com.crystalgui.widget.overlay.Dialog;
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
    /**
     * ANIMATIONS OFF, unless a test turns them on for itself.
     *
     * <p>A window animation defers the thing it animates: `close()` destroys and `hide()`
     * detaches only once the flight has finished, so a test that asserts the state straight
     * after the gesture reads the state BEFORE it. Disabled, the continuation runs
     * synchronously, which is what lets every assertion here be immediate. The tests that are
     * ABOUT the animation enable it themselves and restore this in a finally.</p>
     */

    /** AND PUT IT BACK. The flag is STATIC, so leaving it off leaks into every later test in the
     *  run -- a governance test that asks whether every shipped rule still matches something then
     *  finds `taskbar .__entry__.__animating__` matching nothing, because nothing animates. */


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
    public void quietAnimationsForTheFixture() {
        Desktop.setAnimationsEnabled(false);
    }

    @After
    public void restoreAnimationsAfterTheFixture() {
        Desktop.setAnimationsEnabled(true);
    }

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
        assertTrue("a live desktop claimed no width", widthOf(d) > 0f);

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
        assertTrue("the work area went to zero", widthOf(d) > 0f);
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
        UIElement entry = d.taskbar().entryFor(frame);
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
        for (UIElement child : frame.children()) {
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
        UIElement edge = hit(box.worldX() + 1f, box.worldY() + box.height() / 2f);
        assertNotNull("nothing at all is on the window's left edge", edge);
        assertTrue("the window's left edge is not a resize handle: " + edge.classes(),
                edge.hasClass(Resizer.RESIZER_CLASS));
    }

    /**
     * A closed owned dialog leaves NOTHING over the window it belonged to.
     *
     * <p>The property the {@code __overlays__} slot existed to provide, asserted against the mechanism
     * that provides it now. The slot took a full-size box from an {@code __occupied__} class that Java
     * toggled from a set of what was showing, so a dialog that closed without the matching release left
     * a transparent sheet over the whole window — reported as a window whose caption buttons and
     * content had all stopped responding after a modal had been opened once.</p>
     *
     * <p>There is no set now: an owned surface is an ordinary out-of-flow child of the frame, a modal
     * blocks through its own backdrop, and <b>a node that is not displayed has no box</b> — so the
     * question answers itself on every layout and cannot go stale.</p>
     *
     * <p><b>The open half is the counter-assertion and is not a formality</b>: a change that simply
     * stopped the dialog covering anything would satisfy the closed half perfectly and destroy
     * modality, which is the more expensive of the two failures.</p>
     */
    @Test
    public void aClosedOwnedDialogLeavesNothingOverTheWindow() {
        WindowFrame frame = open("One");
        Dialog dialog = new Dialog("Owned");
        frame.attachOwned(dialog);
        dialog.showModal();
        frame();
        frame();

        Box box = frame.box();
        assertNotNull(box);
        float x = box.worldX() + box.width() / 2f;
        float y = box.worldY() + box.height() / 2f;

        // THROUGH THE POINTER, not through the raw box test. Modality on this engine is `pushModal`
        // plus the inertness predicate the input service consults -- not a covering box -- so
        // `boxes().hitTest` answers what is geometrically there and knows nothing about being blocked.
        // Asserting on it would pass against a window that is completely dead.
        move(x, y);
        frame();
        UIElement blocked = document.input().hoverTarget();
        assertFalse("the window's own content is reachable while its modal is open",
                blocked != null && !partOf(blocked, dialog));

        dialog.close();
        frame();
        frame();

        move(x, y);
        frame();
        UIElement after = document.input().hoverTarget();
        assertNotNull("the window is unreachable after its modal closed", after);
        assertFalse("a closed dialog is still blocking the window: " + after.classes(),
                partOf(after, dialog));
    }

    /**
     * A modal is centred on the window it opens over <b>on its very first frame</b>.
     *
     * <p>The fifth appearance of one engine gap and the reason it was finally closed there: anything
     * whose position depends on its own measured size has to place itself after layout, and with layout
     * running exactly once that write landed on the NEXT frame — so the thing was drawn once, at full
     * opacity, at its containing block's origin. Reported here as a modal appearing in the corner of the
     * Geometry window for one frame before snapping to the middle.</p>
     *
     * <p><b>One {@code frame()} is the whole assertion.</b> Two would pass against no fix at all, which
     * is exactly how this survived four previous encounters — each widget parked itself off-screen until
     * placed, so the misplacement became an invisible frame instead of a visible one and every test that
     * settled for a couple of frames went green.</p>
     */
    @Test
    public void anOwnedModalIsCentredOnItsWindowOnTheFirstFrame() {
        WindowFrame frame = open("Geometry");
        Dialog dialog = new Dialog("Owned");
        frame.attachOwned(dialog);
        dialog.showModal();
        frame();

        Box self = boxOf(dialog);
        Box host = self.host();
        assertNotNull("the modal is hosted by nothing", host);
        assertSame("an owned modal was promoted away from its window", frame.box(), host);
        assertTrue("the modal has not been laid out", self.width() > 0f && self.height() > 0f);

        assertEquals("not centred horizontally on its window",
                (host.width() - self.width()) / 2f, self.x(), 0.5f);
        assertEquals("not centred vertically on its window",
                (host.height() - self.height()) / 2f, self.y(), 0.5f);
    }

    /**
     * A modal blocks the window it belongs to, and <b>only</b> that window.
     *
     * <p>Modality here is a question about a FOCUS NAVIGATION SCOPE: a modal makes the nearest scope
     * enclosing it inert, minus itself. The service was written that way from the start and nothing in
     * the tree ever declared a scope, so {@code scopeOf} always answered the document — and a dialog
     * opened in one window made every other window on the desktop unclickable. A window is a scope
     * now, which is the granularity the old engine settled on: smaller and a modal stops blocking the
     * window it belongs to, larger is the document again.</p>
     *
     * <p><b>A one-window fixture cannot see any of this</b> — scoped and unscoped agree when there is
     * only one window to block — which is why the modality tests that already existed were green
     * against it.</p>
     */
    @Test
    public void aModalBlocksItsOwnWindowAndNoOther() {
        WindowFrame blocked = open("Blocked");
        blocked.moveTo(20, 20).resizeTo(160, 120);
        WindowFrame other = open("Other");
        other.moveTo(300, 20).resizeTo(160, 120);
        frame();

        Dialog dialog = new Dialog("Owned");
        blocked.attachOwned(dialog);
        dialog.showModal();
        frame();
        frame();

        move(centreX(other), centreY(other));
        frame();
        UIElement reachable = document.input().hoverTarget();
        assertNotNull("the OTHER window is unreachable while a modal is open elsewhere", reachable);
        assertNull("the other window is being blamed on a modal it does not contain",
                document.focus().blockingModal(reachable));

        move(centreX(blocked), centreY(blocked));
        frame();
        UIElement owner = document.input().hoverTarget();
        assertFalse("the modal does not block its own window",
                owner != null && !partOf(owner, dialog));
    }

    private static float centreX(WindowFrame frame) {
        Box box = frame.box();
        assertNotNull(box);
        return box.worldX() + box.width() / 2f;
    }

    private static float centreY(WindowFrame frame) {
        Box box = frame.box();
        assertNotNull(box);
        return box.worldY() + box.height() / 2f;
    }

    /**
     * Pressing a modally blocked window still brings it forward.
     *
     * <p>Everything in a blocked window is inert and inertness is {@code pointer-events: none}, so the
     * press resolves to nothing — which is also precisely what a press on bare desktop looks like.
     * Treating the two alike meant a blocked window could not be raised, could not be focused, and left
     * the desktop reporting no active window at all: on screen, indistinguishable from a hung
     * application, and reachable only by clicking the dialog itself.</p>
     *
     * <p>Asserted on the CAPTION, which is the part a user reaches for to bring a window forward and
     * the part that is most obviously dead when this is wrong.</p>
     */
    @Test
    public void pressingABlockedWindowStillRaisesIt() {
        WindowFrame blocked = open("Blocked");
        blocked.moveTo(20, 20).resizeTo(160, 120);
        WindowFrame other = open("Other");
        other.moveTo(300, 20).resizeTo(160, 120);
        frame();

        Dialog dialog = new Dialog("Owned");
        blocked.attachOwned(dialog);
        dialog.showModal();
        frame();
        frame();

        desktop().activate(other, true);
        frame();
        assertSame("the fixture did not manage to activate the other window",
                other, desktop().activeWindow());

        Box caption = boxOf(blocked.titleBar());
        press(caption.worldX() + caption.width() / 2f, caption.worldY() + caption.height() / 2f);
        frame();

        assertSame("pressing a blocked window's caption did not bring it forward",
                blocked, desktop().activeWindow());
    }

    /**
     * A mirror is laid out at its host's origin, not at the position its source sits on the desktop.
     *
     * <p>A mirror shares the source's NODE and therefore its computed style, and a window's style
     * carries the {@code left}/{@code top} that place it on the desktop. Applied to the copy those
     * become an offset inside the thumbnail, so a taskbar preview drew its picture at the window's own
     * desktop position scaled down — a window near the left edge came out slightly off-centre, and one
     * near the right was drawn outside the preview panel entirely, over the taskbar.</p>
     *
     * <p>The old engine cancelled it explicitly: its thumbnail's pose ended
     * {@code translate(-src.getX(), -src.getY())}, commented "put the window's own origin at zero".
     * That line had no counterpart in the port — the two before it became the caller's transform and
     * were carried over, and the one that cancels the origin was the one with nowhere to go.</p>
     *
     * <p><b>The window has to be somewhere other than the origin</b> or the test passes against no fix
     * at all, which is why it is moved before it is mirrored.</p>
     */
    @Test
    public void aMirrorIsLaidOutAtItsHostOriginAndNotItsSources() {
        WindowFrame source = open("Source");
        source.moveTo(120, 90).resizeTo(200, 150);
        UIElement host = new UIElement();
        host.layout(l -> l.width(80f).height(60f));
        desktop().append(host);
        frame();
        frame();

        Box sourceBox = boxOf(source);
        assertTrue("the fixture did not move the window off the origin",
                sourceBox.x() > 1f && sourceBox.y() > 1f);

        Box mirror = document.boxes().mirror(source, boxOf(host));
        try {
            frame();
            assertEquals("the mirror took its source's left inset", 0f, mirror.x(), 0.01f);
            assertEquals("the mirror took its source's top inset", 0f, mirror.y(), 0.01f);
            // AND IT IS THE SOURCE'S SIZE, not something the host squeezed it into. A mirror shares the
            // source's computed style, and `window { max-width: 100% }` in the shipped sheet resolves
            // against the HOST -- so against an 80px thumbnail a 200px window laid out at its own
            // min-content instead, and the preview showed a narrower window with its text re-wrapped
            // and its content cut off. The picture was drawn perfectly, at a size nobody wanted.
            assertEquals("the mirror re-flowed to fit its host instead of picturing its source",
                    sourceBox.width(), mirror.width(), 0.5f);
            assertEquals("the mirror re-flowed to fit its host instead of picturing its source",
                    sourceBox.height(), mirror.height(), 0.5f);
            // AND IT SURVIVES A RESTYLE, which is the half that made this intermittent. `BoxStyle.apply`
            // rewrites width, height and the maximums from the source's computed style, so re-applying
            // one silently undid the pin -- and the re-pin then early-outed, because the SOURCE's size
            // had not changed and that was all it compared. The mirror was left clamped by
            // `window { max-width: 100% }` against its own thumbnail. It held until the first restyle
            // of that node, so a switcher tile looked right for a frame or two and then broke.
            source.addClass("__restyled__");
            frame();
            assertEquals("a restyle lost the mirror's pinned width",
                    sourceBox.width(), mirror.width(), 0.5f);
            assertEquals("a restyle lost the mirror's pinned height",
                    sourceBox.height(), mirror.height(), 0.5f);

            // AND ITS TRANSFORM PIVOTS ABOUT ITS OWN CORNER. `transform-origin` defaults to 50%, so
            // without this a mirror scaled about the SOURCE's centre: the picture came out the right
            // size and hung outside the panel holding it, over the taskbar. The old engine could not
            // get this wrong -- it composed `translate(left, top); scale(s, s)` into a pose by hand,
            // and a pose scales about its origin.
            mirror.setTransform(Transform.of(Transform.Op.scale(0.5f, 0.5f)));
            frame();
            assertEquals("a mirror pivots about its source's centre, not its own corner",
                    boxOf(host).worldX(), mirror.worldX(), 0.01f);
            assertEquals("a mirror pivots about its source's centre, not its own corner",
                    boxOf(host).worldY(), mirror.worldY(), 0.01f);
        } finally {
            document.boxes().unmirror(mirror);
        }
    }

    /**
     * {@code Mod+Tab} opens the window switcher, rather than tabbing between windows.
     *
     * <p><b>The whole keymap was unreachable.</b> {@code Input.Chords} is the seam a keymap installs
     * into and nothing ever called {@code setChords}, so it was permanently null and no chord resolved
     * anywhere — every shortcut in the application was inert. It did not look inert: an unresolved
     * chord falls through to ordinary dispatch and then to Tab traversal, so {@code Ctrl+Tab} cycled
     * focus between windows, which reads as a deliberate and slightly wrong feature rather than a
     * missing one.</p>
     *
     * <p><b>The modifier has to be HELD on the platform</b>, not passed in the event: {@code Input}
     * reads {@code getCurrentModifiers}, and the test stub used to answer a hard-coded zero. So every
     * chord any test ever sent was silently unmodified and the keymap path could not be reached at all
     * — {@code KeymapTest} says so in its own comment and drives the resolver directly instead. The
     * resolver was covered, the bindings were covered, and the one thing nobody could ask was whether
     * anything called them.</p>
     */
    @Test
    public void modTabOpensTheSwitcherInsteadOfTabbingBetweenWindows() {
        open("One");
        open("Two");
        frame();

        assertFalse("the switcher is open before anything was pressed", desktop().switcher().isOpen());
        int mod = KeyStroke.parse("Mod+Tab").modifiers();
        assertTrue("Mod+Tab was not consumed", chord(CgKeyCodes.KEY_TAB, mod));
        frame();

        // STILL HOLDING, as a user is: the switcher is held open by the modifier and commits the moment
        // it is let go, so releasing before this assertion would close it before it could be seen.
        assertTrue("Mod+Tab did not open the switcher", desktop().switcher().isOpen());
        releaseModifiers();
    }

    /**
     * A promoted element sizing itself as a fraction of the screen gets the screen.
     *
     * <p>The old engine's {@code TopLayer.reparentTaffyNodeToRoot} made a promoted element a child of
     * the ROOT, which is where "a promoted element's containing block is the root" comes from and why
     * {@code width: 100%} there meant the viewport. This engine hosts promoted boxes on a top-layer
     * node instead, and that node had no size of its own — so it shrank to its content and every
     * promoted element asking for a fraction of the screen got a fraction of ITSELF.</p>
     *
     * <p>The window switcher is the clearest case: a full-screen overlay that centres its panel with
     * flexbox precisely so it never has to measure anything, collapsed onto its own content in the
     * top-left corner.</p>
     */
    @Test
    public void aPromotedElementsPercentagesAreOfTheScreen() {
        UIElement overlay = new UIElement();
        overlay.layout(l -> l.widthPercent(100f).heightPercent(100f));
        desktop().append(overlay);
        document.promote(overlay);
        frame();
        frame();

        Box root = boxOf(document);
        Box box = boxOf(overlay);
        assertEquals("a promoted overlay is not the width of the screen", root.width(), box.width(), 0.5f);
        assertEquals("a promoted overlay is not the height of the screen", root.height(), box.height(), 0.5f);
    }

    /**
     * ...and the layer that makes that possible never answers a hit test itself.
     *
     * <p>Sizing the top layer to the viewport makes it the largest box in the tree, and a full-size box
     * that can be the answer to a hit test is this codebase's most-repeated failure: it eats every
     * click that lands on background with nothing on screen to explain why. {@code hit-test: false} is
     * not the alternative — it is subtree-wide and returns without recursing, so it would make every
     * popup, menu and dialog in the layer unhittable. A stacking container is simply never the answer.
     */
    @Test
    public void theTopLayerIsNotItselfAHitTarget() {
        WindowFrame frame = open("One");
        document.topLayerNode();
        frame();

        Box box = frame.box();
        assertNotNull(box);
        UIElement under = hit(box.worldX() + box.width() / 2f, box.worldY() + box.height() / 2f);
        assertNotNull("nothing at all is under the pointer", under);
        assertNotSame("the top layer swallowed a press meant for the window beneath it",
                document.topLayerNode(), under);
    }

    /** Whether {@code node} is the dialog, inside it, or the backdrop it owns. */
    private static boolean partOf(UIElement node, Dialog dialog) {
        if (node.hasClass(Dialog.BACKDROP_CLASS)) return true;
        for (UIElement walk = node; walk != null; walk = walk.parentElement()) {
            if (walk == dialog) return true;
        }
        return false;
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
        UIElement entry = taskbar.entryFor(first);
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

        UIElement host = sized("host", 120f, 90f);
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
        // Restored by restoreAnimations(), which reads the value captured before this ran.
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
                Transform.IDENTITY, box.transform());
        assertTrue("the box carries no compositor opacity mid-animation", box.opacity() < 1f);

        // CANCEL, never a wait: a timeline advances on wall time a test loop cannot step, and every
        // ending here runs through the same teardown. @see WindowAnimation#cancel
        frame.cancelAnimation();
        frame();
        Box settled = frame.box();
        assertNotNull(settled);
        assertEquals("the animation left an opacity behind", 1f, settled.opacity(), 0.001f);
    }

    /**
     * Minimising a BACKGROUND window does not bring it forward on the way out.
     *
     * <p>Pressing a background window's minimise button raised it — click-focus lands on the button, and
     * {@code Desktop.focusMoved} activates whatever frame focus moved into — so its taskbar entry lit for
     * the press and then faded out over its own transition, which reads as a flicker lasting about as
     * long as the flight.</p>
     *
     * <p><b>It also took the foreground with it.</b> {@code minimize()} deactivates only
     * {@code if (owner.activeWindow() == this)}, "or minimising a background one would deactivate the
     * foreground" — but the press had just made the background one active, so the test passed and the
     * front window went dark too. Measured through the gesture: before it the front window is active,
     * after it NOBODY was.</p>
     *
     * <p><b>Driven as a press at a POINT.</b> {@code sendInputEvent} skips click-focus entirely, which is
     * the whole mechanism here, and calling {@code minimize()} directly was already correct — the API
     * path never showed this.</p>
     */
    @Test
    public void minimisingABackgroundWindowLeavesTheForegroundActive() {
        WindowFrame back = open("Background");
        WindowFrame front = open("Front");
        assertSame("the newest window is active", front, desktop().activeWindow());

        UIElement button = findByClass(back, WindowFrame.MINIMIZE_CLASS);
        assertNotNull("the frame has a minimise control", button);
        Box box = button.box();
        press(box.worldX() + box.width() / 2f, box.worldY() + box.height() / 2f);
        assertSame("pressing a background window's minimise must not raise it",
                front, desktop().activeWindow());

        release(box.worldX() + box.width() / 2f, box.worldY() + box.height() / 2f);
        frame();
        assertSame("and the foreground keeps the keyboard", front, desktop().activeWindow());
    }

    private static UIElement findByClass(UINode at, String cls) {
        if (at instanceof UIElement self && self.classes().contains(cls)) return self;
        for (UIElement child : at.children()) {
            UIElement hit = findByClass(child, cls);
            if (hit != null) return hit;
        }
        UINode shadow = at instanceof UIElement element ? element.shadowRoot() : null;
        return shadow == null ? null : findByClass(shadow, cls);
    }
}
