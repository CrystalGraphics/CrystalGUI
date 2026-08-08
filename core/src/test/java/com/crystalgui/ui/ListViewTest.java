package com.crystalgui.ui;

import com.crystalgui.core.property.ObservableList;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.list.FixedHeightStrategy;
import com.crystalgui.ui.elements.list.ListRenderer;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.ui.elements.list.ListView;
import com.crystalgui.ui.elements.list.SelectionMode;
import com.crystalgui.ui.input.FocusPolicy;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.1.3 — the virtualised list.
 *
 * <h3>What is actually being asserted</h3>
 * <p>Not "the list works" — a list that realised every row would pass any test of its contents. What
 * these pin is the property that makes the widget worth its costs: <b>the element count is bounded by the
 * viewport, not by the model</b>, and it stays bounded while scrolling. Everything else here exists
 * because virtualisation breaks it and it had to be handed back deliberately.</p>
 *
 * <p>A viewport of 100px over rows of 10px sees ten rows, plus two of overscan either side. So the
 * expected realised count is in the mid-teens and must not grow with a model of a hundred thousand.</p>
 */
public class ListViewTest extends UiTestBase {

    private UIWindow window;
    private ObservableList<String> model;
    private ListView<String> list;

    private int templatesCreated;
    private int bindCalls;

    private ListView<String> build(int itemCount) {
        model = new ObservableList<>();
        for (int i = 0; i < itemCount; i++) model.add("item " + i);

        list = new ListView<>(model);
        list.setItemHeight(10f);
        list.layout(l -> l.width(100).height(100));
        list.setRenderer(new ListRenderer<String>() {
            @Override
            public UIElement createTemplate() {
                templatesCreated++;
                UIElement row = new UIElement();
                row.setFocusPolicy(FocusPolicy.FOCUSABLE);
                return row;
            }

            @Override
            public void bind(String item, int index, UIElement template) {
                bindCalls++;
                template.setId(item);
            }
        });

        UIElement root = new UIElement().layout(l -> l.width(100).height(100));
        root.addChild(list);
        window = new UIWindow(Ui.of(root));
        window.init(200, 200); // uiScale 2 -> logical 100x100
        settle();
        return list;
    }

    /** Style + tickers + layout, which is what drives the window — see ListView.tickFrame. */
    private void settle() {
        for (int i = 0; i < 4; i++) window.updateWithoutPainting();
    }

    // ── The property the widget exists for ──────────────────────────────────

    @Test
    public void onlyTheVisibleWindowIsRealised() {
        build(100_000);
        assertTrue("realised " + list.realisedCount() + " rows for a 100k model",
                list.realisedCount() < 20);
        assertTrue("but it did realise something", list.realisedCount() > 0);
    }

    /**
     * <b>Scrolling recycles; it does not allocate.</b>
     *
     * <p>The count of templates ever created is the honest measure — a "virtualised" list that built a
     * fresh element per scroll step would keep the realised count low and still be worthless.</p>
     */
    @Test
    public void scrollingRecyclesRatherThanAllocating() {
        build(100_000);
        int afterFirstLayout = templatesCreated;
        int realisedBefore = list.realisedCount();

        for (int step = 1; step <= 50; step++) {
            list.setScrollTop(step * 40f);
            settle();
        }

        // Not equal to the count at rest: at the very top the leading overscan is clipped by index 0, so
        // the window is two rows smaller there than in the interior. Bounded is the property that matters.
        assertTrue("window grew to " + list.realisedCount(), list.realisedCount() <= realisedBefore + 2);
        assertTrue("fifty scroll steps created " + (templatesCreated - afterFirstLayout) + " new elements;"
                        + " the pool should reach its steady size and stop",
                templatesCreated <= afterFirstLayout + 2);
        assertTrue("and rows were re-bound as they came into view", bindCalls > afterFirstLayout);
    }

