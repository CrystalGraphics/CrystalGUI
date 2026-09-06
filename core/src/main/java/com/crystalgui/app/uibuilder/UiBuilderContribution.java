package com.crystalgui.app.uibuilder;

import com.crystalgui.app.uibuilder.canvas.BuilderEditor;
import com.crystalgui.app.uibuilder.document.UiBuilderDocument;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.workbench.WorkbenchContext;
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
                .editor(document -> new BuilderEditor((UiBuilderDocument) document.model())),
                "cgui");
        // The kind is registered ON the workbench, so it goes when the workbench does and needs no
        // handle of its own. @see WorkbenchExtension
        return () -> { };
    }
}
