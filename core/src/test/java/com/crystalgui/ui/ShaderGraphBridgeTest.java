package com.crystalgui.ui;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgraphics.gl.material.parse.CgShaderParser;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.shader.ShaderGraphBridge;
import com.crystalgui.testsupport.UiTestBase;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.6 / 6.3.8 — the editor's document becoming a real shader.
 *
 * <h3>What is actually being asserted</h3>
 * <p>The join. Both halves are already tested on their own — {@code GraphDocumentTest} for the document,
 * {@code CgGraphCompilerTest} and {@code CgShaderEmitterTest} for the compiler — so what is left is
 * whether a document built the way the <em>editor</em> builds one survives the mapping. It is the seam
 * that had no coverage, and the one a gallery page would otherwise be the only witness to.</p>
 *
 * <p>In {@code test} rather than {@code headlessTest} deliberately: the bridge reaches CrystalGraphics
 * core, which headless excludes by design.</p>
 */
public class ShaderGraphBridgeTest extends UiTestBase {

    private static NodeTypeRegistry library() {
        return ShaderGraphBridge.asNodeLibrary(CgShaderNodeRegistry.builtins());
    }

    // ── The library the editor sees ─────────────────────────────────────────

    /**
     * <b>Every shader node shows up as an editor node type, plus the master.</b>
     *
     * <p>One bridge call gives the create menu, its search and the widget factory everything they need,
     * with no shader-specific UI code. The master is included because a graph has to end somewhere, and
     * leaving it out would make the one node every graph needs the one the menu cannot create.</p>
     */
    @Test
    public void theShaderNodesBecomeAnEditorLibrary() {
        NodeTypeRegistry library = library();

        // Derived rather than hard-coded: the node library is meant to grow, and a magic number here
        // turns every new built-in into a test failure that says nothing about the bridge.
        assertEquals("every shader node plus the master",
                CgShaderNodeRegistry.builtins().all().size() + 1, library.size());
        assertNotNull(library.get("cg:Math/Basic/multiply"));
        assertNotNull(library.get(ShaderGraphBridge.MASTER_TYPE));
        assertEquals("Multiply", library.get("cg:Math/Basic/multiply").label());
        assertEquals("categories come from the id's own namespace, nested to match Unity's own "
                        + "Math ▸ Basic/Advanced/Range/Round grouping",
                "Math/Basic", library.get("cg:Math/Basic/multiply").category());
    }

    /** A dynamic port has no GLSL name, so it is presented as float — the identity of the promotion
     * order — rather than as something the editor cannot colour or validate. */
    @Test
    public void dynamicPortsArePresentedConcretelyToTheEditor() {
        NodeType multiply = library().get("cg:Math/Basic/multiply");

        assertTrue(multiply.ports().stream().allMatch(p -> !p.typeId().isEmpty()));
        assertEquals("dynamic ports are their own type, not float — float would refuse every vector",
                ShaderGraphBridge.DYNAMIC_TYPE, multiply.ports().get(0).typeId());
    }

    /** The editor's compatibility rule must agree with what the compiler will do at emit time. */
    @Test
    public void promotionAgreesWithTheCompiler() {
        assertTrue(ShaderGraphBridge.GLSL_PROMOTION.accepts("float", "vec3"));
        assertTrue(ShaderGraphBridge.GLSL_PROMOTION.accepts("vec3", "vec3"));
        // Narrowing is ALLOWED, matching Unity: a Vector4 dropped on a Vector3 slot connects and loses
        // its w. The compiler swizzles it down; see CgGraphCompiler.mayNarrow, which had to be reversed
        // in the same breath so the two cannot disagree.
        assertTrue("a wider value truncates rather than being refused",
                ShaderGraphBridge.GLSL_PROMOTION.accepts("vec3", "float"));
        assertTrue("UV(4) into Base Color(3) is the case this exists for",
                ShaderGraphBridge.GLSL_PROMOTION.accepts("vec4", "vec3"));
        // Still refused: a sampler is not a number, and no swizzle turns one into one.
        assertFalse(ShaderGraphBridge.GLSL_PROMOTION.accepts("sampler2D", "vec3"));
    }

