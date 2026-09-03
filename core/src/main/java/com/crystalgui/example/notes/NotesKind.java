package com.crystalgui.example.notes;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.document.DocumentKind;
import com.crystalgui.document.DocumentKinds;

/**
 * A file type in one declaration — <b>the whole of what a mod writes to own one</b>.
 *
 * <pre>{@code
 * NotesKind.register(workbench.kinds());
 * }</pre>
 *
 * <p>From that: {@code .notes} files open as checklists with this icon; ticking an item is undoable;
 * Ctrl+S encodes and writes with the etag; a change on the server reloads a clean document and marks a
 * dirty one; an unsaved list survives a quit; and the tab says which file it is.</p>
 *
 * <p>Nothing is registered anywhere else. There is no factory to bind separately, no panel type to
 * declare, no reader to add to a switch — the two calls that used to be needed were meaningless apart,
 * so a half-done registration failed at the moment somebody opened a file rather than here.</p>
 *
 * <h3>What this example is for</h3>
 *
 * <p>It is the smallest complete kind: a model that is genuinely headless ({@link NotesModel}), a view
 * that is genuinely only a view ({@link NotesView}), and this. Read together they are the answer to
 * "what do I have to write to own a file type", and {@code WorkspaceApiTest} keeps them honest by
 * asserting against the class files that the model reaches no widget and this declaration reaches
 * nothing but the document layer.</p>
 */
public final class NotesKind {

    /** Namespaced, and the string a session record persists — so it is picked once. */
    public static final String ID = "crystalgui:notes";

    public static final DocumentKind KIND = DocumentKind.of(ID, "Notes")
            .files(DocumentKind.FilePatterns.extension("notes"))
            .icon("crystalgui:file-text")
            .model((resource, bytes) -> NotesModel.decode(bytes))
            .editor(NotesView::new);

    private NotesKind() {
    }

    /** @return a handle that withdraws the kind, so a mod that unloads takes its file type with it */
    public static Disposable register(DocumentKinds kinds) {
        return kinds.register(KIND);
    }
}
