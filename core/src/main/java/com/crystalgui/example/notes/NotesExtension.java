package com.crystalgui.example.notes;

import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.workbench.WorkbenchContext;
import com.crystalgui.workbench.WorkbenchExtension;

/**
 * The Notes file type, as an extension — <b>the whole of what a mod writes to reach every host</b>.
 *
 * <p>{@link NotesKind} is the declaration; this is the attachment, and between them there is nothing
 * else. No host code: the harness used to call {@code NotesKind.register(workbench.kinds())} in two
 * scenes and the 1.7.10 loader called it nowhere, so a file type shipped in this repository opened in
 * the harness and not in the game — which is not a decision anybody made, it is the shape of
 * "somebody has to remember to call this".</p>
 *
 * <p>It is also the smallest possible example of the seam: an id, one call, and a handle back. The
 * handle is {@code DocumentKinds.register}'s own, so withdrawing the extension withdraws the file
 * type.</p>
 */
public final class NotesExtension implements WorkbenchExtension {

    @Override
    public String id() {
        return NotesKind.ID;
    }

    @Override
    public Disposable activate(WorkbenchContext workbench) {
        return NotesKind.register(workbench.kinds());
    }
}
