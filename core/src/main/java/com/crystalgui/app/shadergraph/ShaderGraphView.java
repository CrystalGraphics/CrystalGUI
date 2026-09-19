package com.crystalgui.app.shadergraph;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgraphics.shadergraph.CgShaderProblem;
import com.crystalgui.app.shadergraph.blackboard.BlackboardPanel;
import com.crystalgui.app.shadergraph.extension.BlackboardExtension;
import com.crystalgui.app.shadergraph.extension.GeneratedShaderExtension;
import com.crystalgui.app.shadergraph.extension.NodeLibraryExtension;
import com.crystalgui.app.shadergraph.extension.PreviewsExtension;
import com.crystalgui.app.shadergraph.extension.ShaderSectionsExtension;
import com.crystalgui.app.shadergraph.preview.MainPreviewPanel;
import com.crystalgui.app.shadergraph.preview.ShaderGraphPreviews;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataContext;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.data.DataProvider;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.dispose.Disposer;
import com.crystalgui.core.notify.StatusBar;
import com.crystalgui.core.notify.StatusBarAlignment;
import com.crystalgui.core.notify.StatusBarEntry;
import com.crystalgui.core.notify.StatusBarEntryAccessor;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.fs.Resource;
import com.crystalgui.graph.NodeType;
import com.crystalgui.graph.NodeTypeRegistry;
import com.crystalgui.serialization.StateMap;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.style.sheet.StyleSheetRegistry;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphView;

/**
 * One pane onto a {@link ShaderGraphDocument}: the node canvas with its floating Main Preview and Blackboard, and
 * where this pane is looking. The graph, its history and its compile are the document's, so two panes onto one
 * file edit one graph — each with its own camera, selection and panels.
 *
 * <pre>{@code
 * ShaderGraphView pane = new ShaderGraphView(document);
 * ShaderGraphView standalone = new ShaderGraphView();   // a graph with no file, as the gallery builds one
 * }</pre>
 *
 * <p>The generated GLSL is not here: it is a tab of its own, {@link GeneratedSourceView}, which a host places
 * where it likes — a docking host wants it draggable, closable and restorable like every other tab.</p>
 *
 * <p>Previews attach from a frame hook once there is a window, never from the layout pass: attaching adds elements,
 * and doing that inside layout re-dirtied the tree on every pass until the window hung.</p>
 */
public class ShaderGraphView extends UIElement implements DocumentEditor, Disposable.Gl, DataProvider {
    /** A whole shader graph editor. Named by the sheets. */
    public static final Name NAME = Name.of("shadergrapheditor");

    /** UNIQUE, never the shared "__content__" -- see ProjectFileTree.CONTENT_CLASS for why. */
    public static final String CONTENT_CLASS = "__shader-content__";
    public static final String GRAPH_CLASS = "__shader-graph__";

    /**
     * One compile's summary, or that it failed — a {@link StatusBar} item, while this pane is in front.
     *
     * <p>Announced rather than relayed, which is what lets the contribution take no status sink.</p>
     */
    public static final String COMPILE_STATUS = "shadergraph.compile";

    /**
     * Where the canvas was looking, as settings on the document — read as the fallback for a graph nobody here has
     * looked at before, and written by nothing any more: where you look is per person. @see #writeViewState
     */
    public static final String VIEW_ZOOM = "graph.view.zoom";
    public static final String VIEW_PAN_X = "graph.view.panX";
    public static final String VIEW_PAN_Y = "graph.view.panY";
    /** Where the two floating panels sit and how big they are. Same rules as the camera. */
    public static final String VIEW_PREVIEW_RECT = "graph.view.previewRect";
    public static final String VIEW_BLACKBOARD_RECT = "graph.view.blackboardRect";

    /** Opens the GLSL this graph emits, as its own tab. */
    public static final String VIEW_GENERATED_COMMAND = "shadergraph.viewGenerated";

    /** This editor, for a command that acts on one. Declared here because it is this feature's concept. */
    public static final DataKey<ShaderGraphView> SHADER_GRAPH =
            DataKey.create("shaderGraph.new", ShaderGraphView.class);

