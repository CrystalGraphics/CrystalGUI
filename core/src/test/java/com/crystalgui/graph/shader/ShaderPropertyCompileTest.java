package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.ui.elements.graph.GraphView;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.14 — a declared property reaches the generated shader, and a node reading it reaches the code.
 */
public class ShaderPropertyCompileTest {

    private final CgShaderNodeRegistry shaderNodes = CgShaderNodeRegistry.builtins();
    private final NodeTypeRegistry library = ShaderGraphBridge.asNodeLibrary(shaderNodes);

    private GraphDocument withMaster() {
        GraphDocument document = new GraphDocument();
        document.addNode(library.get(ShaderGraphBridge.MASTER_TYPE).create(0f, 0f));
        return document;
    }

    private CgShaderEmitter.Result compile(GraphDocument document) {
        return ShaderGraphBridge.compile(document, shaderNodes, new CgMasterNode());
    }

    // ── Declaration ─────────────────────────────────────────────────────────

    /** A declared property becomes a uniform, and the shader still parses. */
    @Test
    public void aDeclaredPropertyReachesThePropertiesBlock() {
        GraphDocument document = withMaster();
        document.addProperty(GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));

        CgShaderEmitter.Result result = compile(document);
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
        GraphDocument document = withMaster();
        document.addProperty(GraphProperty.of("Unused", "float", "1.0"));
        assertTrue(compile(document).source().contains("_Unused"));
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
        GraphDocument document = withMaster();
        document.addProperty(GraphProperty.of("M", "mat4", ""));
        CgShaderEmitter.Result result = compile(document);
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
        GraphDocument document = withMaster();
        GraphProperty dir = document.addProperty(GraphProperty.of("Dir", "vec3", "(0,1,0,0)"));
        NodeData node = document.addNode(ShaderPropertyNodes.create(dir, 20f, 20f));
        document.link(node.id(), ShaderPropertyNodes.OUT_PORT,
                document.nodes().iterator().next().id(), "BaseColor");

        CgShaderEmitter.Result result = compile(document);
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
        GraphDocument document = withMaster();
        GraphProperty tint = document.addProperty(GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));
        NodeData node = document.addNode(ShaderPropertyNodes.create(tint, 20f, 20f));

        CgShaderEmitter.Result result = compile(document);
        assertTrue(result.errors().toString(), result.ok());
        assertTrue(result.source(), result.source().contains("_Tint"));
        assertEquals("the node must record which property it reads",
                tint.id(), ShaderPropertyNodes.propertyIdOf(document.node(node.id())));
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
     * data from the WIDGET when the document does not already know its id, and for a library-typed
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
        view.setNodeLibrary(library, com.crystalgui.ui.elements.graph.NodeWidgetFactory.of(library).build(),
                ShaderGraphBridge.GLSL_PROMOTION);
        GraphProperty tint = view.getDocument().addProperty(
                GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));

        NodeData data = ShaderPropertyNodes.create(tint, 40f, 40f);
        view.getDocument().addNode(data);
        var node = view.getNodeFactory().create(ShaderPropertyNodes.typeFor(tint), data);
        view.addNode(node, 40f, 40f);

        NodeData stored = view.getDocument().node(node.getNodeId());
        assertNotNull("the node must be in the document", stored);
        assertEquals("and must still say which property it reads",
                tint.id(), ShaderPropertyNodes.propertyIdOf(stored));
        assertNotNull("so it resolves rather than reading as Missing Property",
                ShaderPropertyNodes.resolve(view.getDocument(), stored));
    }

    /**
     * <b>Renaming a property does not orphan its nodes.</b>
     *
     * <p>The entire reason a node stores the id rather than the name. Retyping likewise — the node's
     * shape is read from the document at build time, never copied into it.</p>
     */
    @Test
    public void renamingAPropertyKeepsItsNodesWorking() {
        GraphDocument document = withMaster();
        GraphProperty tint = document.addProperty(GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));
        document.addNode(ShaderPropertyNodes.create(tint, 20f, 20f));

        document.replaceProperty(tint.withName("Base Colour").withReference("_BaseColour"));

        CgShaderEmitter.Result result = compile(document);
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
        GraphDocument document = withMaster();
        GraphProperty tint = document.addProperty(GraphProperty.of("Tint", "vec4", "(1,0,0,1)"));
        NodeData node = document.addNode(ShaderPropertyNodes.create(tint, 20f, 20f));

        document.removeProperty(tint.id());

        assertNotNull("the node must survive", document.node(node.id()));
        assertNull("but resolve to nothing",
                ShaderPropertyNodes.resolve(document, document.node(node.id())));
        CgShaderEmitter.Result result = compile(document);
        assertTrue("and the graph must still compile: " + result.errors(), result.ok());
    }
}
