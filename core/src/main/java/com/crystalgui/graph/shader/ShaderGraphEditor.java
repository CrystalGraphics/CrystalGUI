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
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.editor.TextEditor;
import com.crystalgui.ui.elements.graph.GraphCommands;
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
 * <h3>This widget IS the canvas; {@link #source()} is a panel it owns but does not contain</h3>
 *
 * <p>The two used to share an internal {@code SplitView}, which is a layout decision — and layout is the
 * <em>host's</em>, not a widget's. A host that docks its panels wants the generated GLSL to be a tab like
 * every other tab: draggable to another pane, closable, restorable from a saved arrangement. It cannot be
 * any of those while it is nailed beside the canvas inside one element.</p>
 *
 * <p>So {@code source()} hands back a {@link TextEditor} this widget keeps compiled and up to date and
 * <b>never parents</b>. Whoever wants it on screen adds it wherever it belongs — {@code CrystalEditor}
 * gives it a dock panel of its own titled {@code compiled_graph.shader}. A host that adds it nowhere still
 * gets a working editor; the emit simply has no viewer, which is a legitimate configuration rather than a
 * broken one.</p>
 *
 * <h3>Previews attach on first layout, not on construction</h3>
 *
 * <p>{@link ShaderGraphPreviews#attach()} registers a frame ticker, so it needs a window — and at
 * construction there is none. The scene that owned this had two booleans and a per-frame check to manage
 * that. Doing it from {@link #onLayoutChanged()} makes it the widget's own business, the same way
 * {@code ListView} starts its ticker: by the time layout has run, the element is attached by definition.</p>
 */
public class ShaderGraphEditor extends UIElement {

    /** UNIQUE, never the shared "__content__" -- see ProjectFileTree.CONTENT_CLASS for why. */
    public static final String CONTENT_CLASS = "__shader-content__";
    public static final String GRAPH_CLASS = "__shader-graph__";

    /** On {@link #source()}, which this widget does NOT contain — so the rule for it is tag-qualified
     * rather than a descendant of {@code shadergrapheditor}. See the class note. */
    public static final String SOURCE_CLASS = "__shader-source__";

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

    /** Kept compiled and never parented — see the class note on why this widget does not contain it. */
    private final TextEditor source = new TextEditor();

    /** Marked internal exactly ONCE, while empty -- see the constructor for what stamping a populated
     * subtree cost. */
    private final UIElement content = new UIElement();
    private final NodeTypeRegistry library;

    private final ShaderGraphPreviews previews;
    private final MainPreviewPanel mainPreview;
    private final BlackboardPanel blackboard;

    private boolean previewsAttached;
    private boolean mainPreviewAttached;

    @Nullable
    private CgShaderEmitter.Result lastCompile;

    public ShaderGraphEditor() {
        // Explicit, like every command set in this engine -- a registry that quietly acquired
        // declarations nobody asked for surprises anything that walks it, and a generated settings panel
        // is precisely such a thing. Idempotent, since registering replaces.
        ShaderGraphSettings.register();

        graph.addClass(GRAPH_CLASS);
        source.addClass(SOURCE_CLASS);

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

        // THE WRAPPER IS MARKED INTERNAL WHILE EMPTY; the graph is an ordinary child of it.
        //
        // addInternalChild(graph) would be the obvious line and it is what hung both scenes.
        // markAsInternal() RECURSES, so it stamps the GraphView, its canvas and every node and preview
        // under them -- and removeChild/clearAllChildren SILENTLY REFUSE internal children. The previews
        // add and retire a thumbnail per node as the graph changes, so every retirement was declined, the
        // tree grew without bound, and layout took longer every frame until the window stopped
        // responding. The thread dump was pure Taffy, which reads as a layout cycle and is really an
        // unbounded tree.
        //
        // Same fix as QuickPick and ProblemsPanel, for the same reason, which is why the wrapper exists
        // rather than a comment saying "do not stamp this".
        content.addClass(CONTENT_CLASS);
        addInternalChild(content);
        content.addChild(graph);

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

        // The Blackboard is the SECOND consumer of the overlay seam, which is what makes it worth
        // having been a seam. Same viewport placement, same clamp -- see CanvasOverlayMove.
        blackboard = new BlackboardPanel(graph.getDocument(), "shader_graph", graph.undoStack());
        blackboard.onPropertySelected.connect(id -> {
            // Two selection sources, one inspector subject: picking a property clears the node
            // selection so the two cannot both claim to be what is being inspected.
            if (id != null) graph.getSelection().clear();
        });
        graph.addOverlay(blackboard);

        recompile();
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }

    public GraphView graph() {
        return graph;
    }

    /**
     * The generated GLSL, read-only — <b>detached</b>, for a host to place.
     *
     * <p>Recompiled in place, so a caller may parent it once and never ask again. It is not a child of
     * this widget: see the class note.</p>
     */
    public TextEditor source() {
        return source;
    }

    public NodeTypeRegistry library() {
        return library;
    }

    /** The floating preview, so a second host can share its view state rather than keep a copy. */
    public MainPreviewPanel mainPreview() {
        return mainPreview;
    }

    /** The floating property board. @see BlackboardPanel */
    public BlackboardPanel blackboard() {
        return blackboard;
    }

    /** The compiler-side master. Written only at compile time — see {@link ShaderGraphSettings}. */
    public com.crystalgraphics.shadergraph.CgMasterNode master() {
        return master;
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
     * Registers the attach ticker once there is a window. <b>Registration only — never the attach.</b>
     *
     * <p>{@code onLayoutChanged} runs <em>inside</em> {@code calculateLayout()}'s
     * {@code while (isLayoutDirty())} loop, and attaching the previews adds elements. Doing it here
     * re-dirties the tree on every pass, so the loop never terminates and the window hangs before it
     * paints a frame. It is not a slow frame — it is an infinite one.</p>
     *
     * <p>{@code ListView} looks like a precedent for doing work in this hook and is not one: what it calls
     * here is {@code ensureTicking()}, and the realisation happens in {@code tickFrame}. The rule is the
     * same one the fold arrows and the caret follow — <b>structural changes belong outside the layout
     * pass</b>.</p>
     */
    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        if (ticking || getAttachedWindow() == null) return;
        ticking = true;
        getAttachedWindow().registerTicker(this::attachPreviews);
    }

    private boolean ticking;

    /**
     * Attaches the preview renderers, retrying until they take.
     *
     * <p>Runs from a frame ticker, which is ahead of layout and therefore free to change the tree. The
     * main preview reports whether it succeeded and is retried because it needs the GL context, which may
     * not exist on the first frame; {@code registerTicker} is idempotent and this drops itself once both
     * are up, so the retry costs one comparison per frame until then.</p>
     */
    private boolean attachPreviews(float deltaSeconds) {
        ensureGraphTheme();
        // Commands are NOT installed here: GraphView installs its own, so a bare graph anywhere gets
        // Delete, Space, F and Ctrl+Z without a host remembering to ask for them.
        if (!previewsAttached) {
            previews.attach();
            previewsAttached = true;
        }
        if (!mainPreviewAttached) mainPreviewAttached = mainPreview.attach();
        blackboard.installCommands();
        blackboard.reclamp();
        return !(previewsAttached && mainPreviewAttached);
    }

    /**
     * Installs {@code crystalgui:graph} on the window, once.
     *
     * <p><b>The widget owns this, not its host.</b> {@code graph.css} is not decoration a scene may choose
     * to skip: a wire reads its colour <em>out of the cascade</em> — {@code NodePort.typeColor()} returns
     * the dot's computed {@code border-color} — so without the sheet the nodes are grey boxes and every
     * wire is colourless. A requirement that every consumer has to remember is a requirement that gets
     * forgotten, and it was: the dock scene shipped without it and looked broken.</p>
     *
     * <p>From the ticker rather than {@link #onLayoutChanged()}, because adding a sheet invalidates style
     * matching and doing that inside the layout pass is how this widget hung the window once already. The
     * cost is that the first frame or two are unthemed, which is invisible.</p>
     *
     * <p>{@code StyleSheetRegistry.of} caches, so the identity check is meaningful: two editors in one
     * window install one sheet, and re-adding would otherwise append it again at the highest priority.</p>
     */
    private void ensureGraphTheme() {
        UIWindow window = getAttachedWindow();
        if (window == null) return;
        StyleSheet theme = StyleSheetRegistry.of("crystalgui:graph");
        if (window.getStyleEngine().getSheets().contains(theme)) return;
        window.getStyleEngine().addStylesheet(theme);
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
