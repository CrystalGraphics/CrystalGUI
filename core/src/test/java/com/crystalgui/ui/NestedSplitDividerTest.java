package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.SplitView;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * <b>A split nested inside a split still has a grabbable divider.</b>
 *
 * <p>Both sheets styled the divider with a <em>descendant</em> selector — {@code splitview.__vertical__
 * .__divider__} — and splits nest. So a horizontal split inside a vertical one matched the vertical rule,
 * took {@code width: auto}, and resolved to <b>zero width</b> in a row: an invisible divider between two
 * panes that could never be resized again. Measured at {@code 0.0 x 4.0} where {@code 4.0 x 348} was
 * wanted.</p>
 *
 * <p>It survived until something nested two splits, which the dock does routinely — a horizontal work
 * area inside a vertical column. The fix is the child combinator, and this asserts the geometry rather
 * than the selector so it holds however the sheets are rewritten.</p>
 */
public class NestedSplitDividerTest extends UiTestBase {

    /** Builds outer(vertical) > inner(horizontal), and returns every split in the tree. */
    private List<SplitView> buildNested(boolean withTheme) {
        SplitView inner = new SplitView();
        inner.setOrientation(SplitView.Orientation.HORIZONTAL);
        inner.first().addChild(new UIElement());
        inner.second().addChild(new UIElement());

        SplitView outer = new SplitView();
        outer.setOrientation(SplitView.Orientation.VERTICAL);
        outer.first().addChild(inner);
        outer.second().addChild(new UIElement());

        UIElement root = new UIElement().layout(l -> l.width(800).height(600)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(outer);
        UIWindow window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        // The theme repeats every divider rule, so a fix in only one sheet leaves the bug for any host
        // that loads a theme -- which is all of them.
        if (withTheme) window.getStyleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:ore"));
        window.init(1600, 1200);
        for (int i = 0; i < 5; i++) window.updateWithoutPainting();

        List<SplitView> splits = new ArrayList<>();
        collect(root, splits);
        return splits;
    }

    private static void collect(UIElement element, List<SplitView> out) {
        if (element instanceof SplitView split) out.add(split);
        for (UIElement child : element.getChildren()) collect(child, out);
    }

    private static UIElement dividerOf(SplitView split) {
        for (UIElement child : split.getChildren()) {
            if (child.hasClass(SplitView.DIVIDER_CLASS)) return child;
        }
        return null;
    }

    private void assertEveryDividerIsGrabbable(boolean withTheme) {
        for (SplitView split : buildNested(withTheme)) {
            UIElement divider = dividerOf(split);
            assertNotNull("a split with no divider at all", divider);
            var box = divider.getRuntimeCache();
            // BOTH axes. The failure was a divider with the right thickness on one axis and zero on the
            // other, which a "has some size" assertion would have called healthy.
            assertTrue("divider is " + box.getWidth() + " x " + box.getHeight()
                            + " — a zero axis cannot be seen or grabbed"
                            + (withTheme ? " (with the ore theme)" : ""),
                    box.getWidth() > 0f && box.getHeight() > 0f);
            assertTrue("a divider that is not hit-testable cannot start a drag", divider.isHitTest());
        }
    }

    @Test
    public void nestedDividersAreGrabbableWithTheUserAgentSheetAlone() {
        assertEveryDividerIsGrabbable(false);
    }

    @Test
    public void nestedDividersAreGrabbableWithTheOreTheme() {
        assertEveryDividerIsGrabbable(true);
    }
}
