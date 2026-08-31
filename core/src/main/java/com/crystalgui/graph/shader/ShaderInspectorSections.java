package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgPreviewMesh;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.settings.SettingsLayer;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PortRef;
import com.crystalgui.graph.PortSpec;
import com.crystalgui.graph.PropertyEdits;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.core.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.Configurator;
import com.crystalgui.ui.elements.config.SettingsConfigurator;
import com.crystalgui.ui.elements.graph.GraphConnection;
import com.crystalgui.ui.elements.graph.NodeFieldBinder;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphSelection;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.ui.elements.inspector.InspectorForm;
import com.crystalgui.ui.elements.inspector.InspectorRegistry;
import com.crystalgui.ui.elements.inspector.InspectorSection;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * What a shader graph tells the inspector about itself — five sections, no dispatch.
 *
 * <h3>Five {@code accepts}, not one if-chain</h3>
 *
 * <p>This was one {@code ShaderNodeInspector} whose {@code rebuild()} branched: a property, then a wire,
 * then one node, then several. Blender does not do that — a property, a wire and a node are different
 * subjects, and in Blender each would be a panel whose {@code poll()} answers for itself. Splitting them
 * removes the dispatch entirely, and a sixth subject becomes a registration rather than another
 * {@code else if}.</p>
 *
 * <h3>What went to the engine</h3>
 *
 * <p>The rebuild-if-changed key, the "do not rebuild during a gesture" guard, the selection listener and
 * the scrolling panel. All of that is true of <em>every</em> inspectable subject, and each of the four
 * had been written here. What is left is the part that is genuinely about shader graphs.</p>
 */
public final class ShaderInspectorSections {

    private ShaderInspectorSections() {
    }

    public static final String NODE_TAB = "Node";
    public static final String GRAPH_TAB = "Graph";

    /** Registers all five. Idempotent, like every contribution. */
    public static void register() {
        InspectorRegistry.register(new PropertySection());
        InspectorRegistry.register(new NodeSection());
        InspectorRegistry.register(new MultiNodeSection());
        InspectorRegistry.register(new WireSection());
        InspectorRegistry.register(new GraphSettingsSection());
    }

    // ── Shared resolution ───────────────────────────────────────────────────────────────────────

    @Nullable
    private static ShaderGraphEditor editor(DataContext context) {
        return context.get(ShaderGraphEditor.SHADER_GRAPH);
    }

    @Nullable
    private static GraphSelection selection(DataContext context) {
        ShaderGraphEditor editor = editor(context);
        return editor == null ? null : editor.graph().getSelection();
    }

    /** The property the blackboard has selected, which is shown INSTEAD of the graph selection. */
    @Nullable
    private static GraphProperty selectedProperty(DataContext context) {
        ShaderGraphEditor editor = editor(context);
        if (editor == null || editor.blackboard() == null) return null;
        return editor.blackboard().selectedProperty();
    }

    private static List<GraphNode> nodes(DataContext context) {
        GraphSelection selection = selection(context);
        return selection == null ? List.of() : selection.nodes();
    }

    /** A node that merely REFERENCES a property is shown as that property — it has no fields of its own. */
    @Nullable
    private static GraphProperty referencedProperty(DataContext context, GraphNode node) {
        ShaderGraphEditor editor = editor(context);
        if (editor == null || node.getNodeId() == null) return null;
        NodeData data = editor.graph().getDocument().node(node.getNodeId());
        return data == null ? null : ShaderPropertyNodes.resolve(editor.graph().getDocument(), data);
    }

    private static void readOnly(InspectorForm group, String label, String value) {
        group.row(ConfigDescriptor.info(label, label), value);
    }

    // ── What is selected — asked once, answered once ────────────────────────────────────────────

    /**
     * The kinds of thing the {@code Node} tab can describe. <b>Exactly one applies at a time.</b>
     *
     * @see #subject
     */
    private enum Subject { NONE, PROPERTY, WIRE, NODE, MULTI }

