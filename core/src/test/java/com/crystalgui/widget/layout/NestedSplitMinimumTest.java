package com.crystalgui.widget.layout;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;

import dev.vfyjxf.taffy.style.FlexDirection;

/**
 * <b>A divider stops where a nested split's panes would go below their floors</b> — the split's floor is theirs
 * added up, as VS Code's grid adds its views'. Before, only a pane's own content was asked, and a split states no
 * minimum, so a split editor could be dragged to a sliver while each group in it declared one.
 */
public class NestedSplitMinimumTest extends UiDocumentTestBase {

    @Test
    public void aNestedSplitKeepsItsPanesFloors() {
        SplitView inner = new SplitView();
        inner.first().append(floored());
        inner.second().append(floored());
        SplitView outer = new SplitView().setLimits(0f, 100f);
        outer.first().append(floored());
        outer.second().append(inner);
        UIElement root = new UIElement().layout(l -> l.width(800).height(300).flexDirection(FlexDirection.COLUMN));
        root.append(outer);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        for (int i = 0; i < 4; i++) frame();

        assertTrue("the nested split's floor is its two panes' " + inner.minimumSize(false),
                inner.minimumSize(false) >= 400f);

        int[] grip = centreOf(outer.divider());
        mouse(grip[0], grip[1], -1, false);
        frame();
        mouse(grip[0], grip[1], 0, true);
        mouse(grip[0] + 10_000, grip[1], -1, false);
        frame();
        mouse(grip[0] + 10_000, grip[1], 0, false);
        for (int i = 0; i < 4; i++) frame();

        assertTrue("the nested split was dragged to " + inner.box().width() + "px, under its panes' 400",
                inner.box().width() >= 400f - 0.5f);
    }

    private static UIElement floored() {
        return new UIElement().layout(l -> l.minWidth(200f).flexGrow(1f));
    }

    private void mouse(int x, int y, int button, boolean down) {
        document.input().consumeMouseEvent(new CgSystemInput.Mouse.Event(x, y, 0, 0, button, down, 0f,
                button < 0 ? -1L : System.currentTimeMillis()));
    }
}
