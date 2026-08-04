package com.crystalgui.graph.shader;

import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeField;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PropertyEdits;
import com.crystalgui.graph.PortSpec;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.config.ConfigControl;
import com.crystalgui.ui.elements.config.ConfigDescriptor;
import com.crystalgui.ui.elements.config.Configurator;
import com.crystalgui.ui.elements.config.ConfiguratorGroup;
import com.crystalgui.ui.elements.config.ConfiguratorPanel;
import com.crystalgui.ui.elements.graph.GraphConnection;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.NodePort;
import com.crystalgui.ui.elements.graph.GraphSelection;
import com.crystalgui.ui.elements.graph.GraphView;
import com.crystalgui.ui.elements.graph.NodeFieldBinder;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Node Settings tab — what is selected, and everything about it that can be changed.
 *
 * <p>Unity reference: marker <b>F</b> in {@code docs/research/unity-inspector/07-full-window.png}, and
 * {@code 01-inspector-property.png} for the caption-then-rows form.</p>
 *
 * <h3>A third placement for {@link NodeField}, not a second control layer</h3>
 * <p>The rows here are built by {@code NodeFieldWidgets} and written by {@link NodeFieldBinder} — the
 * exact widgets and the exact writer the node's own body controls and its port editors already use. The
 * research set puts it best: <em>a dropdown left of a port and a dropdown in an inspector row are one
 * widget in two hosts</em>.</p>
 *
 * <p>Everything that behaviour needs therefore already works and is not reimplemented here: undo,
 * gesture bracketing so a scrub is one step, live recompile, and the two-way follow that makes Ctrl+Z
 * update the widget as well as the document. Editing a value here moves the on-node editor and vice
 * versa, because they are two bindings of one field and both follow the document.</p>
 *
 * <h3>Port defaults stay visible when connected — a deliberate divergence</h3>
 * <p>On the node a port's editor <em>vanishes</em> once something is wired into it, which is what
 * {@code nodeport:blank} exists to express and is right there: the literal is genuinely unused and the
 * space belongs to the wire. In an inspector that would be wrong. A control that disappears is
 * indistinguishable from one that never existed, and the inspector is precisely where you go to find out
 * what a node has. So the row stays, disabled, saying what is connected to it instead.</p>
 */
public class ShaderNodeInspector extends ConfiguratorPanel {

    public static final String PANEL_CLASS = "__node-inspector__";

    /** Shown when nothing is selected. A blank panel reads as broken; a sentence reads as a state. */
    public static final String EMPTY_MESSAGE = "Nothing selected";

    private final GraphView graph;
    private final NodeTypeRegistry library;

    @Nullable
    private final Runnable onChange;

    @Nullable
    private final BlackboardPanel board;

    /** What the last rebuild was showing, so a redundant one can be skipped. @see #refresh */
    private String shownKey = "";

    public ShaderNodeInspector(GraphView graph, NodeTypeRegistry library, @Nullable Runnable onChange) {
        this(graph, library, onChange, null);
    }

    /**
     * @param board the property board whose selection this also reflects, or null for a graph with none
     */
    public ShaderNodeInspector(GraphView graph, NodeTypeRegistry library, @Nullable Runnable onChange,
                               @Nullable BlackboardPanel board) {
        this.graph = graph;
        this.library = library;
        this.onChange = onChange;
        this.board = board;
        addClass(PANEL_CLASS);
        graph.getSelection().onChanged.connect(this::refresh);
        // Only LISTENS. Which of the two selections leads is ShaderGraphEditor's business -- it keeps the
        // pill and the nodes pointing at the same property -- and a second opinion here fought it: this
        // panel is built lazily, so its listener ran last and cleared the board the editor had just set,
        // and selecting a property node lit no pill at all.
        if (board != null) board.onPropertySelected.connect(id -> refresh());
        rebuild();
    }

    private GraphDocument document() {
        return graph.getDocument();
    }

    @Nullable
    private UndoStack undo() {
        return graph.undoStack();
    }

    // ── Rebuilding ──────────────────────────────────────────────────────────

    /**
     * Rebuilds only when the selection actually names something different.
     *
     * <p><b>Not an optimisation.</b> A rebuild replaces every control in the panel, and this engine has a
     * standing rule that a widget must never rebuild the elements it is being clicked or dragged on —
     * {@code screenToLocal} goes stale and every later frame of the gesture feeds it garbage, which is
     * how the table header froze. {@link GraphSelection} emits on operations that leave the selection
     * <em>identical</em> (a press on an already-selected node re-asserts it), so without this a drag
     * begun on a selected node would tear down the panel under it.</p>
     */
    public void refresh() {
        String key = selectionKey();
        if (key.equals(shownKey)) return;
        // A live gesture inside this panel is the other half of the same rule. It cannot arise from the
        // canvas today, but a Shift+click while scrubbing an inspector row would, and the failure would
        // be a mid-drag teardown rather than anything that looks like a selection bug.
        if (isAnyControlInteracting()) return;
        shownKey = key;
        rebuild();
    }