    /**
     * <b>Which of the four Node-tab sections applies.</b>
     *
     * <h3>Why this is one method and not four polls</h3>
     *
     * <p>{@link InspectorSection}s in a tab are <b>additive</b> — that is the engine's design, and it is
     * what lets the {@code Graph} tab stack Shader, Preview and Compile into one panel. But the four
     * Node-tab sections are not additions to each other; they are four answers to the single question
     * "what is selected", and nothing in the engine can know that.</p>
     *
     * <p>So each of them used to decide alone, and they disagreed. Three separately re-derived the same
     * exclusion ({@code selectedProperty(context) == null}) and none of them looked at <b>how many</b>
     * things were selected, which produced the bug in both directions: a marquee that caught a wire made
     * {@code MultiNodeSection} <em>and</em> {@code WireSection} both accept, so the tab rendered two
     * stacked panels; and a marquee that caught a property node tripped the property guard, so a
     * ten-node selection was replaced outright by a view of that one property.</p>
     *
     * <p><b>Plural outranks everything</b>, because the multi-selection view is the only one that can
     * describe more than one thing. Demoting it to whichever single item happened to fall inside the band
     * loses the other nine, and does it silently.</p>
     *
     * <p>Adding a fifth kind is a branch here rather than an audit of four {@code accepts()} that have to
     * be kept mutually exclusive by hand — which is the property that was missing.</p>
     */
    private static Subject subject(DataContext context) {
        GraphSelection selection = selection(context);
        int selected = selection == null ? 0 : selection.size();

        if (selected > 1) return Subject.MULTI;
        // Above the emptiness check: the blackboard can have a property selected with nothing selected in
        // the graph at all, which is how you edit a property you have not placed a node for.
        if (PropertySection.resolve(context) != null) return Subject.PROPERTY;
        if (selected == 0) return Subject.NONE;
        return selection.wire() != null ? Subject.WIRE : Subject.NODE;
    }

    // ── One node ────────────────────────────────────────────────────────────────────────────────

    /** A single selected node, with its declared fields and the facts about what it is. */
    private static final class NodeSection implements InspectorSection {

        @Override
        public String tab() {
            return NODE_TAB;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean accepts(DataContext context) {
            // A property node is a reference with no fields of its own, so subject() answers PROPERTY for
            // it and this section never sees it.
            return subject(context) == Subject.NODE;
        }

        @Override
        public String subjectKey(DataContext context) {
            List<GraphNode> nodes = nodes(context);
            return "node:" + (nodes.isEmpty() ? "" : nodes.get(0).getNodeId());
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            ShaderGraphEditor editor = editor(context);
            GraphNode widget = nodes(context).get(0);
            GraphDocument document = editor.graph().getDocument();
            NodeData data = widget.getNodeId() == null ? null : document.node(widget.getNodeId());
            if (data == null) return;

            NodeTypeRegistry library = editor.library();
            NodeType type = library.get(data.typeId());
            form.header(type == null ? data.typeId() : type.label());

            if (type != null) {
                for (NodeField field : type.fields()) {
                    fieldRow(form, editor, widget, data, field);
                }
            }
            about(form, library, data, type);
        }
    }

    /**
     * One field, as a row.
     *
     * <p>Built through {@link NodeFieldBinder#buildControl}, so the write path is the one the node's own
     * inline editor uses. A port that something is wired into shows the source instead of its own value,
     * because a literal nothing reads is not "the value greyed out" — it has been overridden.</p>
     */
    private static void fieldRow(InspectorForm form, ShaderGraphEditor editor, GraphNode widget,
                                 NodeData data, NodeField field) {
        GraphDocument document = editor.graph().getDocument();
        EdgeData incoming = field.isPortField()
                ? document.edgeInto(new PortRef(data.id(), field.portId()))
                : null;
        if (incoming != null) {
            connectedRow(form, editor, field, incoming);
            return;
        }
        // A DYNAMIC port has no width until the graph gives it one, so the declaration cannot say how many
        // boxes to draw -- the resolved answer lives on the port widget. Asking ShaderPortArity is what
        // keeps this row identical to the node's own inline editor.
        NodeField shaped = field.isPortField()
                ? ShaderPortArity.fieldFor(field, widget.portNamed(field.portId()),
                        data.properties().get(field.id()))
                : field;

        UIElement control = NodeFieldBinder.buildControl(shaped, document, data.id(),
                editor.graph().undoStack(), editor::recompile,
                shaped == field ? null : shaped.defaultValue());
        if (control instanceof ConfigControl typed) form.control(field.id(), field.label(), typed);
    }

