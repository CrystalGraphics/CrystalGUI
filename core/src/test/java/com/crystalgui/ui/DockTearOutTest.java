package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.input.FocusPolicy;
import java.util.Map;
import java.util.HashMap;
import com.crystalgui.ui.elements.dock.DockArea;
import com.crystalgui.ui.elements.dock.DockDragPayload;
import com.crystalgui.ui.elements.dock.DockDropZone;
import com.crystalgui.ui.elements.dock.DockLayout;
import com.crystalgui.ui.elements.dock.DockLeaf;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPanelRegistry;
import com.crystalgui.ui.elements.dock.DockWindow;
import com.crystalgui.ui.elements.desktop.WindowFrame;

import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tearing an editor tab out into a window of its own — W9.
 *
 * <h3>The dock occupies the TOP HALF on purpose</h3>
 *
 * <p>A tear-out is a release <b>no dock accepted</b>, and a dock that filled the root would accept
 * every release there is. So the fixture leaves half the window empty, which is where the desktop
 * would be in the application — a drag out onto bare background.</p>
 *
 * <p>Driven through real mouse events for the reason W8's stripe test records: {@code onDragEnd} fires
 * on every button release, including one that never passed the activation threshold, so the difference
 * between a click and a drag exists only in the input layer. A test calling the tear-out directly
 * passes against a build where clicking a tab tears it out.</p>
 */
public class DockTearOutTest extends UiTestBase {

    /** Surface pixels. The dock is 200 logical tall in a 400 logical root at uiScale 2, so 720 is
     * 360 logical — bare background, which is what a tear-out is released onto. */
    private static final float BELOW_DOCK_Y = 720f;

    private UIWindow window;
    private DockArea area;

    private static final String DECORATION = "decoration-library";

    /** What the registry last built for each panel — the thing a focused editor stands for here. */
    private final Map<String, UIElement> panelContents = new HashMap<>();

    private final DockPanelRef alpha = new DockPanelRef("alpha");
    private final DockPanelRef beta = new DockPanelRef("beta");