    /**
     * <b>The scroll range comes from the model, not the children.</b>
     *
     * <p>The one place the existing scroll machinery had to be taught something new. Derived from the
     * children it would be the height of the dozen realised rows, so the list would scroll about a
     * hundred pixels and stop — with everything else (max scroll, clamping, the scrollbar thumb) wrong in
     * the same way, since they all read through here.</p>
     */
    @Test
    public void scrollHeightComesFromTheModelNotTheChildren() {
        build(1_000);
        assertEquals(10_000f, list.getScrollHeight(), 0.01f);
        assertEquals(10_000f - list.getClientHeight(), list.getMaxScrollTop(), 0.01f);
    }

    @Test
    public void scrollToIndexReachesARowThatDoesNotExistYet() {
        build(10_000);
        assertNull("row 9000 is nowhere near realised", list.realisedRows().get(9_000));

        list.scrollToIndex(9_000);
        settle();

        assertNotNull("and now it is", list.realisedRows().get(9_000));
    }

    // ── The recycling contract ──────────────────────────────────────────────

    /**
     * <b>Structure is built once per element; data is written per row.</b>
     *
     * <p>The split that makes recycling safe rather than a listener leak — a renderer has nowhere to
     * attach a listener in {@code bind}, so it cannot accumulate one per scroll step. Ported from VS
     * Code's {@code IListRenderer}.</p>
     */
    @Test
    public void templatesAreCreatedOncePerElementAndBoundPerRow() {
        build(10_000);
        int templatesAfterLayout = templatesCreated;
        int bindsAfterLayout = bindCalls;

        list.setScrollTop(5_000f);
        settle();
        int templatesAfterWarmup = templatesCreated;
        // Now the pool has reached its steady size (the interior window is two larger than the one at the
        // very top, where overscan is clipped), so from here on scrolling must create nothing at all.
        for (int i = 1; i <= 20; i++) {
            list.setScrollTop(5_000f + i * 130f);
            settle();
        }

        assertEquals("no new templates once the pool is warm", templatesAfterWarmup, templatesCreated);
        assertTrue("and the first scroll added at most the two extra interior rows",
                templatesAfterWarmup <= templatesAfterLayout + 2);
        assertTrue("but every newly visible row was bound", bindCalls > bindsAfterLayout);
    }

    // ── Model changes ───────────────────────────────────────────────────────

    @Test
    public void aModelChangeUpdatesTheView() {
        build(20);
        float heightBefore = list.getScrollHeight();

        model.add("one more");
        settle();

        assertEquals(heightBefore + 10f, list.getScrollHeight(), 0.01f);
    }

    /**
     * <b>A model change must re-bind the rows on screen, not merely resize the scroll range.</b>
     *
     * <p>The bug this exists for: {@code updateWindow} only realises an index it is not already holding,
     * so resetting the range markers left every visible row still showing whatever it was last bound to.
     * The model changed underneath and the screen did not move.</p>
     *
     * <p>It survived because the original test asserted {@code getScrollHeight()} — which reads the model
     * directly and was therefore always right. Asserting the model proves nothing about the display; this
     * asserts what a row actually says.</p>
     */
    @Test
    public void aModelChangeReBindsTheVisibleRows() {
        build(20);
        assertEquals("item 0", boundTextOf(0));

        model.set(0, "CHANGED");
        settle();

        assertEquals("the row on screen shows the new value", "CHANGED", boundTextOf(0));
    }

    /** Inserting at the top shifts every following row's content by one — the case an index-keyed
     * realised map gets wrong most visibly. */
    @Test
    public void insertingAtTheTopReBindsEverythingBelow() {
        build(20);
        assertEquals("item 0", boundTextOf(0));

        model.add(0, "NEW FIRST");
        settle();

        assertEquals("NEW FIRST", boundTextOf(0));
        assertEquals("what used to be row 0 is now row 1", "item 0", boundTextOf(1));
    }

