package com.crystalgui.app.uibuilder.panel;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
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

    @Nullable
    private HierarchyPanel hierarchy;

    @Nullable
    private BuilderContext shown;

    public DesignToolWindow(WorkbenchContext workbench) {
        super(NAME);
        this.workbench = workbench;
        addClass(PANEL_CLASS);
        follow();
    }

    /** The hierarchy currently shown, or null when the tab in front is not a {@code .cgui}. */
    @Nullable
    public HierarchyPanel hierarchy() {
        return hierarchy;
    }

    /**
     * Asked each frame, because <b>there is no announcement to listen to</b>.
     *
     * <p>Three were tried. The dock's {@code onDidChangeActivePanel} fires while the read behind the tab
     * is still in flight, so the panel is announced before it has an editor; {@code onDidOpenDocument}
     * and the editor service's own signals fire at moments when {@code editors().active()} is still
     * null. Measured on the real path, {@code follow()} ran five times before the document arrived and
     * not once after — which is exactly the empty panel, and why patching the signal list twice fixed
     * nothing.</p>
     *
     * <p>What is actually missing is an <em>active editor changed</em> signal; the Inspector works around
     * the same gap with three sources of its own. Until that exists this asks, which costs two field
     * reads and a reference comparison and stops when the panel leaves the tree.</p>
     */
    @Override
    protected void connected() {
        super.connected();
        UIDocument window = document();
        // GUARDED, because `every` is a plain add and the dock detaches and re-attaches a panel on every
        // rebuild -- so an unguarded registration stacks one hook per rebuild. Cleared in disconnected(),
        // or a panel that is hidden and reshown comes back with the flag set and no hook behind it.
        if (ticking || window == null) return;
        ticking = true;
        window.animation().every(this, delta -> {
            follow();
            return true;
        });
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        ticking = false;
    }

    /** @see #connected */
    private boolean ticking;

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

}
