package com.crystalgui.app.uibuilder.library;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.canvas.Placement;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.ActionButton;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.view.FocusableView;
import com.crystalgui.workbench.view.TitleActionsContributor;

/**
 * The <b>Library</b> tool window: every placeable kind, placed into whatever {@code .cgui} is in front.
 *
 * <p>One panel for the whole workbench. Unlike the Hierarchy it keeps its content when no builder is in
 * front — the kinds do not depend on the document — and only placement needs one.</p>
 */
public final class LibraryToolWindow extends UIElement implements TitleActionsContributor, FocusableView {

    public static final Name NAME = Name.of("librarytoolwindow");

    /** The private store's scope for the user's Library. @see WorkbenchContext#config */
    public static final String STORE = "uibuilder.library";

    private final WorkbenchContext workbench;
    private final LibraryPanel panel = new LibraryPanel(LibraryCatalog.current());

    @Nullable
    private List<ActionButton> titleActions;

    public LibraryToolWindow(WorkbenchContext workbench) {
        super(NAME);
        this.workbench = workbench;
        append(panel);
        // THE USER'S GROUPS AND VIEW, from the workspace's private store; kept for the session on a host with none.
        panel.useLibrary(UserLibrary.in(workbench.config(STORE)));
        panel.onPlace.connect(this::place);
    }

    /** Places {@code entry} into the builder in front, by the rule New ▸ uses. Nothing happens with no builder. */
    public boolean place(LibraryCatalog.Entry entry) {
        BuilderContext builder = builder();
        return builder != null && Placement.intoSelection(builder, entry.build());
    }

    public LibraryPanel panel() {
        return panel;
    }

    /** The builder in front, or null when the active tab is not a {@code .cgui}. */
    @Nullable
    public BuilderContext builder() {
        return BuilderEditor.inFront(workbench.editors());
    }

    @Override
    @Nullable
    public UIElement focusTarget() {
        return panel.search().searchField();
    }

    @Override
    public List<ActionButton> titleActions() {
        if (titleActions == null) titleActions = LibraryActions.titleActions(() -> panel);
        return titleActions;
    }
}