    /**
     * <b>The PORT TYPE check must agree with the document's rule, because there are two of them.</b>
     *
     * <p>The document's {@link com.crystalgui.graph.TypeCompatibility} governs
     * {@code GraphDocument.connect}; a widget drag goes through {@code GraphView.canConnect}, which asks
     * the {@code PortType}. Registering the rule in only one produced the worst kind of failure — the
     * starter graph reported {@code 4n/0e}, a connection simply refused, with no wire, no error and
     * nothing to read anywhere.</p>
     */
    @Test
    public void theWidgetSideCompatibilityAgreesWithTheDocumentSide() {
        library(); // registering the library is what registers the port types

        var vec4 = com.crystalgui.graph.port.PortTypeRegistry.get("vec4");
        var dynamic = com.crystalgui.graph.port.PortTypeRegistry.get(
                ShaderGraphBridge.DYNAMIC_TYPE);
        var floatType = com.crystalgui.graph.port.PortTypeRegistry.get("float");
        assertNotNull("the bridge must register its port types", vec4);
        assertNotNull(dynamic);

        assertTrue("a vec4 must be able to reach a dynamic port", vec4.isCompatibleWith(dynamic));
        assertTrue("and a dynamic port must be able to feed anything", dynamic.isCompatibleWith(vec4));
        assertTrue("a scalar promotes", floatType.isCompatibleWith(vec4));
        assertTrue("and a wider value truncates", vec4.isCompatibleWith(floatType));

        // The two checks must not disagree, or a drag and a document write reach opposite conclusions.
        assertEquals(ShaderGraphBridge.GLSL_PROMOTION.accepts("vec4", ShaderGraphBridge.DYNAMIC_TYPE),
                vec4.isCompatibleWith(dynamic));
        assertEquals(ShaderGraphBridge.GLSL_PROMOTION.accepts("vec4", "float"),
                vec4.isCompatibleWith(floatType));
    }

    // ── The join ────────────────────────────────────────────────────────────

    /**
     * <b>A document built as the editor builds one compiles to a {@code .shader} that parses.</b>
     *
     * <p>The end-to-end claim of the whole track: nodes, edges and port values on one side; a file the
     * material layer can load on the other, with no GL anywhere in between.</p>
     */
    @Test
    public void anEditorDocumentCompilesToAParseableShader() {
        NodeTypeRegistry library = library();
        GraphDocument document = new GraphDocument();
        document.setTypeCompatibility(ShaderGraphBridge.GLSL_PROMOTION);

        NodeData colour = document.addNode(library.get("cg:Input/Basic/color").create(0f, 0f));
        NodeData time = document.addNode(library.get("cg:Input/Basic/time").create(0f, 120f));
        NodeData multiply = document.addNode(library.get("cg:Math/Basic/multiply").create(200f, 40f));
        NodeData master = document.addNode(library.get(ShaderGraphBridge.MASTER_TYPE).create(400f, 40f));

        document.link(colour, "Out", multiply, "A");
        document.link(time, "Time", multiply, "B");
        document.link(multiply, "Out", master, CgMasterNode.BASE_COLOR);

        CgShaderEmitter.Result result = ShaderGraphBridge.compile(
                document, CgShaderNodeRegistry.builtins(), new CgMasterNode());

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertNotNull("the generated file must parse", CgShaderParser.parse(result.source()));

        // vec4 * float: the dynamic node widened to vec4 and the compiler inserted the cast, which is
        // the thing a user would otherwise have to know to do by hand.
        assertTrue(result.source(), result.source().contains("vec4 node_" + multiply.id() + "_Out;"));
        assertTrue("the scalar side was promoted",
                result.source().contains("vec4(node_" + time.id() + "_Time)"));
        // Narrowed to .xyz: BaseColor is a vec3 now that Alpha is a port of its own, and the master is
        // the one boundary allowed to truncate a user-drawn edge. @see CgShaderEmitter#adapted
        assertTrue("and it reaches the output",
                result.source().contains("fragColor = vec4(node_" + multiply.id() + "_Out.xyz, cg_alpha);"));
    }

    /** Port values the editor collected become literals in the generated GLSL. */
    @Test
    public void portValuesFromTheEditorBecomeLiterals() {
        NodeTypeRegistry library = library();
        GraphDocument document = new GraphDocument();
        document.setTypeCompatibility(ShaderGraphBridge.GLSL_PROMOTION);
        NodeData floatNode = document.addNode(
                library.get("cg:Input/Basic/float").create(0f, 0f).withProperty("X", "0.25"));
        NodeData master = document.addNode(library.get(ShaderGraphBridge.MASTER_TYPE).create(200f, 0f));
        document.link(floatNode, "Out", master, CgMasterNode.BASE_COLOR);

        String source = ShaderGraphBridge.compile(
                document, CgShaderNodeRegistry.builtins(), new CgMasterNode()).source();

        assertTrue(source, source.contains("= 0.25;"));
    }

