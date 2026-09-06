package com.crystalgui.workbench.editor;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgui.fs.Resource;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.dock.drag.DockPlacement;
import com.crystalgui.workbench.dock.layout.DockPanelRef;
import com.crystalgui.workbench.dock.panel.DockInput;
import com.crystalgui.workbench.dock.panel.DockOpenOptions;

/**
 * A read-only tab <b>of</b> something else, opened beside it — a shader graph's generated GLSL, a UI
 * document's generated Java, a decompiled class.
 *
 * <pre>{@code
 * private static final DerivedView GENERATED =
 *         DerivedView.of("shadersource", "shader-generated").titled(r -> name(r) + "_compiled.shader");
 *
 * GENERATED.open(workbench, graph.resource(), graph);          // beside the graph
 * Resource origin = GENERATED.originOf(panel.state(PATH, "")); // and back again
 * }</pre>
 *
 * <p><b>One tab per origin, not a singleton following the front one.</b> Five open graphs have five
 * different generated shaders, and one shared panel cannot be diffed against another, cannot be left
 * open beside a second graph, and loses its scroll on every tab change.</p>
 *
 * <p>The derived resource carries its origin inside its own path, so getting back to what a tab is
 * <em>of</em> is a parse and a lookup rather than a map kept beside the document store — and it survives
 * a saved session, with nothing to keep in step.</p>
 */
public final class DerivedView {

    private final String panelType;
    private final String scheme;

    private Function<Resource, String> title = Resource::name;

    private DerivedView(String panelType, String scheme) {
        this.panelType = panelType;
        this.scheme = scheme;
    }

    /**
     * @param panelType the dock panel type this opens
     * @param scheme    the resource scheme the derived resource is spelled with
     */
    public static DerivedView of(String panelType, String scheme) {
        return new DerivedView(panelType, scheme);
    }

    /** What the tab is called, from the derived resource. */
    public DerivedView titled(Function<Resource, String> title) {
        this.title = title;
        return this;
    }

    public String panelType() {
        return panelType;
    }

    /** The derived resource for an origin — what a tab's state carries. */
    public Resource resourceFor(Resource origin) {
        return Resource.derived(scheme, origin);
    }

    /**
     * Opens the derived tab beside {@code besides}.
     *
     * @return whether it opened — false when there is no origin to derive from
     */
    public boolean open(WorkbenchContext workbench, @Nullable Resource origin, UIElement besides) {
        if (origin == null) return false;
        Resource derived = resourceFor(origin);
        DockPanelRef ref = new DockPanelRef(panelType)
                .withState(DockPanelRef.PATH, derived.toString())
                .withState(DockPanelRef.TITLE, title.apply(derived));
        workbench.open(DockInput.of(ref), DockPlacement.with(besides), DockOpenOptions.ACTIVATE);
        return true;
    }

    /**
     * What a derived tab is <em>of</em>, from the resource string in its own state.
     *
     * <p>Null when the state is empty or unparseable. A session saved before a panel's state became a
     * derived resource stored the bare origin path, which parses as a project resource with no origin —
     * reading it as the origin itself costs one line against invalidating every saved layout.</p>
     */
    @Nullable
    public Resource originOf(String rawResource) {
        if (rawResource == null || rawResource.isEmpty()) return null;
        Resource parsed;
        try {
            parsed = Resource.parse(rawResource);
        } catch (RuntimeException unparseable) {
            return null;
        }
        return parsed.origin() != null ? parsed.origin() : parsed;
    }
}