    /** What the renderer last wrote into the row at {@code index} — the display, not the model. */
    private String boundTextOf(int index) {
        UIElement row = list.realisedRows().get(index);
        assertNotNull("row " + index + " is not realised", row);
        return row.getId();
    }

    @Test
    public void anEmptyModelRealisesNothing() {
        build(0);
        assertEquals(0, list.realisedCount());
        assertEquals(0f, list.getScrollHeight(), 0.01f);
        assertEquals("and cannot scroll", 0f, list.getMaxScrollTop(), 0.01f);
    }

    /** A model shorter than the viewport is the ordinary case for most lists, and must realise all of it
     * rather than falling into a windowing edge case. */
    @Test
    public void aModelShorterThanTheViewportRealisesAllOfIt() {
        build(3);
        assertEquals(3, list.realisedCount());
    }

    @Test
    public void clearingTheModelRecyclesEverything() {
        build(500);
        assertTrue(list.realisedCount() > 0);

        model.clear();
        settle();

        assertEquals(0, list.realisedCount());
        assertTrue("the elements are pooled, not destroyed", list.pooledCount() > 0);
    }

    // ── What virtualisation breaks, handed back deliberately ────────────────

    /**
     * <b>Focus survives a row being recycled.</b>
     *
     * <p>The focused element is scrolled away, recycled, and re-bound to some other item. Tracking the
     * element would therefore leave focus on the wrong row; the view tracks the <em>index</em>, which is
     * a row's only stable identity, and restores focus when that index is realised again. Without it a
     * keyboard user loses their place on every scroll.</p>
     */
    @Test
    public void focusFollowsTheIndexAcrossRecycling() {
        build(10_000);
        list.setFocusedIndex(3);
        settle();

        UIElement rowThree = list.realisedRows().get(3);
        assertNotNull(rowThree);
        assertSame("focus starts on row 3's element",
                rowThree, window.getInputHandler().getFocusedElement());

        list.setScrollTop(8_000f);
        settle();
        assertNull("row 3 is long gone", list.realisedRows().get(3));

        list.setScrollTop(0f);
        settle();

        UIElement rowThreeAgain = list.realisedRows().get(3);
        assertNotNull(rowThreeAgain);
        assertSame("and focus is back on row 3, whichever element now represents it",
                rowThreeAgain, window.getInputHandler().getFocusedElement());
    }

    /**
     * <b>Focus that arrives on a row directly — by click or Tab — is tracked too.</b>
     *
     * <p>The first version only knew about {@code setFocusedIndex}, which nothing in a real UI calls: a
     * user clicks a row. So focus stayed on the element, the element was recycled and re-bound to some
     * unrelated item, and the focus ring appeared to jump onto whatever scrolled into its place. The view
     * now learns the index from the row's own focus event, so any route into focus is covered.</p>
     */
    @Test
    public void focusArrivingOnARowIsTrackedByIndex() {
        build(10_000);
        UIElement rowFive = list.realisedRows().get(5);
        assertNotNull(rowFive);

        window.getInputHandler().requestFocus(rowFive);
        settle();
        assertEquals("the view learned the index without being told",
                5, list.getFocusedIndex());
    }

    /**
     * <b>A recycled row gives up focus before it is reused.</b>
     *
     * <p>The worst symptom this widget had: focus rode the recycled element into the pool and back out,
     * so scrolling away from a focused row left the ring sitting on whatever unrelated item inherited
     * that element. The element is not the identity — the index is.</p>
     */
    @Test
    public void aRecycledRowDoesNotCarryFocusToItsNextItem() {
        build(10_000);
        window.getInputHandler().requestFocus(list.realisedRows().get(5));
        settle();

        list.setScrollTop(9_000f);
        settle();

        UIElement focused = window.getInputHandler().getFocusedElement();
        for (var entry : list.realisedRows().entrySet()) {
            assertNotSame("row " + entry.getKey() + " inherited a recycled element's focus",
                    entry.getValue(), focused);
        }
    }

