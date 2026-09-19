package com.crystalgui.app.uibuilder.panel;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.ActionButton;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.view.FocusableView;
import com.crystalgui.workbench.view.TitleActionsContributor;

/**
 * The <b>Hierarchy</b> tool window: the tree of the {@code .cgui} on screen that was last in front.
 *
 * <p>One panel for the whole workbench, re-pointed as that changes — the same shape the Inspector takes, and for
 * the same reason. A panel per open document would mean the dock cached one hierarchy per file and showed whichever
 * it built first. Focusing a CSS file beside the canvas keeps the tree: it has nothing to say about CSS, and the
 * canvas is still there to edit. @see EditorService#follow</p>
 *
 * <p>It empties rather than disappearing when no builder is on screen. A tool window that comes and goes moves
 * everything beside it, and "the panel I docked has gone" is indistinguishable from a bug.</p>
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
        whileConnected(() -> workbench.editors().follow(UIBuilderView.class, this::show));
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

    /** The hierarchy currently shown, or null while no {@code .cgui} is on screen. */
    @Nullable
    public HierarchyPanel hierarchy() {
        return hierarchy;
    }

    /** Shows {@code editor}'s tree, rebuilding only when the builder changed; empty with none. */
    private void show(@Nullable UIBuilderView editor) {
        BuilderContext builder = editor == null ? null : editor.surface();
        if (builder == shown) return;
        shown = builder;
        removeAll();
        hierarchy = builder == null ? null : new HierarchyPanel(builder);
        if (hierarchy != null) append(hierarchy);
    }

}