    /**
     * <b>A document with no Output node reports rather than throwing.</b>
     *
     * <p>The normal state of a graph someone is still building — the editor must be able to say so in a
     * status line, not crash on the way to one.</p>
     */
    @Test
    public void aDocumentWithNoMasterIsReported() {
        NodeTypeRegistry library = library();
        GraphDocument document = new GraphDocument();
        document.addNode(library.get("cg:Input/Basic/color").create(0f, 0f));

        CgShaderEmitter.Result result = ShaderGraphBridge.compile(
                document, CgShaderNodeRegistry.builtins(), new CgMasterNode());

        assertFalse(result.ok());
        // "output node", from the EMITTER now rather than the bridge. The bridge used to refuse a
        // masterless document outright and return null, which killed every node thumbnail to report one
        // missing node; it now builds the graph and lets the emit be the thing that fails, because the
        // emit is the only operation a master is required for.
        assertTrue(result.errors().get(0),
                result.errors().get(0).toLowerCase(java.util.Locale.ROOT).contains("output"));
    }

    // ── Multiple outputs (6.3.6's real remaining blocker, now proven through the full bridge) ────

    /**
     * <b>{@code Split}'s four outputs show up as four editor ports, and two of them wire to two
     * different downstream nodes independently.</b>
     *
     * <p>{@code CgGraphCompilerTest} already proves the raw compiler handles this; what had no
     * coverage is the EDITOR half — {@code ShaderGraphBridge.asNodeType} turning
     * {@link com.crystalgraphics.shadergraph.CgShaderNode#outputs()} into {@code NodeType} ports, and
     * {@code GraphDocument.link} accepting two edges out of the same node on two different port ids.
     * Nothing before {@code cg:Channel/split} existed for either of those to have ever run against a
     * real multi-output node.</p>
     */
    @Test
    public void splitsFourOutputsBecomeFourPortsAndWireIndependently() {
        NodeTypeRegistry library = library();
        NodeType split = library.get("cg:Channel/split");
        assertNotNull(split);
        assertEquals("one input, four outputs", 5, split.ports().size());
        assertEquals(4, split.ports().stream()
                .filter(p -> p.direction() == com.crystalgui.graph.PortDirection.OUTPUT).count());

        GraphDocument document = new GraphDocument();
        document.setTypeCompatibility(ShaderGraphBridge.GLSL_PROMOTION);
        NodeData colour = document.addNode(library.get("cg:Input/Basic/color").create(0f, 0f));
        NodeData splitNode = document.addNode(split.create(200f, 0f));
        NodeData multiply = document.addNode(library.get("cg:Math/Basic/multiply").create(400f, 0f));
        NodeData master = document.addNode(library.get(ShaderGraphBridge.MASTER_TYPE).create(600f, 0f));

        document.link(colour, "Out", splitNode, "In");
        // R and G each feed a DIFFERENT input of the same downstream node — two edges out of the same
        // source node, on two different output ports, which is exactly what a single-output node could
        // never produce.
        document.link(splitNode, "R", multiply, "A");
        document.link(splitNode, "G", multiply, "B");
        document.link(multiply, "Out", master, CgMasterNode.BASE_COLOR);

        CgShaderEmitter.Result result = ShaderGraphBridge.compile(
                document, CgShaderNodeRegistry.builtins(), new CgMasterNode());

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertNotNull("the generated file must parse", CgShaderParser.parse(result.source()));
        assertTrue("R reaches A", result.source().contains(
                "node_" + multiply.id() + "_Out = node_" + splitNode.id() + "_R * "));
        assertTrue("G reaches B, not R's line",
                result.source().contains("* node_" + splitNode.id() + "_G;"));
    }

    /**
     * <b>A node type the shader library does not know is skipped, not fatal.</b>
     *
     * <p>Same reasoning that makes an unknown type open as a placeholder rather than eating the graph:
     * the result is a shader missing that branch, which the user can see and fix.</p>
     */
    @Test
    public void anUnknownNodeTypeIsSkippedRatherThanFatal() {
        NodeTypeRegistry library = library();
        GraphDocument document = new GraphDocument();
        document.setTypeCompatibility(ShaderGraphBridge.GLSL_PROMOTION);
        document.addNode(document.newNode("some.mod.Missing").at(0f, 0f).out("Out", "vec4").build());
        NodeData master = document.addNode(library.get(ShaderGraphBridge.MASTER_TYPE).create(200f, 0f));

        CgShaderEmitter.Result result = ShaderGraphBridge.compile(
                document, CgShaderNodeRegistry.builtins(), new CgMasterNode());

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertNotNull(CgShaderParser.parse(result.source()));
        assertTrue("the master's own default stands in for the missing branch",
                result.source().contains("fragColor = vec4(vec3(1.0, 1.0, 1.0), cg_alpha);"));
    }
}
