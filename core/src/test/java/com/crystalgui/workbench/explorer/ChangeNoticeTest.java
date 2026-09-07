package com.crystalgui.workbench.explorer;

import com.crystalgui.fs.protocol.FsMessages;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * What a file change is told to somebody as: a heading, a line, and who.
 *
 * <p>Three things the wording got wrong in turn, each pinned below — a move described as a rename, a
 * folder indistinguishable from a file, and a location named by its last segment.</p>
 */
public class ChangeNoticeTest {

    private static FsMessages.FileChange change(FsMessages.ChangeKind kind, String path,
                                                String from, boolean directory) {
        return new FsMessages.FileChange(path, kind, "etag", from, "", directory);
    }

    @Test
    public void aNewFileNamesWhereItLanded() {
        FsMessages.FileChange made =
                change(FsMessages.ChangeKind.CREATED, "proj:src/main/java/Main.java", "", false);
        assertEquals("New File", ChangeNotice.header(made));
        assertEquals("Main.java was added to proj:src/main/java/", ChangeNotice.body(made));
    }

    /** A file says which it is by having an extension; a folder has to be told. */
    @Test
    public void aNewDirectoryIsMarkedAsOne() {
        FsMessages.FileChange made = change(FsMessages.ChangeKind.CREATED, "proj:coo", "", true);
        assertEquals("New Directory", ChangeNotice.header(made));
        assertEquals("coo/ was added to proj:", ChangeNotice.body(made));
    }

    @Test
    public void aDeletionSaysWhereItWentFrom() {
        FsMessages.FileChange gone =
                change(FsMessages.ChangeKind.DELETED, "proj:src/Main.java", "", false);
        assertEquals("File Deleted", ChangeNotice.header(gone));
        assertEquals("Main.java was deleted from proj:src/", ChangeNotice.body(gone));
    }

    /** RENAMED is one event covering both, and the NAME is exactly what a move keeps. */
    @Test
    public void aRenameInPlaceIsNotAMove() {
        FsMessages.FileChange renamed = change(FsMessages.ChangeKind.RENAMED,
                "proj:docs/NOTES.md", "proj:docs/README.md", false);
        assertEquals("File Renamed", ChangeNotice.header(renamed));
        assertEquals("the subject is the name it HAD, or it renames itself to itself",
                "README.md was renamed to NOTES.md in proj:docs/", ChangeNotice.body(renamed));
    }

    @Test
    public void aMoveNamesTheWholeDestinationNotItsLastSegment() {
        FsMessages.FileChange moved = change(FsMessages.ChangeKind.RENAMED,
                "proj:src/main/java/README.md", "proj:README.md", false);
        assertEquals("File Moved", ChangeNotice.header(moved));
        assertEquals("README.md was moved to proj:src/main/java/", ChangeNotice.body(moved));
    }

    @Test
    public void aMovedDirectorySaysSoInBothPlaces() {
        FsMessages.FileChange moved = change(FsMessages.ChangeKind.RENAMED,
                "proj:assets/icons", "proj:icons", true);
        assertEquals("Directory Moved", ChangeNotice.header(moved));
        assertEquals("icons/ was moved to proj:assets/", ChangeNotice.body(moved));
    }

    /** A move that also renames is genuinely both, and says so. */
    @Test
    public void aMoveThatAlsoRenamesSaysBoth() {
        FsMessages.FileChange moved = change(FsMessages.ChangeKind.RENAMED,
                "proj:fah/NOTES.md", "proj:README.md", false);
        assertEquals("README.md was moved to proj:fah/ as NOTES.md", ChangeNotice.body(moved));
    }

    /** Who is a line of its own, and nothing outside the workspace can be given a name. */
    @Test
    public void whoIsItsOwnLine() {
        // The name only -- the mark beside it is drawn, so it cannot go missing with the font.
        assertEquals("alice", ChangeNotice.attribution("alice"));
        assertEquals("on disk", ChangeNotice.attribution(""));
        assertEquals("on disk", ChangeNotice.attribution(null));
    }
}
