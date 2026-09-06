package com.crystalgui.app.shadergraph;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import com.crystalgraphics.shadergraph.CgMasterNode;
import com.crystalgraphics.shadergraph.CgShaderEmitter;
import com.crystalgraphics.shadergraph.CgShaderNodeRegistry;
import com.crystalgui.app.shadergraph.blackboard.BlackboardPanel;
import com.crystalgui.app.shadergraph.preview.MainPreviewPanel;
import com.crystalgui.app.shadergraph.preview.ShaderGraphPreviews;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.graph.GraphDocument;

/**
 * The shader domain's shared state, per open graph.
 *
 * <pre>{@code
 * ShaderGraphServices shader = ShaderGraphServices.of(graph.getDocument());
 * shader.compiled.connect(this::showSource);
 * shader.requestRecompile();
 * }</pre>
 *
 * <p><b>Keyed on the document, because that is what "one shader graph" means.</b> Five features are
 * written against a surface and reach it through {@link com.crystalgui.widget.graph.GraphContext},
 * which is deliberately free of anything shader-shaped — so they need somewhere to agree on the node
 * registry, the master node and when to recompile, and none of them may name the editor or each other
 * to get it. Two panes onto one file share these for the same reason they share an undo stack.</p>
 *
 * <p>Entries are weakly held, so closing a graph drops them with the document.</p>
 */
public final class ShaderGraphServices {

    private static final Map<GraphDocument, ShaderGraphServices> BY_DOCUMENT =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The services for {@code document}, created on first ask. */
    public static ShaderGraphServices of(GraphDocument document) {
        synchronized (BY_DOCUMENT) {
            return BY_DOCUMENT.computeIfAbsent(document, ignored -> new ShaderGraphServices());
        }
    }

    private ShaderGraphServices() {
    }

    /** The shader node set every feature compiles and previews against. */
    private final CgShaderNodeRegistry nodes = CgShaderNodeRegistry.builtins();

    /** The output the graph terminates in. */
    private final CgMasterNode master = new CgMasterNode();

    /**
     * Asks for a recompile.
     *
     * <p>Anything that changes what the graph EMITS fires this — a wire, a dropdown, a property retype.
     * A connection is a discrete user action so it needs no debouncing; the previews debounce their own
     * side because a field edit is per-keystroke.</p>
     */
    public final Signal.Action recompileRequested = new Signal.Action();

    /** Fires with each finished compile, successful or not. The editor's source pane and status entry
     * are listeners like any other. */
    public final Signal.Value<CgShaderEmitter.Result> compiled = new Signal.Value<>();

    @Nullable
    private CgShaderEmitter.Result lastResult;

    /**
     * What the extensions built, for the editor that has to save and restore it.
     *
     * <p>Null until the surface is attached and its extensions come up, which is later than an editor's
     * constructor — so every reader guards. A session restored before the panels exist is applied when
     * they arrive rather than lost.</p>
     */
    @Nullable
    private ShaderGraphPreviews previews;

    @Nullable
    private MainPreviewPanel mainPreview;

    @Nullable
    private BlackboardPanel blackboard;

    @Nullable
    public ShaderGraphPreviews previews() {
        return previews;
    }

    @Nullable
    public MainPreviewPanel mainPreview() {
        return mainPreview;
    }

    @Nullable
    public BlackboardPanel blackboard() {
        return blackboard;
    }

    /** Called by the extension that owns each. */
    public void publishPreviews(ShaderGraphPreviews built, MainPreviewPanel panel) {
        this.previews = built;
        this.mainPreview = panel;
        panelsReady.emit();
    }

    /** @see #publishPreviews */
    public void publishBlackboard(BlackboardPanel panel) {
        this.blackboard = panel;
        panelsReady.emit();
    }

    /** Fires whenever a panel arrives, so a session restored before the extensions came up can be
     * applied rather than dropped. */
    public final Signal.Action panelsReady = new Signal.Action();

    public CgShaderNodeRegistry nodes() {
        return nodes;
    }

    public CgMasterNode master() {
        return master;
    }

    /** The most recent compile, or null before the first. */
    @Nullable
    public CgShaderEmitter.Result lastResult() {
        return lastResult;
    }

    /** Called by whatever ran the compile. */
    public void publish(CgShaderEmitter.Result result) {
        this.lastResult = result;
        compiled.emit(result);
    }

    /** Cheap and idempotent — call it on every change that could alter the emitted source. */
    public void requestRecompile() {
        recompileRequested.emit();
    }
}
