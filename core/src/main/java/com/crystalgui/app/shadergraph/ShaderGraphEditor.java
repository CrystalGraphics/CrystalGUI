package com.crystalgui.app.shadergraph;
import com.crystalgui.app.shadergraph.node.ShaderVectorFieldWidget;
import com.crystalgui.app.shadergraph.node.ShaderColorFieldWidget;
import com.crystalgui.app.shadergraph.blackboard.BlackboardPanel;
import com.crystalgui.app.shadergraph.blackboard.PropertyPill;
import com.crystalgui.app.shadergraph.node.ShaderPropertyNodes;
import com.crystalgui.app.shadergraph.preview.MainPreviewPanel;
import com.crystalgui.app.shadergraph.preview.ShaderGraphPreviews;
import com.crystalgui.core.data.DataProvider;

import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;

import java.nio.charset.StandardCharsets;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.document.FileDocument;
import com.crystalgui.ui.dom.UIElement;
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
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.Resource;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.widget.graph.NodeWidgetFactory;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.KeywordTokenizer;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.widget.graph.GraphCommands;
import com.crystalgui.graph.GraphProperty;
import com.crystalgui.graph.NodeData;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.ui.event.DragEvent;
import com.crystalgui.widget.graph.GraphView;

import com.crystalgraphics.shadergraph.CgShaderProblem;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * painted. Those are announced as {@link StatusBar} items rather than built here.</p>
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
 * that. Doing it from {@link #connected()} makes it the widget's own business, the same way
 * {@code ListView} starts its ticker: by the time layout has run, the element is attached by definition.</p>
 */
public class ShaderGraphEditor extends UIElement implements FileDocument, Disposable.Gl, DataProvider {
    /** A whole shader graph editor. Named by the sheets. */
    public static final Name NAME = Name.of("shadergrapheditor");


    /** UNIQUE, never the shared "__content__" -- see ProjectFileTree.CONTENT_CLASS for why. */
    public static final String CONTENT_CLASS = "__shader-content__";
    public static final String GRAPH_CLASS = "__shader-graph__";

    /** On {@link #source()}, which this widget does NOT contain — so the rule for it is tag-qualified
     * rather than a descendant of {@code shadergrapheditor}. See the class note. */
    public static final String SOURCE_CLASS = "__shader-source__";

    /**
     * One compile's summary, or its first error — a {@link StatusBar} item.
     *
     * <p>Was a {@code Signal.Value<String>} that the application had to be handed and asked to relay.
     * Announcing it instead is what let {@code ShaderGraphContribution.register} stop taking a status
     * sink, which is the whole of "a contribution needs nothing from the application".</p>
     */
    public static final String COMPILE_STATUS = "shadergraph.compile";

    /**
     * Which node emitted the line the caret is on, as {@code "line 12 emitted by cg:Math/Basic/multiply"}.
     *
     * <p>The payoff of the emitter's line map, and the reason it exists: a driver reports an error at a
     * line in code the user never wrote, and this turns that into somewhere to go and look.</p>
     *
     * <p>A <b>separate item</b> from {@link #COMPILE_STATUS} rather than the same slot, which is the
     * point of keying them: this fires on every caret move in the generated source, and sharing one slot
     * meant it erased the compile summary a few milliseconds after every compile.</p>
     */
    public static final String LINE_OWNER_STATUS = "shadergraph.lineOwner";

    /** Whether this graph is the tab in front, and therefore entitled to speak. @see #setActive */
    private boolean statusActive;

    /**
     * The last summary this graph produced, so activating its tab restores it without a recompile.
     *
     * <p>Kept rather than recomputed because a compile is not free and the answer has not changed: the
     * document is exactly as it was when the tab lost focus.</p>
     */
    @Nullable
    private String lastCompileStatus;

    /** The detail the item has no room for — the character count, or the first error in full. */
    @Nullable
    private String lastCompileTooltip;

    /** Whether the last compile failed, so restoring the item on activation restores its colour too. */
    private boolean lastCompileFailed;

    /**
     * The two entries this graph owns while its tab is in front.
     *
     * <p>Handles rather than string keys, so withdrawing them is disposing what was registered — see
     * {@link StatusBarEntryAccessor}. Null while the tab is in the background, which is what makes
     * "entitled to speak" a fact about whether the entry exists rather than a flag consulted at three
     * separate write sites.</p>
     */
    @Nullable
    private StatusBarEntryAccessor compileEntry;

    @Nullable
    private StatusBarEntryAccessor lineOwnerEntry;

    /** Left group, compile summary ahead of the line-owner readout. @see StatusBar */
    private static final int COMPILE_PRIORITY = 100;
    private static final int LINE_OWNER_PRIORITY = 90;

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
        super(NAME);
        // Explicit, like every command set in this engine -- a registry that quietly acquired
        // declarations nobody asked for surprises anything that walks it, and a generated settings panel
        // is precisely such a thing. Idempotent, since registering replaces.
        ShaderGraphSettings.register();

        graph.addClass(GRAPH_CLASS);
        source.addClass(SOURCE_CLASS);

        // The library IS the shader node set -- the create menu, its search and the widget factory all
        // come from one bridge call, so there is no shader-specific UI code anywhere below this line.
        // THIS ENGINE'S FIELD WIDGETS, which `ShaderNodeLibrary.of` cannot install.
        //
        // That helper belongs to the old engine and installs the old engine's COLOR and VECTOR
        // factories into the old `NodeFieldWidgets`. This editor's nodes are built by
        // `widget.graph.node.NodeFieldBinder`, which reads the NEW registry -- and that one has no
        // default for either kind by design, "so skipping this is a visible regression (a text field)
        // rather than a silent one". It was worse than a text field here: with no factory at all a
        // Color node drew nothing but its output port, and a Vector node the same.
        //
        // Installed at the one place a library is built, and idempotent -- both installers REPLACE
        // their registration rather than adding to it, so a second graph costs two map writes.
        ShaderColorFieldWidget.install();
        ShaderVectorFieldWidget.install();
        library = ShaderNodeLibrary.of(shaderNodes);
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
        // append(graph) would be the obvious line and it is what hung both scenes.
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
        append(content);
        content.append(graph);

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
     * Publishes this graph's ambient state while its tab is in front, and withdraws it when it is not.
     *
     * <p>The bug this fixes was visible and easy to misread: the compile summary sat on the status bar
     * while a plain text file was open, because a status item is written once and stays until somebody
     * takes it away. Nobody did — a graph goes on compiling in the background, so it kept re-asserting a
     * fact about a document you were not looking at.</p>
     *
     * <p>The line-owner readout is not restored on activation, deliberately: it describes where the caret
     * is in the generated source, and there is no caret in it until you look at it again.</p>
     */
    @Override
    public void setActive(boolean active) {
        statusActive = active;
        if (!active) {
            if (compileEntry != null) compileEntry.dispose();
            compileEntry = null;
            if (lineOwnerEntry != null) lineOwnerEntry.dispose();
            lineOwnerEntry = null;
            return;
        }
        publishCompileStatus();
    }

    /**
     * Puts the compile summary on the bar, or updates the one already there.
     *
     * <p><b>Only while this graph is the tab in front.</b> A graph recompiles whether or not you are
     * looking at it — an animated node recompiles every frame — so writing unconditionally put a
     * background document's summary on the bar underneath somebody else's file. @see #setActive</p>
     *
     * <p>One place rather than the three that each rebuilt the same entry by hand, which is how the
     * failure colour came to be spelled out at every one of them.</p>
     */
    private void publishCompileStatus() {
        if (!statusActive || lastCompileStatus == null) return;
        // A FAILING READOUT IS A WAY IN. VS Code's error counter opens its Problems panel; a count you
        // cannot click is a number you then have to go and find the panel for. Only when it failed --
        // "compiled 12n/9e" has nothing to show you.
        StatusBarEntry entry = new StatusBarEntry("Shader graph compilation", lastCompileStatus,
                lastCompileTooltip, lastCompileFailed ? "workbench.showProblems" : null,
                lastCompileFailed ? StatusBarEntry.Kind.ERROR : StatusBarEntry.Kind.STANDARD);
        if (compileEntry == null) {
            compileEntry = StatusBar.addEntry(entry, COMPILE_STATUS, StatusBarAlignment.LEFT,
                    COMPILE_PRIORITY);
        } else {
            compileEntry.update(entry);
        }
    }


    /**
     * The compiler's problems, as diagnostics — what the Problems panel shows for a graph.
     *
     * <h3>No line, and that is the honest answer</h3>
     *
     * <p>A graph problem is about a <b>node</b>, not a row: there is no text for it to point at until the
     * driver rejects the generated source, which is a different reporter. So the range is
     * {@code Diagnostic.NO_POSITION} and the node id travels in {@code code}, which is where LSP puts a
     * reporter's own identity for a complaint. A panel that renders a row number then has something true
     * to say rather than a confident "line 1".</p>
     *
     * <p>{@code setAll} rather than incremental adds: a compile is a complete statement about the graph, so
     * anything it did not repeat is fixed. The set announces once, so a panel rebuilds once however many
     * problems moved.</p>
     */
    private void publishProblems(CgShaderEmitter.Result result) {
        List<Diagnostic> emitter = new ArrayList<>();
        for (CgShaderProblem problem : result.problems()) {
            emitter.add(new Diagnostic(Diagnostic.NO_POSITION, Diagnostic.NO_POSITION,
                    problem.isError() ? DiagnosticSeverity.ERROR : DiagnosticSeverity.WARNING,
                    problem.message(), OWNER_EMITTER, problem.nodeId()));
        }
        List<Diagnostic> driver = new ArrayList<>();
        addDriverProblems(driver, result);
        List<Diagnostic> preview = new ArrayList<>();
        addPreviewProblems(preview);
        List<Diagnostic> graphWarnings = new ArrayList<>();
        addGraphWarnings(graphWarnings);

        // FOUR OWNERS, ONE ANNOUNCEMENT. These are four independent producers of an opinion about the same
        // document, and they used to be merged into one list by hand because the set could only hold one --
        // so any of them writing alone would have erased the other three. `changeAll` is what keeps a
        // Problems panel bound to this from rebuilding once per producer on every compile.
        Map<String, List<Diagnostic>> byOwner = new LinkedHashMap<>();
        byOwner.put(OWNER_EMITTER, emitter);
        byOwner.put(OWNER_DRIVER, driver);
        byOwner.put(OWNER_PREVIEW, preview);
        byOwner.put(OWNER_GRAPH, graphWarnings);
        problems.changeAll(byOwner);
        List<Diagnostic> diagnostics = problems.all();

        // THE STATUS ITEM FOLLOWS THE DIAGNOSTICS, not just the emit. A driver refusal arrives AFTER a
        // successful emit -- the graph produced GLSL it believed in and the hardware refused it -- so
        // `result.ok()` is true while the shader does not exist. Reporting "compiled 10n/7e" in that state
        // is the most misleading thing the bar can say: a confident success beside a blank preview and a
        // red row in Problems.
        // COUNTED, not assumed. This said "1 error(s)" unconditionally while walking to the FIRST error --
        // so a graph with no output node AND a driver refusal reported one of them and claimed there was
        // one, with two red rows visible in the Problems panel directly above it. The count is the whole
        // information content of the readout; the first message is what the tooltip is for.
        int errors = problems.count(DiagnosticSeverity.ERROR);
        if (errors == 0) return;
        lastCompileFailed = true;
        lastCompileStatus = FAILED_STATUS;
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.severity() != DiagnosticSeverity.ERROR) continue;
            lastCompileTooltip = diagnostic.message();
            break;
        }
        publishCompileStatus();
    }

    /**
     * What this graph's readout says when it will not compile — <b>deliberately not a count</b>.
     *
     * <p>It used to say {@code "2 errors"}, which sat on the bar directly beside the workspace's own
     * {@code "2 errors, 0 warnings"} and read as a contradiction: two numbers of different scopes,
     * adjacent, with nothing saying which was which. Neither reference does that — VS Code shows only the
     * workspace tally, IntelliJ only the current file's state.</p>
     *
     * <p>The count belongs to the workspace entry, which owns every file. This one says whether the thing
     * you are looking at builds, which is the fact the workspace tally cannot give you, and its tooltip
     * still carries the first error in full.</p>
     */
    private static final String FAILED_STATUS = "compile failed";

    /**
     * The driver's refusal of the generated source, mapped back to the node that wrote the line.
     *
     * <h3>The failure the graph cannot predict</h3>
     *
     * <p>Everything above is the emitter's opinion of the graph. This is the case where the emitter was
     * satisfied and the <b>driver</b> was not — an unsupported builtin, a profile difference, a swizzle the
     * emitter got wrong. It used to produce a blank panel and a log line while the status bar said
     * "compiled 12n/9e", which is the worst of both: a confident success and nothing on screen.</p>
     *
     * <p>A driver reports {@code 0(278) : error C1503}, and 278 is a line of code the user never wrote.
     * {@code lineOwners} is what makes that actionable — it is the map this whole mechanism was built for,
     * and until now its only consumer was the caret readout in the status bar.</p>
     */
    private void addDriverProblems(List<Diagnostic> into, CgShaderEmitter.Result result) {
        String driver = mainPreview.lastDriverError();
        publishedDriverError = driver;
        if (driver == null) return;
        int line = glslLineOf(driver);
        String owner = line > 0 ? result.ownerOfLine(line) : null;
        into.add(new Diagnostic(
                line > 0 ? new TextPoint(line - 1, 0) : Diagnostic.NO_POSITION,
                line > 0 ? new TextPoint(line - 1, 0) : Diagnostic.NO_POSITION,
                DiagnosticSeverity.ERROR,
                owner == null ? driver : driver + "  (emitted by " + owner + ")",
                "glsl", owner));
    }

    /**
     * The first line number in a driver message, or -1.
     *
     * <p>{@code 0(278) : error C1503} is NVIDIA's shape and {@code ERROR: 0:278:} is Mesa's; both put the
     * line after a colon or a bracket following the source index. Matching a number in either rather than
     * a vendor's exact grammar, because getting it wrong costs the attribution and never the message —
     * which is still shown in full.</p>
     */
    private static int glslLineOf(String message) {
        // Scanned rather than matched, because the two vendor shapes differ only in a bracket and the
        // pattern for both is "a number, a separator, the line". A regex for it needs four escaped
        // classes and says less than this does.
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

    /**
     * Nodes whose own thumbnail will not compile.
     *
     * <p>{@code CgPreviewRenderer} knew which nodes these were and why, and told nobody — the node drew
     * nothing, permanently, because the failure set is also what stops it retrying. A blank thumbnail with
     * no explanation is indistinguishable from one that has not rendered yet.</p>
     */
    private void addPreviewProblems(List<Diagnostic> into) {
        previews.renderer().failures().forEach((nodeId, reasons) -> {
            for (CgShaderProblem reason : reasons) {
                into.add(new Diagnostic(Diagnostic.NO_POSITION, Diagnostic.NO_POSITION,
                        DiagnosticSeverity.WARNING,
                        "Preview unavailable: " + reason.message(), OWNER_PREVIEW, nodeId));
            }
        });
    }

    /**
     * What the compiler has no reason to look at — <b>warnings</b> about the document rather than errors
     * about the emit.
     *
     * <p>Duplicate property names are the one that matters: they become GLSL uniform names, which must be
     * unique, so two properties called {@code Color} compile to a driver error at a line the user never
     * wrote. Catching it here names the actual problem instead.</p>
     */
    private void addGraphWarnings(List<Diagnostic> into) {
        addUnknownNodeProblems(into);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (GraphProperty property : graph.getDocument().properties()) {
            String name = property.name() == null ? "" : property.name().trim();
            if (name.isEmpty()) {
                into.add(warning("A property with no name cannot become a uniform", property.id()));
            } else if (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
                into.add(warning("Two properties are named '" + name
                        + "' — they become one uniform, and the second wins", property.id()));
            }
        }
    }

    /**
     * Nodes this build has no definition for — the "opened without the plugin" case, said out loud.
     *
     * <h3>Surviving silently is half a feature</h3>
     *
     * <p>The document model is deliberately built so an unknown node <em>survives</em>: it keeps its id,
     * position, values and edges, so opening a graph in a build that lacks one of its node types and saving
     * it again does not quietly delete the user's work. That half is right and worth keeping.</p>
     *
     * <p>The half that was missing is that nobody was told. {@code GraphView} builds a placeholder widget
     * and the bridge marks the node absent and drops every edge touching it, so the graph <b>compiles
     * without it</b> — a shader that is silently missing a step, from a canvas that looks almost normal.
     * An error, not a warning: what is emitted is not what the document says.</p>
     */
    private void addUnknownNodeProblems(List<Diagnostic> into) {
        for (NodeData data : graph.getDocument().nodes()) {
            String typeId = data.typeId();
            // The two the registry legitimately does not hold: the master is the compiler's own object, and
            // a property node is synthesised from the document's declarations rather than registered.
            if (ShaderGraphBridge.MASTER_TYPE.equals(typeId) || ShaderPropertyNodes.isPropertyNode(data)) {
                continue;
            }
            if (shaderNodes.get(typeId) != null) continue;
            into.add(new Diagnostic(Diagnostic.NO_POSITION, Diagnostic.NO_POSITION,
                    DiagnosticSeverity.ERROR,
                    "No definition for node type '" + typeId + "' in this build — it is kept in the"
                            + " document but left out of the compiled shader",
                    OWNER_GRAPH, data.id()));
        }
    }

    private static Diagnostic warning(String message, String code) {
        return new Diagnostic(Diagnostic.NO_POSITION, Diagnostic.NO_POSITION,
                DiagnosticSeverity.WARNING, message, OWNER_GRAPH, code);
    }

    /**
     * What is wrong with this graph. @see #publishProblems
     *
     * <p>Owned here rather than built on demand, because a view binds to it once and then listens.</p>
     */
    @Override
    public DiagnosticSet diagnostics() {
        return problems;
    }

    private final DiagnosticSet problems = new DiagnosticSet();



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
        // ONLY WHEN THERE IS A BOX TO RECORD. An unmeasured panel yields "", and writing that would both
        // erase a good rect from the file and churn the settings layer on frames where nothing moved.
        String preview = rectOf(mainPreview);
        if (!preview.isEmpty()) settings.setRaw(SettingsLayer.DOCUMENT, VIEW_PREVIEW_RECT, preview);
        String board = rectOf(blackboard);
        if (!board.isEmpty()) settings.setRaw(SettingsLayer.DOCUMENT, VIEW_BLACKBOARD_RECT, board);
    }

    /**
     * A panel's box as {@code left,top,width,height}, in its containing block's space.
     *
     * <p>Empty while the panel has not been laid out, and {@link #applyRect} refuses an empty string —
     * writing a zero box would persist the same "measured before layout" corruption that sent the preview
     * to the graph's origin, except into the file where a relaunch cannot recover from it.</p>
     *
     * <h3>A panel bigger than the box it sits in is a squeeze, not a placement</h3>
     *
     * <p>The zero check alone was not enough, and the way it failed is worth keeping. This runs from
     * {@link #encode()} — a <b>save</b> — and a save does not require the editor to be on screen: a
     * background tab is {@code display: none}, so every box in it measures 0. The panels do not measure 0
     * with it, because they carry {@code min-width}/{@code min-height}; they measure exactly their
     * <em>minimum</em>, at the canvas origin. Both numbers are positive, both passed, and the file was
     * written with both panels at {@code 0,0,80,100}.</p>
     *
     * <p>It then restored perfectly, which is what made it read as a restore bug: every {@code .shadergraph}
     * opened with two tiny panels stacked in the corner, no matter where they had been left.</p>
     *
     * <p>So the test is against the container, not against zero. A panel that does not fit inside its own
     * containing block is being clamped by something that is not the user, and a measurement taken then
     * describes the clamp. Refusing leaves the last good rect in the settings — {@link #captureView} only
     * writes a non-empty one — which is exactly right for a tab that was never opened this session.</p>
     */
    private static String rectOf(UIElement panel) {
        UIElement block = panel.parent();
        if (block == null) return "";
        Box box = panel.box();
        Box blockBox = block.box();
        if (box == null || blockBox == null) return "";
        if (box.width() <= 0f || box.height() <= 0f) return "";
        if (blockBox.width() < box.width() || blockBox.height() < box.height()) return "";
        // The panel's origin IN THE BLOCK'S SPACE. `Box.x()` is parent-relative here, so
        // subtracting two boxes' raw offsets only means anything when they share a parent.
        var origin = Box.originIn(box, blockBox);
        return origin.x() + "," + origin.y() + "," + box.width() + "," + box.height();
    }

    /** @see #rectOf */
    private static boolean applyRect(UIElement panel, @Nullable String raw) {
        if (raw == null || raw.isEmpty()) return false;
        String[] parts = raw.split(",");
        if (parts.length != 4) return false;
        Float left = readFloat(parts[0]);
        Float top = readFloat(parts[1]);
        Float width = readFloat(parts[2]);
        Float height = readFloat(parts[3]);
        if (left == null || top == null || width == null || height == null) return false;
        if (width <= 0f || height <= 0f) return false;
        // INLINE, which is the origin the resizer and the drag both write at -- so a restored box is
        // exactly a box the user could have dragged to, and moving it afterwards simply replaces this.
        StyleGroup.inlinePipeline(panel.getStyle().getLayoutGroup(),
                l -> l.left(left).top(top).width(width).height(height));
        return true;
    }

    /** Puts the canvas back where the file says it was. A file without them is left at the default. */
    private void restoreView() {
        var settings = graph.getDocument().settings();
        Float zoom = readFloat(settings.raw(VIEW_ZOOM));
        Float panX = readFloat(settings.raw(VIEW_PAN_X));
        Float panY = readFloat(settings.raw(VIEW_PAN_Y));
        if (zoom != null) graph.setZoom(zoom);
        if (panX != null && panY != null) graph.setPan(panX, panY);
        // A RESTORED POSITION IS A DELIBERATE ONE, and each panel has to be TOLD: the re-clamp that tracks
        // a resizing canvas is gated on having been placed, which only a drag used to set.
        //
        // BOTH PANELS, and the missing half is worth naming because it read as an unrelated bug. Only the
        // preview was marked, so a restored Blackboard ignored the canvas shrinking until it had been
        // grabbed once -- at which point it started tracking correctly and looked like a redraw problem
        // rather than a flag that nothing but a drag ever set.
        if (applyRect(mainPreview, settings.raw(VIEW_PREVIEW_RECT))) mainPreview.markPlaced();
        if (applyRect(blackboard, settings.raw(VIEW_BLACKBOARD_RECT))) blackboard.markPlaced();
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
    public CgMasterNode master() {
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

        // AMBIENT, not an event. A graph with an animated node recompiles every frame, so a compile
        // RESULT is a description of how things are rather than something that happened -- routed
        // through Notifications it would push a hundred entries a second into a history and bury
        // everything else in it. StatusBar replaces its own item and keeps no history, which is exactly
        // the difference. A compile FAILURE is still ambient for the same reason: it is true until the
        // next edit fixes it, and it re-arrives on every frame while it lasts.
        // SHORT ENOUGH TO GLANCE AT, with the rest on hover. This was one item carrying five facts --
        // "compiled 12n/9e 996 chars 1 varyings 6 mapped lines" -- which is a sentence, and a status bar
        // is read without stopping. Both references keep the bar to a readout and put the detail behind
        // it; the numbers are not lost, they are one hover away.
        lastCompileStatus = result.ok()
                ? String.format("compiled  %dn/%de",
                        graph.getDocument().nodeCount(), graph.getDocument().edges().size())
                : FAILED_STATUS;
        lastCompileFailed = !result.ok();
        lastCompileTooltip = result.ok()
                ? String.format("%d chars, %d varyings, %d mapped lines",
                        result.source().length(), result.varyings().size(), result.lineOwners().size())
                : result.errors().get(0);
        publishCompileStatus();
        // UNCONDITIONALLY, unlike the status item above: a diagnostic set belongs to the document, so a
        // graph compiling in the background must keep its problems current for whenever its tab returns.
        // Only the STATUS BAR is a claim on the screen right now.
        publishProblems(result);
    }

    private void reportLineOwner() {
        if (lastCompile == null) return;
        int line = source.caretPoint().row() + 1;
        String owner = lastCompile.ownerOfLine(line);
        if (owner == null) return;
        var node = graph.getDocument().node(owner);
        StatusBarEntry entry = StatusBarEntry.of("Emitting node", "line " + line + " emitted by "
                + (node == null ? owner : node.typeId() + "  (" + owner + ")"));
        if (lineOwnerEntry == null) {
            lineOwnerEntry = StatusBar.addEntry(entry, LINE_OWNER_STATUS, StatusBarAlignment.LEFT,
                    LINE_OWNER_PRIORITY);
        } else {
            lineOwnerEntry.update(entry);
        }
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
    protected void connected() {
        super.connected();
        UIDocument document = document();
        // The guard is not the old one's: `registerTicker` was HashSet-backed and idempotent, and
        // `Animation.every` is a plain add. `disconnected()` clears it, or an editor that is hidden
        // and reshown comes back with the flag set and no hooks behind it.
        if (ticking || document == null) return;
        ticking = true;
        document.animation().every(this, this::attachPreviews);
        document.animation().every(this, this::watchDriverError);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        ticking = false;
    }

    /**
     * Republishes the diagnostics when the driver's verdict changes.
     *
     * <h3>The compile finishes before the failure exists</h3>
     *
     * <p>{@link #publishProblems} runs from {@link #recompile()} and asks the preview what the driver said —
     * but a material compiles lazily on its first <b>bind</b>, so at that moment the GLSL has not been near
     * the hardware yet. The error appears one frame later, inside the preview's own tick, with nothing left
     * to report it.</p>
     *
     * <p>It used to be masked: an animated graph recompiles every frame, so the <em>next</em> compile's
     * publish carried the previous frame's error and it looked like it worked. Break a static graph — change
     * the vertex format to one with no normals — and the recompile happens once, before the failure, and the
     * Problems panel stays empty about a shader that does not exist.</p>
     *
     * <p>A polled string comparison rather than a signal from the renderer: the renderer is CrystalGraphics'
     * and has no business knowing a diagnostics panel exists, and this is one reference comparison per frame
     * against a value that changes about once a minute.</p>
     */
    private boolean watchDriverError(float deltaSeconds) {
        String current = mainPreview.lastDriverError();
        if (!java.util.Objects.equals(current, publishedDriverError) && lastCompile != null) {
            publishProblems(lastCompile);
        }
        // NEVER DROPPED: there is no signal that says a compile is about to fail.
        return true;
    }

    /** What {@link #publishProblems} last saw from the driver. @see #watchDriverError */
    @Nullable
    private String publishedDriverError;

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
     * <p>Same shape as {@link #COMPILE_STATUS}, which announces rather than writing into a status bar it
     * would otherwise have to be handed.</p>
     */
    public final Signal.Action onViewGeneratedRequested = new Signal.Action();


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
     */
    /**
     * Registers this widget's commands. No window needed, so it runs from the constructor.
     *
     * <p>It used to need one, purely to reach {@code window.getCommands()} — and because the window is
     * not there at construction, the call lived in {@code attachPreviews}, which is a <b>frame
     * ticker</b>. So a graph's commands did not exist until a frame after it attached, and a palette
     * opened before that was missing them. Commands being global removes the reason the window was ever
     * involved.</p>
     */
    @Override
    protected void registerCommands(CommandRegistry registry) {
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

    /** This editor, for a command that acts on one. Declared here because it is this feature's concept. */
    public static final DataKey<ShaderGraphEditor> SHADER_GRAPH =
            DataKey.create("shaderGraph.new", ShaderGraphEditor.class);

    /**
     * What this editor knows: itself, plus whatever the canvas below it answers.
     *
     * <p>Note it does <b>not</b> answer {@code SELECTION} — the {@code GraphView} inside it does, and it
     * is inside, so the walk reaches it first. Answering here as well would mean the outer element
     * shadowing the inner one whenever the inner answer happened to be empty.</p>
     */
    @Override
    public Object getData(DataKey<?> key) {
        if (key == SHADER_GRAPH) return this;
        return null;
    }

    @Nullable
    private static ShaderGraphEditor editorFor(CommandContext context) {
        return context.data().get(SHADER_GRAPH);
    }

    private boolean attachPreviews(float deltaSeconds) {
        ensureGraphTheme();
        // Commands are NOT installed here: GraphView installs its own, so a bare graph anywhere gets
        // Delete, Space, F and Ctrl+Z without a host remembering to ask for them.
        if (!previewsAttached) {
            previews.attach();
            previewsAttached = true;
        }
        if (!mainPreviewAttached) {
            mainPreviewAttached = mainPreview.attach();
            if (mainPreviewAttached) ownGlParts();
        }
        // NOTHING PER-FRAME BELONGS HERE. This ticker drops itself the moment both previews are up, so a
        // standing job parked in it runs for two frames and then stops -- which is exactly what happened to
        // the Blackboard's re-clamp. It ticks itself now; see BlackboardPanel.tickFrame.
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
     * <p>From the ticker rather than {@link #connected()}, because adding a sheet invalidates style
     * matching and doing that inside the layout pass is how this widget hung the window once already. The
     * cost is that the first frame or two are unthemed, which is invisible.</p>
     *
     * <p>{@code StyleSheetRegistry.of} caches, so the identity check is meaningful: two editors in one
     * window install one sheet, and re-adding would otherwise append it again at the highest priority.</p>
     */
    private void ensureGraphTheme() {
        UIDocument window = document();
        if (window == null) return;
        StyleSheet theme = StyleSheetRegistry.of("crystalgui:graph");
        if (window.styles().getSheets().contains(theme)) return;
        window.styles().addStylesheet(theme);
    }

    /**
     * Releases the preview renderers' GL resources. Safe to call more than once.
     *
     * <p>{@code Disposable.Gl} rather than plain {@code Disposable}: a {@code CgPreviewRenderer} owns
     * {@code createOwned} framebuffers, and freeing those off the GL thread is silent corruption rather
     * than an exception. {@link com.crystalgui.core.dispose.Disposer} defers accordingly.</p>
     */
    @Override
    public void dispose() {
        if (previewsAttached) {
            previews.delete();
            previewsAttached = false;
        }
        mainPreviewAttached = false;
    }

    /**
     * Takes ownership of the parts that hold GL resources.
     *
     * <p>{@link MainPreviewPanel} is the reason this exists: its {@code delete()} had <b>no caller
     * anywhere</b>, so its {@code createOwned} target and meshes leaked for the life of the process.
     * Registration is how that stops being something somebody has to remember.</p>
     */
    private void ownGlParts() {
        Disposer.register(this, mainPreview);
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

    /**
     * Where the two floating panels sit and how big they are.
     *
     * <p>Same argument as pan and zoom, and stored beside them for the same reason: a shader graph is an
     * asset you <em>arrange</em>, and a Main Preview you widened to look at a sphere properly is work that
     * reopening should not throw away. Unity keeps both in its {@code .shadergraph} too.</p>
     *
     * <p>Also the same cost, accepted the same way: dragging a panel makes the file modified, because the
     * bytes genuinely changed.</p>
     */
    public static final String VIEW_PREVIEW_RECT = "graph.view.previewRect";
    public static final String VIEW_BLACKBOARD_RECT = "graph.view.blackboardRect";
    public static final String VIEW_PAN_X = "graph.view.panX";
    public static final String VIEW_PAN_Y = "graph.view.panY";

    /**
     * What this graph is a document <em>of</em>. Null until the workbench builds it for a path.
     *
     * <p>Set rather than constructor-injected because a graph is a perfectly good widget with no file
     * behind it — the gallery scene builds one directly — and requiring an address would make the
     * standalone case the awkward one.</p>
     */
    @Nullable
    private Resource resource;

    public ShaderGraphEditor setResource(@Nullable Resource resource) {
        this.resource = resource;
        // The Blackboard is named after the DOCUMENT, which is Unity's reference and the reason the panel
        // reads as part of the graph rather than as a tool inspecting it -- so it cannot be told at
        // construction, when the widget has no file yet, and every board said "shader_graph" instead.
        //
        // Without the extension, like Unity's asset name and like the graph itself: the tab beside it
        // already carries "new.shadergraph", so repeating the suffix here says nothing twice.
        blackboard.setDocumentName(resource == null ? "" : stripExtension(resource.name()));
        return this;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        // A leading dot is the whole name of a dotfile, not an extension -- `.gitignore` must not become "".
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @Override
    @Nullable
    public Resource resource() {
        return resource;
    }

    /**
     * The graph's undo stack, which <b>is</b> its change log.
     *
     * <p>Every document change goes through an {@code Edit} by construction — the boundary this codebase
     * draws between document state and view state — so "something was pushed, undone or redone" is
     * exactly "the content changed". Pan, zoom and selection move nothing here, which is correct: they
     * are view state and cannot make a file dirty.</p>
     */
    @Override
    public Connection onDidChange(Runnable listener) {
        return graph.undoStack().onChanged.connect(listener);
    }

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
                : GraphCodecs.DOCUMENT.decode(JsonOps.INSTANCE, new JsonParser().parse(text));
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
