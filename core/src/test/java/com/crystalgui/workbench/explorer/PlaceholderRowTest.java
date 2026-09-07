package com.crystalgui.workbench.explorer;

import com.crystalgui.fs.CgPath;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The row you are still naming is not a file.
 *
 * <p>Its name is a control character no filesystem permits, chosen so a path escaping the client is
 * refused rather than creating something. Enter commits the name and activates the selected row in one
 * keystroke, so the placeholder reached the opener and was refused exactly as designed — as an error in
 * front of somebody who had merely made a file.</p>
 */
public class PlaceholderRowTest {

    @Test
    public void aPendingRowIsRecognisedAsAPlaceholder() {
        // No workspace: beginPendingNew is pure path arithmetic and local state, and the point
        // here is that what it mints is what isPlaceholder recognises.
        WorkspaceTreeSource source = new WorkspaceTreeSource(null);
        CgPath pending = source.beginPendingNew(CgPath.parse("proj:src"), false);

        assertTrue("what beginPendingNew mints is what isPlaceholder answers for",
                WorkspaceTreeSource.isPlaceholder(pending));
    }

    @Test
    public void anOrdinaryFileIsNot() {
        assertFalse(WorkspaceTreeSource.isPlaceholder(CgPath.parse("proj:src/Main.java")));
        assertFalse(WorkspaceTreeSource.isPlaceholder(CgPath.parse("proj:new")));
        assertFalse(WorkspaceTreeSource.isPlaceholder(null));
    }
}
