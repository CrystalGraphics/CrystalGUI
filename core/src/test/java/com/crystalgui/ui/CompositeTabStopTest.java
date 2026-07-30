package com.crystalgui.ui;

import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Button;
import com.crystalgui.ui.elements.Tab;
import com.crystalgui.ui.elements.TabView;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.tree.UITreeTraversal;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;

/**
 * Composite tab stops — the ARIA APG's <b>roving tabindex</b>, ported as
 * {@link FocusPolicy#CLICK_NOT_TABBABLE}.
 *
 * <p>The APG rule these pin: <i>"the tab sequence should include only one focusable element of a
 * composite UI component"</i>, while <i>"the arrow keys move focus inside of components"</i>. Before
 * this, a ten-tab strip was ten Tab presses to walk past.</p>
 *
 * <p>The distinction under test is <b>focusable vs. tabbable</b>. Everything here stays focusable — by
 * click, by {@code requestFocus}, by arrow keys — and only Tab's view of it changes. That split is
 * why {@code UITreeTraversal} carries two families of walker rather than one.</p>
 */
public class CompositeTabStopTest extends UiTestBase {

    private UIWindow window;
    private UIElement root;

    @Before
    public void setUpTree() {
        root = new UIElement().layout(l -> l.width(400).height(300));
        window = new UIWindow(Ui.of(root));
        window.init(800, 600);
    }

    /**
     * Style pass only, no {@code calculateLayout()} — same restriction {@code TabViewTest} documents: a
     * Tab's {@code UIText} label re-shapes through {@code FontFamilyCache} on layout, and geometry is
     * the harness's job anyway. Everything here is policy and traversal, neither of which needs a box.
     */
    private void styleFrame() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    /** For the one test that needs real hit-testing — safe only on a text-free tree. */
    private void layoutFrame() {
        window.getStyleEngine().calculateStyle(0.016f);
        window.calculateLayout();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
    }