    /**
     * A port something is wired into: the row says <em>what</em>.
     *
     * <p>The source goes in the CONTROL column, never the label — the label column is a fixed 114px, which
     * is what gives a stack of unlike controls a common left edge, and it truncated every connected port
     * to {@code Multiply.O…}. Spelled {@code from X}, not with an arrow: the bundled fonts have no U+2190
     * and it drew as a blank advance.</p>
     */
    private static void connectedRow(InspectorForm form, ShaderGraphEditor editor, NodeField field,
                                     EdgeData incoming) {
        GraphDocument document = editor.graph().getDocument();
        NodeData source = document.node(incoming.from().nodeId());
        NodeType sourceType = source == null ? null : editor.library().get(source.typeId());
        String from = (sourceType == null ? incoming.from().nodeId() : sourceType.label())
                + "." + incoming.from().portId();

        form.row(ConfigDescriptor.info(field.id(), field.label())
                .tooltip("Driven by " + from + ", so this port's own value is unused."), "from " + from);
    }

    /**
     * The read-only facts — what this node IS.
     *
     * <p>Worth a group because there is nowhere else to look them up: the emitted source reports
     * {@code line 12 emitted by cg:Math/Basic/multiply}, and nothing else on screen says which node that
     * is.</p>
     */
    private static void about(InspectorForm form, NodeTypeRegistry library, NodeData data,
                              @Nullable NodeType type) {
        InspectorForm about = form.group("About", true);
        readOnly(about, "Type", data.typeId());
        if (type != null && !type.category().isEmpty()) readOnly(about, "Category", type.category());
        readOnly(about, "Node id", data.id());

        StringBuilder inputs = new StringBuilder();
        StringBuilder outputs = new StringBuilder();
        for (PortSpec port : data.ports()) {
            StringBuilder into = port.direction().isInput() ? inputs : outputs;
            if (into.length() > 0) into.append(", ");
            into.append(port.portId()).append('(').append(port.typeId()).append(')');
        }
        if (inputs.length() > 0) readOnly(about, "In", inputs.toString());
        if (outputs.length() > 0) readOnly(about, "Out", outputs.toString());
    }

    // ── Several nodes ───────────────────────────────────────────────────────────────────────────

    /**
     * A multi-selection.
     *
     * <p>Same type throughout: the shared fields are editable and one write reaches all of them as a
     * single undo step, which is what Unity does and what makes selecting two nodes useful. Mixed: a count
     * per type, because there is no field they agree on and inventing one would be guessing.</p>
     */
    private static final class MultiNodeSection implements InspectorSection {

