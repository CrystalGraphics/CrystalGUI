package com.crystalgui.app.shadergraph;

import com.crystalgui.app.shadergraph.node.ShaderPropertyNodes;
import com.crystalgui.core.property.Property;
import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.widget.graph.GraphView;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.14 — a declared property reaches the generated shader, and a node reading it reaches the code.
 */
public class ShaderPropertyCompileTest {

    private final CgShaderNodeRegistry shaderNodes = CgShaderNodeRegistry.builtins();
    private final NodeTypeRegistry library = ShaderGraphBridge.asNodeLibrary(shaderNodes);

    private GraphDocument withMaster() {
        GraphDocument graphDocument = new GraphDocument();
        // The SAME rule the editor installs. A bare graphDocument defaults to EXACT, so an edge the real
        // editor permits was silently refused here and the graph compiled as though it had never been
        // drawn -- a fixture that quietly tests a different product.
        graphDocument.setTypeCompatibility(ShaderGraphBridge.GLSL_PROMOTION);
        graphDocument.addNode(library.get(ShaderGraphBridge.MASTER_TYPE).create(0f, 0f));
        return graphDocument;
    }

    private CgShaderEmitter.Result compile(GraphDocument graphDocument) {
        return ShaderGraphBridge.compile(graphDocument, shaderNodes, new CgMasterNode());
    }

    // ── Declaration ─────────────────────────────────────────────────────────

    /** A declared property becomes a uniform, and the shader still parses. */
    @Test
    public void aDeclaredPropertyReachesThePropertiesBlock() {
        GraphDocument graphDocument = withMaster();
        graphDocument.addProperty(GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));