    /**
     * What this graph's readout says when it will not compile — <b>deliberately not a count</b>. The count belongs to
     * the workspace entry beside it, which owns every file; two numbers of different scopes, adjacent, read as a
     * contradiction.
     */
    private static final String FAILED_STATUS = "compile failed";

    private static final int COMPILE_PRIORITY = 100;

    /**
     * The five features that make a node graph a SHADER graph, by id. A {@code GraphView} enabling none of them is a
     * plain node editor; each is discovered on the classpath and reaches the graph through
     * {@link com.crystalgui.widget.graph.GraphContext}, never through this editor.
     */
    private static final List<String> SHADER_EXTENSIONS = List.of(
            NodeLibraryExtension.ID,
            GeneratedShaderExtension.ID,
            PreviewsExtension.ID,
            BlackboardExtension.ID,
            ShaderSectionsExtension.ID);

    private final ShaderGraphDocument document;
    private final GraphView graph;

    /** Marked internal exactly ONCE, while empty: `markAsInternal()` recurses, and removals under an internal
     * subtree are silently refused -- the previews' retired thumbnails piled up until the window hung. */
    private final UIElement content = new UIElement();

    /**
     * The compile entry this pane owns while it is in front. A handle rather than a key, so withdrawing it is
     * disposing what was registered; null in the background, which is what makes "entitled to speak" a fact.
     */
    @Nullable
    private StatusBarEntryAccessor compileEntry;

    private boolean statusActive;

    /** The panel rectangles last read, held until this pane's panels exist. @see #applyPendingViewState */
    @Nullable
    private String pendingPreviewRect = "";
    @Nullable
    private String pendingBoardRect = "";

    private boolean previewsAttached;
    private boolean mainPreviewAttached;
    private boolean ticking;

    /** @see #ShaderGraphView(ShaderGraphDocument) */
    private final Connection adoption;

    /** Asked for when someone invokes {@link #VIEW_GENERATED_COMMAND} on this graph — the shell decides what a tab
     * is and where it goes; the graph knows nothing about docks. */
    public final Signal.Action onViewGeneratedRequested = new Signal.Action();

    /** A graph with no file behind it. */
    public ShaderGraphView() {
        this(new ShaderGraphDocument());
    }

    public ShaderGraphView(ShaderGraphDocument document) {
        super(NAME);
        this.document = document;
        this.graph = new GraphView(document.graph(), document.history(), SHADER_EXTENSIONS);
        graph.addClass(GRAPH_CLASS);
        content.addClass(CONTENT_CLASS);
        append(content);
        content.append(graph);

        // A GRAPH ALREADY HERE -- a second pane, a file loaded before its tab was built -- is on the plane at once:
        // the extensions are up, so the node factory is.
        graph.syncFromDocument();
        restoreView();

        whileConnected(() -> document.shader().compiled.connect(result -> refreshStatus()));
        whileConnected(() -> document.diagnostics().onChanged.connect(this::refreshStatus));
        // A FILE OPENED INTO THE DOCUMENT is on this pane at once, attached or not -- a layout restore builds panes
        // before it shows them -- and the camera the file carried with it is the fallback for where to look. For the
        // pane's whole life, so it ends in dispose().
        adoption = document.onDidAdopt.connect(() -> {
            graph.syncFromDocument();
            restoreView();
        });
        whileConnected(() -> document.onDidChangeResource.connect(this::nameBlackboard));
        // A session can be restored before the surface is attached, which is when the extensions come up.
        whileConnected(() -> document.shader().panelsReady.connect(() -> {
            applyPendingViewState();
            nameBlackboard();
        }));
    }

    public ShaderGraphDocument model() {
        return document;
    }

    public GraphView graph() {
        return graph;
    }

    public NodeTypeRegistry library() {
        return graph.getNodeLibrary();
    }

    /** This pane's floating preview, or null before its extensions came up. */
    @Nullable
    public MainPreviewPanel mainPreview() {
        return document.shader().mainPreview(graph);
    }

    /** This pane's floating property board, or null before its extensions came up. @see BlackboardPanel */
    @Nullable
    public BlackboardPanel blackboard() {
        return document.shader().blackboard(graph);
    }

    /** @see ShaderGraphDocument#master() */
    public CgMasterNode master() {
        return document.master();
    }

