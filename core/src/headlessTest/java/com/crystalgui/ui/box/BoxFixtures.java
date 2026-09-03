package com.crystalgui.ui.box;

import static org.junit.Assert.assertNotNull;

import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** Nodes with a size and a place, written the way an author writes them: inline, at INLINE origin. */
final class BoxFixtures {

    private BoxFixtures() {
    }

    static UIElement sized(float width, float height) {
        UIElement node = new UIElement();
        layout(node, l -> {
            l.width(width);
            l.height(height);
        });
        return node;
    }

    static UIElement absolute(float x, float y, float width, float height) {
        UIElement node = sized(width, height);
        layout(node, l -> {
            l.positionType(TaffyPosition.ABSOLUTE);
            l.left(x);
            l.top(y);
        });
        return node;
    }

    static void layout(UIElement node, Consumer<LayoutGroup> style) {
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), style);
    }

    static void general(UIElement node, Consumer<GeneralGroup> style) {
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), style);
    }

    static Box box(UIElement node) {
        Box box = node.box();
        assertNotNull("<" + node + "> has no box", box);
        return box;
    }

    /** The node under a world point, or null over nothing. */
    static @Nullable UIElement hit(UIDocument document, float x, float y) {
        Box box = document.boxes().hitTest(x, y);
        return box == null ? null : box.node();
    }
}
