package com.crystalgui.workbench.extension;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.widget.config.inspector.Inspector;
import com.crystalgui.widget.config.inspector.InspectorRegistry;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.toolwindow.ToolWindowKind;

/**
 * The Inspector, as a feature a manifest can enable.
 *
 * <p>It was four subscriptions and a tool-window registration in {@code CrystalEditor}'s constructor,
 * which made "does this product have an inspector" a property of the product's <em>class</em>. Here it
 * is an id in a list, which is the difference the whole rewrite is for.</p>
 *
 * <h3>A general inspector, which is why it names no type</h3>
 *
 * <p>The subject is the <b>focus owner</b>, and {@link Inspector} resolves that itself — latching it,
 * ignoring focus that lands inside itself, and keeping the last describable one. A package makes
 * something inspectable by registering an {@code InspectorSection}; nothing here knows about graphs.</p>
 */
public final class InspectorExtension implements WorkbenchExtension {

    public static final String ID = "crystalgui:inspector";

    /** Matches what a session record and a stripe button call it. */
    public static final String TYPE = "inspector";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        // BUILT EAGERLY: the dock caches a panel factory's result permanently, so returning a
        // placeholder while waiting for something hands back the placeholder for the rest of the
        // session.
        Inspector inspector = new Inspector();
        ConnectionGroup lifetime = new ConnectionGroup();

        // ONE DECLARATION, which is the whole of what this says about the panel: where it goes, what
        // builds it, and that it is open on a fresh workspace.
        Disposable panel = workbench.registerToolWindow(
                ToolWindowKind.of(TYPE, "Inspector")
                        .icon("crystalgui:package")
                        .region(DockRegion.AUXILIARY)
                        .view(ctx -> inspector)
                        .openByDefault());

        // A SEED, NOT THE POLICY. The workbench states what it just put in front so a restored tab has a
        // subject before anything has been focused; focus supersedes it the moment there is one.
        lifetime.add(workbench.dock().onDidChangeActivePanel.connect(active -> seed(workbench, inspector)));
        // AND WHEN A DOCUMENT LANDS. The active PANEL is announced as soon as the dock has built its
        // tree, which can be before the document behind it exists -- a restored tab's content arrives
        // over the network some frames later. Following only the panel leaves the inspector empty at
        // startup until something else moves, which is exactly what "I have to click something first" is.
        lifetime.add(workbench.onDidOpenDocument().connect(path -> seed(workbench, inspector)));
        // AND ON ANY ANNOUNCED CHANGE -- "what is being looked at has moved", which is what a selection
        // produces.
        lifetime.add(InspectorRegistry.onDidChangeSubject.connect(() -> seed(workbench, inspector)));

        return () -> {
            lifetime.disconnectAll();
            panel.dispose();
        };
    }

    private static void seed(WorkbenchContext workbench, Inspector inspector) {
        EditorService.Tab active = workbench.editors().active();
        DocumentEditor view = active == null ? null : active.editor();
        if (view != null) inspector.inspect(view.view());
    }
}
