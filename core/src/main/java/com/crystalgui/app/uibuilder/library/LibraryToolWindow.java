package com.crystalgui.app.uibuilder.library;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.canvas.Placement;
import com.crystalgui.core.storage.ConfigStorage;
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



    private final WorkbenchContext workbench;
    private final LibraryPanel panel = new LibraryPanel(LibraryCatalog.current());

    @Nullable
    private List<ActionButton> titleActions;

    /** The extension whose store keeps the user's Library. */
    private final String extensionId;

    /** Whether the panel reads the extension's store yet. */
    private boolean storeBound;

    /** @param extensionId the extension this panel ships with, whose store keeps the user's groups */
    public LibraryToolWindow(WorkbenchContext workbench, String extensionId) {
        super(NAME);
        this.workbench = workbench;
        this.extensionId = extensionId;
        append(panel);
        panel.onPlace.connect(this::place);
        // THE USER'S GROUPS AND VIEW, from the extension's store -- read on the first attach, NOT here: extensions
        // activate inside the Workbench constructor, and the application supplies the stores only after it, so
        // asked now the store is always null and every group was the session's alone. A host with no store
        // keeps the panel's session library.
        onConnected(() -> {
            if (storeBound) return;
            ConfigStorage store = workbench.extensionStore(extensionId);
            if (store == null) return;
            storeBound = true;
            panel.useLibrary(UserLibrary.in(store));
        });
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
