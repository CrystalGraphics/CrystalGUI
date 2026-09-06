package com.crystalgui.app.uibuilder.panel;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.workbench.WorkbenchContext;

/**
 * The <b>Design</b> tool window: the hierarchy of whatever {@code .cgui} is in front.
 *
 * <p>One panel for the whole workbench, re-pointed as the active tab changes — the same shape the
 * Inspector takes, and for the same reason. A panel per open document would mean the dock cached one
 * hierarchy per file and showed whichever it built first.</p>
 *
 * <p>It empties rather than disappearing when the active tab is not a builder. A tool window that comes
 * and goes moves everything beside it, and "the panel I docked has gone" is indistinguishable from a
 * bug.</p>
 */
public final class DesignToolWindow extends UIElement {

    public static final Name NAME = Name.of("designtoolwindow");

    public static final String PANEL_CLASS = "__design-panel__";

    private final WorkbenchContext workbench;

    private final ConnectionGroup connections = new ConnectionGroup();

    @Nullable
    private HierarchyPanel hierarchy;

    @Nullable
    private BuilderContext shown;

    public DesignToolWindow(WorkbenchContext workbench) {
        super(NAME);
        this.workbench = workbench;
        addClass(PANEL_CLASS);
        connections.add(workbench.dock().onDidChangeActivePanel.connect(unused -> follow()));
        // AND WHEN A DOCUMENT LANDS, which is the Inspector's own note and the same defect: the active
        // PANEL is announced as soon as the dock has built its tree, which is before the document behind
        // it exists. Following only the panel left this empty from startup until something else moved --
        // and for a workspace restored with a .cgui already in front, nothing else ever does.
        connections.add(workbench.onDidOpenDocument().connect(path -> follow()));
        // AND THE EDITOR SERVICE ITSELF, which is the one that knows when a tab has an editor rather than
        // when the dock has a panel. A restored workspace announces its active panel while the document
        // behind it is still arriving, and never announces again -- so a workbench that came back with a
        // .cgui already in front showed an empty panel for the whole session.
        connections.add(workbench.editors().onDidOpen.connect(tab -> follow()));
        connections.add(workbench.editors().onDidChangeState.connect(tab -> follow()));
        follow();
    }

    /** The hierarchy currently shown, or null when the tab in front is not a {@code .cgui}. */
    @Nullable
    public HierarchyPanel hierarchy() {
        return hierarchy;
    }

    /** Points the panel at whatever builder is in front, and rebuilds only when that changed. */
    public void follow() {
        BuilderContext builder = activeBuilder();
        if (builder == shown) return;
        shown = builder;
        removeAll();
        hierarchy = builder == null ? null : new HierarchyPanel(builder);
        if (hierarchy != null) append(hierarchy);
    }

    @Nullable
    private BuilderContext activeBuilder() {
        EditorService.Tab active = workbench.editors().active();
        DocumentEditor view = active == null ? null : active.editor();
        return view instanceof BuilderEditor builder ? builder.surface() : null;
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        connections.disconnectAll();
    }
}