        CgShaderEmitter.Result result = compile(graphDocument);
        assertTrue(result.errors().toString(), result.ok());
        assertTrue(result.source(), result.source().contains("_Tint (\"_Tint\", vec4) = (1,0,0,1)"));
    }

    /**
     * <b>Every declared property is a uniform, whether or not a node reads it.</b>
     *
     * <p>Declaring only what is referenced would make the shader's interface change as the graph is
     * wired, so a material's bindings would appear and vanish while the user was still building it.</p>
     */
    @Test
    public void anUnreferencedPropertyIsStillDeclared() {
        GraphDocument graphDocument = withMaster();
        graphDocument.addProperty(GraphProperty.of("Unused", "float", "1.0"));
        assertTrue(compile(graphDocument).source().contains("_Unused"));
    }

    /**
     * <b>Two documents through one master do not leave each other's uniforms behind.</b>
     *
     * <p>The master is the compiler's object, not storage — the same trap {@code ShaderGraphSettings}
     * already has a test for, and the reason the declarations are cleared before each emit.</p>
     */
    @Test
    public void oneMasterCompilingTwoDocumentsDoesNotLeak() {
        CgMasterNode shared = new CgMasterNode();
        GraphDocument first = withMaster();
        first.addProperty(GraphProperty.of("Tint", "vec4", "(1,1,1,1)"));
        GraphDocument second = withMaster();

        assertTrue(ShaderGraphBridge.compile(first, shaderNodes, shared).source().contains("_Tint"));
        assertFalse("the second graph never declared Tint",
                ShaderGraphBridge.compile(second, shaderNodes, shared).source().contains("_Tint"));
    }

    /** A matrix has no property form, so it is skipped rather than breaking the compile. */
    @Test
    public void aTypeWithNoPropertyFormIsSkipped() {
        GraphDocument graphDocument = withMaster();
        graphDocument.addProperty(GraphProperty.of("M", "mat4", ""));
        CgShaderEmitter.Result result = compile(graphDocument);
        assertTrue("a graph must still compile around it", result.ok());
        assertFalse(result.source().contains("_M "));
    }

    // ── The vec3 narrowing, end to end ──────────────────────────────────────

    /**
     * <b>A Vector 3 property is declared as vec4 and read back as .xyz.</b>
     *
     * <p>The landmine the plan found: {@code CgPropertiesParser} bans {@code vec3}, so a graph exposing
     * one would have emitted a shader that fails to parse. This asserts both halves in one place — the
     * declaration and the read must agree or the GLSL is a type error instead.</p>
     */
    @Test
    public void aVec3PropertyIsDeclaredWideAndReadNarrow() {
        GraphDocument graphDocument = withMaster();
        GraphProperty dir = graphDocument.addProperty(GraphProperty.of("Dir", "vec3", "(0,1,0,0)"));
        NodeData node = graphDocument.addNode(ShaderPropertyNodes.create(dir, 20f, 20f));
        graphDocument.link(node.id(), ShaderPropertyNodes.OUT_PORT,
                graphDocument.nodes().iterator().next().id(), "BaseColor");

        CgShaderEmitter.Result result = compile(graphDocument);
        assertTrue(result.errors().toString(), result.ok());
        assertTrue("declared wide: " + result.source(),
                result.source().contains("_Dir (\"_Dir\", vec4)"));
        assertTrue("read narrow: " + result.source(), result.source().contains("_Dir.xyz"));
        assertFalse("the banned token must never reach the file",
                result.source().contains("\", vec3)"));
    }

    // ── The node ────────────────────────────────────────────────────────────

    /** A property node emits the uniform's name — that is the whole of what it does. */
    @Test
    public void aPropertyNodeEmitsTheUniform() {
        GraphDocument graphDocument = withMaster();
        GraphProperty tint = graphDocument.addProperty(GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));
        NodeData node = graphDocument.addNode(ShaderPropertyNodes.create(tint, 20f, 20f));

        CgShaderEmitter.Result result = compile(graphDocument);
        assertTrue(result.errors().toString(), result.ok());
        assertTrue(result.source(), result.source().contains("_Tint"));
        assertEquals("the node must record which property it reads",
                tint.id(), ShaderPropertyNodes.propertyIdOf(graphDocument.node(node.id())));
    }

    /**
     * <b>The edge that crashed the editor now simply works.</b>
     *
     * <p>A Vector 2 into the master's {@code BaseColor(vec3)} — refused before, and refused mid-recompile
     * from the click that drew it, which killed the window. It adapts now, padding the missing channel
     * with zero.</p>
     */
    @Test
    public void aNarrowerVectorWidensIntoAWiderPort() {
        GraphDocument graphDocument = withMaster();
        String masterId = graphDocument.nodes().iterator().next().id();

        GraphProperty uv = graphDocument.addProperty(GraphProperty.of("Uv", "vec2", "(1,1)"));
        NodeData node = graphDocument.addNode(ShaderPropertyNodes.create(uv, 20f, 20f));
        graphDocument.link(node.id(), ShaderPropertyNodes.OUT_PORT, masterId, "BaseColor");

        CgShaderEmitter.Result result = compile(graphDocument);
        assertTrue(result.errors().toString(), result.ok());
        assertTrue("the padding must be in the emitted GLSL: " + result.source(),
                result.source().contains("vec3(") && result.source().contains("0.0"));
    }

    /**
     * <b>A preview substitutes the property's default instead of reading its uniform.</b>
     *
     * <p>{@code CgPreviewEmitter} writes no {@code Properties} block — a node preview is a tiny
     * standalone shader, not the material — so a preview referencing {@code _Float} had an undeclared
     * identifier, failed to compile, and fell back to white. Every preview downstream of a property node
     * was blank: a Float set to 0 multiplied by anything showed a white square.</p>
     *
     * <p>Declaring the block in previews would not have helped: a preview has no material to SET the
     * uniform, so the only value it could ever show is the default.</p>
     */
    @Test
    public void aPreviewUsesTheDefaultRatherThanTheUniform() {
        assertEquals("0", ShaderPropertyForm.glslLiteral(
                GraphProperty.of("A", "float", "0")));
        assertEquals("vec2(1, 0)", ShaderPropertyForm.glslLiteral(
                GraphProperty.of("B", "vec2", "(1,0)")));
        assertEquals("a Vector 3 stores four components and reads three",
                "vec3(1, 0, 0)", ShaderPropertyForm.glslLiteral(
                        GraphProperty.of("C", "vec3", "(1,0,0,1)")));
        assertNull("a texture cannot be a literal, so there is nothing to substitute",
                ShaderPropertyForm.glslLiteral(GraphProperty.of("D", "sampler2D", "\"white\"")));

        // And the node really carries it, rather than the helper merely being correct.
        var node = ShaderPropertyNodes.compilerNodeFor(GraphProperty.of("Amount", "float", "0"));
        assertTrue("the node must offer a preview form", node.hasPreviewForm());
    }

    /**
     * <b>A node added through the view keeps its property reference.</b>
     *
     * <p>The regression this pins is data loss, not styling. {@code GraphView.addNode} derives a node's
     * data from the WIDGET when the graphDocument does not already know its id, and for a library-typed
     * widget it derives {@code properties = Map.of()} — reasonable, since a type's defaults can normally
     * be rebuilt from the type. A property node's {@code propertyId} is instance state and its type is
     * synthesised per property and never registered, so there is nothing to rebuild it from: the
     * reference was dropped on the way in.</p>
     *
     * <p>It stayed invisible while the node's title was baked in at creation, and surfaced only once the
     * node started re-reading its property — every one of them turned into "Missing Property" the first
     * time anything changed.</p>
     */
    @Test
    public void aNodeAddedThroughTheViewKeepsItsPropertyReference() {
        GraphView view = new GraphView();
        view.setNodeLibrary(library, com.crystalgui.widget.graph.NodeWidgetFactory.of(library).build(),
                ShaderGraphBridge.GLSL_PROMOTION);
        GraphProperty tint = view.getDocument().addProperty(
                GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));

        NodeData data = ShaderPropertyNodes.create(tint, 40f, 40f);
        view.getDocument().addNode(data);
        var node = view.getNodeFactory().create(ShaderPropertyNodes.typeFor(tint), data);
        view.addNode(node, 40f, 40f);

        NodeData stored = view.getDocument().node(node.getNodeId());
        assertNotNull("the node must be in the graphDocument", stored);
        assertEquals("and must still say which property it reads",
                tint.id(), ShaderPropertyNodes.propertyIdOf(stored));
        assertNotNull("so it resolves rather than reading as Missing Property",
                ShaderPropertyNodes.resolve(view.getDocument(), stored));
    }

    /**
     * <b>Renaming a property does not orphan its nodes.</b>
     *
     * <p>The entire reason a node stores the id rather than the name. Retyping likewise — the node's
     * shape is read from the graphDocument at build time, never copied into it.</p>
     */
    @Test
    public void renamingAPropertyKeepsItsNodesWorking() {
        GraphDocument graphDocument = withMaster();
        GraphProperty tint = graphDocument.addProperty(GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));
        graphDocument.addNode(ShaderPropertyNodes.create(tint, 20f, 20f));

        graphDocument.replaceProperty(tint.withName("Base Colour").withReference("_BaseColour"));

        CgShaderEmitter.Result result = compile(graphDocument);
        assertTrue(result.errors().toString(), result.ok());
        assertTrue("the node must now read the NEW reference", result.source().contains("_BaseColour"));
    }

    /**
     * <b>Deleting a property leaves an error node, not a crash and not a silent removal.</b>
     *
     * <p>Same call {@code GraphDocument} makes for an unknown node type. A graph mid-edit is normally
     * broken, so one dangling reference must not cost every other node its preview — and removing the
     * wired nodes outright would destroy connections nobody asked to lose.</p>
     */
    @Test
    public void deletingAPropertyLeavesItsNodesStanding() {
        GraphDocument graphDocument = withMaster();
        GraphProperty tint = graphDocument.addProperty(GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));
        NodeData node = graphDocument.addNode(ShaderPropertyNodes.create(tint, 20f, 20f));

        graphDocument.removeProperty(tint.id());

        assertNotNull("the node must survive", graphDocument.node(node.id()));
        assertNull("but resolve to nothing",
                ShaderPropertyNodes.resolve(graphDocument, graphDocument.node(node.id())));
        CgShaderEmitter.Result result = compile(graphDocument);
        assertTrue("and the graph must still compile: " + result.errors(), result.ok());
    }
}
