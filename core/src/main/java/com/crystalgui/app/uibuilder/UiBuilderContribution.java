package com.crystalgui.app.uibuilder;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.app.uibuilder.library.LibraryActions;
import com.crystalgui.app.uibuilder.library.LibraryToolWindow;
import com.crystalgui.app.uibuilder.panel.HierarchyActions;
import com.crystalgui.app.uibuilder.panel.HierarchyToolWindow;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.region.DockRegion;
import com.crystalgui.workbench.toolwindow.ToolWindowKind;
import com.crystalgui.workbench.extension.WorkbenchExtension;

/**
 * <b>The UI builder as a workbench feature</b> — one file type, opened on a design surface.
 *
 * <p>Ship this jar and a {@code .cgui} opens in a tab like any other document; an application turns it
 * on by naming {@link #ID}. Nothing else in the workbench knows the builder exists.</p>
 *
 * <pre>{@code
 * public static final List<String> EXTENSIONS = List.of(..., UiBuilderContribution.ID);
 * }</pre>
 *
 * <p>Model and editor are separate here, unlike the shader graph: a document is a tree and a header with
 * an undo history, and a view of it is a canvas — two panes onto one file is the case that difference
 * exists for.</p>
 */
public final class UiBuilderContribution implements WorkbenchExtension {

    public static final String ID = "crystalgui:uibuilder";

    /** The hierarchy's tool window. */
    public static final String HIERARCHY_PANEL = "uibuilder.hierarchy";

    /** The Library's tool window. */
    public static final String LIBRARY_PANEL = "uibuilder.library";

    /** The file type. {@code DocumentKinds} resolves a {@code .cgui} to this. */
    public static final String DOCUMENT_TYPE = "cgui.file";

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public UiBuilderContribution() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        workbench.contribute(DocumentKind.of(DOCUMENT_TYPE, "UI Document")
                .files(DocumentKind.FilePatterns.extension("cgui"))
                .icon("crystalgui:layout")
                .model((resource, bytes) -> new UiBuilderDocument(bytes, resource.toString()))
                // The store is asked per editor: stores are supplied after extensions activate.
                .editor(document -> new BuilderEditor((UiBuilderDocument) document.model(), workbench.extensionStore(ID))),
                "cgui");
        // The kind is registered ON the workbench, so it goes when the workbench does and needs no
        // handle of its own. @see WorkbenchExtension
        Disposable commands = BuilderCommands.register();
        // THE ONE THING IN A PROCESS-WIDE REGISTRY, so it is what this handle has to be able to take
        // back. Counted: a second editor must not double the forms, and the first one closing must not
        // empty the inspector under the second. The graph's own note, and the same shape.
        Disposable sections = BuilderInspectorSections.register();

        // New ▸ and the edit rows on a row's right-click, and the title line's commands. Once, for every hierarchy.
        Disposable rowMenu = HierarchyActions.register(CommandRegistry.global());

        Disposable panel = workbench.registerToolWindow(
                ToolWindowKind.of(HIERARCHY_PANEL, "Hierarchy")
                        .icon("crystalgui:toolwindows/hierarchy")
                        .region(DockRegion.SIDEBAR)
                        .view(new HierarchyToolWindow(workbench))
                        .openByDefault());

        Disposable libraryCommands = LibraryActions.register(CommandRegistry.global());
        Disposable library = workbench.registerToolWindow(
                ToolWindowKind.of(LIBRARY_PANEL, "Library")
                        .icon("crystalgui:toolwindows/library")
                        .region(DockRegion.SIDEBAR)
                        .view(new LibraryToolWindow(workbench, ID)));

        return () -> {
            library.dispose();
            libraryCommands.dispose();
            panel.dispose();
            rowMenu.dispose();
            sections.dispose();
            commands.dispose();
        };
    }
}