        @Override
        public String tab() {
            return NODE_TAB;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean accepts(DataContext context) {
            return subject(context) == Subject.MULTI;
        }

        @Override
        public String subjectKey(DataContext context) {
            StringBuilder key = new StringBuilder("nodes");
            for (GraphNode node : nodes(context)) key.append(':').append(node.getNodeId());
            // The wire counts. It changes what the header says and what Delete will act on, so a key that
            // ignored it would leave the panel reporting the previous contents of the band.
            GraphSelection selection = selection(context);
            if (selection != null && selection.wire() != null) {
                key.append(":wire:").append(System.identityHashCode(selection.wire()));
            }
            return key.toString();
        }

        /**
         * What the header says.
         *
         * <p>Names the wire when one is in the band. A count of nodes alone under-reports the selection
         * that Delete is about to act on, and "3 nodes selected" over a band holding four things is the
         * kind of wrong that is only noticed after the fourth is gone.</p>
         */
        /**
         * How a node is named in the breakdown — {@code name (Type)} for a property reference, and the
         * node type's own label for everything else.
         *
         * <p>Every property node in a graph carries the same {@code typeId}, and the library has no entry
         * for it — so the raw {@code cg:property} was printed, and every property in the selection
         * collapsed into that one row regardless of which property it referenced.</p>
         *
         * <p>The property <b>is</b> that node's identity: it declares no fields of its own, which is the
         * same reason {@code subject()} routes a single one to {@code PropertySection}. So it is named by
         * the property, and two different properties are two rows. The type is worth carrying because the
         * name alone collides with the node type it came from — a {@code Color} property sits next to a
         * {@code Color} node in exactly the selection that prompted this.</p>
         */
        private static String labelOf(DataContext context, ShaderGraphEditor editor, GraphNode node,
                                      NodeData data) {
            GraphProperty property = referencedProperty(context, node);
            if (property != null) return property.name() + " (" + property.displayType() + ")";
            NodeType type = editor.library().get(data.typeId());
            return type == null ? data.typeId() : type.label();
        }

        private static String describe(int nodeCount, boolean hasWire) {
            String nodesPart = nodeCount + (nodeCount == 1 ? " node" : " nodes");
            return hasWire ? nodesPart + " and 1 wire selected" : nodesPart + " selected";
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            ShaderGraphEditor editor = editor(context);
            GraphDocument document = editor.graph().getDocument();
            List<GraphNode> nodes = nodes(context);
            GraphSelection selection = selection(context);
            form.header(describe(nodes.size(), selection != null && selection.wire() != null));

            Map<String, Integer> byLabel = new LinkedHashMap<>();
            Set<String> typeIds = new LinkedHashSet<>();
            for (GraphNode node : nodes) {
                NodeData data = node.getNodeId() == null ? null : document.node(node.getNodeId());
                if (data == null) continue;
                typeIds.add(data.typeId());
                byLabel.merge(labelOf(context, editor, node, data), 1, Integer::sum);
            }

            // The EDITABLE path needs one node type that actually declares fields.
            //
            // It used to gate on `byType.size() == 1` and then bail on a null library lookup, which put a
            // selection of nothing but property nodes in the worst place: they all share the typeId
            // `cg:property`, so the breakdown was skipped as homogeneous, and the library has no entry
            // for that id, so the field loop returned immediately -- leaving a bare header and no
            // indication of what was selected at all.
            NodeType type = typeIds.size() == 1 ? editor.library().get(typeIds.iterator().next()) : null;
            if (type == null || byLabel.size() != 1 || type.fields().isEmpty()) {
                InspectorForm group = form.group("Selection", true);
                for (Map.Entry<String, Integer> entry : byLabel.entrySet()) {
                    readOnly(group, entry.getKey(), String.valueOf(entry.getValue()));
                }
                // The wire belongs in the inventory, not only in the header. This group is what Delete is
                // about to act on, and a list that names eight of nine things is worse than no list.
                if (selection != null && selection.wire() != null) readOnly(group, "Wire", "1");
                return;
            }

            List<String> ids = new ArrayList<>();
            for (GraphNode node : nodes) {
                if (node.getNodeId() != null) ids.add(node.getNodeId());
            }
            String firstId = nodes.get(0).getNodeId();
            if (firstId == null) return;

            for (NodeField field : type.fields()) {
                // The row shows the first node's value, which is what every inspector does with a
                // multi-selection: the write applies to all of them regardless, so it is a starting point
                // rather than a claim that they agree.
                UIElement control = NodeFieldBinder.buildMultiControl(field, document, ids, firstId,
                        editor.graph().undoStack(), editor::recompile);
                if (control instanceof ConfigControl typed) {
                    form.control(field.id(), field.label(), typed);
                }
            }
        }
    }

    // ── A wire ──────────────────────────────────────────────────────────────────────────────────

    private static final class WireSection implements InspectorSection {

        @Override
        public String tab() {
            return NODE_TAB;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean accepts(DataContext context) {
            return subject(context) == Subject.WIRE;
        }

        @Override
        public String subjectKey(DataContext context) {
            GraphSelection selection = selection(context);
            return "wire:" + (selection == null || selection.wire() == null ? ""
                    : System.identityHashCode(selection.wire()));
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            ShaderGraphEditor editor = editor(context);
            GraphConnection wire = selection(context).wire();
            form.header("Connection");
            InspectorForm about = form.group("About", true);
            readOnly(about, "From", describe(editor, wire.from()));
            readOnly(about, "To", describe(editor, wire.to()));
        }

        /** {@code Multiply.Out}, from the port's own widget. */
        private static String describe(ShaderGraphEditor editor, NodePort port) {
            GraphNode owner = port.node();
            NodeData data = owner == null || owner.getNodeId() == null ? null
                    : editor.graph().getDocument().node(owner.getNodeId());
            NodeType type = data == null ? null : editor.library().get(data.typeId());
            String node = type != null ? type.label() : data != null ? data.typeId() : "?";
            return node + "." + port.getPortId();
        }
    }

