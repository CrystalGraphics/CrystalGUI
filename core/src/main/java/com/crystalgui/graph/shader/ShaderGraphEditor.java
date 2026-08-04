package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.ui.elements.graph.NodeWidgetFactory;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.KeywordTokenizer;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.SplitView;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;

import javax.annotation.Nullable;

/**
 * A whole shader graph editor — the node canvas, the GLSL it emits, and the previews — as one widget.
 *
 * <h3>Why this is a widget and not a scene</h3>
 *
 * <p>All of it lived in a harness scene, which meant the <b>only</b> assembled shader graph in existence
 * was owned by a debug tool. Everything here is application behaviour rather than demonstration: which
 * node library the canvas offers, that the emitted source is GLSL and read-only, that a connection change
 * recompiles, that previews attach once there is a window to tick them. A second consumer would have had
 * to copy all of it, and the copy is where the two start disagreeing about what a shader graph is.</p>
 *
 * <p>What stays with a scene is genuinely scene-shaped: buttons, hint text, and where the status line is
 * painted. Those are reported through {@link #onStatusChanged} rather than built here.</p>
 *
 * <h3>Previews attach on first layout, not on construction</h3>
 *
 * <p>{@link ShaderGraphPreviews#attach()} registers a frame ticker, so it needs a window — and at
 * construction there is none. The scene that owned this had two booleans and a per-frame check to manage
 * that. Doing it from {@link #onLayoutChanged()} makes it the widget's own business, the same way
 * {@code ListView} starts its ticker: by the time layout has run, the element is attached by definition.</p>
 */
public class ShaderGraphEditor extends UIElement {

    public static final String SPLIT_CLASS = "__shader-split__";
    public static final String GRAPH_CLASS = "__shader-graph__";
    public static final String SOURCE_CLASS = "__shader-source__";

    /** How much of the width the canvas takes. A generated shader is sometimes the thing you are reading
     * and sometimes just confirmation, which is why the divider is draggable rather than fixed. */
    private static final float GRAPH_PERCENT = 80f;

    /** One compile's summary, or its first error — whatever a status line should say. */
    public final Signal.Value<String> onStatusChanged = new Signal.Value<>();

    /**
     * Which node emitted the line the caret is on, as {@code "line 12 emitted by cg:Math/Basic/multiply"}.
     *
     * <p>The payoff of the emitter's line map, and the reason it exists: a driver reports an error at a
     * line in code the user never wrote, and this turns that into somewhere to go and look.</p>
     */
    public final Signal.Value<String> onLineOwnerChanged = new Signal.Value<>();

    private final CgShaderNodeRegistry shaderNodes = CgShaderNodeRegistry.builtins();
    private final CgMasterNode master = new CgMasterNode();

    private final GraphView graph = new GraphView();
    private final TextEditor source = new TextEditor();
    private final SplitView split = new SplitView();
    private final NodeTypeRegistry library;

    private final ShaderGraphPreviews previews;
    private final MainPreviewPanel mainPreview;

    private boolean previewsAttached;
    private boolean mainPreviewAttached;

    @Nullable
    private CgShaderEmitter.Result lastCompile;

