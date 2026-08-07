package com.crystalgui.graph.shader;

import com.crystalgui.core.signal.Signal;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.dock.DockInput;
import com.crystalgui.ui.elements.dock.DockOpenOptions;
import com.crystalgui.ui.elements.dock.DockPanelDescriptor;
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.dock.DockPlacement;
import com.crystalgui.ui.elements.inspector.InspectorRegistry;
import com.crystalgui.ui.elements.workbench.DocumentType;
import com.crystalgui.ui.elements.workbench.FileDocument;
import com.crystalgui.ui.elements.workbench.Workbench;

import javax.annotation.Nullable;

/**
 * Everything the shader-graph feature declares about itself.
 *
 * <h3>Why this class exists</h3>
 *
 * <p>All of it lived in {@code CrystalEditor}: which extension opens as a graph, how to build one, what
 * its generated source is, which panel shows it, and an inspector type named after it. That made the
 * <em>general</em> editor the place a file type is added — invasive, where both references are additive.
 * IntelliJ declares a {@code FileEditorProvider} in a plugin; VS Code declares
 * {@code contributes.customEditors} in a manifest; neither platform has a class that enumerates file
 * types, because the set is open by construction.</p>
 *
 * <p>So the package that owns the type declares it, and an application's only decision is which
 * contributions to enable — one call naming one package.</p>
 */
public final class ShaderGraphContribution {

    private ShaderGraphContribution() {
    }

    /** A {@code .shadergraph} file. The only kind of shader graph — there is no pathless scratch one. */
    public static final String GRAPH_TYPE = "shadergraph.file";

    /** The GLSL a graph emits, as a document of its own — one per graph. */
    public static final String SOURCE_TYPE = "shadersource";

    /**
     * The scheme a generated shader lives under.
     *
     * <p>The panel type says which widget draws it; the scheme says what it <b>is</b> and carries the
     * origin. Two names for what looks like one thing, and genuinely different questions: a diff view of
     * the same generated source would share the scheme and not the type.</p>
     */
    public static final String SOURCE_SCHEME = "shader-generated";

    /** The descriptor's fallback title. A real tab is named for its graph — see {@link #titleFor}. */
    public static final String SOURCE_TITLE = "compiled_graph.shader";

    /**
     * Declares the graph document type, its generated-source view, and its inspector sections.
     *
     * @param status where a graph's own messages go — the one thing the application still supplies,
     *               because where a status line lives is its decision and not this feature's
     */
    public static void register(Workbench workbench, Signal.Value<String> status) {
        workbench.contribute(DocumentType.of(GRAPH_TYPE, "Shader Graph")
                .forExtensions("shadergraph")
                .document(path -> {
                    ShaderGraphEditor editor = new ShaderGraphEditor();
                    editor.setResource(Resource.of(path));
                    editor.onStatusChanged.connect(status::emit);
                    editor.onLineOwnerChanged.connect(status::emit);
                    // THE GRAPH ASKS, THE SHELL DECIDES -- and the shell is this contribution now rather
                    // than the application. The graph knows it can emit GLSL and nothing about docks.
                    editor.onViewGeneratedRequested.connect(() -> showGenerated(workbench, editor));

                    // The graph's own selection announces itself -- GraphSelection does it, so no
                    // contribution has to remember to. What is left here is the one announcement this
                    // package genuinely owns, below.
                    //
                    // NOT onStatusChanged. A graph with an animated node recompiles continuously, so
                    // announcing per compile rebuilt the inspector every frame -- which tore its tabs
                    // down under the pointer and made them take three or four clicks to hit. The Graph
                    // tab's compile stats go slightly stale between real subject changes, which is the
                    // right trade: they are derived numbers, not something being interacted with.
                    if (editor.blackboard() != null) {
                        editor.blackboard().onPropertySelected.connect(
                                id -> InspectorRegistry.subjectChanged());
                    }
                    return editor;
                }));

        // A DOCUMENT, one per graph -- not a singleton view following the front tab. Five open graphs have
        // five different generated shaders, and one shared panel cannot be diffed against another, cannot
        // be left open beside a second graph, and loses its scroll position on every tab change. Unity's
        // Shader Graph opens "View Generated Shader" per graph for the same reason.
        workbench.registerPanel(DockPanelDescriptor.document(SOURCE_TYPE, SOURCE_TITLE),
                ref -> {
                    ShaderGraphEditor graph = graphFor(workbench, ref.state(DockPanelRef.PATH, ""));
                    // An empty box rather than null when the graph has since closed: a tab with nothing
                    // behind it is visible and reportable, a silently absent one looks like a failed
                    // restore.
                    return graph == null ? new UIElement() : graph.source();
                });

        ShaderInspectorSections.register();
    }


    /** A generated resource to the tab label its graph deserves. */
    public static String titleFor(Resource generated) {
        String name = generated.name();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + "_compiled.shader";
    }

    /** Opens the generated shader for {@code graph}, beside it. */
    public static boolean showGenerated(Workbench workbench, @Nullable ShaderGraphEditor graph) {
        if (graph == null || graph.resource() == null) return false;
        Resource generated = Resource.derived(SOURCE_SCHEME, graph.resource());
        DockPanelRef ref = new DockPanelRef(SOURCE_TYPE)
                .withState(DockPanelRef.PATH, generated.toString())
                .withState(DockPanelRef.TITLE, titleFor(generated));
        workbench.open(DockInput.of(ref), DockPlacement.with(graph), DockOpenOptions.ACTIVATE);
        return true;
    }

    /**
     * The graph a generated-source tab is showing, resolved from its own input.
     *
     * <p>The derived resource carries the origin, so this is a parse and a lookup rather than a map kept
     * beside the document store.</p>
     */
    @Nullable
    private static ShaderGraphEditor graphFor(Workbench workbench, String rawResource) {
        if (rawResource.isEmpty()) return null;
        Resource parsed;
        try {
            parsed = Resource.parse(rawResource);
        } catch (RuntimeException unparseable) {
            return null;
        }
        // A session saved before this panel's state became a derived resource stored the graph's bare
        // path, which parses as a project resource with no origin. Reading it as the origin itself costs
        // one line against invalidating every saved layout that had the tab open.
        Resource origin = parsed.origin() != null ? parsed.origin() : parsed;
        if (!origin.isProject()) return null;
        FileDocument document = workbench.documentFor(origin.asPath());
        return document instanceof ShaderGraphEditor graph ? graph : null;
    }
}
