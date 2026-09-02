package com.crystalgui.workbench.dock.layout;

import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.layout.SplitView;
import com.crystalgui.workbench.dock.DockArea;
import com.crystalgui.workbench.dock.drag.DockDropZone;
import com.crystalgui.workbench.dock.layout.DockLayout;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockPanelRegistry;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>A grow chain inside a split pane must not collapse.</b>
 *
 * <p>{@code align-items: stretch} gives a pane the split's full cross size, and the measured box is the
 * same whether that comes from stretching or from an explicit {@code 100%}. What differs is whether the
 * size is <b>definite</b>. A stretched box is not, so Taffy resolves the pane's children against an
 * indeterminate cross size and a grow chain survives exactly <em>one</em> level before collapsing to
 * zero.</p>
 *
 * <h3>What this cost</h3>
 *
 * <p>Two dock panels side by side put a {@code SplitView} above every group. The shader graph —
 * {@code shadergrapheditor > __shader-content__ > graphview}, three boxes all growing — measured
 * <b>376px, 0px, 0px</b>: pane right, widget right, everything inside it gone. It looked like a culling
 * bug, then like a resize bug, and was neither. Dragging the divider brought the graph back, because that
 * writes real weights and forces a layout against definite space.</p>
 *
 * <h3>Why these go through a real {@link DockArea}</h3>
 *
 * <p>Because a bare {@code SplitView} with a declared size does <b>not</b> reproduce it — written that way
 * first, every assertion here passed with the fix reverted. The pane's cross size is only indeterminate
 * when the split's own is, and in the dock it is: the split fills a group that fills a pane that grew
 * inside a tab view, none of which declares a height. That whole stack is the fixture, so the fixture is
 * the dock.</p>
 *
 * <p>Recorded because the shortcut is the obvious thing to reach for, and what it produces is green
 * tests that assert nothing.</p>
 */
public class SplitPaneGrowChainTest extends UiDocumentTestBase {


    /** outer → middle → leaf, each filling both axes the only two ways there are. */
    private static UINode growChain(UINode leaf) {
        UINode outer = fill(new UINode());
        UINode middle = fill(new UINode());
        outer.append(middle);
        middle.append(fill(leaf));
        return outer;
    }

    private static UINode fill(UINode element) {
        return element.layout(l -> l.widthPercent(100f).height(0).flexGrow(1f));
    }

    /**
     * A dock holding {@code content} plus a second panel — so there is a {@link SplitView} above it.
     *
     * @param zone which way the second panel splits off, i.e. which of the pane's axes ends up stretched
     */
    private DockArea dockWithTwoPanels(UINode content, DockDropZone zone) {
        DockPanelRegistry<UINode> registry = new DockPanelRegistry<>();
        registry.register(DockPanelDescriptor.document("subject", "Subject"), ref -> content);
        registry.register(DockPanelDescriptor.document("other", "Other"), ref -> new UINode());

        DockLeaf centre = new DockLeaf(new DockPanelRef("subject"));
        centre.setCentral(true);
        DockLayout layout = DockLayout.of(centre);
        layout.drop(centre, zone, new DockLeaf(new DockPanelRef("other")));

        DockArea dock = new DockArea(registry, layout);
        UINode root = new UINode().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.append(dock);

        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        for (int i = 0; i < 5; i++) frame();
        return dock;
    }

    private static float height(UINode element) {
        return element.box().height();
    }

    private static float width(UINode element) {
        return element.box().width();
    }

    /**
     * Panels side by side — the split is horizontal, so the pane's HEIGHT is the stretched axis and
     * {@code height: 0; flex-grow: 1} is what collapses. This is the arrangement that shipped broken.
     */
    @Test
    public void aGrowChainFillsAPaneOfAHorizontalSplit() {
        UINode leaf = new UINode();
        UINode outer = growChain(leaf);
        dockWithTwoPanels(outer, DockDropZone.SPLIT_RIGHT);

        assertTrue("the first box did not fill the pane -- the fixture is wrong", height(outer) > 0f);
        assertEquals("the chain collapsed below the first level under a split pane",
                height(outer), height(leaf), 0.5f);
    }

    // A vertical split has NO equivalent failure and there is deliberately no test for one: the pane's
    // stretched axis there is the WIDTH, and the dock gives every split a definite width from the top
    // down, so nothing below it is ever resolving against an unknown. Written anyway at first, it passed
    // with the fix reverted -- a green test asserting nothing, which is worse than the gap it covers.

    /**
     * <b>The weights still decide the split.</b>
     *
     * <p>The fix writes a size onto the pane, and writing it onto the <em>main</em> axis by mistake would
     * fight {@code flex-basis: 0} and stop the weights meaning anything — while both tests above still
     * passed, because the cross axis would be perfectly correct.</p>
     */
    @Test
    public void theWeightsStillDecideTheMainAxis() {
        SplitView split = new SplitView();
        split.setLimits(0f, 100f);
        split.setPercentage(25f);
        split.layout(l -> l.width(400).height(400));

        UINode root = new UINode().layout(l -> l.width(600).height(600)
                .flexDirection(FlexDirection.COLUMN));
        root.append(split);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        for (int i = 0; i < 4; i++) frame();

        float first = width(split.first());
        float second = width(split.second());
        assertTrue("the panes have no width at all", first > 0f && second > 0f);
        assertEquals("a 25% split did not divide the width 1:3", first * 3f, second, 4f);
    }

    /**
     * A split with no height of its own still shows its content.
     *
     * <p>The regression risk of writing an explicit {@code height: 100%}: a percentage against an
     * indeterminate parent must fall back to auto — and therefore to stretch — rather than to zero, or a
     * content-sized split becomes invisible instead of merely undersized.</p>
     */
    @Test
    public void aSplitWithNoHeightOfItsOwnStillShowsItsContent() {
        UINode tall = new UINode().layout(l -> l.width(50).height(120));
        SplitView split = new SplitView();
        split.first().append(tall);
        split.layout(l -> l.width(400).heightAuto());

        UINode root = new UINode().layout(l -> l.width(600).heightAuto()
                .flexDirection(FlexDirection.COLUMN));
        root.append(split);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        for (int i = 0; i < 4; i++) frame();

        assertTrue("a content-sized split collapsed its panes to nothing",
                height(split.first()) > 0f);
    }
}