    public ShaderGraphEditor() {
        graph.addClass(GRAPH_CLASS);
        source.addClass(SOURCE_CLASS);
        split.addClass(SPLIT_CLASS);

        // The library IS the shader node set -- the create menu, its search and the widget factory all
        // come from one bridge call, so there is no shader-specific UI code anywhere below this line.
        library = ShaderGraphBridge.asNodeLibrary(shaderNodes);
        graph.setNodeLibrary(library, NodeWidgetFactory.of(library).build(),
                ShaderGraphBridge.GLSL_PROMOTION);

        source.setReadOnly(true);
        // The generated file IS GLSL, so it gets the GLSL language and tokenizer rather than being shown
        // as plain text. Colours come from the user-agent sheet, which styles what a tokenizer publishes.
        source.setLanguage(Language.glsl());
        source.setTokenizer(KeywordTokenizer.glsl());
        source.onSelectionChanged.connect(this::reportLineOwner);

        split.setPercentage(GRAPH_PERCENT);
        // Either pane collapsed to nothing is a state with no way back -- the divider would have no width
        // left to grab.
        split.setLimits(20f, 95f);
        split.first().addChild(graph);
        split.second().addChild(source);
        addInternalChild(split);

        // A connection is a discrete user action, so this needs no debouncing; a per-keystroke trigger
        // would (6.3.8).
        graph.onConnectionsChanged.connect(this::recompile);

        previews = new ShaderGraphPreviews(graph, shaderNodes, master);
        // A dropdown on a node changes the emitted GLSL but not the graph's SHAPE, so
        // onConnectionsChanged never fires for it -- without this the source pane silently shows the
        // previous variant.
        previews.onPropertyChanged.connect(this::recompile);

        // Over the canvas, not beside it: addOverlay puts it in the viewport rather than on the plane, so
        // it stays put while the graph pans underneath -- which is what "floating preview" means.
        // Deliberately NOT promoted to the top layer, which would put it above every dialog too.
        mainPreview = new MainPreviewPanel(graph.getDocument(), shaderNodes, master);
        graph.addOverlay(mainPreview);

        recompile();
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public GraphView graph() {
        return graph;
    }

    /** The generated GLSL, read-only. */
    public TextEditor source() {
        return source;
    }

    public NodeTypeRegistry library() {
        return library;
    }

    /** The last emit, or null before the first compile. */
    @Nullable
    public CgShaderEmitter.Result lastCompile() {
        return lastCompile;
    }

    // ── Compiling ───────────────────────────────────────────────────────────────────────────────

    /**
     * Maps the document to the compiler's IR, emits, and shows the result.
     *
     * <p>Errors are reported rather than swallowed: a graph that cannot compile is the <b>normal</b> state
     * while one is being built, so this is a status message and not an error path.</p>
     */
    public void recompile() {
        CgShaderEmitter.Result result =
                ShaderGraphBridge.compile(graph.getDocument(), shaderNodes, master);
        lastCompile = result;

        source.setText(result.source().isEmpty()
                ? "// nothing to compile yet\n" + String.join("\n", result.errors())
                : result.source());

        onStatusChanged.emit(result.ok()
                ? String.format("compiled  %dn/%de  %d chars  %d varyings  %d mapped lines",
                        graph.getDocument().nodeCount(), graph.getDocument().edges().size(),
                        result.source().length(), result.varyings().size(), result.lineOwners().size())
                : result.errors().size() + " error(s): " + result.errors().get(0));
    }

    private void reportLineOwner() {
        if (lastCompile == null) return;
        int line = source.caretPoint().row() + 1;
        String owner = lastCompile.ownerOfLine(line);
        if (owner == null) return;
        var node = graph.getDocument().node(owner);
        onLineOwnerChanged.emit("line " + line + " emitted by "
                + (node == null ? owner : node.typeId() + "  (" + owner + ")"));
    }

    // ── Content ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Seeds the canvas with a small working graph: {@code Color * Time} into the master, plus the three
     * geometry inputs left unwired.
     *
     * <p>Opt-in rather than automatic, because an editor opening a saved document must not have anything
     * in it. It exercises dynamic widening ({@code vec4 * float}) and an engine builtin, so the emitted
     * source shows a compiler-inserted cast rather than a straight copy — and the three unconnected nodes
     * are exactly the ones a preview system exists to show, none of which needs wiring for its thumbnail
     * to be the point.</p>
     */
    public ShaderGraphEditor addStarterGraph() {
        GraphNode colour = addNode(library.get("cg:Input/Basic/color"), 20f, 30f);
        GraphNode time = addNode(library.get("cg:Input/Basic/time"), 20f, 150f);
        GraphNode multiply = addNode(library.get("cg:Math/Basic/multiply"), 240f, 60f);
        GraphNode output = addNode(library.get(ShaderGraphBridge.MASTER_TYPE), 470f, 60f);

        graph.connect(colour.getOutputPorts().get(0), multiply.getInputPorts().get(0));
        graph.connect(time.getOutputPorts().get(0), multiply.getInputPorts().get(1));
        graph.connect(multiply.getOutputPorts().get(0), output.getInputPorts().get(1));

        addNode(library.get("cg:Input/Geometry/uv"), 20f, 330f);
        addNode(library.get("cg:Input/Geometry/position"), 240f, 330f);
        addNode(library.get("cg:Input/Geometry/normal"), 460f, 330f);

        recompile();
        return this;
    }

    /** Builds a widget for a library type and places it, keeping the document binding the factory does. */
    public GraphNode addNode(NodeType type, float x, float y) {
        GraphNode node = graph.getNodeFactory().create(type, type.create(x, y));
        graph.addNode(node, x, y);
        return node;
    }

    public ShaderGraphEditor fitToContent() {
        graph.fitToContent(24f);
        return this;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────

    /**
     * Attaches the preview renderers once there is a window to tick them.
     *
     * <p>{@code attach()} registers a frame ticker and builds GL resources, neither of which exists at
     * construction. Layout is the earliest point at which the element is attached <em>by definition</em>,
     * which is what makes this the right hook rather than a flag the caller has to remember to poll —
     * {@code ListView} starts its own ticker the same way and for the same reason.</p>
     *
     * <p>The main preview reports whether it succeeded and is retried until it does: it needs the GL
     * context, and on the very first layout there may not be one yet.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (getAttachedWindow() == null) return;
        if (!previewsAttached) {
            previews.attach();
            previewsAttached = true;
        }
        if (!mainPreviewAttached) mainPreviewAttached = mainPreview.attach();
    }

    /** Releases the preview renderers' GL resources. Safe to call more than once. */
    public void delete() {
        if (previewsAttached) {
            previews.delete();
            previewsAttached = false;
        }
        mainPreviewAttached = false;
    }
}
