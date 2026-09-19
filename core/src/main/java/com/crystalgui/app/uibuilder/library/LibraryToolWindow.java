package com.crystalgui.app.uibuilder.library;

import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.app.uibuilder.canvas.BuilderContext;
import com.crystalgui.app.uibuilder.canvas.UIBuilderView;
import com.crystalgui.app.uibuilder.canvas.Placement;
import com.crystalgui.core.storage.ConfigStorage;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.composite.ActionButton;
import com.crystalgui.widget.display.EmptyState;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.view.FocusableView;
import com.crystalgui.workbench.view.TitleActionsContributor;

/**
 * The <b>Library</b> tool window: every placeable kind, placed into whatever {@code .cgui} is in front.
 *
 * <p>One panel for the whole workbench, shown while a builder is in front and an {@link EmptyState} otherwise — the Hierarchy's
 * rule, followed on the same signals. Vacant rather than rebuilt, because unlike a document's tree the kinds
 * do not change with the tab: a card built once is kept, and coming back to a {@code .cgui} draws them at once.</p>
 */
public final class LibraryToolWindow extends UIElement implements TitleActionsContributor, FocusableView {

    public static final Name NAME = Name.of("librarytoolwindow");

    private final WorkbenchContext workbench;
    private final LibraryPanel panel = new LibraryPanel(LibraryCatalog.current());
    private final EmptyState empty = EmptyState.of(this, "To place something from the Library:",
            "— Open a document that takes it, such as a .cgui file",
            "— Double-click a card, or drag it onto the document");

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
        // THE BUILDER ON SCREEN, as the Hierarchy follows it. @see EditorService#follow
        whileConnected(() -> workbench.editors().follow(UIBuilderView.class, this::show));
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

    /** The builder the panel places into, or null while none is on screen. */
    @Nullable
    private BuilderContext builder;

    /** Shows the panel while a builder is on screen, and its empty state otherwise. */
    private void show(@Nullable UIBuilderView editor) {
        builder = editor == null ? null : editor.surface();
        empty.setVacant(builder == null);
    }

    /** Whether the panel is shown: a {@code .cgui} is on screen. */
    public boolean isShowing() {
        return !empty.isVacant();
    }

    /** Places {@code entry} into the builder on screen, by the rule New ▸ uses. Nothing happens with no builder. */
    public boolean place(LibraryCatalog.Entry entry) {
        BuilderContext builder = builder();
        return builder != null && Placement.intoSelection(builder, entry.build());
    }

    public LibraryPanel panel() {
        return panel;
    }

    /** The builder on screen that was last in front, or null while none is. @see EditorService#follow */
    @Nullable
    public BuilderContext builder() {
        return builder;
    }

    @Override
    @Nullable
    public UIElement focusTarget() {
        return isShowing() ? panel.search().searchField() : null;
    }

    /** @see LibraryActions#titleActions — on nothing while the panel is hidden, as the Hierarchy's are. */
    @Override
    public List<ActionButton> titleActions() {
        if (titleActions == null) titleActions = LibraryActions.titleActions(() -> isShowing() ? panel : null);
        return titleActions;
    }
}
