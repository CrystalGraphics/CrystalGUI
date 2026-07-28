package com.crystalgui.ui;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.core.input.CgUiInputAdapter;
import com.crystalgui.ui.elements.Slider;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * The DOM-shaped tree query API on {@link UIElement} and {@link UIWindow}.
 *
 * <p>These delegate to the same {@code Selector} the stylesheet cascade uses — there is deliberately
 * no second matcher — so anything the cascade can select, a query can find.</p>
 */
public class TreeQueryTest {

    private UIWindow window;
    private UIElement root, panel, a, b, deep;

    @Before
    public void setUp() {
        CrystalGuiCore.setAdapter(new CgUiInputAdapter() {
            @Override public int getCurrentModifiers() { return 0; }
            @Override public int translateKeyboardCodes(int platformCode) { return platformCode; }
            @Override public boolean isKeyDown(int localKeyCode) { return false; }
            @Override public boolean isMouseDown(int localMouseCode) { return false; }
            @Override public int howManyMouseButtons() { return 3; }
        });

        //  root(.app)
        //  └── panel(.panel #main)
        //      ├── a(.item)
        //      ├── b(.item)
        //      └── deep(.nested) > (.item, id=leaf)
        root = new UIElement();
        root.addClass("app");

        panel = new UIElement();
        panel.addClass("panel").setId("main");
        root.addChild(panel);

        a = new UIElement();
        a.addClass("item");
        panel.addChild(a);

        b = new UIElement();
        b.addClass("item");
        panel.addChild(b);

        deep = new UIElement();
        deep.addClass("nested");
        panel.addChild(deep);

        UIElement leaf = new UIElement();
        leaf.addClass("item").setId("leaf");
        deep.addChild(leaf);

        window = new UIWindow(Ui.of(root));
        window.init(800, 600);
    }

    @Test
    public void findsByClass() {
        assertSame(a, root.querySelector(".item"));
    }

    @Test
    public void findsById() {
        assertSame(panel, window.getElementById("main"));
        assertNull(window.getElementById("nope"));
    }

    @Test
    public void findsByTypeSelector() {
        Slider slider = new Slider();
        panel.addChild(slider);
        assertSame(slider, window.querySelector("slider"));
    }

    /** Descendant combinators work, since Selector.matches walks the real parent chain. */
    @Test
    public void findsByDescendantCombinator() {
        assertSame("expected the .item nested under .nested",
                deep.getChildren().get(0), root.querySelector(".nested .item"));
    }

    @Test
    public void returnsNullWhenNothingMatches() {
        assertNull(root.querySelector(".does-not-exist"));
        assertTrue(root.querySelectorAll(".does-not-exist").isEmpty());
    }

    /** Depth-first pre-order, matching the DOM. */
    @Test
    public void resultsAreInDocumentOrder() {
        List<UIElement> items = root.querySelectorAll(".item");
        assertEquals(3, items.size());
        assertSame(a, items.get(0));
        assertSame(b, items.get(1));
        assertSame(deep.getChildren().get(0), items.get(2));
    }

    @Test
    public void getElementsByClassNameMatchesQuerySelectorAll() {
        assertEquals(root.querySelectorAll(".item"), root.getElementsByClassName("item"));
    }

    // ── Scoping ─────────────────────────────────────────────────────────────

    /** Element-level queries exclude the element itself, like {@code Element.querySelector}. */
    @Test
    public void elementQueryExcludesItself() {
        assertNull("panel matched itself", panel.querySelector(".panel"));
        assertSame(panel, panel.getParent().querySelector(".panel"));
    }

    /** Window-level queries include the root, since it plays the part of the document. */
    @Test
    public void windowQueryIncludesTheRoot() {
        assertSame(root, window.querySelector(".app"));
    }

    @Test
    public void elementQueryIsScopedToItsOwnSubtree() {
        assertNull("found an element outside the scope", deep.querySelector(".panel"));
        assertNotNull(deep.querySelector(".item"));
    }

    /** A widget's internal children are ordinary elements, so queries reach them — which is the point
     * of having this API at all: tests and callers stop hand-rolling child walks. */
    @Test
    public void reachesWidgetInternalChildren() {
        Slider slider = new Slider();
        panel.addChild(slider);
        assertSame(slider.getChildren().get(1), slider.querySelector("." + Slider.THUMB_CLASS));
    }

    /** Repeated queries must be stable — the parsed-selector cache must not be corrupted by reuse. */
    @Test
    public void repeatedQueriesAreStable() {
        assertSame(root.querySelector(".item"), root.querySelector(".item"));
        assertEquals(3, root.querySelectorAll(".item").size());
        assertEquals(3, root.querySelectorAll(".item").size());
    }
}