    private void key(int keyCode) {
        window.getInputHandler().consumeKeyboardEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Keyboard.Event('\0', keyCode, true, false, 0L));
        window.getInputHandler().consumeKeyboardEvent(
                new com.crystalgraphics.platform.input.CgSystemInput.Keyboard.Event('\0', keyCode, false, false, 0L));
    }

    /** The whole Tab sequence, in order, from a cold start. */
    private List<UIElement> tabSequence() {
        List<UIElement> out = new ArrayList<>();
        for (UIElement at = UITreeTraversal.firstTabbableIn(root);
             at != null && !out.contains(at);
             at = UITreeTraversal.nextTabbable(at)) {
            out.add(at);
        }
        return out;
    }

    // ── FocusPolicy ─────────────────────────────────────────────────────────

    @Test
    public void clickNotTabbableIsFocusableButNotTabbable() {
        assertTrue(FocusPolicy.CLICK_NOT_TABBABLE.isFocusable());
        assertFalse(FocusPolicy.CLICK_NOT_TABBABLE.isTabbable());
        assertTrue("still clickable — that is the whole point of tabindex=\"-1\"",
                FocusPolicy.CLICK_NOT_TABBABLE.focusesOnClick());
    }

    @Test
    public void theOtherPoliciesAreUnchanged() {
        assertFalse(FocusPolicy.NONE.isFocusable());
        assertFalse(FocusPolicy.NONE.isTabbable());
        assertFalse(FocusPolicy.NONE.focusesOnClick());

        assertTrue(FocusPolicy.FOCUSABLE.isTabbable());
        assertFalse("FOCUSABLE has always meant keyboard-only", FocusPolicy.FOCUSABLE.focusesOnClick());

        assertTrue(FocusPolicy.CLICK.isTabbable());
        assertTrue(FocusPolicy.CLICK.focusesOnClick());
    }

    @Test
    public void elementLevelTabbableTracksThePolicy() {
        UIElement e = new UIElement().layout(l -> l.width(10).height(10));
        root.addChild(e);
        e.setFocusPolicy(FocusPolicy.CLICK_NOT_TABBABLE);
        styleFrame();

        assertTrue("must stay focusable, or requestFocus and the arrow keys break", e.focusable());
        assertFalse(e.tabbable());
    }

    // ── Tab traversal ───────────────────────────────────────────────────────

    @Test
    public void tabSkipsANotTabbableElementButStillReachesTheRest() {
        UIElement before = focusable("before", FocusPolicy.CLICK);
        UIElement skipped = focusable("skipped", FocusPolicy.CLICK_NOT_TABBABLE);
        UIElement after = focusable("after", FocusPolicy.CLICK);
        styleFrame();

        List<UIElement> sequence = tabSequence();
        assertEquals(2, sequence.size());
        assertSame(before, sequence.get(0));
        assertSame(after, sequence.get(1));
        assertFalse(sequence.contains(skipped));
    }

    /** Skipping must be symmetric, or Shift+Tab lands somewhere Tab can never return from. */
    @Test
    public void shiftTabSkipsItToo() {
        UIElement before = focusable("before", FocusPolicy.CLICK);
        focusable("skipped", FocusPolicy.CLICK_NOT_TABBABLE);
        UIElement after = focusable("after", FocusPolicy.CLICK);
        styleFrame();

        assertSame(before, UITreeTraversal.previousTabbable(after));
        assertSame(after, UITreeTraversal.lastTabbableIn(root));
    }

    /**
     * The focus-delegate walkers must NOT skip it. A dialog handing focus to its first control wants the
     * first <em>focusable</em> thing, which is a different question from where Tab lands — and conflating
     * them is what makes a composite either unreachable or double-stopped.
     */
    @Test
    public void focusableWalkersStillSeeIt() {
        UIElement notTabbable = focusable("first", FocusPolicy.CLICK_NOT_TABBABLE);
        focusable("second", FocusPolicy.CLICK);
        styleFrame();

        assertSame(notTabbable, UITreeTraversal.firstFocusableIn(root));
        assertNotSame(notTabbable, UITreeTraversal.firstTabbableIn(root));
    }

    /** Tab starting <em>from</em> a non-tabbable element still moves on — arrow keys can leave focus
     * there, and a strip whose selected tab you cannot Tab out of would be a keyboard trap. */
    @Test
    public void tabLeavesANotTabbableElementNormally() {
        UIElement inner = focusable("inner", FocusPolicy.CLICK_NOT_TABBABLE);
        UIElement after = focusable("after", FocusPolicy.CLICK);
        styleFrame();

        assertSame(after, UITreeTraversal.nextTabbable(inner));
    }

    // ── Clicking ────────────────────────────────────────────────────────────

    /** {@code focusesOnClick()} rather than {@code == CLICK}: without it every tab would go dead to the
     * mouse the moment it stopped being the selected one. */
    @Test
    public void aNotTabbableElementStillFocusesOnClick() {
        UIElement e = focusable("clickable", FocusPolicy.CLICK_NOT_TABBABLE);
        layoutFrame(); // no text in this tree, so real geometry is available

        var input = window.getInputHandler();
        // uiScale 2 at 800x600 over a 400x300 root, so logical (5,5) is physical (10,10).
        input.consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                10, 10, 0, 0, -1, false, 0f, -1L));
        input.beginFrame();
        input.endFrame();
        input.consumeMouseEvent(new com.crystalgraphics.platform.input.CgSystemInput.Mouse.Event(
                10, 10, 0, 0, 0, true, 0f, 1L));
        input.beginFrame();
        input.endFrame();

        assertSame(e, input.getFocusedElement());
    }

    // ── TabView: the first real consumer ────────────────────────────────────

    @Test
    public void aTablistIsOneTabStopHoweverManyTabsItHas() {
        TabView view = new TabView();
        root.addChild(view);
        Tab one = view.addTab("one");
        view.addTab("two");
        view.addTab("three");
        styleFrame();

        List<UIElement> sequence = tabSequence();
        assertEquals("three tabs, one tab stop", 1, sequence.size());
        assertSame(one, sequence.get(0));
    }

    @Test
    public void theTabStopRovesWithSelection() {
        TabView view = new TabView();
        root.addChild(view);
        Tab one = view.addTab("one");
        Tab two = view.addTab("two");
        styleFrame();

        assertTrue(one.tabbable());
        assertFalse(two.tabbable());

        view.selectTab(two);
        styleFrame();

        assertFalse("the old stop must clear, or the strip becomes two stops", one.tabbable());
        assertTrue(two.tabbable());
    }

    /** A tab added to a strip that already has a selection must arrive demoted — {@code Button}'s
     * constructor default is {@code CLICK}, so silence here means two tab stops. */
    @Test
    public void aTabAddedLaterDoesNotBecomeASecondTabStop() {
        TabView view = new TabView();
        root.addChild(view);
        Tab one = view.addTab("one");
        Tab late = view.addTab("late");
        styleFrame();

        assertTrue(one.tabbable());
        assertFalse(late.tabbable());
        assertEquals(1, tabSequence().size());
    }

    /**
     * {@code selectTab(null)} is public. Without the first-tab fallback every tab would be
     * {@code tabindex="-1"} and the entire tablist would vanish from the keyboard — strictly worse than
     * the N-stops problem the pattern set out to fix.
     */
    @Test
    public void aDeselectedStripKeepsOneTabStopOnItsFirstTab() {
        TabView view = new TabView();
        root.addChild(view);
        Tab one = view.addTab("one");
        Tab two = view.addTab("two");
        view.selectTab(two);
        view.selectTab(null);
        styleFrame();

        assertNull(view.getSelectedTab());
        assertTrue("the strip must stay keyboard-reachable with nothing selected", one.tabbable());
        assertFalse(two.tabbable());
    }

    /** Removed tabs leave the composite, so they leave its invariant behind too — a tab handed back and
     * re-used elsewhere must not be silently keyboard-dead. */
    @Test
    public void aRemovedTabBecomesOrdinarilyTabbableAgain() {
        TabView view = new TabView();
        root.addChild(view);
        view.addTab("one");
        Tab two = view.addTab("two");
        styleFrame();
        assertFalse(two.tabbable());

        view.removeTab(two);
        root.addChild(two);
        styleFrame();

        assertTrue(two.tabbable());
    }

    @Test
    public void removingTheSelectedTabMovesTheStopWithTheNewSelection() {
        TabView view = new TabView();
        root.addChild(view);
        Tab one = view.addTab("one");
        Tab two = view.addTab("two");
        styleFrame();

        view.removeTab(one);
        styleFrame();

        assertSame(two, view.getSelectedTab());
        assertTrue(two.tabbable());
        assertEquals(1, tabSequence().size());
    }

    /** The stop is one tab stop *of the document*, not an island: Tab must still reach what surrounds
     * the strip, and in document order. */
    @Test
    public void tabMovesInAndOutOfTheStripInDocumentOrder() {
        Button before = new Button("before");
        root.addChild(before);

        TabView view = new TabView();
        root.addChild(view);
        Tab one = view.addTab("one");
        view.addTab("two");

        Button after = new Button("after");
        root.addChild(after);
        styleFrame();

        assertEquals(java.util.Arrays.asList(before, one, after), tabSequence());
    }

    /** Arrow keys are the other half of the pattern; they must keep working on the now-untabbable tabs.
     * (TabView's own arrow handling is covered in {@code TabViewTest} — this pins that the policy change
     * did not break it.) */
    @Test
    public void arrowKeysStillMoveFocusAcrossUntabbableTabs() {
        TabView view = new TabView();
        root.addChild(view);
        Tab one = view.addTab("one");
        Tab two = view.addTab("two");
        styleFrame();

        window.getInputHandler().requestFocus(one);
        key(com.crystalgraphics.platform.input.CgKeyCodes.KEY_RIGHT);

        assertSame("arrows must reach a tab Tab cannot", two, window.getInputHandler().getFocusedElement());
        assertSame(two, view.getSelectedTab());
    }

    private UIElement focusable(String id, FocusPolicy policy) {
        UIElement e = new UIElement().layout(l -> l.width(20).height(20));
        e.setId(id);
        e.setFocusPolicy(policy);
        root.addChild(e);
        return e;
    }
}