    /** Rows are structure. A caller's child would be positioned by index and destroyed on the next
     * scroll, so the composite refuses them like every other widget here. */
    @Test
    public void publicChildrenAreRefused() {
        build(10);
        assertFalse(list.acceptsPublicChildren());
        try {
            list.addChild(new UIElement());
            fail("a public child would be recycled out of existence");
        } catch (RuntimeException expected) {
            // exactly as Button, TabView and Switch behave
        }
    }

    /**
     * <b>A list must be scrollable by the wheel, which is opt-in here.</b>
     *
     * <p>Setting {@code overflow} does not do it: a bare element is programmatic-scroll only however its
     * overflow is set, and taking the wheel is what makes something a scroll <em>view</em>. The first
     * version of this widget set {@code overflow: scroll} on a plain {@code UIElement} and could not be
     * scrolled by hand at all — every test passed, because they all scrolled it from code.</p>
     */
    @Test
    public void theListIsARealScrollView() {
        build(1_000);
        assertTrue("must be a scroll container at all", list.isScrollContainer());
        assertTrue("and must take the wheel, which only a ScrollerView does",
                list instanceof com.crystalgui.ui.elements.ScrollerView);
    }

    /** A ListView is a widget somebody adds to a page, not a part of one. Marking it internal would hide
     * the whole list from public traversal and from the codec — the rows are what is internal. */
    @Test
    public void theListItselfIsNotAnInternalElement() {
        build(10);
        assertFalse(list.isInternalUI());
        assertTrue("but its rows are", list.realisedRows().values().iterator().next().isInternalUI());
    }

    /**
     * <b>Leaving the tree detaches from the model.</b>
     *
     * <p>An {@code ObservableList} outlives the views onto it — a file list survives the panel showing
     * it — so a listener held by a discarded view keeps that view, its pooled elements and every item
     * they reference alive for as long as the model lives. {@code dispose()} exists for explicit use, but
     * a leak that depends on a caller remembering is a leak.</p>
     */
    @Test
    public void removalFromTheTreeDetachesFromTheModel() {
        build(100);
        assertTrue("listening while in the tree", list.isListeningToModel());

        list.removeSelf();
        settle();

        assertFalse("and detached once removed", list.isListeningToModel());
    }

    // ── Selection ───────────────────────────────────────────────────────────

    private void key(int keyCode, int modifiers) {
        UIElement focused = window.getInputHandler().getFocusedElement();
        UIElement target = focused != null ? focused : list;
        window.getInputHandler().sendInputEvent(target,
                new com.crystalgui.ui.event.KeyboardEvent.Down(target, keyCode, (char) 0, false, modifiers, 0L));
        settle();
    }