    /** @see ShaderGraphDocument#lastCompile() */
    @Nullable
    public CgShaderEmitter.Result lastCompile() {
        return document.lastCompile();
    }

    /** @see ShaderGraphDocument#diagnostics() */
    public DiagnosticSet diagnostics() {
        return document.diagnostics();
    }

    public void recompile() {
        document.recompile();
    }

    /** Seeds the document with the starter graph, and shows it here at once. @see ShaderGraphDocument#addStarterGraph */
    public ShaderGraphView addStarterGraph() {
        document.addStarterGraph();
        graph.syncFromDocument();
        return this;
    }

    /** Builds a widget for a library type and places it, keeping the document binding the factory does. */
    public GraphNode addNode(NodeType type, float x, float y) {
        GraphNode node = graph.getNodeFactory().create(type, type.create(x, y));
        graph.addNode(node, x, y);
        return node;
    }

    public ShaderGraphView fitToContent() {
        graph.fitToContent(24f);
        return this;
    }

    // ── The status bar ──────────────────────────────────────────────────────────────────────────

    @Override
    public void activated(boolean active) {
        statusActive = active;
        if (active) {
            refreshStatus();
            return;
        }
        if (compileEntry != null) compileEntry.dispose();
        compileEntry = null;
    }

    /**
     * Puts the compile summary on the bar, or updates the one already there — <b>only while this pane is in
     * front</b>: a graph recompiles whether or not you are looking at it, and writing unconditionally put a background
     * document's summary under somebody else's file.
     *
     * <p>It follows the diagnostics and not just the emit: a driver refusal arrives after a successful emit, and
     * "compiled" beside a blank preview and a red row in Problems is the most misleading thing the bar can say.</p>
     */
    private void refreshStatus() {
        CgShaderEmitter.Result result = document.lastCompile();
        if (!statusActive || result == null) return;
        DiagnosticSet problems = document.diagnostics();
        boolean failed = !result.ok() || problems.count(DiagnosticSeverity.ERROR) > 0;
        String text = failed ? FAILED_STATUS : String.format("compiled  %dn/%de",
                document.graph().nodeCount(), document.graph().edges().size());
        String tooltip = failed ? firstError(result, problems) : String.format("%d chars, %d varyings, %d mapped lines",
                result.source().length(), result.varyings().size(), result.lineOwners().size());
        // A FAILING READOUT IS A WAY IN, as VS Code's error counter opens its Problems panel.
        StatusBarEntry entry = new StatusBarEntry("Shader graph compilation", text, tooltip,
                failed ? "workbench.showProblems" : null,
                failed ? StatusBarEntry.Kind.ERROR : StatusBarEntry.Kind.STANDARD);
        if (compileEntry != null) {
            compileEntry.update(entry);
            return;
        }
        // THERE MAY BE NO BAR YET, and that is routine: the dock detaches a panel's widget to rebuild the strip
        // around it and announces the active panel while it is out of the tree. connected() writes it later.
        StatusBar bar = DataContext.from(this).get(UiDataKeys.STATUS_BAR);
        if (bar == null) return;
        compileEntry = bar.addEntry(entry, COMPILE_STATUS, StatusBarAlignment.LEFT, COMPILE_PRIORITY);
    }

    private static String firstError(CgShaderEmitter.Result result, DiagnosticSet problems) {
        for (Diagnostic diagnostic : problems.all()) {
            if (diagnostic.severity() == DiagnosticSeverity.ERROR) return diagnostic.message();
        }
        return result.errors().isEmpty() ? FAILED_STATUS : result.errors().get(0);
    }

    // ── Where this pane is looking ──────────────────────────────────────────────────────────────

    /**
     * Where <b>this person</b> was looking — pan, zoom and the two floating panels. Per session and per pane, never
     * in the file: with the camera in the document, whoever saved last would impose their view on everyone else.
     */
    @Override
    public <T> void writeViewState(StateMap<T> out) {
        out.putFloat(VIEW_ZOOM, graph.getZoom());
        out.putFloat(VIEW_PAN_X, graph.getPanX());
        out.putFloat(VIEW_PAN_Y, graph.getPanY());
        // ONLY WHEN THERE IS A BOX TO RECORD. An unmeasured panel yields "", and writing that would erase a good
        // rect rather than leave the one already stored.
        String preview = rectOf(mainPreview());
        if (!preview.isEmpty()) out.putString(VIEW_PREVIEW_RECT, preview);
        String board = rectOf(blackboard());
        if (!board.isEmpty()) out.putString(VIEW_BLACKBOARD_RECT, board);
    }

