package com.crystalgui.app.shadergraph.node;

import com.crystalgui.app.shadergraph.ShaderGraphBridge;
import com.crystalgraphics.shadergraph.CgBuiltinShaderNodes;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphView;
import com.crystalgui.widget.graph.NodePort;
import com.crystalgui.widget.graph.node.NodeFieldBinder;
import com.crystalgui.widget.graph.NodeWidgetFactory;
import com.crystalgui.widget.config.control.NumberControl;
import com.crystalgui.widget.config.control.VectorControl;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * P6.3 — Unity's {@code A(1) B(1) Out(1)} on a dynamic node, widening to {@code (3)} once something is
 * wired in.
 *
 * <p>The number is per-PORT and changes with the wiring, so it cannot come from
 * {@code PortType.arity()}, which every port of a type shares. See {@link ShaderPortArity} for the whole
 * argument; these pin the two halves a user actually sees.</p>
 */
public class ShaderPortArityTest extends UiDocumentTestBase {

    private GraphView view;
    private NodeTypeRegistry library;
    private GraphDocument graphDocument;

    private void openWindow() {
        view = new GraphView();
        view.layout(l -> l.width(600).height(400));
        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        root.append(view);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);

        CgShaderNodeRegistry shaderNodes = new CgShaderNodeRegistry();
        CgBuiltinShaderNodes.registerAll(shaderNodes);
        library = ShaderGraphBridge.asNodeLibrary(shaderNodes);
        view.setNodeLibrary(library, NodeWidgetFactory.of(library).build(),
                ShaderGraphBridge.GLSL_PROMOTION);
        // Installed with a non-null onChange, which is what enables the inline-editor rebuild — bare,
        // it would only do labels and colours. NOT via ShaderGraphPreviews (the real caller): that
        // constructs a CgPreviewRenderer, which needs a GL context this test deliberately has none of.
        ShaderPortArity.install(view, () -> { });
        graphDocument = view.getDocument();
    }

    private GraphNode add(String typeId, float x, float y) {
        NodeType type = library.get(typeId);
        NodeData data = graphDocument.addNode(type.create(x, y));
        GraphNode node = view.getNodeFactory().create(type, data);
        view.addNode(node, x, y);
        // What ShaderGraphPreviews.attachTo does for real, minus the previews: without it a port has no
        // inline editor at all and there is nothing for the arity rebuild to re-shape.
        NodeFieldBinder.attach(node, type, graphDocument, view.undoStack(), null);
        return node;
    }

    private static String labelOf(GraphNode node, String portId) {
        NodePort port = node.portNamed(portId);
        return port == null ? null : port.getName();
    }

    /**
     * <b>Unwired, a dynamic node reads {@code (1)}</b> — not a bare name.
     *
     * <p>{@code float} is also what {@code CgGraphCompiler} falls back to for an unresolved dynamic port,
     * so the label agrees with the GLSL that would be emitted rather than only becoming truthful once a
     * wire lands.</p>
     */
    @Test
    public void anUnwiredDynamicNodeReportsFloat() {
        openWindow();
        GraphNode multiply = add(CgBuiltinShaderNodes.MULTIPLY.id(), 0f, 0f);
        frame();

        assertEquals("A(1)", labelOf(multiply, "A"));
        assertEquals("B(1)", labelOf(multiply, "B"));
        assertEquals("Out(1)", labelOf(multiply, "Out"));
    }

    /**
     * <b>Connecting a vec3 widens EVERY dynamic port on the node, not just the one wired.</b>
     *
     * <p>That is the compiler's own rule — {@code CgGraphCompiler.resolveTypes} resolves a node's dynamic
     * ports together to the widest type reaching any of them — and mirroring it is the whole point: a
     * label that disagreed with the emitted cast would be worse than no label. Note {@code B} is still
     * unconnected and still reports {@code (3)}, which is exactly Unity's behaviour in the reference
     * screenshot.</p>
     */
    @Test
    public void connectingAWiderTypeWidensEveryDynamicPortOnTheNode() {
        openWindow();
        GraphNode position = add(CgBuiltinShaderNodes.POSITION.id(), 0f, 0f);   // Out is vec3
        GraphNode multiply = add(CgBuiltinShaderNodes.MULTIPLY.id(), 200f, 0f);
        frame();

        view.connect(position.portNamed("Out"), multiply.portNamed("A"));
        frame();

        assertEquals("A(3)", labelOf(multiply, "A"));
        assertEquals("the unconnected sibling widens too — dynamic ports resolve together",
                "B(3)", labelOf(multiply, "B"));
        assertEquals("Out(3)", labelOf(multiply, "Out"));
    }

    /** And it narrows back when the wire is removed — the label tracks the graph, not a high-water mark. */
    @Test
    public void disconnectingReturnsToFloat() {
        openWindow();
        GraphNode position = add(CgBuiltinShaderNodes.POSITION.id(), 0f, 0f);
        GraphNode multiply = add(CgBuiltinShaderNodes.MULTIPLY.id(), 200f, 0f);
        frame();
        view.connect(position.portNamed("Out"), multiply.portNamed("A"));
        frame();
        assertEquals("A(3)", labelOf(multiply, "A"));

        view.disconnectAll(multiply.portNamed("A"));
        frame();

        assertEquals("A(1)", labelOf(multiply, "A"));
        assertEquals("Out(1)", labelOf(multiply, "Out"));
    }

    /**
     * A width propagates along a CHAIN of dynamic nodes.
     *
     * <p>This is why the resolver iterates to a fixpoint rather than making one pass in whatever order
     * the edges happen to be stored: the second Multiply's width depends on the first's, which is itself
     * resolved rather than declared.</p>
     */
    @Test
    public void widthPropagatesThroughAChainOfDynamicNodes() {
        openWindow();
        GraphNode position = add(CgBuiltinShaderNodes.POSITION.id(), 0f, 0f);
        GraphNode first = add(CgBuiltinShaderNodes.MULTIPLY.id(), 200f, 0f);
        GraphNode second = add(CgBuiltinShaderNodes.ADD.id(), 400f, 0f);
        frame();

        view.connect(position.portNamed("Out"), first.portNamed("A"));
        view.connect(first.portNamed("Out"), second.portNamed("A"));
        frame();

        assertEquals("Out(3)", labelOf(first, "Out"));
        assertEquals("the chain's second node inherits through the first's RESOLVED width",
                "A(3)", labelOf(second, "A"));
        assertEquals("Out(3)", labelOf(second, "Out"));
    }

    /**
     * <b>{@code Add(vec4, vec2)} resolves to the NARROWER side.</b>
     *
     * <p>Unity's rule, and not the obvious one — see {@code CgShaderType.resolveDynamic}. Widest would
     * have to invent the two channels a vec2 does not have; narrowest only discards data the user can
     * see they wired in. Getting it backwards did not merely mislabel the node, it made the graph refuse
     * to compile: resolving to vec4 asked a vec2 to feed a vec4, which {@code canFeed} correctly forbids,
     * so the preview went black.</p>
     */
    @Test
    public void aWiderAndANarrowerInputResolveToTheNarrower() {
        openWindow();
        GraphNode colour = add(CgBuiltinShaderNodes.COLOR.id(), 0f, 0f);       // Out is vec4
        GraphNode vector2 = add(CgBuiltinShaderNodes.VECTOR2.id(), 0f, 200f);  // Out is vec2
        GraphNode add = add(CgBuiltinShaderNodes.ADD.id(), 200f, 0f);
        frame();

        view.connect(colour.portNamed("Out"), add.portNamed("A"));
        view.connect(vector2.portNamed("Out"), add.portNamed("B"));
        frame();

        assertEquals("A(2)", labelOf(add, "A"));
        assertEquals("B(2)", labelOf(add, "B"));
        assertEquals("Out(2)", labelOf(add, "Out"));
    }

    /** A scalar alongside a vector still promotes — it never drags the node down to float. */
    @Test
    public void aScalarNeverDecidesTheWidth() {
        openWindow();
        GraphNode colour = add(CgBuiltinShaderNodes.COLOR.id(), 0f, 0f);    // vec4
        GraphNode scalar = add(CgBuiltinShaderNodes.FLOAT.id(), 0f, 200f);  // float
        GraphNode add = add(CgBuiltinShaderNodes.ADD.id(), 200f, 0f);
        frame();

        view.connect(colour.portNamed("Out"), add.portNamed("A"));
        view.connect(scalar.portNamed("Out"), add.portNamed("B"));
        frame();

        assertEquals("the float promotes rather than narrowing the node to (1)",
                "A(4)", labelOf(add, "A"));
        assertEquals("Out(4)", labelOf(add, "Out"));
    }

    /**
     * <b>Colour follows the same resolution the label does.</b>
     *
     * <p>A dynamic port is otherwise stuck on the flat "unknown" grey {@code type-dynamic} paints. Unity
     * colours it by whatever it resolved to, scalar before anything is wired in — and because the wire
     * reads the dot's computed border-colour back out of the cascade, re-classing the port recolours the
     * wire too, with no second mechanism.</p>
     */
    @Test
    public void colourFollowsTheResolvedTypeAndNotTheDynamicGrey() {
        openWindow();
        GraphNode multiply = add(CgBuiltinShaderNodes.MULTIPLY.id(), 200f, 0f);
        frame();

        NodePort out = multiply.portNamed("Out");
        assertTrue("unwired, a dynamic port takes the SCALAR colour, not the unknown grey",
                out.hasClass("type-float"));
        assertFalse(out.hasClass("type-dynamic"));

        GraphNode position = add(CgBuiltinShaderNodes.POSITION.id(), 0f, 0f);   // vec3
        frame();
        view.connect(position.portNamed("Out"), multiply.portNamed("A"));
        frame();

        assertTrue("resolved to vec3, so it is coloured as one", out.hasClass("type-vec3"));
        assertFalse("and the previous class is REMOVED, not merely overlaid — equal specificity would "
                + "otherwise leave the answer to stylesheet source order", out.hasClass("type-float"));
    }

    /**
     * <b>The unconnected sibling's inline editor grows to N fields.</b>
     *
     * <p>Unity's {@code B} becomes {@code X 2  Y 2  Z 2} when a vec3 lands on {@code A}. A control cannot
     * restructure itself — a {@code VectorControl}'s component count is fixed when it is built — so the
     * editor is rebuilt and handed to the port, and {@code PortDefaultEditor} swaps it in place.</p>
     */
    @Test
    public void anUnconnectedSiblingsEditorGrowsToTheResolvedWidth() {
        openWindow();
        GraphNode position = add(CgBuiltinShaderNodes.POSITION.id(), 0f, 0f);   // vec3
        GraphNode multiply = add(CgBuiltinShaderNodes.MULTIPLY.id(), 200f, 0f);
        frame();

        assertTrue("a scalar port starts as a single number field",
                multiply.portNamed("B").getDefaultEditor() instanceof NumberControl);

        view.connect(position.portNamed("Out"), multiply.portNamed("A"));
        frame();

        UIElement editor = multiply.portNamed("B").getDefaultEditor();
        assertTrue("resolved to vec3, so the editor must now be a vector one",
                editor instanceof VectorControl);
        assertEquals("with one box per component", 3, ((VectorControl) editor).components().size());
    }

    /**
     * Re-shaping the stored literal <b>splats</b> an existing scalar rather than zero-filling.
     *
     * <p>Unity shows {@code X 2 Y 2 Z 2} after a {@code 2} was widened — keeping the value on every axis
     * is what makes widening read as a change of shape rather than a reset.</p>
     */
    @Test
    public void wideningSplatsTheExistingScalarAcrossTheNewComponents() {
        assertEquals("vec3(2.000, 2.000, 2.000)", ShaderPortArity.literalAtArity("2.0", 3));
        assertEquals("an existing vector keeps its own components",
                "vec3(1.000, 2.000, 0.000)", ShaderPortArity.literalAtArity("vec2(1.0, 2.0)", 3));
        assertEquals("and narrowing back to a scalar keeps the first",
                "1.000", ShaderPortArity.literalAtArity("vec3(1.0, 2.0, 3.0)", 1));
    }

    /** A concretely-typed port is never relabelled — {@code vec2} is 2 whatever is wired into it. */
    @Test
    public void aConcretelyTypedPortIsUntouched() {
        openWindow();
        GraphNode polar = add(CgBuiltinShaderNodes.POLAR_COORDINATES.id(), 0f, 0f);
        frame();

        assertEquals("UV(2)", labelOf(polar, "UV"));
        assertEquals("Center(2)", labelOf(polar, "Center"));
        assertEquals("a camelCase port id is DRAWN spaced, while the id itself stays one identifier",
                "Radial Scale(1)", labelOf(polar, "RadialScale"));
        assertEquals("Out(2)", labelOf(polar, "Out"));
    }

    /**
     * <b>A port id is drawn spaced, but stays one identifier.</b>
     *
     * <p>{@code RadialScale} is a GLSL template key, a {@code PortSpec.portId} and what an edge points
     * at — it cannot contain a space. Only the label is humanised, which is the split {@code getName()}
     * already documents against {@code getPortId()}.</p>
     *
     * <p>The acronym case is the one that matters: {@code UV} must not become {@code U V}, which is
     * exactly what a naive "space before every capital" would do.</p>
     */
    @Test
    public void portLabelsAreSpacedWithoutTouchingTheId() {
        openWindow();
        GraphNode polar = add(CgBuiltinShaderNodes.POLAR_COORDINATES.id(), 0f, 0f);
        frame();

        assertEquals("Radial Scale(1)", labelOf(polar, "RadialScale"));
        assertEquals("Length Scale(1)", labelOf(polar, "LengthScale"));
        assertEquals("an acronym stays whole", "UV(2)", labelOf(polar, "UV"));
        assertNotNull("and the id is untouched, or every edge and template key would break",
                polar.portNamed("RadialScale"));
    }

    /** The category comes from the id VERBATIM, so `cg:UV/...` reads "UV" and not "Uv". */
    @Test
    public void aCategoryKeepsTheCaseTheIdWroteItIn() {
        assertEquals("UV", com.crystalgui.app.shadergraph.ShaderGraphBridge
                .asNodeType(CgBuiltinShaderNodes.POLAR_COORDINATES).category());
    }
}