    private boolean isAnyControlInteracting() {
        for (ConfigControl control : controls().values()) {
            if (control.isInteracting()) return true;
        }
        return false;
    }

    /** Identity of what is on screen — cheap, and stable across a re-assertion of the same selection. */
    private String selectionKey() {
        GraphSelection selection = graph.getSelection();
        String property = board == null ? null : board.selectedPropertyId();
        // The property first: it is shown INSTEAD of the graph selection, so it has to be part of what
        // identifies the panel's subject or picking one would look like no change at all.
        if (property != null) return "property:" + property;
        if (selection.wire() != null) return "wire:" + System.identityHashCode(selection.wire());
        StringBuilder key = new StringBuilder("nodes");
        for (GraphNode node : selection.nodes()) key.append(':').append(node.getNodeId());
        return key.toString();
    }

    private void rebuild() {
        clearRows();

        GraphProperty property = board == null ? null : board.selectedProperty();
        if (property != null) {
            showProperty(property);
            return;
        }

        GraphSelection selection = graph.getSelection();
        if (selection.wire() != null) {
            showWire(selection.wire());
            return;
        }
        List<GraphNode> nodes = selection.nodes();
        if (nodes.isEmpty()) {
            add(ConfigDescriptor.header(EMPTY_MESSAGE), null);
            return;
        }
        if (nodes.size() == 1) {
            showNode(nodes.get(0));
            return;
        }
        showMany(nodes);
    }

    // ── One node ────────────────────────────────────────────────────────────

    private void showNode(GraphNode widget) {
        String nodeId = widget.getNodeId();
        NodeData data = nodeId == null ? null : document().node(nodeId);
        if (data == null) {
            add(ConfigDescriptor.header(EMPTY_MESSAGE), null);
            return;
        }
        // A PROPERTY NODE IS A REFERENCE, so the thing worth editing is the property, not the node -- it
        // has no fields of its own and its only port is an output. Showing the generic node form gave a
        // header reading `cg:property` and three read-only facts, which is everything except what the
        // user was actually looking at.
        GraphProperty referenced = ShaderPropertyNodes.resolve(document(), data);
        if (referenced != null) {
            showProperty(referenced);
            return;
        }

        NodeType type = library.get(data.typeId());
        add(ConfigDescriptor.header(type == null ? data.typeId() : type.label()), null);

        if (type != null) {
            for (NodeField field : type.fields()) {
                addFieldRow(this, widget, data, field);
            }
        }
        addAbout(data, type);
    }

    /**
     * One field, as an inspector row.
     *
     * <p>Built through {@link NodeFieldBinder#buildControl}, so the write path is the one shared with the
     * node — see the class note. A port field whose port is connected is shown disabled with the source
     * named, rather than hidden.</p>
     */
    private void addFieldRow(UIElement parent, GraphNode widget, NodeData data, NodeField field) {
        EdgeData incoming = field.isPortField()
                ? document().edgeInto(new com.crystalgui.graph.PortRef(data.id(), field.portId()))
                : null;
        if (incoming != null) {
            addConnectedRow(parent, field, incoming);
            return;
        }
        // A DYNAMIC port has no width until the graph gives it one, so the declaration cannot say how
        // many boxes to draw — the resolved answer lives on the port widget. Asking ShaderPortArity is
        // what keeps this row identical to the node's own inline editor; building from the declaration
        // instead showed a single box holding `1` beside a node already drawing `X Y`.
        NodeField shaped = field.isPortField()
                ? ShaderPortArity.fieldFor(field, widget.portNamed(field.portId()),
                        data.properties().get(field.id()))
                : field;

        UIElement control = NodeFieldBinder.buildControl(shaped, document(), data.id(), undo(), onChange,
                shaped == field ? null : shaped.defaultValue());
        if (control instanceof ConfigControl typed) addRow(parent, field.label(), field.id(), typed);
    }