    /** @see #writeViewState */
    @Override
    public <T> void readViewState(StateMap<T> in) {
        float zoom = in.getFloat(VIEW_ZOOM, 0f);
        if (zoom > 0f) graph.setZoom(zoom);
        // BOTH OR NEITHER: a pan is a point, and applying one axis moves the camera somewhere nobody left it.
        if (in.has(VIEW_PAN_X) && in.has(VIEW_PAN_Y)) {
            graph.setPan(in.getFloat(VIEW_PAN_X, 0f), in.getFloat(VIEW_PAN_Y, 0f));
        }
        pendingPreviewRect = in.getString(VIEW_PREVIEW_RECT, "");
        pendingBoardRect = in.getString(VIEW_BLACKBOARD_RECT, "");
        applyPendingViewState();
    }

    /** Seeds the camera from the file, for a graph this client has no session entry for. */
    private void restoreView() {
        var settings = document.graph().settings();
        Float zoom = readFloat(settings.raw(VIEW_ZOOM));
        Float panX = readFloat(settings.raw(VIEW_PAN_X));
        Float panY = readFloat(settings.raw(VIEW_PAN_Y));
        if (zoom != null) graph.setZoom(zoom);
        if (panX != null && panY != null) graph.setPan(panX, panY);
        pendingPreviewRect = settings.raw(VIEW_PREVIEW_RECT);
        pendingBoardRect = settings.raw(VIEW_BLACKBOARD_RECT);
        applyPendingViewState();
    }

    /**
     * Puts the last-read panel rectangles back, once there are panels to put them on. <b>A restored position is a
     * deliberate one and each panel has to be TOLD</b>, or the re-clamp that tracks a resizing canvas stays gated on
     * a drag that never happened.
     */
    private void applyPendingViewState() {
        MainPreviewPanel panel = mainPreview();
        if (panel != null && applyRect(panel, pendingPreviewRect)) panel.markPlaced();
        BlackboardPanel board = blackboard();
        if (board != null && applyRect(board, pendingBoardRect)) board.markPlaced();
    }

    /**
     * A panel's box as {@code left,top,width,height}, in its containing block's space — or empty while it has not
     * been laid out, or while its block is smaller than it: a background tab is {@code display: none}, and the panel
     * then measures its own minimum at the origin, which describes the clamp rather than where it was left.
     */
    private static String rectOf(@Nullable UIElement panel) {
        if (panel == null) return "";
        UIElement block = panel.parentElement();
        if (block == null) return "";
        Box box = panel.box();
        Box blockBox = block.box();
        if (box == null || blockBox == null) return "";
        if (box.width() <= 0f || box.height() <= 0f) return "";
        if (blockBox.width() < box.width() || blockBox.height() < box.height()) return "";
        // The panel's origin IN THE BLOCK'S SPACE: `Box.x()` is parent-relative.
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
        // INLINE, the origin the resizer and the drag both write at -- so a restored box is exactly a box the user
        // could have dragged to.
        StyleGroup.inlinePipeline(panel.getStyle().getLayoutGroup(),
                l -> l.left(left).top(top).width(width).height(height));
        return true;
    }

