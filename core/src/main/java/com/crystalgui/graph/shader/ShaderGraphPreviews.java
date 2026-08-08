package com.crystalgui.graph.shader;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgPreviewRenderer;
import com.crystalgraphics.shadergraph.CgShaderGraph;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.ui.UIFrameTicker;
import com.crystalgui.ui.elements.graph.GraphNode;
import com.crystalgui.ui.elements.graph.GraphView;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps every visible node's thumbnail up to date, and nothing else's.
 *
 * <h3>What this class is actually for</h3>
 * <p>The renderer knows how to draw <em>one</em> preview and the pool knows how many may exist. Neither
 * knows when a graph changed or which nodes are on screen — both of which are the editor's business. This
 * is that seam, and keeping it out of {@link GraphView} is deliberate: a graph view is a general widget
 * that must stay usable for a dialogue tree or a state machine, and it should not acquire a dependency on
 * a GLSL compiler because one consumer renders shaders.</p>
 *
 * <h3>Three things bound the cost, and all three are needed</h3>
 * <ol>
 *   <li><b>Only visible nodes</b> — the cull set is the render set, so a node scrolled off screen gives
 *       its target back rather than being drawn where nobody is looking.</li>
 *   <li><b>Only changed nodes</b> — a preview whose emitted source is identical is not redrawn, so
 *       panning and selecting cost nothing at all.</li>
 *   <li><b>A per-frame budget</b> — a graph where everything changed at once still only pays a bounded
 *       number of passes per frame, and catches up over the next few.</li>
 * </ol>
 */
public final class ShaderGraphPreviews implements UIFrameTicker {

    private final GraphView view;
    private final CgShaderNodeRegistry shaderNodes;
    private final CgMasterNode master;
    private final CgPreviewRenderer renderer;

    /** Rebuilt only when the document changed, not per frame — the mapping walks the whole graph. */
    @Nullable
    private CgShaderGraph graph;
    private boolean graphDirty = true;

    /** Nodes whose property dropdowns have been built, so they are built exactly once. */
    private final Set<String> controlled = new HashSet<>();

    /**
     * Fires after a change that alters the emitted GLSL — a field edit, a connection — <b>debounced</b>.
     *
     * <p>Exists so the host can recompile the displayed source: the previews handle themselves, but
     * whatever is showing the generated {@code .shader} has no other way to learn the graph changed.</p>
     *
     * <h4>Debounced, because a field edit is not a discrete gesture</h4>
     * <p>A connection is one event and could drive a recompile directly. Typing into a number box is not
     * — it is one event per keystroke, and each would re-emit the whole shader and re-lay out a text
     * editor showing it. The signal therefore fires at most once per {@link #RECOMPILE_DEBOUNCE_SECONDS}
     * and always fires eventually, so the pane cannot be left showing a stale shader.</p>
     */
    public final com.crystalgui.core.signal.Signal.Action onPropertyChanged =
            new com.crystalgui.core.signal.Signal.Action();

    /** Long enough to swallow a burst of typing, short enough to feel immediate. */
    public static final float RECOMPILE_DEBOUNCE_SECONDS = 0.15f;

    private boolean recompilePending;
    private float sinceRecompileRequest;

    /** Requests a debounced recompile. Cheap and idempotent — call it on every change. */
    public void requestRecompile() {
        recompilePending = true;
        sinceRecompileRequest = 0f;
    }

    public ShaderGraphPreviews(GraphView view, CgShaderNodeRegistry shaderNodes, CgMasterNode master) {
        this(view, shaderNodes, master, new CgPreviewRenderer());
    }

    public ShaderGraphPreviews(GraphView view, CgShaderNodeRegistry shaderNodes, CgMasterNode master,
                               CgPreviewRenderer renderer) {
        this.view = view;
        this.shaderNodes = shaderNodes;
        this.master = master;
        this.renderer = renderer;
        // Any structural change invalidates every emitted source downstream of it, and working out
        // exactly which is more expensive than letting the source comparison decide per node.
        view.onConnectionsChanged.connect(this::invalidate);
    }

    public CgPreviewRenderer renderer() {
        return renderer;
    }

    /** Marks the document as changed. The per-node source comparison still decides what redraws. */
    public void invalidate() {
        graphDirty = true;
        renderer.invalidateAll();
    }

    /**
     * Gives every node a preview slot, and starts ticking.
     *
     * <p>Call once, after the graph has nodes. Attaching a slot is what makes {@code __preview__} appear
     * — {@link GraphNode#preview()} creates it lazily, so a node that never asks stays the height of its
     * ports.</p>
     */
    public ShaderGraphPreviews attach() {
        for (GraphNode node : view.nodes()) attachTo(node);
        var window = view.getAttachedWindow();
        if (window != null) window.registerTicker(this);
        // Dynamic port widths, colours and inline-editor shapes. Installed from here rather than left to
        // the caller because this class is what owns the "a field changed, recompile" hook a rebuilt
        // editor has to write through — the same one NodeFieldBinder is given below.
        ShaderPortArity.install(view, () -> {
            invalidate();
            requestRecompile();
        });
        invalidate();
        return this;
    }