    /**
     * A port that something is wired into: the row says <em>what</em> is wired into it.
     *
     * <h3>The source goes in the CONTROL column, never in the label</h3>
     * <p>It was in the label, appended after the field name, and it truncated to {@code Multiply.O…} on
     * every connected port. The label column is deliberately a fixed 114px — that is what gives a stack of
     * unlike controls a common left edge, and widening it for this one row would ragged-edge every panel
     * in the engine. The control column is already wide, already flexible, and already the place a row
     * puts its <em>value</em> — and for a connected port the source IS the value.</p>
     *
     * <p>The field's own widget is dropped rather than disabled, because it would be showing a literal
     * that nothing reads. A colour swatch under a live wire is not "the current value greyed out", it is
     * a value that has been overridden and is no longer true of anything.</p>
     *
     * <p>Spelled {@code from Multiply.Out}, not with an arrow: the bundled Minecraft fonts have no
     * U+2190, so the arrow drew as a blank advance — the same gap that makes {@code UIText} fall back
     * from {@code …} to {@code ...} for its ellipsis.</p>
     */
    private void addConnectedRow(UIElement parent, NodeField field, EdgeData incoming) {
        NodeData source = document().node(incoming.from().nodeId());
        NodeType sourceType = source == null ? null : library.get(source.typeId());
        String from = (sourceType == null ? incoming.from().nodeId() : sourceType.label())
                + "." + incoming.from().portId();

        addTo(parent, ConfigDescriptor.info(field.id(), field.label())
                .tooltip("Driven by " + from + ", so this port's own value is unused."), "from " + from);
    }

