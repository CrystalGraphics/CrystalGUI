package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgraphics.shadergraph.CgShaderGraph;
import com.crystalgraphics.shadergraph.CgShaderNode;
import com.crystalgraphics.shadergraph.CgShaderNodeProperty;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgraphics.shadergraph.CgShaderPort;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PortSpec;
import com.crystalgui.graph.TypeCompatibility;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps the editor's {@link GraphDocument} onto CrystalGraphics' {@link CgShaderGraph}, and compiles it.
 *
 * <h3>Why a bridge rather than one shared model</h3>
 * <p>{@code GraphDocument} is a <b>general-purpose</b> typed graph — a dialogue graph and a shader graph
 * are both documents, and it knows about neither GLSL nor widgets. {@code CgShaderGraph} is the
 * compiler's input and lives in CrystalGraphics, which <b>cannot depend on CrystalGUI</b>: the
 * dependency has always run the other way.</p>
 *
 * <p>So the mapping lives here, on the side that already depends on both. The compiler never learns what
 * a widget is, the document never learns what GLSL is, and a server can compile a graph with no UI
 * library present at all.</p>
 *
 * <h3>⚠ This class reaches CrystalGraphics CORE, and its package is otherwise headless</h3>
 * <p>{@code com.crystalgui.graph} is deliberately free of CrystalGraphics so a dedicated server can
 * author and validate a document with no GL library present — {@code headlessTest} asserts that by
 * excluding CG core from its classpath entirely. This subpackage is the exception: a shader
 * <em>compiler</em> is CrystalGraphics' by definition, so anything that compiles needs it.</p>
 *
 * <p>Class loading is lazy, so the exception is contained as long as nothing headless references this
 * type. <b>Do not call into this from {@code com.crystalgui.graph} proper</b>, or the first headless
 * test to touch a document will fail with {@code NoClassDefFoundError} — which reports the missing CG
 * class rather than the import that dragged it in.</p>
 *
 * <h3>The type ids line up by construction</h3>
 * <p>A {@code PortSpec.typeId} in a document is a plain string, and this bridge writes the GLSL name into
 * it when it builds the editor's {@link NodeType}s — so {@code "vec3"} on the editor side and
 * {@code CgShaderType.VEC3} on the compiler side are the same thing spelled once.</p>
 */
public final class ShaderGraphBridge {

    /** The document {@code typeId} of the master node, and the id its instance takes. */
    public static final String MASTER_TYPE = "cg:master";

    private ShaderGraphBridge() {
    }

    // ── Editor side: a library the create menu can offer ────────────────────

    /**
     * Builds a {@link NodeTypeRegistry} mirroring {@code shaderNodes}, so the create menu, the search
     * and the widget factory all work with no shader-specific code.
     *
     * <p>The master is included, because a graph needs somewhere to end — and because leaving it out
     * would mean the one node every graph must have is the one the menu cannot create.</p>
     */
    public static NodeTypeRegistry asNodeLibrary(CgShaderNodeRegistry shaderNodes) {
        registerPortTypes();
        // Here rather than at a call site, for the same reason the port types are: building a shader
        // node library is the moment the shader domain's vocabulary has to exist, and a colour or
        // vector field silently falling back to a GLSL text box is the kind of miss nobody reports as
        // a bug. NodeFieldWidgets no longer has a generic default for either — see its class javadoc —
        // so skipping this line is now a visible regression (a text field) rather than a silent one.
        ShaderColorFieldWidget.install();
        ShaderVectorFieldWidget.install();
        NodeTypeRegistry library = new NodeTypeRegistry();
        for (CgShaderNode node : shaderNodes.all()) library.register(asNodeType(node));
        library.register(asNodeType(new CgMasterNode()));
        return library;
    }

