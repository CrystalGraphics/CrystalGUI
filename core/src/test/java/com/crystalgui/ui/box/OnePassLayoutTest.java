package com.crystalgui.ui.box;

import static com.crystalgui.ui.box.BoxFixtures.box;
import static com.crystalgui.ui.box.BoxFixtures.layout;
import static com.crystalgui.ui.box.BoxFixtures.sized;
import static org.junit.Assert.assertEquals;

import com.crystalgui.style.LayoutGroup;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.dom.Document;
import com.crystalgui.ui.dom.Node;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.TaffyPosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.Test;

/**
 * The same tree, described once, laid out by both engines — identical geometry, and on the new
 * one {@code computeLayout} runs exactly once (5.3's acceptance).
 *
 * <p>Every property whose DEFAULT the two engines disagree on (D5.8: flex-direction, flex-shrink,
 * min-size, align-content) is stated explicitly on every node, so what is compared is the layout
 * engine's arithmetic and not its defaults. The defaults themselves are asserted separately, on the
 * new engine alone, because that is the behaviour that changed.</p>
 */
public class OnePassLayoutTest extends com.crystalgui.testsupport.UiTestBase {

    /** A tree described once. */
    private static final class Spec {
        final Consumer<LayoutGroup> style;
        final List<Spec> children;

        Spec(Consumer<LayoutGroup> style, Spec... children) {
            this.style = style;
            this.children = Arrays.asList(children);
        }
    }

    private static Spec spec(Consumer<LayoutGroup> style, Spec... children) {
        return new Spec(explicit(style), children);
    }

    private static Consumer<LayoutGroup> explicit(Consumer<LayoutGroup> more) {
        return l -> {
            l.flexDirection(FlexDirection.COLUMN);
            l.flexShrink(0f);
            l.minWidth(0f);
            l.minHeight(0f);
            l.alignContent(AlignContent.FLEX_START);
            more.accept(l);
        };
    }

    private record Geometry(float x, float y, float width, float height) {
    }

    // ── Both engines ─────────────────────────────────────────────────────────

    private static List<Geometry> oldEngine(Spec spec) {
        UIElement root = buildOld(spec);
        UIWindow window = new UIWindow(Ui.of(root));
        window.init(800, 600);
        window.updateWithoutPainting();
        List<Geometry> out = new ArrayList<>();
        collectOld(root, out);
        return out;
    }

    private static UIElement buildOld(Spec spec) {
        UIElement element = new UIElement();
        StyleGroup.inlinePipeline(element.getStyle().getLayoutGroup(), spec.style);
        for (Spec child : spec.children) element.addChild(buildOld(child));
        return element;
    }

    private static void collectOld(UIElement element, List<Geometry> into) {
        UIElement.RuntimeCache c = element.getRuntimeCache();
        into.add(new Geometry(c.getX(), c.getY(), c.getWidth(), c.getHeight()));
        for (UIElement child : element.getChildren()) collectOld(child, into);
    }

    private static Document newEngine(Spec spec, Node[] rootOut) {
        Document document = new Document();
        Node root = buildNew(spec);
        document.append(root);
        document.update(800, 600);
        rootOut[0] = root;
        return document;
    }

    private static Node buildNew(Spec spec) {
        Node node = new Node();
        layout(node, spec.style);
        for (Spec child : spec.children) node.append(buildNew(child));
        return node;
    }

    private static void collectNew(Node node, List<Geometry> into) {
        Box b = box(node);
        into.add(new Geometry(b.worldX(), b.worldY(), b.width(), b.height()));
        for (Node child : node.children()) collectNew(child, into);
    }

    private static void assertSameLayout(Spec spec) {
        List<Geometry> old = oldEngine(spec);
        Node[] root = new Node[1];
        Document document = newEngine(spec, root);
        List<Geometry> fresh = new ArrayList<>();
        collectNew(root[0], fresh);
        assertEquals("same number of boxes", old.size(), fresh.size());
        // Positions relative to the spec's root: the old window centres its root and scales it, and
        // neither is the layout engine's arithmetic, which is what is being compared.
        for (int i = 0; i < old.size(); i++) {
            Geometry o = old.get(i), n = fresh.get(i);
            assertEquals("x of box " + i, o.x - old.get(0).x, n.x - fresh.get(0).x, 0.01f);
            assertEquals("y of box " + i, o.y - old.get(0).y, n.y - fresh.get(0).y, 0.01f);
            assertEquals("width of box " + i, o.width, n.width, 0.01f);
            assertEquals("height of box " + i, o.height, n.height, 0.01f);
        }
        assertEquals("one pass", 1, document.boxes().layoutPasses());
    }