    @Nullable
    private static Float readFloat(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException malformed) {
            // A hand-edited or later-format file degrades to the default view rather than refusing to open.
            return null;
        }
    }

    /** The Blackboard is named after the DOCUMENT, without its extension, as Unity names it after the asset. */
    private void nameBlackboard() {
        BlackboardPanel board = blackboard();
        Resource resource = document.resource();
        if (board != null) board.setDocumentName(resource == null ? "" : stripExtension(resource.name()));
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        // A leading dot is the whole name of a dotfile, not an extension -- `.gitignore` must not become "".
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────────────────────

    @Override
    protected void connected() {
        super.connected();
        // THE STATUS THAT COULD NOT BE WRITTEN WHILE DETACHED, outside the ticking guard.
        refreshStatus();
        UIDocument window = document();
        // `Animation.every` is a plain add, and `disconnected()` clears the flag, or a pane hidden and reshown
        // comes back with the flag set and no hooks behind it.
        if (ticking || window == null) return;
        ticking = true;
        window.animation().every(this, this::attachPreviews);
        window.animation().every(this, this::reportPreviewVerdicts);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        ticking = false;
    }

    /**
     * Attaches this pane's preview renderers, retrying until they take: the main preview needs the GL context, which
     * may not exist on the first frame. Drops itself once both are up. NOTHING PER-FRAME BELONGS HERE.
     */
    private boolean attachPreviews(float deltaSeconds) {
        ensureGraphTheme();
        ShaderGraphPreviews built = document.shader().previews(graph);
        MainPreviewPanel panel = mainPreview();
        if (built == null || panel == null) return true;
        if (!previewsAttached) {
            built.attach();
            previewsAttached = true;
        }
        if (!mainPreviewAttached) {
            mainPreviewAttached = panel.attach();
            // Registered, because MainPreviewPanel's delete() had no caller and its createOwned target leaked.
            if (mainPreviewAttached) Disposer.register(this, panel);
        }
        return !(previewsAttached && mainPreviewAttached);
    }

    /**
     * Tells the document what only this pane's previews can see — the driver refusing the shader, a node's thumbnail
     * failing. A material compiles lazily on its first bind, so the verdict arrives a frame after the compile with
     * nothing left to report it; this is one comparison per frame against a value that changes about once a minute.
     */
    private boolean reportPreviewVerdicts(float deltaSeconds) {
        MainPreviewPanel panel = mainPreview();
        if (panel != null) document.reportDriverError(panel.lastDriverError());
        ShaderGraphPreviews built = document.shader().previews(graph);
        if (built != null) {
            Map<String, List<CgShaderProblem>> failures = built.renderer().failures();
            document.reportPreviewFailures(failures);
        }
        // NEVER DROPPED: there is no signal that says a compile is about to fail.
        return true;
    }

    /**
     * Installs {@code crystalgui:graph} on the window, once. A wire reads its colour out of the cascade, so without the
     * sheet every node is a grey box and every wire colourless. From the ticker rather than {@link #connected()},
     * because adding a sheet inside the layout pass is how this widget hung the window once already.
     */
    private void ensureGraphTheme() {
        UIDocument window = document();
        if (window == null) return;
        StyleSheet theme = StyleSheetRegistry.of("crystalgui:graph");
        if (window.styles().getSheets().contains(theme)) return;
        window.styles().addStylesheet(theme);
    }

    /**
     * Releases this pane's preview renderers and its surface — which retires its extensions, the inspector sections
     * among them. Safe to call more than once. The document is not this pane's to release: another may still show it.
     */
    @Override
    public void dispose() {
        ShaderGraphPreviews built = document.shader().previews(graph);
        if (previewsAttached && built != null) {
            built.delete();
            previewsAttached = false;
        }
        mainPreviewAttached = false;
        adoption.disconnect();
        graph.dispose();
    }

    /** The close hook {@code EditorService} calls when this pane's tab closes. */
    @Override
    public void disposeView() {
        dispose();
    }

    // ── Commands and data ───────────────────────────────────────────────────────────────────────

    /** Registered once for the class; no window needed. Unity's "View Generated Shader". */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        registry.register(Command.of(VIEW_GENERATED_COMMAND, "View Generated Shader")
                .run(context -> {
                    ShaderGraphView graph = editorFor(context);
                    if (graph != null) graph.onViewGeneratedRequested.emit();
                })
                .enabledWhen(context -> editorFor(context) != null));
    }

    /**
     * What this editor knows: itself. Not {@code SELECTION} — the {@code GraphView} inside answers that, and it is
     * inside, so the walk reaches it first.
     */
    @Override
    public Object getData(DataKey<?> key) {
        if (key == SHADER_GRAPH) return this;
        return null;
    }

    @Nullable
    private static ShaderGraphView editorFor(CommandContext context) {
        return context.data().get(SHADER_GRAPH);
    }

    @Override
    public UIElement view() {
        return this;
    }
}