    /** A real press and release at a row's centre, through the input handler. */
    private void clickRow(int index) {
        UIElement row = list.realisedRows().get(index);
        var cache = row.getRuntimeCache();
        var centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        int x = Math.round(centre.x()), y = Math.round(centre.y());
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input
                .CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input
                .CgSystemInput.Mouse.Event(x, y, 0, 0,
                com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, true, 0f, 1L));
        window.getInputHandler().consumeMouseEvent(new com.crystalgraphics.platform.input
                .CgSystemInput.Mouse.Event(x, y, 0, 0,
                com.crystalgraphics.platform.input.CgMouseCodes.LEFT_BUTTON, false, 0f, 2L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        settle();
    }

    /**
     * A real press, not {@code requestFocus}.
     *
     * <p>This used to focus a row and assert it became selected, which passed while selection was
     * driven entirely by the focus event — and that design is exactly what made Ctrl-click and
     * Shift-click impossible, since focus carries no modifiers. Selection is now a press concern, so
     * the test does what its name always said.</p>
     */
    @Test
    public void clickingARowSelectsIt() {
        build(1_000);
        clickRow(4);
        assertEquals(java.util.Set.of(4), list.getSelectedIndices());
    }

    /** Selection follows focus in SINGLE mode — arrowing through a list you then have to press Space in
     * is a keyboard experience nobody wants. */
    @Test
    public void arrowKeysMoveFocusAndSelection() {
        build(1_000);
        list.setFocusedIndex(0);
        settle();

        key(CgKeyCodes.KEY_DOWN, 0);
        assertEquals(1, list.getFocusedIndex());
        assertEquals(java.util.Set.of(1), list.getSelectedIndices());

        key(CgKeyCodes.KEY_DOWN, 0);
        key(CgKeyCodes.KEY_UP, 0);
        assertEquals(1, list.getFocusedIndex());
    }

    @Test
    public void homeAndEndReachBothEndsOfTheModel() {
        build(1_000);
        list.setFocusedIndex(500);
        settle();

        key(CgKeyCodes.KEY_END, 0);
        assertEquals(999, list.getFocusedIndex());
        assertNotNull("and the last row is realised, so End is usable", list.realisedRows().get(999));

        key(CgKeyCodes.KEY_HOME, 0);
        assertEquals(0, list.getFocusedIndex());
    }

    @Test
    public void pageKeysMoveByAViewportAtATime() {
        build(1_000);
        list.setFocusedIndex(0);
        settle();

        key(CgKeyCodes.KEY_NEXT, 0);
        int afterPageDown = list.getFocusedIndex();
        assertTrue("moved by roughly a viewport, was " + afterPageDown,
                afterPageDown >= 8 && afterPageDown <= 11);

        key(CgKeyCodes.KEY_PRIOR, 0);
        assertEquals(0, list.getFocusedIndex());
    }

    @Test
    public void navigationClampsAtBothEnds() {
        build(5);
        list.setFocusedIndex(0);
        settle();
        key(CgKeyCodes.KEY_UP, 0);
        assertEquals("cannot go above the first row", 0, list.getFocusedIndex());

        key(CgKeyCodes.KEY_END, 0);
        key(CgKeyCodes.KEY_DOWN, 0);
        assertEquals("nor past the last", 4, list.getFocusedIndex());
    }

    /** Shift extends from the ANCHOR, not from wherever focus has since reached — which is what makes
     * repeated Shift+Down grow one range rather than a series of pairs. */
    @Test
    public void shiftExtendsARangeFromTheAnchor() {
        build(100);
        list.setSelectionMode(SelectionMode.MULTIPLE);
        list.select(2);
        list.setFocusedIndex(2);
        settle();

        key(CgKeyCodes.KEY_DOWN, CgModifiers.SHIFT);
        key(CgKeyCodes.KEY_DOWN, CgModifiers.SHIFT);

        assertEquals(java.util.Set.of(2, 3, 4), list.getSelectedIndices());
    }

    /** The APG's escape hatch: reach a row without selecting it, so it can be added to a multi-selection
     * deliberately. */
    @Test
    public void ctrlArrowMovesFocusWithoutSelecting() {
        build(100);
        list.setSelectionMode(SelectionMode.MULTIPLE);
        list.select(0);
        list.setFocusedIndex(0);
        settle();

        key(CgKeyCodes.KEY_DOWN, CgModifiers.CTRL);

        assertEquals(1, list.getFocusedIndex());
        assertEquals("selection untouched", java.util.Set.of(0), list.getSelectedIndices());

        key(CgKeyCodes.KEY_SPACE, 0);
        assertEquals("and Space adds it", java.util.Set.of(0, 1), list.getSelectedIndices());
    }

    @Test
    public void singleSelectionReplacesRatherThanAccumulating() {
        build(100);
        list.select(3);
        list.select(7);
        assertEquals(java.util.Set.of(7), list.getSelectedIndices());

        list.toggle(9);
        assertEquals("toggle in SINGLE mode is a replace, not an add",
                java.util.Set.of(9), list.getSelectedIndices());
    }

    @Test
    public void selectAllIsMultipleOnly() {
        build(50);
        list.selectAll();
        assertTrue("SINGLE mode must refuse", list.getSelectedIndices().isEmpty());

        list.setSelectionMode(SelectionMode.MULTIPLE);
        list.selectAll();
        assertEquals(50, list.getSelectedIndices().size());
    }

    @Test
    public void narrowingToSingleKeepsExactlyOne() {
        build(50);
        list.setSelectionMode(SelectionMode.MULTIPLE);
        list.selectAll();

        list.setSelectionMode(SelectionMode.SINGLE);
        assertEquals(1, list.getSelectedIndices().size());
    }

    @Test
    public void noneModeRefusesSelectionEntirely() {
        build(50);
        list.setSelectionMode(SelectionMode.NONE);
        list.select(3);
        list.toggle(4);
        assertTrue(list.getSelectedIndices().isEmpty());
    }

    /** A model that shrank must not leave selections pointing past its end — every consumer would
     * otherwise have to defend against an index that cannot be fetched. */
    @Test
    public void aShrinkingModelDropsSelectionsPastItsEnd() {
        build(50);
        list.setSelectionMode(SelectionMode.MULTIPLE);
        list.selectAll();

        while (model.size() > 10) model.removeAt(model.size() - 1);
        settle();

        assertEquals(10, list.getSelectedIndices().size());
        assertFalse(list.getSelectedIndices().contains(40));
    }

    /** Selected rows carry a class a theme can target, and it survives recycling — a row scrolled away
     * and back must come back still looking selected. */
    @Test
    public void theSelectedClassSurvivesRecycling() {
        build(10_000);
        list.select(5);
        settle();
        assertTrue(list.realisedRows().get(5).hasClass(ListView.SELECTED_CLASS));

        list.setScrollTop(9_000f);
        settle();
        list.setScrollTop(0f);
        settle();

        assertTrue("still marked after a round trip through the pool",
                list.realisedRows().get(5).hasClass(ListView.SELECTED_CLASS));
        assertFalse("and its neighbour is not",
                list.realisedRows().get(6).hasClass(ListView.SELECTED_CLASS));
    }

    @Test
    public void selectionChangesAreSignalled() {
        build(100);
        var seen = new java.util.ArrayList<java.util.Set<Integer>>();
        list.onSelectionChanged.connect(seen::add);

        list.select(3);
        list.select(3);   // unchanged — must not re-emit
        list.select(8);

        assertEquals(2, seen.size());
        assertEquals(java.util.Set.of(8), seen.get(1));
    }

    /**
     * <b>Focus the view moves itself must not touch the selection.</b>
     *
     * <p>Restoring focus to a row that has scrolled back into view is the view's own doing, not a
     * gesture. Without the guard it re-entered the click path and re-selected that row — quietly
     * discarding whatever multi-selection had been built in the meantime. Same guard is what lets
     * Ctrl+arrow move focus without selecting and Shift+arrow extend a range instead of replacing it.</p>
     */
    @Test
    public void restoringFocusAfterRecyclingDoesNotDisturbTheSelection() {
        build(10_000);
        list.setSelectionMode(SelectionMode.MULTIPLE);
        // SELECTED explicitly, then focused. Focus no longer implies selection -- a press does -- so
        // seeding through requestFocus would leave 2 unselected and quietly weaken what follows.
        list.select(2);
        window.getInputHandler().requestFocus(list.realisedRows().get(2));
        settle();
        list.toggle(3);
        list.toggle(4);
        assertEquals(java.util.Set.of(2, 3, 4), list.getSelectedIndices());

        list.setScrollTop(9_000f);
        settle();
        list.setScrollTop(0f);
        settle();

        assertEquals("the multi-selection survived focus being restored",
                java.util.Set.of(2, 3, 4), list.getSelectedIndices());
    }

    /**
     * <b>Enter selects the focused row, and Space toggles it.</b>
     *
     * <p>The pair is the point. After Ctrl+arrow a row is focused and unselected, so there has to be a
     * key that says "just this one" — Space cannot, because in a multi-selection it adds. Enter did
     * nothing at all before this: {@code UIInputHandler} turns Space/Enter into a synthesized click on
     * the focused element, and since selection here is driven by the <em>focus</em> event, clicking a row
     * that already has focus changes nothing.</p>
     */
    @Test
    public void enterSelectsTheFocusedRowAndSpaceToggles() {
        build(100);
        list.setSelectionMode(SelectionMode.MULTIPLE);
        list.select(0);
        list.setFocusedIndex(0);
        settle();

        key(CgKeyCodes.KEY_DOWN, CgModifiers.CTRL);
        key(CgKeyCodes.KEY_DOWN, CgModifiers.CTRL);
        assertEquals("Ctrl+arrow reached row 2 without selecting it",
                java.util.Set.of(0), list.getSelectedIndices());

        key(CgKeyCodes.KEY_RETURN, 0);
        assertEquals("Enter replaces the selection with the focused row",
                java.util.Set.of(2), list.getSelectedIndices());

        key(CgKeyCodes.KEY_DOWN, CgModifiers.CTRL);
        key(CgKeyCodes.KEY_SPACE, 0);
        assertEquals("Space adds rather than replaces", java.util.Set.of(2, 3), list.getSelectedIndices());
    }

    /** Activation is what the user decided; selection is merely where they are. Arrowing through a file
     * list changes the selection constantly and none of those are "open this file". */
    @Test
    public void enterSignalsActivationSeparatelyFromSelection() {
        build(100);
        var activated = new java.util.ArrayList<Integer>();
        list.onRowActivated.connect(activated::add);

        list.setFocusedIndex(4);
        settle();
        key(CgKeyCodes.KEY_DOWN, 0);
        assertTrue("arrowing selects but does not activate", activated.isEmpty());

        key(CgKeyCodes.KEY_RETURN, 0);
        assertEquals(java.util.List.of(5), activated);
    }

    /**
     * <b>The scrollbar sizing rule must be keyed by CLASS, not by a list of tags.</b>
     *
     * <p>A widget's cascade identity is its TAG, never its Java supertype, so every {@code ScrollerView}
     * subclass is a tag that matches none of the {@code scrollerview} rules its superclass depends on.
     * The sheet used to answer that with a list — {@code scrollerview, listview, treeview, texteditor,
     * tableview} — and <b>this test used to assert that same list was complete.</b></p>
     *
     * <p>Both went stale together. {@code ConfiguratorPanel} was added to neither, so its bars existed as
     * elements, got no width from anywhere and did not draw, while the wheel scrolled perfectly — which
     * reads as a widget that forgot its scrollbars rather than a sheet that never sized them. A guard
     * built from a hand-written list of subclasses fails in exactly the way the thing it guards does,
     * because it is a second copy of it.</p>
     *
     * <p>So the rule is class-scoped now and there is no list to keep. Only {@code ScrollerView} creates
     * these classes, so this is narrower in intent and wider in reach at once. Per-widget rules that
     * genuinely differ — {@code texteditor}'s z-index — stay tag-scoped and are unaffected.</p>
     */
    @Test
    public void theScrollbarSizingRuleIsKeyedByClassNotByTag() {
        String sheet = com.crystalgraphics.util.io.CgIO.loadSource("crystalgui:ui/styles/default.css");
        assertNotNull("default.css must be readable", sheet);
        assertTrue("the vertical scrollbar has no class-scoped sizing rule, so any ScrollerView subclass "
                + "not named by a tag rule draws no bar at all", sheet.contains(".__v-scroller__ {"));
        assertTrue("the horizontal scrollbar has no class-scoped sizing rule",
                sheet.contains(".__h-scroller__ {"));
        for (String tag : new String[] { "scrollerview", "listview", "treeview", "tableview" }) {
            assertFalse("'" + tag + " .__v-scroller__' is back. A per-subclass list is the thing that "
                            + "went stale: ConfiguratorPanel was in neither the sheet nor the test that "
                            + "checked the sheet, and its bars silently had no width",
                    sheet.contains(tag + " .__v-scroller__ {"));
        }
    }

    // ── The size strategy ───────────────────────────────────────────────────

    @Test
    public void aZeroRowHeightIsRejected() {
        try {
            new FixedHeightStrategy(0f);
            fail("zero would put every row at the same offset — the whole model in one window");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("positive"));
        }
    }

