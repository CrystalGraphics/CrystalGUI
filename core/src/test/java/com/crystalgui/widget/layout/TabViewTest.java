package com.crystalgui.widget.layout;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.widget.layout.Tab;
import com.crystalgui.widget.layout.TabView;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * {@link TabView} — selection, pane visibility, arrow navigation and removal.
 *
 * <p>Where input is involved it goes through {@code consumeKeyboardEvent}, never by calling
 * {@code onPressed.emit()} directly — a listener that was never wired up has to fail here rather than
 * look fine in isolation, which is exactly what the TextField wiring bug taught.</p>
 *
 * <p><b>No layout happens in this class.</b> A Tab holds a {@code UIText} label, and laying that out
 * re-shapes through FreeType, whose native bindings aren't on the headless test classpath. So
 * anything geometric — mouse hit-testing on a tab, strip scrolling, where the panes actually sit —
 * belongs to {@code CgUiTabViewScene}, not here. See {@link #styleFrame()} and {@link #activate}.</p>
 */
public class TabViewTest extends UiDocumentTestBase {

    private TabView tabView;

    @Before
    public void setUp() {
        tabView = new TabView();
        tabView.layout(l -> l.width(300).height(200));
        UINode root = new UINode().layout(l -> l.width(300).height(200));
        root.append(tabView);
        document.append(root);
        frame();
    }

    private Tab tab(String label) {
        return tabView.addTab(label);
    }


    /**
     * Style pass only — deliberately <b>not</b> {@code calculateLayout()}.
     *
     * <p>A Tab contains a {@code UIText} label, and {@code UIText.onLayoutChanged} re-shapes through
     * {@code FontFamilyCache}, which needs the native FreeType bindings that are not on the headless
     * test classpath. Anything geometric about this widget therefore belongs to the harness scene;
     * everything here is selection state, style state and traversal, none of which needs layout.</p>
     */
    private void styleFrame() {
        document.styleEngine().calculateStyle(0.016f);
        frame();
    }

    private void key(int keyCode) {
        document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', keyCode, true, false, 0L));
        document.input().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', keyCode, false, false, 0L));
    }

    /**
     * Activates a tab the way a user would, through the real input path — focus it and press Space.
     *
     * <p>{@code UIInputHandler.handleActivationKey} synthesizes the same {@code MouseEvent.Down}/
     * {@code Up} pair a click produces and routes it to the focused element, so this exercises
     * Button's press-target activation and the TabView listener hanging off it — everything a mouse
     * click would, minus the hit-test. It is chosen over a synthetic click because hit-testing needs
     * real geometry, which needs layout, which needs FreeType. Mouse targeting is covered by the
     * harness scene instead.</p>
     */
    private void activate(Tab tab) {
        document.focus().requestFocus(tab);
        key(CgKeyCodes.KEY_SPACE);
    }

    private TaffyDisplay displayOf(UINode element) {
        return element.getStyle().getComputed(LayoutProperties.DISPLAY);
    }

    // ── Selection ───────────────────────────────────────────────────────────

    @Test
    public void theFirstTabAddedIsSelected() {
        Tab first = tab("one");
        Tab second = tab("two");

        assertSame(first, tabView.getSelectedTab());
        assertTrue(first.isChecked());
        assertFalse(second.isChecked());
    }

    @Test
    public void exactlyOnePaneIsVisible() {
        Tab first = tab("one");
        Tab second = tab("two");
        Tab third = tab("three");
        styleFrame();

        assertEquals(TaffyDisplay.FLEX, displayOf(first.content()));
        assertEquals(TaffyDisplay.NONE, displayOf(second.content()));
        assertEquals(TaffyDisplay.NONE, displayOf(third.content()));

        tabView.selectTab(second);
        styleFrame();

        assertEquals(TaffyDisplay.NONE, displayOf(first.content()));
        assertEquals(TaffyDisplay.FLEX, displayOf(second.content()));
        assertEquals(TaffyDisplay.NONE, displayOf(third.content()));
    }

    /** {@code isChecked()} is what makes {@code tab:checked} match — there is no marker class. */
    @Test
    public void isCheckedTracksSelection() {
        Tab first = tab("one");
        Tab second = tab("two");

        assertTrue(first.isChecked());
        tabView.selectTab(second);
        assertFalse("the old tab must stop being :checked", first.isChecked());
        assertTrue(second.isChecked());
    }

    @Test
    public void activatingATabSelectsIt() {
        tab("one");
        Tab second = tab("two");
        styleFrame();

        activate(second);
        styleFrame();

        assertSame("activation must select, through the real input path", second, tabView.getSelectedTab());
        assertEquals(TaffyDisplay.FLEX, displayOf(second.content()));
    }

    @Test
    public void selectionSignalFiresOnceOnRealChanges() {
        Tab first = tab("one");
        Tab second = tab("two");

        int[] fired = {0};
        tabView.attachListener(t -> fired[0]++);

        tabView.selectTab(second);
        assertEquals(1, fired[0]);
        tabView.selectTab(second);
        assertEquals("re-selecting the same tab must not signal", 1, fired[0]);
        tabView.selectTab(first);
        assertEquals(2, fired[0]);
    }

    @Test
    public void selectingAForeignTabIsIgnored() {
        Tab mine = tab("one");
        Tab foreign = new Tab("elsewhere");

        tabView.selectTab(foreign);

        assertSame(mine, tabView.getSelectedTab());
    }

    // ── Hidden panes are really hidden ──────────────────────────────────────

    /**
     * The reason panes are hidden with {@code display} rather than left visible or moved off-screen.
     *
     * <p>Asserted against the actual traversal, not against {@code focusable()}: that method only
     * consults an element's <em>own</em> display, so a visible child of a hidden parent answers
     * "yes" and always will. Whole-subtree exclusion lives in {@code hasFocusableDescendant}, which
     * is what Tab-traversal gates on — and which did not check display until this widget needed
     * it.</p>
     */
    @Test
    public void hiddenPaneContentIsNotReachableByTabTraversal() {
        Tab first = tab("one");
        Tab second = tab("two");

        UINode visibleField = new UINode().layout(l -> l.width(50).height(10));
        visibleField.setFocusPolicy(FocusPolicy.FOCUSABLE);
        first.content().append(visibleField);

        UINode hiddenField = new UINode().layout(l -> l.width(50).height(10));
        hiddenField.setFocusPolicy(FocusPolicy.FOCUSABLE);
        second.content().append(hiddenField);
        styleFrame();

        List<UINode> reachable = new ArrayList<>();
        for (UINode at = document.focus().firstTabbableIn(document);
             at != null && !reachable.contains(at);
             at = document.focus().nextTabbable(at, document)) {
            reachable.add(at);
        }

        assertTrue("the showing pane's content must be reachable", reachable.contains(visibleField));
        assertFalse("Tab must not land inside a hidden pane", reachable.contains(hiddenField));
        assertTrue("the selected tab holds the strip's one tab stop", reachable.contains(first));
        assertFalse("...and the unselected one does not — a tablist is one tab stop",
                reachable.contains(second));
    }

    // ── Keyboard ────────────────────────────────────────────────────────────

    @Test
    public void arrowKeysMoveSelectionAlongTheStrip() {
        Tab first = tab("one");
        Tab second = tab("two");
        Tab third = tab("three");
        styleFrame();
        document.focus().requestFocus(first);

        key(CgKeyCodes.KEY_RIGHT);
        assertSame(second, tabView.getSelectedTab());
        key(CgKeyCodes.KEY_RIGHT);
        assertSame(third, tabView.getSelectedTab());
        key(CgKeyCodes.KEY_RIGHT);
        assertSame("must clamp at the end rather than wrap", third, tabView.getSelectedTab());
        key(CgKeyCodes.KEY_LEFT);
        assertSame(second, tabView.getSelectedTab());
    }

    @Test
    public void homeAndEndJumpToTheOuterTabs() {
        Tab first = tab("one");
        tab("two");
        Tab third = tab("three");
        styleFrame();
        document.focus().requestFocus(first);

        key(CgKeyCodes.KEY_END);
        assertSame(third, tabView.getSelectedTab());
        key(CgKeyCodes.KEY_HOME);
        assertSame(first, tabView.getSelectedTab());
    }

    /** A side strip stacks vertically, so up/down drives it and left/right is somebody else's key. */
    @Test
    public void aVerticalStripUsesUpAndDown() {
        tabView.setTabSide(TabView.TabSide.LEFT);
        Tab first = tab("one");
        Tab second = tab("two");
        styleFrame();
        document.focus().requestFocus(first);

        key(CgKeyCodes.KEY_RIGHT);
        assertSame("left/right must not move a vertical strip", first, tabView.getSelectedTab());
        key(CgKeyCodes.KEY_DOWN);
        assertSame(second, tabView.getSelectedTab());
    }

    // ── Removal ─────────────────────────────────────────────────────────────

    @Test
    public void removingTheSelectedTabSelectsItsNeighbour() {
        Tab first = tab("one");
        Tab second = tab("two");
        Tab third = tab("three");
        tabView.selectTab(second);

        assertTrue(tabView.removeTab(second));

        assertEquals(2, tabView.getTabCount());
        assertSame("the tab that took its place should take over", third, tabView.getSelectedTab());
        assertTrue(third.isChecked());
        assertFalse(first.isChecked());
    }

    @Test
    public void removingTheLastTabLeavesNothingSelected() {
        Tab only = tab("one");

        int[] fired = {0};
        Tab[] last = {only};
        tabView.attachListener(t -> { fired[0]++; last[0] = t; });

        tabView.removeTab(only);

        assertNull(tabView.getSelectedTab());
        assertFalse(only.isChecked());
        assertEquals(1, fired[0]);
        assertNull("the signal should report the empty selection", last[0]);
    }

    @Test
    public void removingAnUnselectedTabLeavesSelectionAlone() {
        Tab first = tab("one");
        Tab second = tab("two");

        assertTrue(tabView.removeTab(second));
        assertSame(first, tabView.getSelectedTab());
        assertFalse(tabView.removeTab(second));   // already gone
    }

    @Test
    public void clearTabsEmptiesEverything() {
        tab("one");
        tab("two");
        tabView.clearTabs();

        assertEquals(0, tabView.getTabCount());
        assertNull(tabView.getSelectedTab());
    }

    // ── Structure ───────────────────────────────────────────────────────────

    @Test
    public void theTabViewRefusesArbitraryChildren() {
        try {
            tabView.append(new UINode());
            fail("a TabView's structure is fixed — addChild must be refused");
        } catch (UnsupportedOperationException expected) {
            // the typed accessors are the way in
        }
    }

    /** The panes are elsewhere in the tree, not nested under their tab — that's what keeps it flat. */
    @Test
    public void panesLiveInThePanesContainerNotUnderTheTab() {
        Tab tab = tab("one");

        assertTrue(tabView.panes().children().contains(tab.content()));
        assertFalse(tab.children().contains(tab.content()));
    }

    /**
     * Tabs go in the rail, and the strip's scrollbar is the rail's laid-out sibling.
     *
     * <p>The bar is a real flex item rather than ScrollerView's absolutely-positioned overlay: an
     * overlay floats on top of a strip barely taller than one tab and eats their bottom edge, and
     * reserving padding to dodge it pushes every tab off the pane — which is exactly what the
     * selected tab's seam depends on.</p>
     */
    @Test
    public void theStripHoldsARailAndALaidOutBar() {
        Tab tab = tab("one");

        assertTrue("tabs belong to the rail", tabView.rail().children().contains(tab));
        assertTrue(tabView.strip().children().contains(tabView.rail()));
        assertTrue("the bar is a sibling of the rail, not an overlay inside it",
                tabView.strip().children().contains(tabView.bar()));
        assertFalse("the rail must show no bars of its own",
                tabView.rail().isScrollbarsVisible());
    }

    @Test
    public void tabSideDrivesTheRootDirectionAndTheStateClass() {
        assertEquals(FlexDirection.COLUMN,
                tabView.getStyle().getComputed(LayoutProperties.FLEX_DIRECTION));
        assertTrue(tabView.hasClass(TabView.TOP_CLASS));

        tabView.setTabSide(TabView.TabSide.RIGHT);

        assertEquals(FlexDirection.ROW_REVERSE,
                tabView.getStyle().getComputed(LayoutProperties.FLEX_DIRECTION));
        assertTrue(tabView.hasClass(TabView.RIGHT_CLASS));
        assertFalse("the old side class must be cleared",
                tabView.hasClass(TabView.TOP_CLASS));
    }
}
