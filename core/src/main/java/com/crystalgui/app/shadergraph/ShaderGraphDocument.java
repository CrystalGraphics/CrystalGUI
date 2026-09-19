package com.crystalgui.app.shadergraph;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgraphics.shadergraph.CgShaderProblem;
import com.crystalgui.app.shadergraph.node.ShaderPropertyNodes;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.document.DocumentModel;
import com.crystalgui.fs.Resource;
import com.crystalgui.graph.EdgeData;
import com.crystalgui.graph.GraphCodecs;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.graph.PortDirection;
import com.crystalgui.graph.PortRef;
import com.crystalgui.graph.PortSpec;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.google.gson.JsonParser;

/**
 * One {@code .shadergraph} file: the graph, its history, the shader it compiles to and what is wrong with it. Every
 * {@link ShaderGraphView} showing the file — a split, a torn-out window — is a view of this, with its own camera and
 * selection over the same graph, and an edit made in one is in all of them.
 *
 * <pre>{@code
 * ShaderGraphDocument document = new ShaderGraphDocument();
 * document.adopt(bytes);                                  // a file's content; a blank file opens with a starter graph
 * ShaderGraphView left = new ShaderGraphView(document);
 * ShaderGraphView right = new ShaderGraphView(document);  // the same graph, compiled once
 * }</pre>
 *
 * <ul>
 *   <li>Compiles itself: {@link #lastCompile()} and {@link #diagnostics()} are current whether or not a view is open.</li>
 *   <li>What only a view can see — the driver refusing the shader, a node's preview failing — a view reports in with
 *       {@link #reportDriverError} and {@link #reportPreviewFailures}.</li>
 * </ul>
 */
public final class ShaderGraphDocument implements DocumentModel {

    /**
     * The four independent producers of an opinion about this graph — {@code DiagnosticSet} owners.
     *
     * <p>They are the {@code source} each diagnostic already carried, promoted to the thing that decides
     * what a write replaces. Four producers into one flat list meant whichever wrote last erased the rest,
     * which is why they had to be merged by hand on every compile. @see #publishProblems</p>
     */
    private static final String OWNER_EMITTER = "shadergraph";
    private static final String OWNER_DRIVER = "glsl";
    private static final String OWNER_PREVIEW = "shadergraph.preview";
    private static final String OWNER_GRAPH = "shadergraph.graph";

    private final GraphDocument graph = new GraphDocument();

    /** One history for every view of this file, so an undo in either pane reverses what either did. */
    private final UndoStack history = new UndoStack();

    private final ShaderGraphServices shader = ShaderGraphServices.of(graph);

    private final DiagnosticSet problems = new DiagnosticSet();

    private int version;

    private final Signal.Action onChanged = new Signal.Action();

    /** A file was opened into this document — the graph was replaced wholesale. @see #adopt */
    public final Signal.Action onDidAdopt = new Signal.Action();

    /** {@link #resource()} moved. */
    public final Signal.Action onDidChangeResource = new Signal.Action();

    @Nullable
    private Resource resource;

    /** What a view last reported the driver saying. @see #reportDriverError */
    @Nullable
    private String driverError;

    /** @see #reportPreviewFailures */
    private Map<String, List<CgShaderProblem>> previewFailures = Map.of();

    public ShaderGraphDocument() {
        // Explicit, like every command set in this engine -- a registry that quietly acquired declarations nobody
        // asked for surprises anything that walks it. Idempotent, since registering replaces.
        ShaderGraphSettings.register();
        // THE HISTORY IS THE CHANGE LOG. Every document change goes through an Edit by construction, so "something
        // was pushed, undone or redone" is exactly "the content changed" -- and pan, zoom and selection, which are
        // view state, cannot make the file dirty.
        history.onChanged.connect(() -> {
            version++;
            onChanged.emit();
        });
        // UNCONDITIONALLY, not only while a view is in front: a diagnostic set belongs to the document, so a graph
        // compiling in the background keeps its problems current for whenever its tab returns.
        shader.compiled.connect(this::publishProblems);
        shader.compileIfStale();
    }