    @Test
    public void fixedHeightMapsOffsetsAndIndicesBothWays() {
        FixedHeightStrategy strategy = new FixedHeightStrategy(12f);
        assertEquals(120f, strategy.offsetOf(10), 0.01f);
        assertEquals(10, strategy.indexAt(120f, 1000));
        assertEquals("mid-row still resolves to that row", 10, strategy.indexAt(127f, 1000));
        assertEquals("clamped to the model", 50, strategy.indexAt(99_999f, 50));
        assertEquals(0, strategy.indexAt(-5f, 50));
    }

    // ── Horizontal scrolling ────────────────────────────────────────────────

    /**
     * <b>The horizontal range comes from the realised rows, and it only ever grows.</b>
     *
     * <p>Both halves are the design, and each fails in a way that looks like something else. Deriving the
     * range from the children the way {@code UIElement} does reports the rows, which are written to the
     * viewport's width — so the range is always exactly the viewport, the bar never appears, and a
     * truncated name has no way to be reached. That is the bug this was added for.</p>
     *
     * <p>And a range that tracked only what is on screen right now would collapse the moment the long name
     * scrolled out of the realised window, pulling the thumb and the content sideways under the pointer
     * mid-scroll. Only realised rows can be measured at all, so growing-only is what makes the range
     * usable rather than merely correct at one instant.</p>
     */
    @Test
    public void horizontalScrollingMeasuresTheWidestRealisedRow() {
        ObservableList<String> items = new ObservableList<>();
        for (int i = 0; i < 1000; i++) items.add("item " + i);

        ListView<String> wide = new ListView<>(items);
        wide.setItemHeight(10f);
        wide.setHorizontalScrolling(true);
        wide.layout(l -> l.width(100).height(100));
        wide.setRenderer(new ListRenderer<String>() {
            @Override
            public UIElement createTemplate() {
                UIElement row = new UIElement();
                row.addChild(new UIElement());
                return row;
            }

            @Override
            public void bind(String item, int index, UIElement template) {
                // ONE long row, at the very top, so scrolling away from it leaves it unrealised.
                float width = index == 0 ? 400f : 40f;
                template.getChildren().get(0).layout(l -> l.width(width).height(10f));
            }
        });

        UIElement root = new UIElement().layout(l -> l.width(100).height(100));
        root.addChild(wide);
        UIWindow host = new UIWindow(Ui.of(root));
        host.init(200, 200);
        for (int i = 0; i < 6; i++) host.updateWithoutPainting();

        assertTrue("the long row sets the range, not the viewport: " + wide.getScrollWidth(),
                wide.getScrollWidth() >= 400f);
        assertTrue("so there is somewhere to scroll to", wide.getMaxScrollLeft() > 0f);

        wide.setScrollImmediate(0f, 5_000f);
        for (int i = 0; i < 6; i++) host.updateWithoutPainting();

        assertTrue("the range survives the long row leaving the realised window: " + wide.getScrollWidth(),
                wide.getScrollWidth() >= 400f);
    }

    /** Off by default — and off means the range is the viewport, whatever a row contains. */
    @Test
    public void withoutHorizontalScrollingRowsNeverOutgrowTheViewport() {
        build(1_000);
        assertFalse(list.isHorizontalScrolling());
        assertEquals("rows are written to the viewport width, so there is nothing to scroll",
                0f, list.getMaxScrollLeft(), 0.01f);
    }
}
