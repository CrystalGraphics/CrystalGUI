package com.crystalgui.graph.shader;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;

import com.crystalgui.ui.elements.workbench.FileDocument;
import com.google.gson.JsonParser;
import com.crystalgui.serialization.JsonOps;
import com.crystalgui.graph.GraphDocument;
import com.crystalgui.graph.GraphCodecs;
import com.crystalgui.core.settings.SettingsLayer;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
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
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.event.DragEvent;
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
public class ShaderGraphEditor extends UIElement implements FileDocument {

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
        graph.setNodeLibrary(library, propertyAwareFactory(NodeWidgetFactory.of(library).build()),
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
        // THE PILL AND THE NODE ARE TWO VIEWS OF ONE PROPERTY, so selecting either lights both. Unity
        // does the same, and it is what makes a board of a dozen properties navigable: click a pill to
        // find its nodes, click a node to find its pill.
        //
        // The loop this obviously risks closes itself: both GraphSelection.replaceWith and
        // BlackboardPanel.select return early when handed what they already hold, so the second hop is
        // a no-op rather than a bounce.
        blackboard.onPropertySelected.connect(this::highlightNodesForProperty);
        graph.getSelection().onChanged.connect(this::syncBoardToGraphSelection);
        // A rename, a retype or an Exposed toggle has to reach the nodes reading that property -- they
        // show what it IS, not a copy taken when they were made.
        graph.getDocument().onChanged.connect(this::syncPropertyNodes);
        graph.addOverlay(blackboard);
        installPropertyDrop();

        recompile();
    }

