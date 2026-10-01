package com.crystalgui.app.shadergraph.preview;

import com.crystalgui.core.trace.UiTrace;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgui.widget.graph.node.NodeFieldBinder;
import com.crystalgui.ui.box.Box;
import com.crystalgui.app.shadergraph.ShaderGraphBridge;
import com.crystalgui.app.shadergraph.node.ShaderPortArity;
import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgPreviewRenderer;
import com.crystalgraphics.shadergraph.CgShaderGraph;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.ui.service.Animation;
import com.crystalgui.widget.graph.GraphNode;
import com.crystalgui.widget.graph.GraphContext;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps every visible node's thumbnail up to date, and nothing else's.
 *
 * <h3>What this class is actually for</h3>
 * <p>The renderer knows how to draw <em>one</em> preview and the pool knows how many may exist. Neither
 * knows when a graph changed or which nodes are on screen — both of which are the editor's business. This
 * is that seam, and keeping it out of {@link GraphContext} is deliberate: a graph view is a general widget
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
public final class ShaderGraphPreviews  {

    private final GraphContext view;
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

    public ShaderGraphPreviews(GraphContext view, CgShaderNodeRegistry shaderNodes, CgMasterNode master) {
        this(view, shaderNodes, master, new CgPreviewRenderer());
    }

    public ShaderGraphPreviews(GraphContext view, CgShaderNodeRegistry shaderNodes, CgMasterNode master,
                               CgPreviewRenderer renderer) {
        this.view = view;
        this.shaderNodes = shaderNodes;
        this.master = master;
        this.renderer = renderer;
        // Any structural change invalidates every emitted source downstream of it, and working out
        // exactly which is more expensive than letting the source comparison decide per node.
        view.connectionsChanged().connect(this::invalidate);
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
     * <p>Attaching a slot is what makes {@code __preview__} appear — {@link GraphNode#preview()} creates it
     * lazily, so a node that never asks stays the height of its ports. A second call slots the nodes added
     * since and registers nothing again: the extension attaches while the view is still empty, and the view
     * attaches once it has nodes.</p>
     */
    public ShaderGraphPreviews attach() {
        for (GraphNode node : view.nodes()) attachTo(node);
        if (!attached) {
            // Once: each call registered another tick, and the previews drew twice their budget a frame.
            attached = true;
            // NOT `this`: ShaderGraphPreviews is a scheduler rather than a node, so the hook is OWNED
            // by the surface it drives -- which is also what stops it outliving that surface.
            view.everyFrame(this::tickFrame);
            // Dynamic port widths, colours and inline-editor shapes. Installed from here rather than left to
            // the caller because this class is what owns the "a field changed, recompile" hook a rebuilt
            // editor has to write through — the same one NodeFieldBinder is given below.
            ShaderPortArity.install(view, () -> {
                invalidate();
                requestRecompile();
            });
        }
        invalidate();
        return this;
    }

    private boolean attached;

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
                NodeFieldBinder.attach(
                        node, nodeType, view.getDocument(), view.undoStack(), () -> {
                            // A field changes the emitted GLSL, so it invalidates this node's thumbnail
                            // and everything downstream — which is what a full invalidate is.
                            invalidate();
                            requestRecompile();
                        });
            }
        }

        if (!canPreview(node)) return;
        for (var child : node.preview().children()) {
            if (child instanceof ShaderNodePreview) return;
        }
        node.preview().append(new ShaderNodePreview(renderer, nodeId));
    }

    /** Whether the shader library says this node produces anything a thumbnail could show. */
    private boolean canPreview(GraphNode node) {
        var type = node.getTypeId() == null ? null : shaderNodes.get(node.getTypeId());
        // Null covers both the master (which is not in the registry) and any widget-authored node.
        // showsPreview() excludes constants: a Float's thumbnail is a flat field of exactly the number
        // printed above it, and the empty slot alone makes the node several times taller than its values.
        return type != null && !type.outputs().isEmpty() && type.showsPreview();
    }

        public boolean tickFrame(float deltaSeconds) {
            try (CgTrace.Zone ignored = CgTrace.zone(UiTrace.FRAME, "sg:previews")) {
                return tickFrameTraced(deltaSeconds);
            }
        }

        private boolean tickFrameTraced(float deltaSeconds) {
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
        // NULL IS "NOT LAID OUT YET", which the paragraph above already counts as a reason to skip --
        // it simply could not be asked that way. A node has no box at all until it has been laid out,
        // and this ticker runs on the frame the graph is built.
        Box box = view.viewportBox();
        if (box == null || box.width() <= 0f || box.height() <= 0f) return true;
        // OFF THE GL THREAD -- a document recording on its own sequence -- the thumbnails keep their last picture
        // until previews are recorded with the frame (plan render-graph G3.5).
        if (!CgGL.ownedByCurrentThread()) return true;

        // Newly added nodes get their slot here rather than through a second signal, and only when the plane's
        // nodes changed -- a node rebuilt under its old id is a removal and an insertion, so it counts.
        boolean nodesMoved = view.nodesRevision() != nodesSeen;
        if (nodesMoved || view.cullRevision() != cullSeen) {
            nodesSeen = view.nodesRevision();
            cullSeen = view.cullRevision();
            visible.clear();
            present.clear();
            List<GraphNode> nodes = view.nodes();
            for (int i = 0; i < nodes.size(); i++) {
                GraphNode node = nodes.get(i);
                if (nodesMoved) attachTo(node);
                if (node.getNodeId() == null) continue;
                present.add(node.getNodeId());
                // ON SCREEN ONLY: the renderer's visible set is its render set, and a culled node's thumbnail
                // is drawn into a target nobody composites.
                if (!view.isCulled(node)) visible.add(node.getNodeId());
            }
            if (nodesMoved) renderer.retainNodes(present);
            renderer.setVisible(visible);
        }
        renderer.renderPending(graph);
        // Always keeps ticking: a Time-driven preview has nothing else to wake it.
        return true;
    }

    /** {@link GraphContext#nodesRevision} when the nodes were last attached; the first tick always attaches. */
    private int nodesSeen = Integer.MIN_VALUE;
    /** {@link GraphContext#cullRevision} when the visible set was last sent. */
    private int cullSeen = Integer.MIN_VALUE;

    /** The on-screen nodes' ids, as last sent to the renderer. */
    private final Set<String> visible = new HashSet<>();
    /** Every node's id, culled or not: what the renderer may keep materials for. */
    private final Set<String> present = new HashSet<>();

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