    /**
     * The read-only facts — what this node IS.
     *
     * <p>Worth a group of its own because there is currently nowhere else to look them up: the emitted
     * source reports {@code line 12 emitted by cg:Math/Basic/multiply}, and nothing on screen says which
     * node that is.</p>
     */
    private void addAbout(NodeData data, @Nullable NodeType type) {
        ConfiguratorGroup about = group("About", true);
        addChild(about);
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

    /**
     * A fact, shown as read-only text.
     *
     * <p>{@link ConfigDescriptor.Kind#INFO}, never a disabled {@code TEXT} row. These were text fields,
     * which meant a node's id and its resolved port types drew as editable boxes AND genuinely accepted
     * typing — {@code setEnabled(false)} on the wrapper never reached the field inside it. An inspector
     * inviting the user to edit a fact and then discarding what they typed is worse than one that simply
     * does not offer.</p>
     */
    private void readOnly(ConfiguratorGroup group, String label, String value) {
        addTo(group.content(), ConfigDescriptor.info(label, label), value);
    }

    // ── A property ──────────────────────────────────────────────────────────

    /**
     * The Blackboard form: what a property is called, what it is called in the shader, what it starts as,
     * and whether a material may edit it.
     *
     * <p>Unity reference: {@code docs/research/unity-blackboard/12-property-vector2-settings.png}. Its
     * {@code Precision} and {@code Override Property Declaration} rows are deliberately absent — no
     * precision modes are emitted and nothing would read either, which is the same test 6.3.11 used to
     * reject six master ports. A row that changes nothing is worse than a missing one.</p>
     */
    private void showProperty(GraphProperty property) {
        add(ConfigDescriptor.header("Property: " + property.name()), null);

        Configurator name = addTo(this, ConfigDescriptor.text("property.name", "Name")
                .tooltip("What the Blackboard and its nodes show."), property.name());
        if (name != null) {
            name.control().changed.connect(value ->
                    editProperty(property.id(), p -> p.withName(String.valueOf(value))));
        }

        Configurator reference = addTo(this, ConfigDescriptor.text("property.reference", "Reference")
                .tooltip("The uniform's name in the generated shader. Sanitised on entry."),
                property.reference());
        if (reference != null) {
            reference.control().changed.connect(value ->
                    editProperty(property.id(), p -> p.withReference(String.valueOf(value))));
        }

        // TYPED, and that is why the form cannot be a fixed list of rows: two boxes for a Vector 2, a
        // swatch for a Colour, a checkbox for a Boolean. See ShaderPropertyForm.
        Configurator value = addTo(this, ShaderPropertyForm.describeDefault(property)
                .tooltip("What a material starts with when it has said nothing else."),
                ShaderPropertyForm.readDefault(property));
        if (value != null) {
            value.control().changed.connect(raw -> {
                String literal = ShaderPropertyForm.writeDefault(property, raw);
                if (literal != null) editProperty(property.id(), p -> p.withDefaultValue(literal));
            });
        }

        Configurator exposed = addTo(this, ConfigDescriptor.bool("property.exposed", "Exposed")
                .tooltip("Whether a material inspector offers it. It is a uniform either way."),
                property.exposed());
        if (exposed != null) {
            exposed.control().changed.connect(value2 ->
                    editProperty(property.id(), p -> p.withExposed(Boolean.TRUE.equals(value2))));
        }

        ConfiguratorGroup about = group("About", true);
        addChild(about);
        readOnly(about, "Type", BlackboardPanel.displayTypeOf(property));
        readOnly(about, "Wire type", property.typeId());
        readOnly(about, "Property id", property.id());
    }

    /**
     * Applies an edit to a property, undoably.
     *
     * <p>Re-read by id rather than closing over the record: the panel may have rebuilt since the row was
     * built, and writing a stale copy back would silently undo whatever changed in between.</p>
     */
    private void editProperty(String propertyId, java.util.function.UnaryOperator<GraphProperty> edit) {
        GraphProperty current = document().property(propertyId);
        if (current == null) return;
        PropertyEdits.Change change = PropertyEdits.Change.of(document(), edit.apply(current));
        if (change == null || !change.changesAnything()) return;

        UndoStack undo = undo();
        if (undo != null) undo.execute(change); else change.apply();
        if (onChange != null) onChange.run();
    }

    // ── A wire ──────────────────────────────────────────────────────────────

    private void showWire(GraphConnection wire) {
        add(ConfigDescriptor.header("Connection"), null);
        ConfiguratorGroup about = new ConfiguratorGroup("About", true);
        addChild(about);
        readOnly(about, "From", describe(wire.from()));
        readOnly(about, "To", describe(wire.to()));
    }

    /** {@code Multiply.Out}, from the port's own widget — the type name a wire's colour is read from. */
    private String describe(NodePort port) {
        GraphNode owner = port.node();
        NodeData data = owner == null || owner.getNodeId() == null ? null
                : document().node(owner.getNodeId());
        NodeType type = data == null ? null : library.get(data.typeId());
        String node = type != null ? type.label() : data != null ? data.typeId() : "?";
        return node + "." + port.getPortId();
    }

    // ── Several nodes ───────────────────────────────────────────────────────

    /**
     * A multi-selection.
     *
     * <p>When every node is the same type the shared fields are editable and one write reaches all of
     * them as a single undo step — Unity does this, and without it selecting two nodes makes the panel
     * useless. A mixed selection gets a count per type instead, because there is no field they all agree
     * on and inventing one would be guessing.</p>
     */
    private void showMany(List<GraphNode> nodes) {
        add(ConfigDescriptor.header(nodes.size() + " nodes selected"), null);

        Map<String, Integer> byType = new LinkedHashMap<>();
        for (GraphNode node : nodes) {
            NodeData data = node.getNodeId() == null ? null : document().node(node.getNodeId());
            if (data != null) byType.merge(data.typeId(), 1, Integer::sum);
        }
        if (byType.size() != 1) {
            ConfiguratorGroup group = group("Selection", true);
            addChild(group);
            for (Map.Entry<String, Integer> entry : byType.entrySet()) {
                NodeType type = library.get(entry.getKey());
                readOnly(group, type == null ? entry.getKey() : type.label(),
                        String.valueOf(entry.getValue()));
            }
            return;
        }
        addSharedFields(nodes, byType.keySet().iterator().next());
    }

    private void addSharedFields(List<GraphNode> nodes, String typeId) {
        NodeType type = library.get(typeId);
        if (type == null) return;
        for (NodeField field : type.fields()) {
            addMultiRow(nodes, field);
        }
    }

    /**
     * One field, writing to every selected node at once.
     *
     * <p>The row shows the first node's value, which is what every inspector does with a multi-selection
     * and is honest enough: the write applies to all of them regardless of what they held before, so the
     * displayed value is a starting point rather than a claim that they agree.</p>
     */
    private void addMultiRow(List<GraphNode> nodes, NodeField field) {
        GraphNode first = nodes.get(0);
        String firstId = first.getNodeId();
        if (firstId == null) return;

        UIElement control = NodeFieldBinder.buildMultiControl(field, document(), idsOf(nodes), firstId,
                undo(), onChange);
        if (!(control instanceof ConfigControl typed)) return;
        addRow(this, field.label(), field.id(), typed);
    }

    private List<String> idsOf(List<GraphNode> nodes) {
        return nodes.stream().map(GraphNode::getNodeId).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * This panel, as the surface a host docks.
     *
     * <p>Returns {@code this} — the class <b>is</b> a {@link ConfiguratorPanel}, and the accessor exists
     * so callers name the seam rather than the inheritance.</p>
     */
    public ConfiguratorPanel panel() {
        return this;
    }
}
