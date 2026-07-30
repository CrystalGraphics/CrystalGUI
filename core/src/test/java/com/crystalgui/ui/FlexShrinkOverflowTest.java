package com.crystalgui.ui;

import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.TabView;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * The consequence of this engine's most surprising Taffy divergence: <b>{@code flex-shrink} defaults to
 * {@code 0}</b>, not to CSS's {@code 1}.
 *
 * <p>AGENTS.md records the divergence itself. What it did not record — and what cost a real debugging
 * session on the harness gallery — is what the divergence <em>looks like</em> when it bites: a
 * {@code flex-grow: 1} child whose content is taller than the space available does not shrink and does not
 * scroll. It <b>overflows its parent</b>, and anything inside it that stretches to its height overflows
 * with it. In the gallery that surfaced as a dark panes background spilling past the frame that was
 * supposed to contain it, visible only at small window sizes.</p>
 *
 * <p>The fix is the classic flexbox one — {@code height: 0} with {@code flex-grow: 1}, so the item's basis
 * is zero and it grows into exactly what is left. Both halves are asserted below, because a test that only
 * pinned the fix would not explain why the fix is needed.</p>
 */
public class FlexShrinkOverflowTest extends UiTestBase {

    /** A column parent 100 tall, holding one grow-child whose own content wants 300. */
    private UIElement build(boolean withZeroBasis) {
        UIElement root = new UIElement().layout(l -> l.width(100).height(100));
        UIElement filler = new UIElement().layout(l -> {
            l.flexGrow(1);
            if (withZeroBasis) l.height(0);
        });
        root.addChild(filler);
        for (int i = 0; i < 6; i++) {
            filler.addChild(new UIElement().layout(l -> l.width(20).height(50)));
        }
        UIWindow window = new UIWindow(Ui.of(root));
        window.init(200, 200); // uiScale 2 -> logical 100x100
        for (int i = 0; i < 5; i++) {
            window.getStyleEngine().calculateStyle(0.016f);
            window.calculateLayout();
        }
        return filler;
    }

    /**
     * <b>The trap.</b> Without a zero basis the child keeps its content height and runs past its parent —
     * no shrink, because the default is 0, and no clip, because overflow is visible by default.
     */
    @Test
    public void aGrowChildWithOversizedContentOverflowsItsParent() {
        UIElement filler = build(false);
        assertTrue("300 of content in a 100 box, and flex-shrink: 0 means it does not give way",
                filler.getRuntimeCache().getHeight() > 100f);
    }

    /** And the fix: basis zero, then grow into what is actually left. */
    @Test
    public void aZeroBasisContainsIt() {
        UIElement filler = build(true);
        assertEquals("grows to exactly the space available, never past it",
                100f, filler.getRuntimeCache().getHeight(), 0.5f);
    }

    // ── The instance of this that shipped: TabView's panes container ─────────

    private UIElement panesOf(TabView tabs) {
        for (UIElement child : tabs.getChildren()) {
            if (child.hasClass("__panes__")) return child;
        }
        throw new AssertionError("no __panes__");
    }

    private TabView buildTabs(TabView.TabSide side, int w, int h, int contentW, int contentH) {
        UIElement root = new UIElement().layout(l -> l.width(w).height(h));
        TabView tabs = new TabView();
        tabs.setTabSide(side);
        tabs.layout(l -> l.width(w).height(h));
        root.addChild(tabs);
        for (int i = 0; i < 6; i++) {
            tabs.addTab("t" + i).content()
                    .addChild(new UIElement().layout(l -> l.width(contentW).height(contentH)));
        }
        UIWindow window = new UIWindow(Ui.of(root));
        window.init(w * 2, h * 2);
        for (int i = 0; i < 8; i++) {
            window.getStyleEngine().calculateStyle(0.016f);
            window.calculateLayout();
        }
        return tabs;
    }

    /**
     * <b>A {@code TabView} narrower than its widest page must not overflow itself.</b>
     *
     * <p>{@code __panes__} has {@code flex-grow: 1} so it fills the space beside the strip — but with an
     * {@code auto} basis its basis <em>is</em> its content width, and {@code flex-shrink: 0} means it can
     * then only ever grow. A page wider than the view pushed the whole container past its own parent, and
     * because the panes background stretches with it, the overhang was visible as a pane spilling out of
     * the frame drawn around it. Only at small window sizes, because larger ones had room to spare —
     * which is what made it survive several rounds of looking straight at it.</p>
     */
    @Test
    public void aSideStripTabViewDoesNotOverflowOnTheWidthAxis() {
        TabView tabs = buildTabs(TabView.TabSide.LEFT, 200, 150, 400, 40);
        UIElement panes = panesOf(tabs);
        float right = panes.getRuntimeCache().getX() + panes.getRuntimeCache().getWidth();
        assertTrue("panes ends at " + right + ", past the TabView's own 200", right <= 200.5f);
    }

    /** The same on the other axis, since the basis has to follow whichever way the tabs stack. A leftover
     * {@code width: 0} from a previous side would collapse the panes entirely, so both are written. */
    @Test
    public void aTopStripTabViewDoesNotOverflowOnTheHeightAxis() {
        TabView tabs = buildTabs(TabView.TabSide.TOP, 200, 120, 40, 400);
        UIElement panes = panesOf(tabs);
        float bottom = panes.getRuntimeCache().getY() + panes.getRuntimeCache().getHeight();
        assertTrue("panes ends at " + bottom + ", past the TabView's own 120", bottom <= 120.5f);
        assertTrue("and is not collapsed to nothing by a stale cross-axis basis",
                panes.getRuntimeCache().getWidth() > 0f);
    }
}
