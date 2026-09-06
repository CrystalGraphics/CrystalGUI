package com.crystalgui.ui.dom;

import com.crystalgui.widget.control.Slider;
import com.crystalgui.testsupport.UiDocumentTestBase;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

/**
 * The DOM-shaped tree query API on {@link UIElement} and {@link UIDocument}.
 *
 * <p>These delegate to the same {@code Selector} the stylesheet cascade uses — there is deliberately
 * no second matcher — so anything the cascade can select, a query can find.</p>
 */
public class TreeQueryTest extends UiDocumentTestBase {

    private UIElement root, panel, a, b, deep;

    @Before
    public void setUp() {
        //  root(.app)
        //  └── panel(.panel #main)
        //      ├── a(.item)
        //      ├── b(.item)
        //      └── deep(.nested) > (.item, id=leaf)
        root = new UIElement();
        root.addClass("app");

        panel = new UIElement();
        panel.addClass("panel").setId("main");
        root.append(panel);

        a = new UIElement();
        a.addClass("item");
        panel.append(a);

        b = new UIElement();
        b.addClass("item");
        panel.append(b);

        deep = new UIElement();
        deep.addClass("nested");
        panel.append(deep);

        UIElement leaf = new UIElement();
        leaf.addClass("item").setId("leaf");
        deep.append(leaf);

        document.append(root);
    }

    @Test
    public void findsByClass() {
        assertSame(a, deepOrNull(root, ".item"));
    }

    @Test
    public void findsById() {
        assertSame(panel, document.getElementById("main"));
        assertNull(document.getElementById("nope"));
    }

    @Test
    public void findsByTypeSelector() {
        Slider slider = new Slider();
        panel.append(slider);
        assertSame(slider, deepOrNull(document, "slider"));
    }

    /** Descendant combinators work, since Selector.matches walks the real parent chain. */
    @Test
    public void findsByDescendantCombinator() {
        assertSame("expected the .item nested under .nested",
                deep.children().get(0), deepOrNull(root, ".nested .item"));
    }

    @Test
    public void returnsNullWhenNothingMatches() {
        assertNull(deepOrNull(root, ".does-not-exist"));
        assertTrue(deepAll(root, ".does-not-exist").isEmpty());
    }

    /** Depth-first pre-order, matching the DOM. */
    @Test
    public void resultsAreInDocumentOrder() {
        List<UIElement> items = deepAll(root, ".item");
        assertEquals(3, items.size());
        assertSame(a, items.get(0));
        assertSame(b, items.get(1));
        assertSame(deep.children().get(0), items.get(2));
    }

    @Test
    public void getElementsByClassNameMatchesQuerySelectorAll() {
        // The two APIs, not two spellings of one selector. The codemod rewrote both sides into
        // `deepAll` and turned the second into a TAG query for `item`, which matches nothing and made
        // the assertion compare a class selector against an empty list.
        assertEquals(root.querySelectorAll(".item"), root.getElementsByClassName("item"));
    }

    // ── Scoping ─────────────────────────────────────────────────────────────

    /** Element-level queries exclude the element itself, like {@code Element.querySelector}. */
    @Test
    public void elementQueryExcludesItself() {
        assertNull("panel matched itself", deepOrNull(panel, ".panel"));
        assertSame(panel, panel.parent().querySelector(".panel"));
    }

    /** Window-level queries include the root, since it plays the part of the document. */
    @Test
    public void windowQueryIncludesTheRoot() {
        assertSame(root, deepOrNull(document, ".app"));
    }

    @Test
    public void elementQueryIsScopedToItsOwnSubtree() {
        assertNull("found an element outside the scope", deepOrNull(deep, ".panel"));
        assertNotNull(deepOrNull(deep, ".item"));
    }

    /**
     * <b>A widget's parts are NOT reached by an ordinary query, and that is the feature.</b>
     *
     * <p>This asserted the opposite, and was right about the old engine: internal children were
     * ordinary children with a flag, so {@code querySelector} walked straight into them and the test
     * pinned the position -- {@code slider.children().get(1)} was the thumb. A slider's parts live in
     * a shadow tree now, so {@code children()} is EMPTY and the query stops at the boundary, which is
     * what encapsulation means. Both halves are asserted, because a deep query that found nothing
     * would satisfy the first alone.</p>
     */
    @Test
    public void anOrdinaryQueryStopsAtAWidgetsShadowBoundary() {
        Slider slider = new Slider();
        panel.append(slider);

        assertNull("a part is not reachable from outside the tree that owns it",
                slider.querySelector("." + Slider.THUMB_PART));
        assertNotNull("...and the thumb is really there, one boundary down",
                deepOrNull(slider, "." + Slider.THUMB_PART));
    }

    /** Repeated queries must be stable — the parsed-selector cache must not be corrupted by reuse. */
    @Test
    public void repeatedQueriesAreStable() {
        assertSame(deepOrNull(root, ".item"), deepOrNull(root, ".item"));
        assertEquals(3, deepAll(root, ".item").size());
        assertEquals(3, deepAll(root, ".item").size());
    }
}