    public GraphDocument graph() {
        return graph;
    }

    /** The shader domain's shared state for this graph: the node set, the master node, the compile. */
    public ShaderGraphServices shader() {
        return shader;
    }

    /** The node types a view offers, the one shader node set. */
    public NodeTypeRegistry library() {
        return ShaderNodeLibrary.of(shader.nodes());
    }

    /** The compiler-side master node. Written only at compile time — see {@link ShaderGraphSettings}. */
    public CgMasterNode master() {
        return shader.master();
    }

    /** The last emit, or null before the first compile. */
    @Nullable
    public CgShaderEmitter.Result lastCompile() {
        return shader.lastResult();
    }

    /** Compiles now — for a change the graph's own shape does not show, a dropdown. */
    public void recompile() {
        shader.requestRecompile();
    }

    // ── Identity ────────────────────────────────────────────────────────────────────────────────

    /**
     * Which file this graph is, or null before it has been told — set by the kind's model factory. The derived
     * resource of the generated source is built from it, and each view's Blackboard is named after it.
     */
    @Nullable
    public Resource resource() {
        return resource;
    }

    public ShaderGraphDocument setResource(@Nullable Resource resource) {
        if (Objects.equals(this.resource, resource)) return this;
        this.resource = resource;
        onDidChangeResource.emit();
        return this;
    }

    // ── As a DocumentModel ──────────────────────────────────────────────────────────────────────

    @Override
    public Signal.Action onChanged() {
        return onChanged;
    }

    /**
     * Monotonic, and bumped from the one place a graph changes — its undo stack.
     *
     * <p><b>A counter rather than an encode-and-compare.</b> Dirtiness is {@code version() != savedVersion()}, so a
     * graph that recompiles every frame costs nothing to ask about.</p>
     */
    @Override
    public int version() {
        return version;
    }

    @Override
    public UndoStack history() {
        return history;
    }

