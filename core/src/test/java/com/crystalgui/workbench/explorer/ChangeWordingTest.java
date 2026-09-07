package com.crystalgui.workbench.explorer;

import com.crystalgui.fs.protocol.FsMessages;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * What a file-change notification says.
 *
 * <p><b>A move and a rename are one event on the wire</b>, and describing it by the last segment alone
 * made every move read as {@code renamed test.shadergraph to test.shadergraph} — the name is exactly
 * what a move keeps. The parent directory is what tells them apart.</p>
 */
public class ChangeWordingTest {

    private static FsMessages.FileChange renamed(String from, String to) {
        return new FsMessages.FileChange(to, FsMessages.ChangeKind.RENAMED, "etag", from, "");
    }

    @Test
    public void aRenameInPlaceNamesBothNames() {
        FsMessages.FileChange change = renamed("proj:src/README.md", "proj:src/NOTES.md");
        assertEquals("renamed README.md to NOTES.md", ExplorerBinding.verb(change));
        assertEquals("renamed to NOTES.md", ExplorerBinding.past(change));
    }

    @Test
    public void aMoveNamesWhereItWentRatherThanRepeatingTheName() {
        FsMessages.FileChange change = renamed("proj:test.shadergraph", "proj:fah/test.shadergraph");
        assertEquals("moved test.shadergraph to fah/", ExplorerBinding.verb(change));
        assertEquals("moved to fah/", ExplorerBinding.past(change));
    }

    /** Nested, in full: a project has many folders called java and naming one locates nothing. */
    @Test
    public void aMoveNamesTheWholePathNotTheLastSegment() {
        FsMessages.FileChange change =
                renamed("proj:README.md", "proj:src/main/java/README.md");
        assertEquals("moved README.md to src/main/java/", ExplorerBinding.verb(change));
    }

    /** Out of a folder and up to the root, which has no name of its own. */
    @Test
    public void aMoveToTheProjectRootSaysSo() {
        FsMessages.FileChange change = renamed("proj:fah/test.shadergraph", "proj:test.shadergraph");
        assertEquals("moved test.shadergraph to the project root", ExplorerBinding.verb(change));
    }

    /** Both at once, which a single move can genuinely be. */
    @Test
    public void aMoveThatAlsoRenamesSaysBoth() {
        FsMessages.FileChange change = renamed("proj:README.md", "proj:fah/NOTES.md");
        assertEquals("moved README.md to fah/ as NOTES.md", ExplorerBinding.verb(change));
        assertEquals("moved to fah/ as NOTES.md", ExplorerBinding.past(change));
    }

    /** A file says which it is by having an extension; a folder has to be told. */
    @Test
    public void aFolderIsMarkedAsOne() {
        FsMessages.FileChange folder = new FsMessages.FileChange(
                "proj:coo", FsMessages.ChangeKind.CREATED, "", "", "", true);
        assertEquals("added coo/", ExplorerBinding.verb(folder));

        FsMessages.FileChange file = new FsMessages.FileChange(
                "proj:coo.txt", FsMessages.ChangeKind.CREATED, "etag", "", "", false);
        assertEquals("added coo.txt", ExplorerBinding.verb(file));
    }
}