    /**
     * Gives one node a preview slot, if it has an id, can actually be previewed, and has none yet.
     *
     * <p><b>A node with no output port is skipped entirely</b> rather than given an empty slot. The
     * Output node is the case that matters: it has two inputs and nothing to show, so a slot there is 78
     * pixels of dead space that can never fill in — which reads as a preview that is permanently broken.
     * Unity's master node has no preview for the same reason.</p>
     */
    public void attachTo(GraphNode node) {
        String nodeId = node.getNodeId();
        if (nodeId == null) return;

        // Controls first, and tracked by id rather than by inspecting the widget: a dropdown is added to
        // an internal child, so there is no cheap "does it already have one" question to ask, and adding
        // a second set every frame would be invisible until the node grew a stack of identical rows.
        //
        // Doubling as "have we ever seen this node before" for the compiled preview graph too: `graph`
        // (see #tickFrame) is only rebuilt when `invalidate()` runs, and that was wired ONLY to
        // `view.onConnectionsChanged` — there is no signal anywhere for "a node was added" on its own.
        // A freshly created, still-unwired node got a preview SLOT immediately (the loop below runs every
        // tick regardless), but the compiled `graph` snapshot it would be drawn FROM stayed stale until
        // some unrelated connection changed elsewhere and happened to invalidate everything — so
        // `CgPreviewEmitter.emit` could never find the node's own instance, failed silently, and (since a
        // failed node is deliberately never retried, or a broken material would recompile every frame)
        // stayed blank forever. This `controlled.add` check already fires exactly once per node, on
        // first sight, which is exactly the moment the compiled graph needs to catch up.
        if (controlled.add(nodeId)) {
            invalidate();
            var library = view.getNodeLibrary();
            var nodeType = node.getTypeId() == null || library == null
                    ? null : library.get(node.getTypeId());
            if (nodeType != null) {
                // The GENERIC binder: nothing shader-specific left here. Dropdowns and inline port
                // editors both come from the type's declared fields, and both write through the undo
                // stack rather than straight to the document.
                com.crystalgui.ui.elements.graph.NodeFieldBinder.attach(
                        node, nodeType, view.getDocument(), view.undoStack(), () -> {
                            // A field changes the emitted GLSL, so it invalidates this node's thumbnail
                            // and everything downstream — which is what a full invalidate is.
                            invalidate();
                            requestRecompile();
                        });
            }
        }

        if (!canPreview(node)) return;
        for (var child : node.preview().getChildren()) {
            if (child instanceof ShaderNodePreview) return;
        }
        node.preview().addChild(new ShaderNodePreview(renderer, nodeId));
    }

    /** Whether the shader library says this node produces anything a thumbnail could show. */
    private boolean canPreview(GraphNode node) {
        var type = node.getTypeId() == null ? null : shaderNodes.get(node.getTypeId());
        // Null covers both the master (which is not in the registry) and any widget-authored node.
        // showsPreview() excludes constants: a Float's thumbnail is a flat field of exactly the number
        // printed above it, and the empty slot alone makes the node several times taller than its values.
        return type != null && !type.outputs().isEmpty() && type.showsPreview();
    }

    @Override
    public boolean tickFrame(float deltaSeconds) {
        if (deleted) return false;
        // The debounce. Fires once the changes stop arriving, never per keystroke, and always fires —
        // a pane left showing a stale shader is worse than one that updates a beat late.
        if (recompilePending) {
            sinceRecompileRequest += deltaSeconds;
            if (sinceRecompileRequest >= RECOMPILE_DEBOUNCE_SECONDS) {
                recompilePending = false;
                onPropertyChanged.emit();
            }
        }
        if (graphDirty) {
            graph = ShaderGraphBridge.toShaderGraph(view.getDocument(), shaderNodes, master);
            graphDirty = false;
        }
        if (graph == null) return true;

        // NOT WHILE THE GRAPH IS OFF SCREEN, and this stopped being free the moment graphs became files.
        //
        // There used to be exactly one shader graph in the whole editor, so this cost was fixed. Now
        // there is one per open .shadergraph, every one of them lives in the tree — DockGroup builds
        // EVERY panel in a group, not just the visible one — and an inactive tab is hidden with
        // `display: none` rather than by being detached. So each background graph went on rendering an
        // FBO per node, every frame, for something nobody can see.
        //
        // Asked of the LAYOUT BOX rather than of the host: `display: none` resolves to zero size, so a
        // hidden tab, a collapsed pane and a graph that has not been laid out yet all answer the same
        // way, and none of them needs this class to know what a dock or a tab is. The debounce above
        // deliberately keeps running — a graph switched back to must show its current source, not
        // resume a recompile it was in the middle of.
        var box = view.getRuntimeCache();
        if (box.getWidth() <= 0f || box.getHeight() <= 0f) return true;

        // Newly added nodes get their slot here rather than through a second signal: the set is small,
        // the check is an identity scan over one child, and it cannot go stale.
        Set<String> visible = new HashSet<>();
        for (GraphNode node : view.nodes()) {
            attachTo(node);
            if (node.getNodeId() != null) visible.add(node.getNodeId());
        }
        renderer.setVisible(visible);
        renderer.renderPending(graph);
        // Always keeps ticking: a Time-driven preview has nothing else to wake it.
        return true;
    }

    /**
     * Frees every target and mesh. Must run before the GL context goes away.
     *
     * <p>Idempotent, and it sets the flag {@link #tickFrame} reads. Deleting the renderer without saying
     * so leaves a ticker calling into it every frame, which is a throw rather than a no-op — see the note
     * at the top of {@code tickFrame}.</p>
     */
    public void delete() {
        if (deleted) return;
        deleted = true;
        renderer.delete();
    }

    /** @see #delete() */
    private boolean deleted;
}