    /** The graph as it stands, in the serialized form {@link GraphCodecs#DOCUMENT} defines. */
    @Override
    public byte[] encode() {
        return GraphCodecs.DOCUMENT.encode(JsonOps.INSTANCE, graph).toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Loads a graph file into this document, which every view and panel stays bound to.
     *
     * <p>Not undoable, and the history is cleared: a file is the starting state, not something the user did.</p>
     *
     * <p><b>A malformed file throws, and is meant to.</b> Accepting the bytes and showing an empty canvas would be
     * far worse than refusing: the editor would then differ from the file it failed to read, report itself modified,
     * and the first Save All would write that emptiness over the user's work.</p>
     *
     * <p><b>A blank file is not a malformed one</b> — {@code New File…} creates every file with {@code ""} — and it
     * opens with the starter graph, as Unity, Godot and Blender seed a new shader. A saved graph that genuinely has
     * no nodes comes back empty: seeding that would re-add nodes the user deleted on purpose.</p>
     */
    @Override
    public void adopt(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // THE VERSION MOVES, because adopting is a change to the document -- and the store marks it clean
        // immediately afterwards, which is what makes "loaded, untouched" the same state as "just saved".
        version++;
        boolean blank = text.trim().isEmpty();
        GraphDocument loaded = blank
                ? new GraphDocument()
                : GraphCodecs.DOCUMENT.decode(JsonOps.INSTANCE, new JsonParser().parse(text));
        graph.replaceWith(loaded);
        if (blank) addStarterGraph();
        history.clear();
        onDidAdopt.emit();
        shader.requestRecompile();
    }

    /**
     * Seeds the graph with a small working one: {@code Color * Time} into the master, plus the three geometry inputs
     * left unwired — they are what a preview system exists to show. Straight into the graph and not through the
     * history: nobody performed these edits, so a first Ctrl+Z must not unpick them.
     */
    public ShaderGraphDocument addStarterGraph() {
        NodeTypeRegistry library = library();
        NodeData colour = place(library, "cg:Input/Basic/color", 20f, 30f);
        NodeData time = place(library, "cg:Input/Basic/time", 20f, 150f);
        NodeData multiply = place(library, "cg:Math/Basic/multiply", 240f, 60f);
        NodeData output = place(library, ShaderGraphBridge.MASTER_TYPE, 470f, 60f);
        wire(colour, 0, multiply, 0);
        wire(time, 0, multiply, 1);
        wire(multiply, 0, output, 1);
        place(library, "cg:Input/Geometry/uv", 20f, 330f);
        place(library, "cg:Input/Geometry/position", 240f, 330f);
        place(library, "cg:Input/Geometry/normal", 460f, 330f);
        shader.requestRecompile();
        return this;
    }

    private NodeData place(NodeTypeRegistry library, String typeId, float x, float y) {
        return graph.addNode(library.get(typeId).create(x, y));
    }

    /** Restored rather than connected: these wires are known good, and validation belongs to the author's hand. */
    private void wire(NodeData from, int output, NodeData to, int input) {
        graph.restoreEdge(new EdgeData(new PortRef(from.id(), port(from, PortDirection.OUTPUT, output)),
                new PortRef(to.id(), port(to, PortDirection.INPUT, input))));
    }

    private static String port(NodeData node, PortDirection direction, int index) {
        int seen = 0;
        for (PortSpec port : node.ports()) {
            if (port.direction() != direction) continue;
            if (seen++ == index) return port.portId();
        }
        throw new IllegalStateException(node.typeId() + " has no " + direction + " port " + index);
    }

    // ── What is wrong with it ───────────────────────────────────────────────────────────────────

    /** What is wrong with this graph, from all four producers. @see #publishProblems */
    @Override
    public DiagnosticSet diagnostics() {
        return problems;
    }

    /**
     * What the driver said about the last shader, from a view whose preview compiled it — or null when it accepted
     * it. A material compiles lazily on its first bind, so the verdict arrives a frame after the compile.
     */
    public void reportDriverError(@Nullable String error) {
        if (Objects.equals(driverError, error)) return;
        driverError = error;
        republish();
    }

    /** Nodes whose own thumbnail will not compile, from a view's previews. */
    public void reportPreviewFailures(Map<String, List<CgShaderProblem>> failures) {
        if (previewFailures.equals(failures)) return;
        previewFailures = Map.copyOf(failures);
        republish();
    }

    private void republish() {
        CgShaderEmitter.Result last = shader.lastResult();
        if (last != null) publishProblems(last);
    }

    /**
     * The compiler's problems, as diagnostics — what the Problems panel shows for a graph.
     *
     * <p>A graph problem is about a <b>node</b>, not a row: there is no text for it to point at until the driver
     * rejects the generated source. So the range is {@code Diagnostic.NO_POSITION} and the node id travels in
     * {@code code}, which is where LSP puts a reporter's own identity for a complaint.</p>
     *
     * <p>Four owners, one announcement: {@code changeAll} is what keeps a Problems panel bound to this from
     * rebuilding once per producer on every compile.</p>
     */
    private void publishProblems(CgShaderEmitter.Result result) {
        List<Diagnostic> emitter = new ArrayList<>();
        for (CgShaderProblem problem : result.problems()) {
            emitter.add(new Diagnostic(Diagnostic.NO_POSITION, Diagnostic.NO_POSITION,
                    problem.isError() ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING,
                    problem.message(), OWNER_EMITTER, problem.nodeId()));
        }
        Map<String, List<Diagnostic>> byOwner = new LinkedHashMap<>();
        byOwner.put(OWNER_EMITTER, emitter);
        byOwner.put(OWNER_DRIVER, driverProblems(result));
        byOwner.put(OWNER_PREVIEW, previewProblems());
        byOwner.put(OWNER_GRAPH, graphWarnings());
        problems.changeAll(byOwner);
    }

    /**
     * The driver's refusal of the generated source, mapped back to the node that wrote the line. A driver reports
     * {@code 0(278) : error C1503}, and 278 is a line the user never wrote; {@code lineOwners} makes it actionable.
     */
    private List<Diagnostic> driverProblems(CgShaderEmitter.Result result) {
        if (driverError == null) return List.of();
        int line = glslLineOf(driverError);
        String owner = line > 0 ? result.ownerOfLine(line) : null;
        TextPoint at = line > 0 ? new TextPoint(line - 1, 0) : Diagnostic.NO_POSITION;
        return List.of(new Diagnostic(at, at, DiagnosticSeverity.ERROR,
                owner == null ? driverError : driverError + "  (emitted by " + owner + ")", OWNER_DRIVER, owner));
    }

    /**
     * The first line number in a driver message, or -1. {@code 0(278) : error C1503} is NVIDIA's shape and
     * {@code ERROR: 0:278:} is Mesa's; both put the line after a colon or a bracket, and getting it wrong costs the
     * attribution and never the message.
     */
    static int glslLineOf(String message) {
        for (int i = 0; i < message.length(); i++) {
            if (message.charAt(i) != '(' && message.charAt(i) != ':') continue;
            int j = i + 1;
            while (j < message.length() && message.charAt(j) == ' ') j++;
            int digits = j;
            while (digits < message.length() && Character.isDigit(message.charAt(digits))) digits++;
            if (digits == j) continue;
            try {
                return Integer.parseInt(message.substring(j, digits));
            } catch (NumberFormatException tooLong) {
                return -1;
            }
        }
        return -1;
    }

    /** A blank thumbnail with no explanation is indistinguishable from one that has not rendered yet. */
    private List<Diagnostic> previewProblems() {
        List<Diagnostic> out = new ArrayList<>();
        previewFailures.forEach((nodeId, reasons) -> {
            for (CgShaderProblem reason : reasons) {
                out.add(new Diagnostic(Diagnostic.NO_POSITION, Diagnostic.NO_POSITION, DiagnosticSeverity.WARNING,
                        "Preview unavailable: " + reason.message(), OWNER_PREVIEW, nodeId));
            }
        });
        return out;
    }

    /**
     * Warnings about the document rather than errors about the emit. Duplicate property names are the one that
     * matters: they become GLSL uniform names, which must be unique.
     */
    private List<Diagnostic> graphWarnings() {
        List<Diagnostic> out = new ArrayList<>();
        addUnknownNodeProblems(out);
        Set<String> seen = new HashSet<>();
        for (GraphProperty property : graph.properties()) {
            String name = property.name() == null ? "" : property.name().trim();
            if (name.isEmpty()) {
                out.add(warning("A property with no name cannot become a uniform", property.id()));
            } else if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                out.add(warning("Two properties are named '" + name
                        + "' — they become one uniform, and the second wins", property.id()));
            }
        }
        return out;
    }

    /**
     * Nodes this build has no definition for — the "opened without the plugin" case, said out loud. The node
     * survives in the document, and the graph compiles without it: an error, since what is emitted is not what the
     * document says.
     */
    private void addUnknownNodeProblems(List<Diagnostic> into) {
        for (NodeData data : graph.nodes()) {
            String typeId = data.typeId();
            // The two the registry legitimately does not hold: the master is the compiler's own object, and a
            // property node is synthesised from the document's declarations rather than registered.
            if (ShaderGraphBridge.MASTER_TYPE.equals(typeId) || ShaderPropertyNodes.isPropertyNode(data)) continue;
            if (shader.nodes().get(typeId) != null) continue;
            into.add(new Diagnostic(Diagnostic.NO_POSITION, Diagnostic.NO_POSITION, DiagnosticSeverity.ERROR,
                    "No definition for node type '" + typeId + "' in this build — it is kept in the"
                            + " document but left out of the compiled shader",
                    OWNER_GRAPH, data.id()));
        }
    }

    private static Diagnostic warning(String message, String code) {
        return new Diagnostic(Diagnostic.NO_POSITION, Diagnostic.NO_POSITION,
                DiagnosticSeverity.WARNING, message, OWNER_GRAPH, code);
    }
}