    @Before
    public void setUpDock() {
        DockPanelRegistry<UIElement> registry = new DockPanelRegistry<>();
        for (String id : new String[]{"alpha", "beta"}) {
            // FOCUSABLE, because a real panel's content is: an editor, a tree, a graph. It is what lets
            // this file assert where focus lands when a window opens, which a bare box cannot answer.
            registry.register(new DockPanelDescriptor(id, id), ref -> {
                UIElement content = new UIElement().setFocusPolicy(FocusPolicy.CLICK);
                panelContents.put(id, content);
                return content;
            });
        }
        DockLeaf leaf = new DockLeaf(alpha);
        DockLayout layout = DockLayout.of(leaf);
        layout.drop(leaf, DockDropZone.MERGE, new DockLeaf(beta));

        // Every panel is decorated, so a lost class is visible in the assertion rather than only on screen.
        registry.setDecorationProvider(ref -> DECORATION);
        area = new DockArea(registry, layout);
        area.layout(l -> l.width(600).height(200));

        UIElement root = new UIElement().layout(l -> l.width(600).height(400)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(area);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(1200, 800);
        frame();
        frame();
    }

    private void frame() {
        for (int i = 0; i < 3; i++) {
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    /** <b>A click on a tab is not a tear-out.</b> The gate W8 had to learn, arriving with the gesture. */
    @Test
    public void clickingATabDoesNotTearItOut() {
        Tab tab = tabFor(beta);
        assertNotNull(tab);
        float[] at = centreOf(tab);

        press(at[0], at[1]);
        release(at[0], at[1]);
        frame();

        assertNull("a click opened a window", tornOutWindow());
        assertTrue("and it took the panel out of the dock", holds(beta));
    }

    /**
     * <b>A drag onto bare background opens a window around the tab.</b> And the source lets go of it —
     * anything else leaves the same panel in two docks at once.
     */
    @Test
    public void draggingATabOntoNothingOpensAWindow() {
        Tab tab = tabFor(beta);
        assertNotNull(tab);
        float[] at = centreOf(tab);

        press(at[0], at[1]);
        moveTo(at[0], BELOW_DOCK_Y);
        frame();
        moveTo(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();
        release(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();

        DockWindow torn = tornOutWindow();
        assertNotNull("no window was opened", torn);
        assertFalse("the source dock kept the panel too", holds(beta));
        assertTrue("the window did not get the panel", holdsIn(torn.area(), beta));
        assertTrue("a torn-out editor is a taskbar citizen",
                window.desktop().registry().windows().contains(torn));
    }

    /**
     * <b>A torn-out window is a PEER, not an accessory.</b> The opposite of a floating tool window,
     * which is owned by the frame it came from: a second place to work should bury the first when it is
     * clicked, exactly as two document windows do on every desktop.
     */
    @Test
    public void aTornOutEditorIsNotOwnedByAnything() {
        Tab tab = tabFor(beta);
        float[] at = centreOf(tab);
        press(at[0], at[1]);
        moveTo(at[0], BELOW_DOCK_Y);
        frame();
        moveTo(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();
        release(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();

        DockWindow torn = tornOutWindow();
        assertNotNull(torn);
        assertNull("it was made an accessory of something", torn.ownerWindow());
    }

    /** And an emptied window closes rather than lingering with nothing in it. */
    @Test
    public void anEmptiedDockWindowCloses() {
        Tab tab = tabFor(beta);
        float[] at = centreOf(tab);
        press(at[0], at[1]);
        moveTo(at[0], BELOW_DOCK_Y);
        frame();
        moveTo(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();
        release(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();

        DockWindow torn = tornOutWindow();
        assertNotNull(torn);
        assertFalse("it holds a panel, so it must not close", torn.closeIfEmpty());

        // THE WAY A RE-DOCK DOES IT: the main area takes the panel back, which detaches it from the
        // torn-out one. Nothing tells the window; it has to notice for itself, which is the bug this
        // covers -- dragging the last tab home left an empty window sitting on the desktop.
        area.performDrop(com.crystalgui.ui.elements.dock.DockDragPayload.ofPanel(
                        torn.area(), torn.area().layout().leaves().get(0), beta),
                area.layout().leaves().get(0), DockDropZone.MERGE, false);
        frame();

        assertTrue("the emptied window stayed open", torn.state()
                == com.crystalgui.ui.elements.desktop.WindowState.DESTROYED);
        assertFalse("and it is still in the registry",
                window.desktop().registry().windows().contains(torn));
        assertTrue("the panel did not come home", holds(beta));
    }

    /**
     * <b>A torn-out tab carries the same decoration class it had in the dock it left.</b>
     *
     * <p>The classes come from the registry's decoration provider, and a torn-out window is built with
     * the SAME registry — so this is really asserting that nothing about the move re-routes that. It is
     * worth pinning because the symptom of losing it is invisible in the common case: a one-tab window's
     * tab is always selected, and the sheet lets selection win over the tint, so a missing class and a
     * yielding class look identical on screen.</p>
     */
    @Test
    public void aTornOutTabKeepsItsDecorationClass() {
        Tab before = tabFor(beta);
        assertNotNull(before);
        boolean hadIt = before.hasClass(DECORATION);
        assertTrue("setup: the fixture must decorate beta for this to prove anything", hadIt);

        float[] at = centreOf(before);
        press(at[0], at[1]);
        moveTo(at[0], BELOW_DOCK_Y);
        frame();
        moveTo(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();
        release(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();

        DockWindow torn = tornOutWindow();
        assertNotNull(torn);
        Tab moved = null;
        for (DockLeaf leaf : torn.area().layout().leaves()) {
            var group = torn.area().groupFor(leaf);
            if (group != null && group.tabFor(beta) != null) moved = group.tabFor(beta);
        }
        assertNotNull("the torn-out window built no tab for it", moved);
        assertTrue("the decoration was lost in the move", moved.hasClass(DECORATION));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    private Tab tabFor(DockPanelRef panel) {
        return tabIn(area, panel);
    }

    private Tab tabIn(DockArea in, DockPanelRef panel) {
        for (DockLeaf leaf : in.layout().leaves()) {
            var group = in.groupFor(leaf);
            if (group == null) continue;
            Tab tab = group.tabFor(panel);
            if (tab != null) return tab;
        }
        return null;
    }

    /** Tears {@code beta} out into a window of its own and returns it. */
    private DockWindow tearOutBeta() {
        Tab tab = tabFor(beta);
        assertNotNull(tab);
        float[] at = centreOf(tab);
        press(at[0], at[1]);
        moveTo(at[0], BELOW_DOCK_Y);
        frame();
        moveTo(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();
        release(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();
        DockWindow torn = tornOutWindow();
        assertNotNull("no window was opened", torn);
        return torn;
    }

    /** Whether {@code element} is {@code ancestor} or sits under it. */
    private boolean isInside(UIElement ancestor, UIElement element) {
        for (UIElement walk = element; walk != null; walk = walk.getParent()) {
            if (walk == ancestor) return true;
        }
        return false;
    }

    private boolean holds(DockPanelRef panel) {
        return holdsIn(area, panel);
    }

    private static boolean holdsIn(DockArea in, DockPanelRef panel) {
        for (DockLeaf leaf : in.layout().leaves()) {
            if (leaf.indexOf(panel) >= 0) return true;
        }
        return false;
    }

    private DockWindow tornOutWindow() {
        for (WindowFrame frame : window.desktop().registry().windows()) {
            if (frame instanceof DockWindow dock) return dock;
        }
        return null;
    }

    /** In the surface pixels the input layer speaks — from the LAYOUT chain, never localToWorld. */
    private float[] centreOf(UIElement element) {
        var cache = element.getRuntimeCache();
        var surface = Transform2D.apply(window.getRootTransform(),
                element.getWindowX() + cache.getWidth() / 2f,
                element.getWindowY() + cache.getHeight() / 2f);
        return new float[] { surface.x(), surface.y() };
    }

    private void moveTo(float x, float y) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x), Math.round(y), 0, 0, -1, false, 0f, -1L));
    }

    private void press(float x, float y) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x), Math.round(y), 0, 0, CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
    }

    private void release(float x, float y) {
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round(x), Math.round(y), 0, 0, CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
    }

    /**
     * <b>A torn-out window puts focus in the editor it was torn out with, not on the dock around it.</b>
     *
     * <p>Two things go wrong at once when it does not, and neither reads as a focus bug. Keyboard
     * events dispatch root→target→root, so a keystroke aimed at the {@code DockArea} never reaches the
     * editor inside it — the window takes focus off the one you tore it from and then accepts nothing.
     * And the dock, being focusable only so that commands resolve against it, draws the pane-sized
     * focus ring: a blue line under the caption, which is how it was reported.</p>
     *
     * <p>The timing is the whole difficulty. A dock builds its groups on a rebuild, a frame after the
     * activation that focused the window — so at the moment the frame asks where focus should go there
     * is no group, no tab and no content, and the answer can only be the dock. Asserted after several
     * frames for exactly that reason; asserting on the activation frame passes against the broken
     * build.</p>
     */
    @Test
    public void aTornOutWindowPutsFocusInItsEditor() {
        Tab tab = tabFor(beta);
        float[] at = centreOf(tab);

        press(at[0], at[1]);
        moveTo(at[0], BELOW_DOCK_Y);
        frame();
        moveTo(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();
        release(at[0] + 10f, BELOW_DOCK_Y + 20f);
        frame();

        DockWindow torn = tornOutWindow();
        assertNotNull(torn);
        UIElement focused = window.getInputHandler().getFocusedElement();
        assertNotNull("the new window took focus and gave it to nothing", focused);
        assertNotSame("focus stopped on the dock, which can do nothing with a keystroke",
                torn.area(), focused);
        Tab moved = tabIn(torn.area(), beta);
        assertNotNull("the torn-out window built no tab", moved);
        assertTrue("focus belongs to the panel that was torn out",
                isInside(moved.content(), focused));
    }

    /**
     * <b>Dropping an editor into a dock makes it the one you are working in.</b>
     *
     * <p>Two things were missing and they present as one. The receiving dock's active group was never
     * set, so {@code activePanel()} — and everything resolving through it, the Problems panel and
     * {@code Ctrl+S} among them — went on answering about the panel that was there before: dragging a
     * file back into the main window left the Problems panel reading "No file is open" with the file on
     * screen. And nothing focused it, so the tab arrived selected but cold, drawing its unfocused tint
     * and refusing keystrokes.</p>
     *
     * <p>Driven back out of a WINDOW rather than between two groups, because that is the case where it
     * cannot be papered over: the window the tab came from destroys itself once empty, and destroying a
     * window takes the focus owner out of the tree with it, so there is nothing left to fall back to.</p>
     */
    @Test
    public void dockingAnEditorBackFocusesIt() {
        DockWindow torn = tearOutBeta();

        assertNotNull("the torn-out window built no tab", tabIn(torn.area(), beta));

        // THROUGH performDrop, as anEmptiedDockWindowCloses does, and for the same reason: a fixture
        // cannot easily stage a drag ACROSS windows, because the desktop's window layer is what the
        // pointer resolves against. It is also the honest seam -- performDrop is where a drop happens
        // whatever drove it, and what the drag adds on top of it is covered by the tear-out tests.
        area.performDrop(DockDragPayload.ofPanel(
                        torn.area(), torn.area().layout().leaves().get(0), beta),
                area.layout().leaves().get(0), DockDropZone.MERGE, false);
        frame();

        assertTrue("the panel did not come back", holds(beta));
        assertSame("the dock is still working on the old panel", beta, area.activePanel());

        // ASKED AS "IS FOCUS IN THIS PANEL", never by identity against what the registry last built:
        // a panel's content is rebuilt when it lands in a new home, so the element the fixture recorded
        // and the element on screen are not always the same object. What matters is where focus IS.
        Tab home = tabFor(beta);
        assertNotNull("no tab was built for the panel that came back", home);
        assertTrue("the editor came back cold -- selected, but not focused",
                isInside(home.content(), window.getInputHandler().getFocusedElement()));
    }

    /**
     * <b>Pressing a tab selects it, before any drag begins.</b>
     *
     * <p>A drag never completes a click — the pointer moves, so no Up lands on the tab it went down on —
     * so the selection a click would have made never happened. Dragging a tab that was not already
     * selected therefore lit TWO tabs at once: the selected one, and the one being carried, which
     * click-focus had just outlined. Both references select on press.</p>
     */
    @Test
    public void pressingATabSelectsItBeforeAnyDrag() {
        area.activatePanel(alpha);
        frame();
        assertSame(alpha, area.activePanel());

        float[] at = centreOf(tabFor(beta));
        press(at[0], at[1]);
        frame();

        assertSame("the pressed tab is the selected one", beta, area.activePanel());
    }
}