    // ── A blackboard property ───────────────────────────────────────────────────────────────────

    /**
     * The Blackboard form: what a property is called, what it is called in the shader, what it starts as,
     * and whether a material may edit it.
     *
     * <p>Unity reference: {@code docs/research/unity-blackboard/12-property-vector2-settings.png}. Its
     * {@code Precision} and {@code Override Property Declaration} rows are deliberately absent — no
     * precision modes are emitted and nothing would read either. A row that changes nothing is worse than
     * a missing one.</p>
     */
    private static final class PropertySection implements InspectorSection {

        @Override
        public String tab() {
            return NODE_TAB;
        }

        @Override
        public int order() {
            return 10;
        }

        @Override
        public boolean accepts(DataContext context) {
            return subject(context) == Subject.PROPERTY;
        }

        @Override
        public String subjectKey(DataContext context) {
            GraphProperty property = resolve(context);
            return "property:" + (property == null ? "" : property.id());
        }

        @Nullable
        private static GraphProperty resolve(DataContext context) {
            GraphProperty selected = selectedProperty(context);
            if (selected != null) return selected;
            List<GraphNode> nodes = nodes(context);
            return nodes.size() == 1 ? referencedProperty(context, nodes.get(0)) : null;
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            ShaderGraphEditor editor = editor(context);
            GraphProperty property = resolve(context);
            if (property == null) return;
            GraphDocument document = editor.graph().getDocument();
            UndoStack undo = editor.graph().undoStack();

            form.header("Property: " + property.name());

            bind(form.row(ConfigDescriptor.text("property.name", "Name")
                            .tooltip("What the Blackboard and its nodes show."), property.name()),
                    value -> edit(editor, property.id(), p -> p.withName(String.valueOf(value))));

            bind(form.row(ConfigDescriptor.text("property.reference", "Reference")
                            .tooltip("The uniform's name in the generated shader. Sanitised on entry."),
                            property.reference()),
                    value -> edit(editor, property.id(), p -> p.withReference(String.valueOf(value))));

            // TYPED, which is why this cannot be a fixed list of rows: two boxes for a Vector 2, a swatch
            // for a Colour, a checkbox for a Boolean. See ShaderPropertyForm.
            bind(form.row(ShaderPropertyForm.describeDefault(property)
                            .tooltip("What a material starts with when it has said nothing else."),
                            ShaderPropertyForm.readDefault(property)),
                    raw -> {
                        String literal = ShaderPropertyForm.writeDefault(property, raw);
                        if (literal != null) {
                            edit(editor, property.id(), p -> p.withDefaultValue(literal));
                        }
                    });

            bind(form.row(ConfigDescriptor.bool("property.exposed", "Exposed")
                            .tooltip("Whether a material inspector offers it. It is a uniform either way."),
                            property.exposed()),
                    value -> edit(editor, property.id(),
                            p -> p.withExposed(Boolean.TRUE.equals(value))));

            InspectorForm about = form.group("About", true);
            readOnly(about, "Type", BlackboardPanel.displayTypeOf(property));
            readOnly(about, "Wire type", property.typeId());
            readOnly(about, "Property id", property.id());
        }

        private static void bind(@Nullable Configurator row, java.util.function.Consumer<Object> onChange) {
            if (row != null) row.control().changed.connect(onChange::accept);
        }

        /**
         * Applies an edit undoably, re-reading by id rather than closing over the record.
         *
         * <p>The panel may have rebuilt since the row was built, and writing a stale copy back would
         * silently undo whatever changed in between.</p>
         */
        private static void edit(ShaderGraphEditor editor, String propertyId,
                                 UnaryOperator<GraphProperty> change) {
            GraphDocument document = editor.graph().getDocument();
            GraphProperty current = document.property(propertyId);
            if (current == null) return;
            PropertyEdits.Change edit = PropertyEdits.Change.of(document, change.apply(current));
            if (edit == null || !edit.changesAnything()) return;

            UndoStack undo = editor.graph().undoStack();
            if (undo != null) undo.execute(edit); else edit.apply();
            editor.recompile();
            InspectorRegistry.subjectChanged();
        }
    }

