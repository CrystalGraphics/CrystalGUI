package com.crystalgui.example.notes;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.extension.WorkbenchExtension;

/**
 * <b>A worked example: a file type in one class</b> - the whole of what a mod writes to own one.
 *
 * <p>An id, a {@code DocumentKind} declaration, and an {@code activate} that registers it. From those
 * three things: {@code .notes} files open as checklists with this icon; ticking an item is undoable;
 * Ctrl+S encodes and writes with the etag; a change on the server reloads a clean document and marks a
 * dirty one; an unsaved list survives a quit; and the tab says which file it is - none of which is
 * written here, because all of it is the document layer's.</p>
 *
 * <p><b>Nothing calls this.</b> One line in
 * {@code META-INF/services/com.crystalgui.workbench.extension.WorkbenchExtension} is how the jar says it
 * has the feature, and an application's manifest names {@link #ID} to enable it.</p>
 *
 * <h3>Copy this shape</h3>
 *
 * <p>Note that the declaration and the attachment are one class. Splitting them into a kind plus a
 * separate {@code *Extension} reads as a boundary and is a wrapper: one lifetime, one id, and the second
 * file's only real content is the first one's name.</p>
 */
public final class NotesKind implements WorkbenchExtension {

    /** Namespaced, and the string a session record persists — so it is picked once. */
    public static final String ID = "crystalgui:notes";

    /**
     * The declaration, as <b>data</b>: a launcher and an "open with" lookup read what an extension
     * claims without activating it, so this is a constant rather than something built in
     * {@link #activate}.
     */
    public static final DocumentKind KIND = DocumentKind.of(ID, "Notes")
            .files(DocumentKind.FilePatterns.extension("notes"))
            .icon("crystalgui:file-text")
            .model((resource, bytes) -> NotesModel.decode(bytes))
            .editor(NotesView::new);

    /** {@code ServiceLoader} needs a public no-argument constructor. */
    public NotesKind() {
    }

    @Override
    public String id() {
        return ID;
    }

    /** @return {@code DocumentKinds.register}'s own handle, so withdrawing the extension withdraws the
     * file type — there is nothing else to undo */
    @Override
    public Disposable activate(WorkbenchContext workbench) {
        return workbench.kinds().register(KIND);
    }
}
