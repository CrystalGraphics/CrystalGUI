package com.crystalgui.app.shadergraph;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.LinkedHashMap;
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
import com.crystalgui.widget.graph.GraphContext;

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
 * written against a surface and reach it through {@link GraphContext}, which is deliberately free of anything
 * shader-shaped — so they need somewhere to agree on the node registry, the master node and when to recompile,
 * and none of them may name the editor or each other to get it. Two panes onto one file share these for the same
 * reason they share an undo stack: <b>one compile per change, however many panes show it</b>.</p>
 *
 * <p>What is NOT shared is what each pane builds on its own surface — its previews, its floating Main Preview
 * and its Blackboard. Those are kept per surface, and a pane asks for its own.</p>
 *
 * <p>Entries are weakly held, so closing a graph drops them with the document.</p>
 */
public final class ShaderGraphServices {

    private static final Map<GraphDocument, ShaderGraphServices> BY_DOCUMENT =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The services for {@code document}, created on first ask. */
    public static ShaderGraphServices of(GraphDocument document) {
        synchronized (BY_DOCUMENT) {
            return BY_DOCUMENT.computeIfAbsent(document, ShaderGraphServices::new);
        }
    }

    /** Weak: the map's value may not hold its own key, or no document would ever leave it. */
    private final WeakReference<GraphDocument> document;

    /** Every structural change to the document, counted. @see #compileIfStale */
    private int changes;

    /** {@link #changes} at the last compile, or -1 before the first. */
    private int compiledAt = -1;

    private ShaderGraphServices(GraphDocument document) {
        this.document = new WeakReference<>(document);
        document.onChanged.connect(() -> changes++);
    }

    /** The shader node set every feature compiles and previews against. */
    private final CgShaderNodeRegistry nodes = CgShaderNodeRegistry.builtins();

    /** The output the graph terminates in. */
    private final CgMasterNode master = new CgMasterNode();

    /**
     * Fires when a recompile is asked for, before it runs.
     *
     * <p>Anything that changes what the graph EMITS asks — a wire, a dropdown, a property retype. A connection is a
     * discrete user action so it needs no debouncing; the previews debounce their own side because a field edit is
     * per-keystroke.</p>
     */
    public final Signal.Action recompileRequested = new Signal.Action();

    /** Fires with each finished compile, successful or not. Every pane's source readout and status entry, and the
     * document's diagnostics, are listeners like any other. */
    public final Signal.Value<CgShaderEmitter.Result> compiled = new Signal.Value<>();

    @Nullable
    private CgShaderEmitter.Result lastResult;

    /** What one surface's extensions built. */
    private static final class Panels {
        @Nullable
        ShaderGraphPreviews previews;
        @Nullable
        MainPreviewPanel mainPreview;
        @Nullable
        BlackboardPanel blackboard;
    }

    private final Map<GraphContext, Panels> panels = new LinkedHashMap<>();

    /**
     * {@code surface}'s node previews, or null until its extensions have come up — later than an editor's
     * constructor, so every reader guards. A session restored before the panels exist is applied when they arrive
     * rather than lost; see {@link #panelsReady}.
     */
    @Nullable
    public ShaderGraphPreviews previews(GraphContext surface) {
        Panels built = panels.get(surface);
        return built == null ? null : built.previews;
    }

    /** @see #previews */
    @Nullable
    public MainPreviewPanel mainPreview(GraphContext surface) {
        Panels built = panels.get(surface);
        return built == null ? null : built.mainPreview;
    }

    /** @see #previews */
    @Nullable
    public BlackboardPanel blackboard(GraphContext surface) {
        Panels built = panels.get(surface);
        return built == null ? null : built.blackboard;
    }

    /** Called by the extension that builds them, for its own surface. */
    public void publishPreviews(GraphContext surface, ShaderGraphPreviews built, MainPreviewPanel panel) {
        Panels held = panels.computeIfAbsent(surface, ignored -> new Panels());
        held.previews = built;
        held.mainPreview = panel;
        panelsReady.emit();
    }

    /** @see #publishPreviews */
    public void publishBlackboard(GraphContext surface, BlackboardPanel panel) {
        panels.computeIfAbsent(surface, ignored -> new Panels()).blackboard = panel;
        panelsReady.emit();
    }

    /** Forgets what {@code surface} built, for a surface whose extensions have been retired. */
    public void withdraw(GraphContext surface) {
        panels.remove(surface);
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

    /** Compiles now and tells every listener — for a change the document's own count cannot see, a dropdown. */
    public void requestRecompile() {
        recompileRequested.emit();
        compile();
    }

    /**
     * Compiles unless nothing has changed since the last compile — what a pane calls when its wires change. With
     * two panes onto one graph both see every change, and only the first compiles.
     */
    public void compileIfStale() {
        if (lastResult != null && compiledAt == changes) return;
        compile();
    }

    private void compile() {
        GraphDocument graph = document.get();
        if (graph == null) return;
        compiledAt = changes;
        lastResult = ShaderGraphBridge.compile(graph, nodes, master);
        compiled.emit(lastResult);
    }
}
