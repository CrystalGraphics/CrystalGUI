package com.crystalgui.example.notes;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.WorkbenchExtension;

/**
 * A file type in one class — <b>the whole of what a mod writes to own one</b>.
 *
 * <p>An id, a declaration, and where to attach it. From that: {@code .notes} files open as checklists
 * with this icon; ticking an item is undoable; Ctrl+S encodes and writes with the etag; a change on the
 * server reloads a clean document and marks a dirty one; an unsaved list survives a quit; and the tab
 * says which file it is.</p>
 *
 * <p>Nothing is registered anywhere else, and <b>nothing calls this</b>: one line in
 * {@code META-INF/services/com.crystalgui.workbench.WorkbenchExtension} is how a jar says it has the
 * feature, and an application's manifest names {@link #ID} to enable it. The harness used to call
 * {@code NotesKind.register(workbench.kinds())} in two scenes and the 1.7.10 loader called it nowhere,
 * so a file type shipped in this repository opened in the harness and not in the game — which is not a
 * decision anybody made, it is the shape of "somebody has to remember to call this".</p>
 *
 * <h3>One class, and it was two</h3>
 *
 * <p>{@code NotesExtension} held these two methods and delegated to a {@code register(DocumentKinds)}
 * helper here that nothing else ever called. Splitting a declaration from its attachment reads as a
 * boundary and is a wrapper: they have one lifetime, one id and one reason to exist, and the second
 * file's only real content was the name of the first. What the split did buy was an assertion — that a
 * kind declaration reaches nothing but the document layer — and that assertion is sharper stated
 * honestly: an author reaches the document layer <em>plus the two-method seam</em>, which
 * {@code WorkspaceApiTest} now pins by naming those two interfaces rather than the workbench package.</p>
 *
 * <h3>What this example is for</h3>
 *
 * <p>It is the smallest complete kind: a model that is genuinely headless ({@link NotesModel}), a view
 * that is genuinely only a view ({@link NotesView}), and this. Read together they are the answer to
 * "what do I have to write to own a file type", and {@code WorkspaceApiTest} keeps them honest by
 * asserting against the class files that the model reaches no widget and the view reaches no
 * application.</p>
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
