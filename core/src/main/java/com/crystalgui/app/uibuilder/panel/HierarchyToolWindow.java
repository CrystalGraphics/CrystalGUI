package com.crystalgui.app.uibuilder.panel;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.document.DocumentEditor;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.ActionButton;
import com.crystalgui.workbench.editor.EditorService;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.view.FocusableView;
import com.crystalgui.workbench.view.TitleActionsContributor;

/**
 * The <b>Hierarchy</b> tool window: the tree of whatever {@code .cgui} is in front.
 *
 * <p>One panel for the whole workbench, re-pointed as the active tab changes — the same shape the
 * Inspector takes, and for the same reason. A panel per open document would mean the dock cached one
 * hierarchy per file and showed whichever it built first.</p>
 *
 * <p>It empties rather than disappearing when the active tab is not a builder. A tool window that comes
 * and goes moves everything beside it, and "the panel I docked has gone" is indistinguishable from a
 * bug.</p>
 */
public final class HierarchyToolWindow extends UIElement implements TitleActionsContributor, FocusableView {

    public static final Name NAME = Name.of("hierarchytoolwindow");

    public static final String PANEL_CLASS = "__hierarchy-window__";

    private final WorkbenchContext workbench;

    @Nullable
    private HierarchyPanel hierarchy;

    @Nullable
    private BuilderContext shown;

    public HierarchyToolWindow(WorkbenchContext workbench) {
        super(NAME);
        this.workbench = workbench;
        addClass(PANEL_CLASS);
        // WHICH TAB, and then WHETHER ITS CONTENT IS IN -- a tab is announced before the read behind it
        // lands, so the first answer has an active tab with no editor on it yet.
        whileConnected(() -> workbench.editors().onDidChangeActive.connect(tab -> follow()));
        whileConnected(() -> workbench.editors().onDidLoad.connect(tab -> follow()));
        onConnected(this::follow);
    }

    /** The current hierarchy's tree, or null while no builder is in front. */
    @Nullable
    @Override
    public UIElement focusTarget() {
        return hierarchy == null ? null : hierarchy.tree();
    }

    @Nullable
    private List<ActionButton> titleActions;

    /** @see HierarchyActions#titleActions — on the panel currently shown, since the window swaps it per tab. */
    @Override
    public List<ActionButton> titleActions() {
        if (titleActions == null) {
            titleActions = HierarchyActions.titleActions(() -> hierarchy == null ? null : hierarchy.tree());
        }
        return titleActions;
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

}