    private static Consumer<LayoutGroup> size(float w, float h) {
        return l -> {
            l.width(w);
            l.height(h);
        };
    }

    @Test
    public void aColumnWithAGap() {
        assertSameLayout(spec(l -> {
            size(800, 600).accept(l);
            l.gapAll(10);
        }, spec(size(100, 50)), spec(size(120, 60)), spec(size(80, 40))));
    }

    @Test
    public void aRowThatGrows() {
        assertSameLayout(spec(l -> {
            size(800, 100).accept(l);
            l.flexDirection(FlexDirection.ROW);
        }, spec(l -> {
            l.flexGrow(1);
            l.flexBasis(0);
            l.height(100);
        }), spec(l -> {
            l.flexGrow(2);
            l.flexBasis(0);
            l.height(100);
        }), spec(size(100, 100))));
    }

    @Test
    public void paddingWithARelativeAndAnAbsoluteChild() {
        assertSameLayout(spec(l -> {
            size(400, 300).accept(l);
            l.paddingAll(20);
        }, spec(size(100, 40)), spec(l -> {
            l.positionType(TaffyPosition.ABSOLUTE);
            l.left(5);
            l.top(7);
            size(30, 30).accept(l);
        }, spec(size(10, 10)))));
    }

    @Test
    public void aWrappingRow() {
        assertSameLayout(spec(l -> {
            size(800, 600).accept(l);
            l.flexDirection(FlexDirection.ROW);
            l.flexWrap(FlexWrap.WRAP);
        }, spec(size(300, 50)), spec(size(300, 50)), spec(size(300, 50)), spec(size(300, 50)), spec(size(300, 50))));
    }

    @Test
    public void centredChildrenInANestedColumn() {
        assertSameLayout(spec(l -> {
            size(600, 400).accept(l);
            l.alignItems(AlignItems.CENTER);
        }, spec(l -> {
            size(300, 200).accept(l);
            l.alignItems(AlignItems.CENTER);
        }, spec(size(100, 50)), spec(size(50, 25)))));
    }

    // ── One pass ─────────────────────────────────────────────────────────────

    @Test
    public void anUnchangedTreeIsNotLaidOutAgainAndAChangeCostsOnePass() {
        Document document = new Document();
        Node a = sized(100, 100);
        Node b = sized(100, 100);
        document.append(a).append(b);
        document.update(800, 600);
        assertEquals(1, document.boxes().layoutPasses());
        assertEquals(1, document.boxes().syncPasses());

        document.update(800, 600);
        assertEquals("nothing moved, nothing computed", 1, document.boxes().layoutPasses());
        assertEquals("and nothing walked", 1, document.boxes().syncPasses());

        layout(a, l -> l.width(200));
        document.update(800, 600);
        assertEquals("a style change is one more pass", 2, document.boxes().layoutPasses());
        assertEquals("and no walk -- the structure did not move", 1, document.boxes().syncPasses());
        assertEquals(200f, box(a).width(), 0.001f);

        document.append(sized(10, 10));
        document.update(800, 600);
        assertEquals("an insert is a walk and a pass", 2, document.boxes().syncPasses());
        assertEquals(3, document.boxes().layoutPasses());
    }

    // ── CSS defaults (D5.8) ──────────────────────────────────────────────────

    @Test
    public void theDefaultsAreCssDefaults() {
        Document document = new Document();
        Node row = sized(800, 100);
        Node first = sized(100, 100);
        Node second = sized(100, 100);
        row.append(first).append(second);
        document.append(row);
        document.update(800, 600);
        assertEquals("flex-direction: row -- the second child sits beside the first", 100f, box(second).x(), 0.001f);
        assertEquals(0f, box(second).y(), 0.001f);

        Node tooWide = sized(1000, 50);
        Node container = sized(800, 100);
        container.append(tooWide);
        document.append(container);
        document.update(800, 600);
        assertEquals("flex-shrink: 1 and min-width: auto -- an oversized item shrinks to fit", 800f, box(tooWide).width(), 0.001f);
    }
}