    /**
     * One shader node as an editor node type. Category comes from the id's own namespace.
     *
     * <h4>Properties and port literals both become {@link com.crystalgui.graph.NodeField}s</h4>
     * <p>Which is the whole point of that type existing: a {@code Space} dropdown and a typed-in
     * {@code Color.Value} are the same thing placed differently, so neither needs shader-specific control
     * code. An unconnected input with a default expression gets an inline editor automatically, and the
     * value it writes IS the literal the compiler reads for that port.</p>
     */
    public static NodeType asNodeType(CgShaderNode node) {
        NodeType.Builder builder = NodeType.of(node.id()).label(node.label()).category(categoryOf(node.id()));
        for (CgShaderPort port : node.ports()) {
            if (!port.isInput()) {
                builder.out(port.id(), typeIdOf(port));
                continue;
            }
            NodeField editor = portFieldFor(port);
            if (editor == null) builder.in(port.id(), typeIdOf(port));
            else builder.in(port.id(), typeIdOf(port), editor);
        }
        for (CgShaderNodeProperty property : node.properties()) {
            if (property.isEnumerated()) {
                builder.field(NodeField.enumOf(property.id(), property.label(),
                        property.options().toArray(new String[0])));
            } else {
                // A value property: the widget is chosen from the GLSL TYPE, by the same mapping ports
                // use. CrystalGraphics never says "swatch" — it says vec4, and this side decides what a
                // vec4 deserves to be edited with.
                builder.field(new NodeField(property.id(), property.label(),
                        widgetKindFor(property.type()), java.util.List.of(),
                        property.defaultValue(), null));
            }
        }
        return builder.build();
    }

    /**
     * The inline editor for an unconnected input, or null when it has no default to edit.
     *
     * <p>Kind is chosen from the GLSL type so a better widget can be registered later — a colour picker
     * for {@code vec4}, a drag-number for {@code float} — without any node changing.</p>
     */
    @Nullable
    private static NodeField portFieldFor(CgShaderPort port) {
        String defaultExpression = port.defaultExpression();
        if (defaultExpression == null || defaultExpression.isEmpty()) return null;
        // An engine expression is not a value anyone picks — see CgShaderPort.engineDefault.
        if (!port.defaultIsLiteral()) return null;
        NodeField.Kind kind = widgetKindFor(port.type());
        // Dynamic, matrices and samplers: no meaningful literal to type, so no editor rather than a text
        // box that accepts something the compiler will reject.
        if (kind == null) return null;
        return new NodeField(port.id(), port.id(), kind, java.util.List.of(), defaultExpression, null);
    }

    /**
     * The widget a GLSL type deserves — the <b>one</b> place that decision is made.
     *
     * <p>Shared by ports and value properties so a {@code vec4} is edited the same way wherever it
     * appears, and so registering a colour picker for {@link NodeField.Kind#COLOR} later upgrades both at
     * once. This mapping is CrystalGUI's alone: CrystalGraphics only ever says what the type is.</p>
     */
    @Nullable
    private static NodeField.Kind widgetKindFor(com.crystalgraphics.shadergraph.CgShaderType type) {
        switch (type) {
            case VEC4:
            case VEC3:
                return NodeField.Kind.COLOR;
            case VEC2:
                return NodeField.Kind.VECTOR;
            case BOOL:
                return NodeField.Kind.BOOLEAN;
            case FLOAT:
            case INT:
                // A dynamic port's literal default (Add/Multiply's "0.0"/"1.0") is always a plain scalar
                // regardless of what the port eventually resolves to — Unity's own Add/Multiply show a
                // plain float knob too, never something that changes shape before anything is wired.
            case DYNAMIC:
                return NodeField.Kind.NUMBER;
            default:
                return null;
        }
    }

    /**
     * The document type id of a dynamic port — a real type, not a stand-in.
     *
     * <p>It was briefly presented as {@code float}, on the reasoning that a port the editor cannot
     * colour is worse than one typed loosely. That is exactly backwards: {@code float} is the
     * <em>narrowest</em> numeric type, so under GLSL's own rule (a scalar promotes, nothing demotes) a
     * dynamic port typed {@code float} <b>refuses every vector</b>. Wiring a Color into a Multiply was
     * silently rejected and the graph compiled without it — a connection that just does not happen,
     * with nothing to read anywhere.</p>
     */
    public static final String DYNAMIC_TYPE = "dynamic";