    /**
     * The widget factory, taught about property nodes.
     *
     * <p><b>A property node's type is synthesised per property and never registered</b>, which is
     * deliberate — a type per declared property would put the Blackboard's contents in the create menu.
     * The consequence is that {@code GraphView} cannot look one up: {@code nodeLibrary.get("cg:property")}
     * is null, so it built a plain node from the ports the document stored and a property came back from a
     * file as an ordinary two-row box with the capsule styling gone.</p>
     *
     * <p>Fixed at the factory rather than after the fact, because every path that makes a widget goes
     * through it — loading a file, undoing a delete, a server sync, the create menu — and patching them up
     * afterwards would mean finding all of them, and finding each new one.</p>
     */
    private NodeWidgetFactory propertyAwareFactory(NodeWidgetFactory base) {
        return (type, data) -> {
            if (!ShaderPropertyNodes.isPropertyNode(data)) return base.create(type, data);
            // Resolved from the DOCUMENT, not from the stored type: the node holds a property id, and what
            // that property currently is -- its name, its type, whether it is exposed -- lives on the
            // Blackboard. A property deleted while the file was closed resolves to null, which typeFor
            // turns into the "Missing Property" node rather than a crash.
            GraphProperty property = ShaderPropertyNodes.resolve(graph.getDocument(), data);
            GraphNode node = base.create(ShaderPropertyNodes.typeFor(property), data);
            node.addClass(ShaderPropertyNodes.NODE_CLASS);
            ShaderPropertyNodes.sync(node, property);
            return node;
        };
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

    /**
     * Lets a property pill be dropped on the canvas, where it becomes a node reading that property.
     *
     * <p>Wired HERE rather than in {@code GraphView}, which knows nothing about shaders and should not:
     * a graph view drops whatever its host teaches it to. This is the same seam the node library and the
     * type-promotion rules already come through.</p>
     *
     * <p><b>Rejection is the default</b>, so accepting is an explicit {@code preventDefault()} on every
     * {@code DragOver} — re-read per frame and never latched, which is HTML5 drag-and-drop's one good
     * idea. Without it the drop never arrives and the gesture silently does nothing.</p>
     */
    private void installPropertyDrop() {
        graph.events.getGroup(DragEvent.Over.class).attachListener((element, event) -> {
            if (event.getPayload() instanceof PropertyPill.Payload) event.preventDefault();
        }, false, true);

        graph.events.getGroup(DragEvent.Drop.class).attachListener((element, event) -> {
            if (!(event.getPayload() instanceof PropertyPill.Payload dropped)) return;
            GraphProperty property = graph.getDocument().property(dropped.propertyId());
            // Gone between the press and the release -- deleted from the board mid-drag. Dropping a node
            // that references nothing would create an error node for no reason the user could see.
            if (property == null) return;

            var world = graph.screenToWorld(event.getPosition().x(), event.getPosition().y());
            NodeData data = ShaderPropertyNodes.create(property, world.x(), world.y());

            // ADDED TO THE DOCUMENT FIRST, and that order is load-bearing.
            //
            // GraphView.addNode derives a node's data from the WIDGET when the document does not already
            // know the id -- and for a library-typed widget it derives `properties = Map.of()`, on the
            // reasonable assumption that a type's defaults can be rebuilt from the type. A property
            // node's `propertyId` is instance state and its type is synthesised per property and never
            // registered, so there is nothing to rebuild it from: the reference was dropped on the way
            // in and every node came back as "Missing Property" the moment anything re-read it.
            //
            // Pre-adding makes dataFor find the real record and keep it. attachNode skips an id it
            // already has, so this is idempotent rather than a double insert.
            graph.getDocument().addNode(data);

            GraphNode node = graph.getNodeFactory().create(
                    ShaderPropertyNodes.typeFor(property), data);
            ShaderPropertyNodes.decorate(node, property);
            graph.addNode(node, world.x(), world.y());
            recompile();
            event.stopPropagation();
        }, false, true);
    }

    /**
     * Marks every node reading {@code propertyId}, so picking a pill shows where it is used.
     *
     * <p>A HIGHLIGHT, never the graph selection. Selecting them was the first implementation and it made
     * dragging one node drag every other node reading the same property — because a selection is exactly
     * "the things a drag moves". Answering "where is this used?" must not also answer "what am I about
     * to move?".</p>
     */
    private void highlightNodesForProperty(@Nullable String propertyId) {
        for (GraphNode node : graph.nodes()) {
            String id = ShaderPropertyNodes.propertyIdOf(graph.getDocument().node(node.getNodeId()));
            boolean linked = propertyId != null && propertyId.equals(id);
            if (linked == node.hasClass(ShaderPropertyNodes.LINKED_CLASS)) continue;
            // addClass/removeClass invalidate the style match themselves, so nothing else is needed.
            if (linked) node.addClass(ShaderPropertyNodes.LINKED_CLASS);
            else node.removeClass(ShaderPropertyNodes.LINKED_CLASS);
        }
    }

    /**
     * Points the board at the selected node's property, or clears it.
     *
     * <p>Only for a property node. Selecting an ordinary node clears the board, because the inspector
     * then has a node to show and a lit pill would claim otherwise.</p>
     */
    private void syncBoardToGraphSelection() {
        String property = null;
        for (GraphNode node : graph.getSelection().nodes()) {
            String id = ShaderPropertyNodes.propertyIdOf(graph.getDocument().node(node.getNodeId()));
            if (id == null) continue;
            property = id;
            break;
        }
        blackboard.select(property);
    }

    /** Re-reads every property node from the document. @see ShaderPropertyNodes#sync */
    private void syncPropertyNodes() {
        for (GraphNode node : graph.nodes()) {
            NodeData data = graph.getDocument().node(node.getNodeId());
            if (!ShaderPropertyNodes.isPropertyNode(data)) continue;
            ShaderPropertyNodes.sync(node, ShaderPropertyNodes.resolve(graph.getDocument(), data));
        }
    }

    /**
     * Writes the canvas's pan and zoom onto the document, so {@link #encode()} carries them.
     *
     * <p>Written RAW rather than through {@code SetSettingEdit}: looking around a graph is not an edit and
     * must not land on the undo stack, or Ctrl+Z would move the camera instead of undoing. @see #VIEW_ZOOM</p>
     */
    private void captureView() {
        var settings = graph.getDocument().settings();
        settings.setRaw(SettingsLayer.DOCUMENT, VIEW_ZOOM, String.valueOf(graph.getZoom()));
        settings.setRaw(SettingsLayer.DOCUMENT, VIEW_PAN_X, String.valueOf(graph.getPanX()));
        settings.setRaw(SettingsLayer.DOCUMENT, VIEW_PAN_Y, String.valueOf(graph.getPanY()));
    }

    /** Puts the canvas back where the file says it was. A file without them is left at the default. */
    private void restoreView() {
        var settings = graph.getDocument().settings();
        Float zoom = readFloat(settings.raw(VIEW_ZOOM));
        Float panX = readFloat(settings.raw(VIEW_PAN_X));
        Float panY = readFloat(settings.raw(VIEW_PAN_Y));
        if (zoom != null) graph.setZoom(zoom);
        if (panX != null && panY != null) graph.setPan(panX, panY);
    }

    @Nullable
    private static Float readFloat(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException malformed) {
            // A hand-edited or later-format file degrades to the default view rather than refusing to
            // open -- the graph is the file's content, and where a camera sat is not worth losing it over.
            return null;
        }
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
        // THE THUMBNAILS TOO. A property node bakes its default into the preview shader as a literal --
        // it has to, since a preview has no Properties block and no material to set a uniform -- so
        // editing a property's Default leaves every thumbnail downstream showing the OLD value until the
        // preview graph is rebuilt. The previews rebuild themselves for a node field and for a resolved
        // port width, but a property is edited through a different path and told them nothing: a Float
        // changed from 0 to 1 left its Multiply thumbnail black while the Main Preview went white.
        //
        // Cheap: requestRecompile is debounced, and this already runs only on real changes.
        // Null-guarded because `graph.onConnectionsChanged` is wired BEFORE the previews are built, so a
        // connection change raised during their construction would reach this with the field still unset.
        if (previews != null) {
            previews.invalidate();
            previews.requestRecompile();
        }

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
    /** Opens the GLSL this graph emits, as its own tab. */
    public static final String VIEW_GENERATED_COMMAND = "shadergraph.viewGenerated";

    /**
     * Asked for when someone invokes {@link #VIEW_GENERATED_COMMAND} on this graph.
     *
     * <h3>A request, not a call — the dependency points outward</h3>
     *
     * <p>The graph knows it can emit GLSL and knows nothing about docks, tabs or where a panel should
     * land; that is the shell's business. So it announces the intent and {@code CrystalEditor} decides
     * what a tab is and where it goes. The reverse — the graph reaching for a workbench — would put the
     * whole editor shell on the far side of every test that builds a graph, and would make a graph
     * unusable anywhere but inside one.</p>
     *
     * <p>Same shape as {@link #onStatusChanged}, which already reports upward rather than writing to a
     * status bar it would otherwise have to know about.</p>
     */
    public final Signal.Action onViewGeneratedRequested = new Signal.Action();

    private boolean commandsInstalled;

    /**
     * Registers this widget's own commands on the window, once.
     *
     * <p><b>The widget owns this, not its host</b> — the same rule {@link #ensureGraphTheme} and
     * {@code BlackboardPanel.installCommands} already follow, and for the same reason: a graph dropped
     * into any scene should answer to its own commands without the scene remembering to ask.</p>
     *
     * <p>The command resolves the nearest enclosing graph from the focused element, exactly as
     * {@link GraphCommands} does, so with several graphs open the one you are looking at is the one that
     * answers. Registration is global and idempotent; the <em>binding</em> is what makes it reachable.</p>
     *
     * @return whether the commands are installed — false only while there is no window yet
     */
    public boolean installCommands() {
        UIWindow window = getAttachedWindow();
        if (window == null) return false;
        if (commandsInstalled) return true;
        commandsInstalled = true;

        CommandRegistry registry = window.getCommands();
        if (!registry.contains(VIEW_GENERATED_COMMAND)) {
            // Unity's "View Generated Shader". A command rather than only a button, so it reaches the
            // palette and can be bound -- and so a toolbar button invokes THIS rather than duplicating it,
            // which is how "the button works but the shortcut does not" is avoided.
            registry.register(Command.of(VIEW_GENERATED_COMMAND, "View Generated Shader")
                    .run(context -> {
                        ShaderGraphEditor graph = editorFor(context);
                        if (graph != null) graph.onViewGeneratedRequested.emit();
                    })
                    .enabledWhen(context -> editorFor(context) != null));
        }
        return true;
    }

    @Nullable
    private static ShaderGraphEditor editorFor(CommandContext context) {
        for (UIElement element = context.source(); element != null; element = element.getParent()) {
            if (element instanceof ShaderGraphEditor graph) return graph;
        }
        return null;
    }

    private boolean attachPreviews(float deltaSeconds) {
        ensureGraphTheme();
        // Commands are NOT installed here: GraphView installs its own, so a bare graph anywhere gets
        // Delete, Space, F and Ctrl+Z without a host remembering to ask for them.
        if (!previewsAttached) {
            previews.attach();
            previewsAttached = true;
        }
        if (!mainPreviewAttached) mainPreviewAttached = mainPreview.attach();
        installCommands();
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

    // ── As a FileDocument ───────────────────────────────────────────────────────────────────────

    /**
     * This widget IS the panel, so there is nothing to wrap.
     *
     * <p>{@code TextEditor} got a wrapper instead, and the difference is worth stating: that widget is
     * general-purpose and is used where no file exists at all — the emitted-source pane below, harness
     * scenes — so making it a document would give every one of those an {@code encode()} nobody calls. A
     * shader graph editor is one graph file, so implementing this directly is the honest shape.</p>
     */
    @Override
    public UIElement view() {
        return this;
    }

    /**
     * Where the canvas is looking, as three settings on the document.
     *
     * <p><b>In the file, which is Unity's choice for a {@code .shadergraph} and not the obvious one.</b>
     * Pan and zoom are view state by this project's own rule — they are not undoable, and Ctrl+Z after a
     * pan must not move the camera. But "not undoable" and "not saved" are different questions, and a
     * shader graph is an asset you arrange: reopening one to find it back at the origin loses real work,
     * because where things sit relative to the viewport is part of how a graph is read.</p>
     *
     * <p>The cost is accepted rather than hidden: panning makes the file <b>modified</b>, because the
     * bytes genuinely changed. Unity behaves the same way for the same reason.</p>
     *
     * <p>Carried in the DOCUMENT settings layer rather than as new codec fields, so there is nothing to
     * version — that layer already round-trips, is already excluded from the user and workspace layers,
     * and is already content-hashed with the rest of the graph.</p>
     */
    public static final String VIEW_ZOOM = "graph.view.zoom";
    public static final String VIEW_PAN_X = "graph.view.panX";
    public static final String VIEW_PAN_Y = "graph.view.panY";

    /** The graph as it stands, in the serialized form {@link GraphCodecs#DOCUMENT} defines. */
    @Override
    public byte[] encode() {
        captureView();
        return GraphCodecs.DOCUMENT.encode(JsonOps.INSTANCE, graph.getDocument())
                .toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Loads a graph file.
     *
     * <p>Decode, then {@link GraphView#load} — which copies the contents into the view's own document
     * rather than adopting the decoded object. That distinction is the whole reason this could not be
     * written before: the Main Preview, the Blackboard and this class's own {@code onChanged} listener
     * are all bound to {@code graph.getDocument()} at construction, so a load that swapped the instance
     * would leave every one of them driving a document nobody was showing.</p>
     *
     * <p>Not undoable, and the view clears the stack: a file is the starting state, not something the
     * user did. The previews are invalidated and the source pane recompiled, because nothing else fires
     * for a wholesale replacement — {@code onConnectionsChanged} covers the wires, but a graph loaded
     * with no edges at all would otherwise show the previous file's generated GLSL.</p>
     *
     * <p><b>A malformed file throws, and is meant to.</b> Accepting the bytes and showing an empty canvas
     * would be far worse than refusing: the editor would then differ from the file it failed to read,
     * report itself modified, and the first Save All would write that emptiness over the user's work.
     * The workbench catches this, says so, and refuses to save the file at all.</p>
     *
     * <h3>A BLANK file is not a malformed one</h3>
     * <p>{@code New File…} creates every file with {@code ""}, so the first thing anyone does with this
     * type — make {@code thing.shadergraph}, open it, wire something up, save — hit that refusal on the
     * very first step: {@code CodecException: Not a JSON object: null}, and then a {@code Ctrl+S} that
     * declined to write because the file "never loaded". A zero-byte file is the universal spelling of an
     * empty document and has nothing to lose, which is the entire premise of the refusal.</p>
     *
     * <p>Only truly blank content takes this path. Anything else that will not parse still throws, so the
     * protection stays exactly where it was aimed — at a file with content in it that this build cannot
     * read.</p>
     *
     * <p>The tab shows modified as soon as it opens, and that is honest rather than a wart: what is on
     * disk is not a graph, {@code encode()} says what one would be, and they genuinely differ until saved
     * once.</p>
     *
     * <h3>And a blank file opens with the STARTER graph</h3>
     * <p>Which is a product decision, not a consequence: an empty canvas is a worse first thing to be
     * handed than a small working graph you can take apart. Unity, Godot and Blender all seed a new
     * shader with a working output for the same reason.</p>
     *
     * <p>It applies to a <b>blank</b> file only. A saved graph that genuinely has no nodes must come back
     * empty — seeding that would silently re-add nodes the user deleted on purpose, every time they
     * reopened it, which is the one behaviour worse than an empty canvas.</p>
     */
    @Override
    public void adopt(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        boolean blank = text.trim().isEmpty();
        GraphDocument loaded = blank
                ? new GraphDocument()
                : GraphCodecs.DOCUMENT.decode(JsonOps.INSTANCE, JsonParser.parseString(text));
        graph.load(loaded);
        restoreView();
        if (blank) {
            addStarterGraph();
            // AFTER seeding, and load's own clear is not enough: addStarterGraph goes through the ordinary
            // mutators, so every node and wire it adds is an undoable step. Without this the first Ctrl+Z
            // in a brand-new file unpicks the graph it was just handed, one node at a time -- the same
            // rule load already follows, for the same reason. Nobody performed these edits.
            graph.undoStack().clear();
        }
        // The property nodes carry a title and an exposed dot read back out of the properties they
        // reference, and nothing re-derives those for a load: syncPropertyNodes runs on document change,
        // and the emit that load ends with arrives before these widgets have been rebuilt.
        syncPropertyNodes();
        if (previews != null) {
            previews.invalidate();
            previews.requestRecompile();
        }
        recompile();
    }
}