    // ── The graph itself ────────────────────────────────────────────────────────────────────────

    /** Document settings, the preview's view state, and what the last emit produced. */
    private static final class GraphSettingsSection implements InspectorSection {

        @Override
        public String tab() {
            return GRAPH_TAB;
        }

        @Override
        public boolean accepts(DataContext context) {
            return editor(context) != null;
        }

        @Override
        public String subjectKey(DataContext context) {
            ShaderGraphEditor editor = editor(context);
            if (editor == null) return "";
            // The DOCUMENT only. Including the compile result was a mistake with a sharp edge: it is a
            // fresh object per emit, so an animated graph -- one with a Time node -- produced a new key
            // every frame and rebuilt the whole inspector continuously, destroying the tabs faster than
            // they could be clicked. A subject key must identify WHAT is shown, never how fresh it is.
            return "graph:" + System.identityHashCode(editor.graph().getDocument());
        }

        @Override
        public void build(InspectorForm form, DataContext context) {
            ShaderGraphEditor editor = editor(context);
            GraphDocument document = editor.graph().getDocument();

            form.header("Shader");
            SettingsConfigurator.build(form.panel(), document.settings(), SettingsLayer.DOCUMENT,
                    ShaderGraphSettings.all(), editor.graph().undoStack());

            preview(form, editor.mainPreview());
            compile(form, document, editor.lastCompile());
        }

        /** Not saved with the graph — view state, in the sense the document/view boundary means. */
        private static void preview(InspectorForm form, @Nullable MainPreviewPanel preview) {
            if (preview == null) return;
            InspectorForm group = form.group("Preview");

            List<String> meshes = new ArrayList<>();
            for (CgPreviewMesh shape : CgPreviewMesh.values()) meshes.add(shape.label());

            Configurator mesh = group.row(ConfigDescriptor.select("preview.mesh", "Mesh", meshes)
                    .tooltip("Which shape the Main Preview draws. Not saved with the graph."),
                    preview.mesh().label());
            if (mesh != null) {
                mesh.control().changed.connect(value -> {
                    CgPreviewMesh chosen = meshNamed(String.valueOf(value));
                    if (chosen != null) preview.setMesh(chosen);
                });
            }

            Configurator lit = group.row(ConfigDescriptor.bool("preview.lighting", "Lighting")
                    .tooltip("Viewport shading, not a lighting model — see CgShaderEmitter.Shading."),
                    preview.isLit());
            if (lit != null) {
                lit.control().changed.connect(value -> preview.setLit(Boolean.TRUE.equals(value)));
            }
        }

        @Nullable
        private static CgPreviewMesh meshNamed(String label) {
            for (CgPreviewMesh shape : CgPreviewMesh.values()) {
                if (shape.label().equals(label)) return shape;
            }
            return null;
        }

        /**
         * What the last emit produced.
         *
         * <p>Every number already exists on {@code CgShaderEmitter.Result} and was otherwise visible only
         * as a status line the next message overwrites. A graph that will not compile is the
         * <em>normal</em> state while one is being built, so the error count is worth somewhere
         * permanent.</p>
         *
         * <p>Plain rows now, rebuilt when {@link #subjectKey} moves. They used to be a {@code List} of
         * {@code Configurator}s poked by <b>index</b> — {@code setStat(3, …)} — which is a binding held
         * together by counting.</p>
         */
        private static void compile(InspectorForm form, GraphDocument document,
                                    @Nullable CgShaderEmitter.Result result) {
            InspectorForm group = form.group("Compile", true);
            // INFO, not a disabled TEXT row: a compile count is a fact, and a text field drew it as
            // something to type into -- which it also genuinely was, since disabling the wrapper never
            // reached the field inside it.
            readOnly(group, "Nodes", String.valueOf(document.nodeCount()));
            readOnly(group, "Edges", String.valueOf(document.edges().size()));
            readOnly(group, "Varyings", result == null ? "—" : String.valueOf(result.varyings().size()));
            readOnly(group, "Characters", result == null ? "—" : String.valueOf(result.source().length()));
            readOnly(group, "Errors", result == null ? "—" : String.valueOf(result.errors().size()));
        }
    }
}
