package com.crystalgui.widget.graph;

import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.control.Button;
import com.crystalgui.graph.NodeField;
import com.crystalgui.app.shadergraph.node.ShaderColorFieldWidget;
import com.crystalgui.app.shadergraph.node.ShaderVectorFieldWidget;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.widget.graph.node.NodeFieldWidgets;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * The assembly line's guard rail: <b>every control kind, measured, in one pass.</b>
 *
 * <h3>What this exists to stop</h3>
 * <p>The node library is ~170 nodes drawn from about a dozen widget kinds. If a kind is a pixel wrong,
 * it is wrong in every node that uses it — and it is wrong in a way that costs a review round per node
 * to find, because nothing fails. This class measures the kit instead of trusting it, so the review a
 * human does is of the KIT, once, rather than of each node that happens to contain one.</p>
 *
 * <h3>Two failures it is aimed at, both of which have already happened once</h3>
 * <ul>
 *   <li><b>The leak.</b> {@code graphnode .__control-row__ textfield { width: 0; flex-grow: 1 }} reached
 *       into a promoted colour picker, because promotion moves an element's Taffy parent and transform
 *       but never its DOM parent. The rules tied on specificity, so the WIDTH was won on source order
 *       and the sheet read correctly — while the grow factor went uncontested and the picker's colour
 *       tracks quietly lost their slack to its value fields. Nothing failed; only the picture was
 *       wrong.</li>
 *   <li><b>The set that is not a set.</b> A text field is 16px, a dropdown 14 and a checkbox 12, each
 *       right in a form. Three of them in one node used to sit at three heights.</li>
 * </ul>
 *
 * <h3>Adding a control kind</h3>
 * <p>Register it in {@link NodeFieldWidgets} and it appears here automatically — {@link #everyKind()}
 * enumerates {@link NodeField.Kind}, so a kind with no widget is a named failure rather than a silent
 * gap. Then add its tag to the kit selector in {@code default.css}.</p>
 */
public class NodeControlKitTest extends UiDocumentTestBase {

    /** From the `--graph-ctrl-h` token. Restated because a test that reads its own expectation out of
     * the sheet asserts only that the sheet is self-consistent, which it always is. */
    // --graph-ctrl-h: 14px was sized for the control kit's old 6px text; it became 18px alongside
    // --graph-font going from 6 to 9 (matching the title), so the same box wouldn't crowd the bigger
    // text against its own chevron/mark.
    private static final float CTRL_H = 18f;
    private static final float ROW_H = 25f;

    private UIElement root;

    private void openWindow() {
        root = new UIElement();
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
        document.styleEngine().addStylesheet(StyleSheetRegistry.of("crystalgui:graph"));
        // Since P6.1.8 step 7: COLOR and VECTOR have no domain-agnostic default in NodeFieldWidgets —
        // the GLSL literal parsing is genuinely the shader domain's, so it registers its own codec
        // (see NodeFieldWidgets' class javadoc). This test's own `fieldFor` already assumes GLSL
        // literals (`vec2(...)`, `vec4(...)`), so installing here is what makes it test the real,
        // end-to-end shader-graph experience rather than a widget that no longer exists by default.
        // Idempotent, so re-installing on every call is harmless.
        ShaderColorFieldWidget.install();
        ShaderVectorFieldWidget.install();
    }

    /** Every kind, with a value each one will actually accept. */
    private static NodeField fieldFor(NodeField.Kind kind) {
        switch (kind) {
            case ENUM:
                return new NodeField("f", "Mode", kind, List.of("One", "Two", "Three"), "One", null);
            case BOOLEAN:
                return new NodeField("f", "On", kind, List.of(), "false", null);
            case NUMBER:
                return new NodeField("f", "Value", kind, List.of(), "0.5", null);
            case VECTOR:
                return new NodeField("f", "Value", kind, List.of(), "vec2(0.0, 0.0)", null);
            case COLOR:
                return new NodeField("f", "Value", kind, List.of(), "vec4(1.0, 1.0, 1.0, 1.0)", null);
            default:
                return new NodeField("f", "Value", kind, List.of(), "text", null);
        }
    }

    /** A node carrying exactly one control of the given kind, laid out. */
    private UIElement controlInNode(NodeField.Kind kind) {
        GraphNode node = new GraphNode("Node");
        NodeField field = fieldFor(kind);
        UIElement widget = NodeFieldWidgets.create(field, field.defaultValue(), v -> { });
        assertNotNull("no widget registered for kind " + kind, widget);
        node.addControl(field.label(), widget);
        root.append(node);
        frame();
        return widget;
    }

    private static float height(UIElement e) {
        return e.box().height();
    }

    /**
     * <b>The kit is a set.</b> Every control kind lands on one height, so a node with three of them
     * shows three widgets on a line rather than three heights.
     */
    @Test
    public void everyKind() {
        List<String> wrong = new ArrayList<>();
        for (NodeField.Kind kind : NodeField.Kind.values()) {
            openWindow();
            UIElement widget = controlInNode(kind);
            float h = height(widget);
            if (Math.abs(h - CTRL_H) > 0.5f) {
                wrong.add(kind + " is " + h + "px (kit height is " + CTRL_H + ")");
            }
        }
        assertTrue("control kinds off the kit height — add the widget's tag to the kit selector in "
                + "default.css:\n  " + String.join("\n  ", wrong), wrong.isEmpty());
    }

    /**
     * <b>One root cause, three symptoms.</b> A node's title bar, a control row's label, and every
     * {@code Button}/{@code Dropdown}'s own selected-value text all wrapped onto a second line under
     * ore.css's wider {@code MinecraftRegular.otf} — "Position" as "Positio/n", "Space" as "Sp/ac/e",
     * a dropdown's "World" or "View" cut mid-word. All three trace to the same gap: none of them ever
     * had {@code flex-shrink}/{@code white-space}/{@code text-overflow} set, because the bundled pixel
     * font is narrow enough that a too-narrow box never actually happened before. Fixed as one pattern
     * in three places rather than three unrelated bugs — this test pins all three so a fourth spot
     * doesn't need its own screenshot to find.
     */
    @Test
    public void labelsClipInsteadOfWrappingEverywhereTheSameGapExisted() {
        openWindow();
        GraphNode node = new GraphNode("A Rather Long Node Title");
        node.addControl("A Rather Long Control Label", new UIElement());
        com.crystalgui.widget.overlay.Dropdown dropdown = new com.crystalgui.widget.overlay.Dropdown("");
        dropdown.addOption("A Rather Long Selected Value").select(0);
        root.append(node);
        root.append(dropdown);
        frame();

        UIElement title = deepAll(node, ".__title__").get(0);
        UIElement controlLabel = deepAll(node, ".__control-row__ .__label__").get(0);
        // NAMED, not filtered by parentage. The original picked the dropdown's own label out of a
        // "text" sweep by asking which one was a direct child -- the closed menu holds a MenuItem per
        // option, each with its own text, so the first match was not reliably the right one. Every one
        // of those is a shadow PART now, so none of them is a child of the dropdown and the filter
        // matched nothing at all. The part name says exactly which label is meant.
        UIElement dropdownLabel = part(dropdown, Button.LABEL_PART);

        for (UIElement label : List.of(title, controlLabel, dropdownLabel)) {
            assertEquals("must not wrap onto a second line: " + label,
                    com.crystalgui.style.property.visual.text.WhiteSpace.NOWRAP,
                    label.getStyle().getComputed(
                            com.crystalgui.style.property.StylePropertyRegistry.WHITE_SPACE));
            assertEquals("must clip with an ellipsis instead: " + label,
                    com.crystalgui.style.property.visual.text.TextOverflow.ELLIPSIS,
                    label.getStyle().getComputed(
                            com.crystalgui.style.property.StylePropertyRegistry.TEXT_OVERFLOW));
        }
    }

    /** The row is a FLOOR that holds whatever it is given, never a cap that crops it. */
    @Test
    public void theRowIsAFloorNotACap() {
        openWindow();
        UIElement widget = controlInNode(NodeField.Kind.NUMBER);
        UIElement row = widget.parentElement();
        assertTrue("the control row must not be shorter than its floor, was " + height(row),
                height(row) >= ROW_H - 0.5f);
        assertTrue("a control must never be taller than the row containing it",
                height(widget) <= height(row) + 0.5f);
    }

    /**
     * <b>The leak guard, and the reason this class exists.</b> A self-contained composite opened from a
     * node keeps its <em>own internal</em> geometry.
     *
     * <p>Note carefully what is NOT asserted: a control's height legitimately differs inside a node,
     * because the kit rule is scoped to {@code graphnode .__control-row__} and that is the whole point
     * of it. What must not differ is the geometry <em>inside</em> a composite that a node merely
     * happens to host. The picker is 188px of carefully-sized parts; the row it was summoned from is
     * entitled to size the picker, and entitled to nothing within it.</p>
     *
     * <p>That distinction is exactly what went wrong: {@code graphnode .__control-row__ textfield}
     * matched the picker's four value fields, tied its own rule on specificity so the width was won on
     * source order, and left the grow factor uncontested — so the fields ate the slack and the colour
     * tracks shrank to a third. Every number in the stylesheet still read correctly.</p>
     *
     * <p><b>Every future composite belongs in this list</b> — the gradient editor, the matrix grid, the
     * sampler-state pair. Each is a panel of parts sized against each other, and each is one ambient
     * selector away from the same failure.</p>
     */
    @Test
    public void aCompositeKeepsItsInternalGeometryInsideANode() {
        List<String> diverged = new ArrayList<>();
        for (Composite composite : COMPOSITES) {
            openWindow();
            UIElement free = composite.build();
            root.append(free);
            frame();
            List<Float> expected = composite.measure(free);

            openWindow();
            GraphNode node = new GraphNode("Node");
            UIElement slot = new UIElement();
            slot.addClass(GraphNode.FULL_WIDTH_CLASS);
            node.addControl("", slot);
            root.append(node);
            UIElement hosted = composite.build();
            slot.append(hosted);
            // Promoted, because that is how a node presents one — and promotion is precisely what makes
            // this worth testing: it moves the Taffy parent, the transform and the paint entry, and
            // leaves the DOM parent alone, so the cascade reaches in exactly as if nothing had moved.
            document.promote(hosted);
            frame();
            List<Float> actual = composite.measure(hosted);

            for (int i = 0; i < expected.size(); i++) {
                if (Math.abs(expected.get(i) - actual.get(i)) > 0.5f) {
                    diverged.add(composite.name + " part " + composite.partNames.get(i)
                            + ": " + expected.get(i) + "px free vs " + actual.get(i) + "px in a node");
                }
            }
        }
        assertTrue("a node's own rules are reaching inside a composite it is only hosting:\n  "
                + String.join("\n  ", diverged), diverged.isEmpty());
    }

    /** A composite widget, and the parts whose sizes are its design. */
    private static final class Composite {
        final String name;
        final List<String> partNames;
        private final java.util.function.Supplier<UIElement> factory;
        private final List<String> selectors;

        Composite(String name, java.util.function.Supplier<UIElement> factory, String... selectors) {
            this.name = name;
            this.factory = factory;
            this.selectors = List.of(selectors);
            this.partNames = this.selectors;
        }

        UIElement build() {
            return factory.get();
        }

        /** Widths, because a composite's design is how its parts divide the width between them. */
        List<Float> measure(UIElement instance) {
            List<Float> widths = new ArrayList<>();
            for (String selector : selectors) {
                var found = deepAll(instance, selector);
                assertFalse(name + " has no " + selector, found.isEmpty());
                widths.add(found.get(0).box().width());
            }
            return widths;
        }
    }

    private static final List<Composite> COMPOSITES = List.of(
            new Composite("ColorSelector", com.crystalgui.widget.composite.ColorSelector::new,
                    "." + com.crystalgui.widget.composite.ColorSelector.CHANNEL_ROW_CLASS + " textfield",
                    "." + com.crystalgui.widget.composite.ColorSelector.CHANNEL_ROW_CLASS + " slider",
                    "." + com.crystalgui.widget.composite.ColorSelector.RING_CLASS,
                    "." + com.crystalgui.widget.composite.ColorSelector.SQUARE_CLASS));
}
