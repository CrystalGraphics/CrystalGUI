package com.crystalgui.workbench.explorer;

import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.fs.protocol.FsMessages;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The rows behind <b>Restore Deleted File…</b>.
 *
 * <p>Asserted without a window, which is what {@code itemsFor} is public for: the picker's shell is
 * {@code QuickPick}'s and the part with decisions in it is which rows appear, in what order, and what
 * tells two deletions of one name apart.</p>
 */
public class TrashPickerTest {

    private static final long NOW = 1_700_000_000_000L;

    private static FsMessages.TrashEntry entry(String id, String path, String actor, long agoMillis) {
        return new FsMessages.TrashEntry(id, path, actor, NOW - agoMillis, false, 12L);
    }

    /**
     * <b>Newest first</b>, because the thing you meant is nearly always the thing you just did.
     *
     * <p>The server answers in its own order and a picker that passed it through would be ordered by
     * whatever the trash directory happened to enumerate in.</p>
     */
    @Test
    public void theNewestDeletionIsFirst() {
        List<QuickPickItem> items = TrashPicker.itemsFor(List.of(
                entry("t-1", "proj:src/Old.java", "alice", 90_000L),
                entry("t-2", "proj:src/New.java", "alice", 1_000L)), NOW);

        assertEquals(List.of("New.java", "Old.java"),
                List.of(items.get(0).label(), items.get(1).label()));
    }

    /**
     * <b>A row is addressed by its trash id</b>, never by an index into the list.
     *
     * <p>The id is what a restore takes, so a row that went stale between the list being built and a
     * row being chosen fails to restore and says so — where an index would restore whatever has since
     * taken its place. Same reason {@code GoToFile} addresses a row by its resource.</p>
     */
    @Test
    public void aRowIsAddressedByItsTrashId() {
        List<QuickPickItem> items = TrashPicker.itemsFor(
                List.of(entry("t-7", "proj:src/Main.java", "alice", 0L)), NOW);

        assertEquals("t-7", items.get(0).id());
    }

    /** The folder it came from is the category, so two {@code Main.java}s are told apart by where. */
    @Test
    public void theFolderItCameFromIsTheCategory() {
        List<QuickPickItem> items = TrashPicker.itemsFor(
                List.of(entry("t-1", "proj:src/a/Main.java", "alice", 0L)), NOW);

        assertEquals("proj:src/a", items.get(0).category());
    }

    /** ...and when and who, which is what tells two deletions of ONE path apart. */
    @Test
    public void theDescriptionSaysWhenAndWho() {
        String described = TrashPicker.describe(
                entry("t-1", "proj:src/Main.java", "alice", 4 * 60_000L), NOW);

        assertTrue(described, described.contains("4 minutes ago"));
        assertTrue(described, described.contains("alice"));
    }

    /** A server that reports no actor says when, and does not say "by ". */
    @Test
    public void anAnonymousDeletionStillSaysWhen() {
        String described = TrashPicker.describe(entry("t-1", "proj:x.txt", "", 0L), NOW);

        assertEquals("deleted just now", described);
    }

    @Test
    public void ageIsCoarse() {
        assertEquals("just now", TrashPicker.ago(59_000L));
        assertEquals("1 minute ago", TrashPicker.ago(60_000L));
        assertEquals("2 minutes ago", TrashPicker.ago(120_000L));
        assertEquals("1 hour ago", TrashPicker.ago(3_600_000L));
        assertEquals("3 days ago", TrashPicker.ago(3 * 86_400_000L));
        // A CLOCK THAT DISAGREES is ordinary between two machines, and a negative age must not read as
        // "18446744073709 days ago".
        assertEquals("just now", TrashPicker.ago(-5_000L));
    }
}
