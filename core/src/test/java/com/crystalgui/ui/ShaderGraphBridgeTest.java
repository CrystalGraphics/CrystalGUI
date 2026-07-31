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

        assertEquals("five built-ins plus the master", 6, library.size());
        assertNotNull(library.get("cg:math/multiply"));
        assertNotNull(library.get(ShaderGraphBridge.MASTER_TYPE));
        assertEquals("Multiply", library.get("cg:math/multiply").label());
        assertEquals("categories come from the id's own namespace",
                "Math", library.get("cg:math/multiply").category());
    }

    /** A dynamic port has no GLSL name, so it is presented as float — the identity of the promotion
     * order — rather than as something the editor cannot colour or validate. */
    @Test
    public void dynamicPortsArePresentedConcretelyToTheEditor() {
        NodeType multiply = library().get("cg:math/multiply");

        assertTrue(multiply.ports().stream().allMatch(p -> !p.typeId().isEmpty()));
        assertEquals("dynamic ports are their own type, not float — float would refuse every vector",
                ShaderGraphBridge.DYNAMIC_TYPE, multiply.ports().get(0).typeId());
    }

    /** The editor's compatibility rule must agree with what the compiler will do at emit time. */
    @Test
    public void promotionAgreesWithTheCompiler() {
        assertTrue(ShaderGraphBridge.GLSL_PROMOTION.accepts("float", "vec3"));
        assertTrue(ShaderGraphBridge.GLSL_PROMOTION.accepts("vec3", "vec3"));
        assertFalse("nothing demotes", ShaderGraphBridge.GLSL_PROMOTION.accepts("vec3", "float"));
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

        var vec4 = com.crystalgui.ui.elements.graph.PortTypeRegistry.get("vec4");
        var dynamic = com.crystalgui.ui.elements.graph.PortTypeRegistry.get(
                ShaderGraphBridge.DYNAMIC_TYPE);
        var floatType = com.crystalgui.ui.elements.graph.PortTypeRegistry.get("float");
        assertNotNull("the bridge must register its port types", vec4);
        assertNotNull(dynamic);

        assertTrue("a vec4 must be able to reach a dynamic port", vec4.isCompatibleWith(dynamic));
        assertTrue("and a dynamic port must be able to feed anything", dynamic.isCompatibleWith(vec4));
        assertTrue("a scalar promotes", floatType.isCompatibleWith(vec4));
        assertFalse("nothing demotes", vec4.isCompatibleWith(floatType));

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

        NodeData colour = document.addNode(library.get("cg:input/color").create(0f, 0f));
        NodeData time = document.addNode(library.get("cg:input/time").create(0f, 120f));
        NodeData multiply = document.addNode(library.get("cg:math/multiply").create(200f, 40f));
        NodeData master = document.addNode(library.get(ShaderGraphBridge.MASTER_TYPE).create(400f, 40f));

        document.link(colour, "Out", multiply, "A");
        document.link(time, "Out", multiply, "B");
        document.link(multiply, "Out", master, CgMasterNode.BASE_COLOR);

        CgShaderEmitter.Result result = ShaderGraphBridge.compile(
                document, CgShaderNodeRegistry.builtins(), new CgMasterNode());

        assertTrue(String.join("\n", result.errors()), result.ok());
        assertNotNull("the generated file must parse", CgShaderParser.parse(result.source()));

        // vec4 * float: the dynamic node widened to vec4 and the compiler inserted the cast, which is
        // the thing a user would otherwise have to know to do by hand.
        assertTrue(result.source(), result.source().contains("vec4 node_" + multiply.id() + "_Out;"));
        assertTrue("the scalar side was promoted",
                result.source().contains("vec4(node_" + time.id() + "_Out)"));
        assertTrue("and it reaches the output",
                result.source().contains("fragColor = node_" + multiply.id() + "_Out;"));
    }

    /** Port values the editor collected become literals in the generated GLSL. */
    @Test
    public void portValuesFromTheEditorBecomeLiterals() {
        NodeTypeRegistry library = library();
        GraphDocument document = new GraphDocument();
        document.setTypeCompatibility(ShaderGraphBridge.GLSL_PROMOTION);
        NodeData floatNode = document.addNode(
                library.get("cg:input/float").create(0f, 0f).withProperty("Value", "0.25"));
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
        document.addNode(library.get("cg:input/color").create(0f, 0f));

        CgShaderEmitter.Result result = ShaderGraphBridge.compile(
                document, CgShaderNodeRegistry.builtins(), new CgMasterNode());

        assertFalse(result.ok());
        assertTrue(result.errors().get(0), result.errors().get(0).contains("Output"));
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
                result.source().contains("fragColor = vec4(1.0"));
    }
}