    /**
     * A {@link com.crystalgui.ui.elements.graph.PortType} carrying GLSL's promotion rule.
     *
     * <p><b>There are two compatibility checks and they are not the same one.</b> The document's
     * {@link TypeCompatibility} governs {@code GraphDocument.connect}; a widget drag goes through
     * {@code GraphView.canConnect}, which asks the <b>PortType</b>. Registering the rule in only one of
     * them produces the worst kind of failure: a connection that is simply refused, with no wire, no
     * error and nothing to read anywhere. That is exactly what happened — the starter graph reported
     * {@code 4n/0e} because {@code BasicPortType} defaults to identity and a {@code vec4} could not
     * reach a {@code dynamic} port.</p>
     */
    public record GlslPortType(String id, int arity) implements
            com.crystalgui.ui.elements.graph.PortType {

        /**
         * A {@code dynamic} port is presented as a <b>float</b> until something resolves it otherwise.
         *
         * <p>The same reasoning as its arity being 1 rather than 0: unwired, this port really will compile
         * as a float, so showing it in the palette's "unknown" grey states something untrue and makes the
         * one node type a user meets first — {@code Add}, {@code Multiply} — the only grey thing on the
         * canvas. Unity colours it as the scalar and recolours on connection, which is exactly what
         * {@link ShaderPortArity} then does from here.</p>
         *
         * <p>Done on the TYPE rather than left to that resolver because a freshly created node has no
         * connections, so nothing would fire to correct it — it would sit grey until its first wire.
         * {@code type-dynamic} is left in {@code graph.css} for consumers whose port types genuinely
         * cannot resolve; a GLSL one always can.</p>
         */
        @Override
        public String cssClass() {
            return DYNAMIC_TYPE.equals(id)
                    ? com.crystalgui.ui.elements.graph.PortType.CSS_CLASS_PREFIX + "float"
                    : com.crystalgui.ui.elements.graph.PortType.super.cssClass();
        }

        @Override
        public boolean isCompatibleWith(com.crystalgui.ui.elements.graph.PortType other) {
            if (other == null) return false;
            // Dynamic has no type until the compiler resolves one from what is connected, so refusing
            // here would refuse the connection that decides it.
            if (DYNAMIC_TYPE.equals(id) || DYNAMIC_TYPE.equals(other.id())) return true;
            return id.equals(other.id()) || "float".equals(id);
        }
    }

    /**
     * Registers a {@link GlslPortType} for every type the shader nodes use.
     *
     * <p>Called by {@link #asNodeLibrary}, because a library whose ports cannot be wired together is not
     * a library. Idempotent — {@code PortTypeRegistry.register} tolerates a repeat.</p>
     */
    public static void registerPortTypes() {
        // Arity 1, not 0 — a dynamic port reads "(1)" before anything is wired into it, exactly as
        // Unity's unwired Multiply reads A(1) B(1) Out(1). 0 would mean "no width worth printing" (what
        // a texture or sampler says) and suppressed the suffix entirely, so a Multiply showed a bare
        // "A B Out" until it was connected. float is also the compiler's own fallback for an unresolved
        // dynamic port, so the label and the emitted GLSL agree from the start rather than only once a
        // wire lands. ShaderPortArity widens it from here as the graph is built.
        register(new GlslPortType(DYNAMIC_TYPE, 1));
        register(new GlslPortType("float", 1));
        register(new GlslPortType("vec2", 2));
        register(new GlslPortType("vec3", 3));
        register(new GlslPortType("vec4", 4));
        register(new GlslPortType("bool", 1));
        register(new GlslPortType("int", 1));
    }

    private static void register(GlslPortType type) {
        if (com.crystalgui.ui.elements.graph.PortTypeRegistry.get(type.id()) == null) {
            com.crystalgui.ui.elements.graph.PortTypeRegistry.register(type);
        }
    }

    private static String typeIdOf(CgShaderPort port) {
        return port.type() == com.crystalgraphics.shadergraph.CgShaderType.DYNAMIC
                ? DYNAMIC_TYPE : port.type().glsl();
    }

    /**
     * Every path segment but the last, title-cased and rejoined.
     *
     * <p>{@code cg:math/basic/add} → {@code Math/Basic}; {@code cg:input/basic/vector4} →
     * {@code Input/Basic}; {@code cg:master} → {@code Output}.</p>
     *
     * <p>Nested rather than just the first segment, because the menu is a tree and Unity's is two deep —
     * {@code Input ▸ Basic ▸ Vector 4}. Taking only the head would pile every input node into one flat
     * list, which is exactly the thing a tree was built to avoid.</p>
     */
    private static String categoryOf(String id) {
        int colon = id.indexOf(':');
        String path = colon < 0 ? id : id.substring(colon + 1);
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return "Output";

        StringBuilder category = new StringBuilder();
        for (String segment : path.substring(0, lastSlash).split("/")) {
            if (segment.isEmpty()) continue;
            if (category.length() > 0) category.append('/');
            category.append(Character.toUpperCase(segment.charAt(0))).append(segment.substring(1));
        }
        return category.toString();
    }

