package com.crystalgui.ui.box;

import static org.junit.Assert.assertNotNull;

import com.crystalgui.style.GeneralGroup;
import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UINode;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/** Nodes with a size and a place, written the way an author writes them: inline, at INLINE origin. */
final class BoxFixtures {

    private BoxFixtures() {
    }

    static UINode sized(float width, float height) {
        UINode node = new UINode();
        layout(node, l -> {
            l.width(width);
            l.height(height);
        });
        return node;
    }

    static UINode absolute(float x, float y, float width, float height) {
        UINode node = sized(width, height);
        layout(node, l -> {
            l.positionType(TaffyPosition.ABSOLUTE);
            l.left(x);
            l.top(y);
        });
        return node;
    }

    static void layout(UINode node, Consumer<LayoutGroup> style) {
        StyleGroup.inlinePipeline(node.getStyle().getLayoutGroup(), style);
    }

    static void general(UINode node, Consumer<GeneralGroup> style) {
        StyleGroup.inlinePipeline(node.getStyle().getGeneralGroup(), style);
    }

    static Box box(UINode node) {
        Box box = node.box();
        assertNotNull("<" + node + "> has no box", box);
        return box;
    }

    /** The node under a world point, or null over nothing. */
    static @Nullable UINode hit(UIDocument document, float x, float y) {
        Box box = document.boxes().hitTest(x, y);
        return box == null ? null : box.node();
    }
}
