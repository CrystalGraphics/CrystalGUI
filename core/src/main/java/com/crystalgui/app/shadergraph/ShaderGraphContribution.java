package com.crystalgui.app.shadergraph;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.fs.Resource;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.core.notify.Notification;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.dock.panel.DockOpenOptions;
import com.crystalgui.workbench.dock.panel.DockPanelDescriptor;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.drag.DockPlacement;
import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.document.Document;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.extension.WorkbenchExtension;

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
 * contributions to enable — <b>an id in a list</b>. Nothing calls this: one line in
 * {@code META-INF/services/com.crystalgui.workbench.extension.WorkbenchExtension} is how the jar says it has the
 * feature. It used to be contributed by {@code CrystalEditor}, which made a feature's availability a
 * product's responsibility and put it out of reach of every other product.</p>
 */
public final class ShaderGraphContribution implements WorkbenchExtension {

    /** The id an application's manifest names to enable the graph. @see WorkbenchExtension */
    public static final String ID = "crystalgui:shadergraph";

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public ShaderGraphContribution() {
    }

    @Override
    public String id() {
        return ID;
    }

    /**
     * The attachment. A one-line override rather than a class of its own: {@code ShaderGraphExtension}
     * held exactly this and the id, and a declaration split from its attachment reads as a boundary
     * while being a wrapper — one lifetime, one id, one reason to exist.
     */
    @Override
    public Disposable activate(WorkbenchContext workbench) {
        return register(workbench);
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
     * <p><b>Takes nothing but the workbench.</b> It used to take a status sink, with a note explaining
     * that where a status line lives is the application's decision — which is true, and was the wrong
     * conclusion: <em>where</em> a message is shown belongs to the application, but <em>that</em> one
     * exists does not, and a parameter for it couples every contribution to an application that has one.
     * The graph announces through {@link com.crystalgui.core.notify.StatusBar} and
     * {@link com.crystalgui.core.notify.Notifications} instead, and an application displays them or does
     * not.</p>
     */
    public static Disposable register(WorkbenchContext workbench) {
        // A GRAPH IS ITS OWN MODEL AND ITS OWN VIEW, and saying so is more honest than splitting it: the
        // canvas holds the GraphDocument, the previews and the Blackboard are bound to that instance at
        // construction, and a load copies into it rather than replacing it (see GraphView.load). A second
        // object in front of it would be a wrapper with nothing of its own to hold. What the split DOES
        // buy elsewhere -- two views of one document -- a graph does not yet offer, and the day it does
        // is the day this is worth separating.
        workbench.contribute(DocumentKind.of(GRAPH_TYPE, "Shader Graph")
                .files(DocumentKind.FilePatterns.extension("shadergraph"))
                .model((resource, bytes) -> {
                    ShaderGraphEditor editor = new ShaderGraphEditor();
                    editor.setResource(resource);
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
                    editor.adopt(bytes);
                    return editor;
                })
                .editor(document -> (ShaderGraphEditor) document.model()),
                "shadergraph");

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

        // AND WHY THAT TAB IS NOT AN ORDINARY EDITOR. It is setReadOnly(true), so typing in it silently
        // does nothing -- which reads as a broken editor rather than as a generated file, and there was
        // nowhere for it to say otherwise. The action is the useful half: the thing you actually wanted
        // was the graph, and the derived resource carries its origin, so this can offer it.
        workbench.panels().registerBanner(panel -> {
            if (!SOURCE_TYPE.equals(panel.typeId())) return null;
            ShaderGraphEditor graph = graphFor(workbench, panel.state(DockPanelRef.PATH, ""));
            Notification banner = Notification.info(
                    "Generated from the shader graph. Edit the graph, not this file.");
            if (graph == null || graph.resource() == null || !graph.resource().isProject()) return banner;
            return banner.withAction("Open Graph",
                    () -> workbench.openFile(graph.resource().asPath()));
        });

        // THE ONE THING THAT OUTLIVES THE WORKBENCH. Everything else above is registered ON the
        // workbench -- the kind, the panel, the banner provider -- and goes when it does. The inspector
        // sections are in a process-wide registry, so they are what a caller has to be able to hand
        // back, and they are the whole of what this returns.
        return ShaderInspectorSections.register();
    }


    /** A generated resource to the tab label its graph deserves. */
    public static String titleFor(Resource generated) {
        String name = generated.name();
        int dot = name.lastIndexOf('.');
        return (dot < 0 ? name : name.substring(0, dot)) + "_compiled.shader";
    }

    /** Opens the generated shader for {@code graph}, beside it. */
    public static boolean showGenerated(WorkbenchContext workbench, @Nullable ShaderGraphEditor graph) {
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
    private static ShaderGraphEditor graphFor(WorkbenchContext workbench, String rawResource) {
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
        Document document = workbench.documentFor(origin.asPath());
        return document != null && document.model() instanceof ShaderGraphEditor graph ? graph : null;
    }
}