    /**
     * GLSL's own promotion rule, in the form the document wants — a scalar feeds any vector, nothing
     * demotes.
     *
     * <p>Declared once here rather than at each call site so the create menu offers exactly what a drag
     * would accept, and both agree with what {@code CgShaderType.canFeed} will do at compile time.</p>
     */
    public static final TypeCompatibility GLSL_PROMOTION = (from, to) -> {
        if (from == null || to == null) return false;
        // A dynamic port accepts anything and feeds anything — it has no type until the compiler
        // resolves one from what is actually connected, so refusing here would refuse the very
        // connection that decides it.
        if (DYNAMIC_TYPE.equals(from) || DYNAMIC_TYPE.equals(to)) return true;
        return from.equals(to) || from.equals("float");
    };

    // ── Compiler side: document to IR ───────────────────────────────────────

    /**
     * Converts a document into the compiler's IR.
     *
     * <p>Nodes whose {@code typeId} is not in {@code shaderNodes} are <b>skipped rather than fatal</b> —
     * the same reasoning that makes an unknown type open as a placeholder instead of eating the graph.
     * The result is a shader missing that branch, which the user can see, rather than an exception.</p>
     *
     * @return the IR, or {@code null} when the document has no master node to compile toward
     */
    @Nullable
    public static CgShaderGraph toShaderGraph(GraphDocument document, CgShaderNodeRegistry shaderNodes,
                                              CgMasterNode master) {
        CgShaderGraph graph = new CgShaderGraph();
        Map<String, Boolean> present = new LinkedHashMap<>();
        String masterId = null;

        for (NodeData data : document.nodes()) {
            CgShaderNode type = MASTER_TYPE.equals(data.typeId()) ? master : shaderNodes.get(data.typeId());
            if (type == null) {
                present.put(data.id(), false);
                continue;
            }
            if (type == master) masterId = data.id();
            graph.add(new CgShaderGraph.Instance(data.id(), type, inputValuesOf(data),
                    propertyValuesOf(data, type)));
            present.put(data.id(), true);
        }
        if (masterId == null) return null;

        for (EdgeData edge : document.edges()) {
            if (!Boolean.TRUE.equals(present.get(edge.from().nodeId()))) continue;
            if (!Boolean.TRUE.equals(present.get(edge.to().nodeId()))) continue;
            graph.link(edge.from().nodeId(), edge.from().portId(),
                    edge.to().nodeId(), edge.to().portId());
        }
        return graph.output(masterId);
    }

    /**
     * The literal values on a node's unconnected inputs.
     *
     * <p>A document stores properties as {@code String}, which is already a GLSL literal for every type
     * the five built-ins use — so this is a filter, not a conversion. A property keyed by a port name is
     * that port's value; anything else is node state the compiler has no use for.</p>
     */
    private static Map<String, String> inputValuesOf(NodeData data) {
        Map<String, String> values = new LinkedHashMap<>();
        for (PortSpec port : data.ports()) {
            if (!port.direction().isInput()) continue;
            String value = data.properties().get(port.portId());
            if (value != null && !value.isEmpty()) values.put(port.portId(), value);
        }
        return values;
    }

    /**
     * The node's chosen values for the compile-time properties its type declares.
     *
     * <p>Read from the same {@code NodeData.properties} map as port literals, which is safe because the
     * two are looked up by different keys: {@link #inputValuesOf} only reads keys matching an
     * <em>input port</em> id, and this only reads keys matching a <em>declared property</em> id. A node
     * declaring a port and a property with the same name would collide — so don't.</p>
     */
    private static Map<String, String> propertyValuesOf(NodeData data,
                                                        com.crystalgraphics.shadergraph.CgShaderNode type) {
        Map<String, String> values = new LinkedHashMap<>();
        for (var property : type.properties()) {
            String chosen = data.properties().get(property.id());
            if (chosen != null && !chosen.isEmpty()) values.put(property.id(), chosen);
        }
        return values;
    }

    /** Document to finished {@code .shader} source, in one call. */
    public static CgShaderEmitter.Result compile(GraphDocument document,
                                                 CgShaderNodeRegistry shaderNodes,
                                                 CgMasterNode master) {
        CgShaderGraph graph = toShaderGraph(document, shaderNodes, master);
        if (graph == null) {
            return new CgShaderEmitter.Result("", java.util.List.of(),
                    java.util.List.of("The graph has no Output node, so there is nothing to compile"));
        }
        return CgShaderEmitter.emit(graph, master);
    }
}
