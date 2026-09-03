package com.crystalgui.headless;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceOperation;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspacePresence;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.server.RecentOperations;
import com.crystalgui.fs.server.WorkspaceAudit;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@code plan_fs_rewrite.md} F3 — editing presence, the audit, the rate limit and idempotent retries.
 *
 * <p>Two actors throughout, because every claim here is about what one peer's action means to another
 * one, and a single-actor fixture agrees with any implementation.</p>
 */
public class WorkspaceServerTest {

    /** {@code WorkspaceActor} is a one-method interface, so a named peer is a lambda plus a name. */
    private static WorkspaceActor actor(String id, String name) {
        return new WorkspaceActor() {
            @Override public String id() { return id; }
            @Override public String displayName() { return name; }
        };
    }

    private static final WorkspaceActor ALICE = actor("alice", "Alice");
    private static final WorkspaceActor BOB = actor("bob", "Bob");
    private static final CgPath FILE = CgPath.parse("proj:src/Main.java");
    private static final CgPath OTHER = CgPath.parse("proj:README.md");

    private WorkspaceService service() {
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("proj:src/Main.java", "class Main {}")
                .seed("proj:README.md", "# hi");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("proj", "Proj", Paths.get("/srv/proj"))));
        return new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);
    }

    // ── Editing presence ────────────────────────────────────────────────────────────────────────

    /**
     * <b>D12.</b> Presence answered "who has this open", which is the wrong question for the moment it
     * matters: two people find out they are both editing when the second one saves and is refused,
     * by which point both have work to reconcile.
     */
    @Test
    public void editingPresenceReachesTheOtherPeerOnTheFirstKeystroke() {
        WorkspacePresence presence = new WorkspacePresence();
        presence.opened(ALICE, FILE);
        presence.opened(BOB, FILE);

        assertEquals("nobody is editing yet", List.of(), presence.whoElseIsEditing(ALICE, FILE));

        presence.setEditing(BOB, FILE, true);

        assertEquals(List.of("Bob"), presence.whoElseIsEditing(ALICE, FILE));
        assertEquals("and Bob is not told about himself", List.of(),
                presence.whoElseIsEditing(BOB, FILE));
        assertTrue(presence.isEditing(BOB, FILE));
    }

    @Test
    public void savingClearsTheEditingFlag() {
        WorkspacePresence presence = new WorkspacePresence();
        presence.opened(ALICE, FILE);
        presence.opened(BOB, FILE);
        presence.setEditing(BOB, FILE, true);

        presence.setEditing(BOB, FILE, false);

        assertEquals(List.of(), presence.whoElseIsEditing(ALICE, FILE));
    }

    /** A flag that outlived what it describes is a banner nothing would ever take down. */
    @Test
    public void closingTheFileClearsTheEditingFlag() {
        WorkspacePresence presence = new WorkspacePresence();
        presence.opened(ALICE, FILE);
        presence.opened(BOB, FILE);
        presence.setEditing(BOB, FILE, true);

        presence.closed(BOB, FILE);

        assertFalse(presence.isEditing(BOB, FILE));
        assertEquals(List.of(), presence.whoElseIsEditing(ALICE, FILE));
    }

    /** And so does disconnecting, which is the case {@code unwatch} cannot cover. */
    @Test
    public void aDisconnectClearsEveryEditingFlagThatPeerHad() {
        WorkspacePresence presence = new WorkspacePresence();
        presence.opened(BOB, FILE);
        presence.opened(BOB, OTHER);
        presence.opened(ALICE, FILE);
        presence.setEditing(BOB, FILE, true);
        presence.setEditing(BOB, OTHER, true);

        presence.left(BOB);

        assertEquals(List.of(), presence.whoElseIsEditing(ALICE, FILE));
        assertFalse(presence.isEditing(BOB, OTHER));
    }

    /** An editing flag on a file nobody has open is one nothing will ever clear. */
    @Test
    public void aFlagOnAnUnopenedFileIsIgnored() {
        WorkspacePresence presence = new WorkspacePresence();

        presence.setEditing(BOB, FILE, true);

        assertFalse(presence.isEditing(BOB, FILE));
    }

    @Test
    public void theVersionMovesWhenEditingChanges() {
        WorkspacePresence presence = new WorkspacePresence();
        presence.opened(BOB, FILE);
        int before = presence.version();

        presence.setEditing(BOB, FILE, true);
        assertTrue(presence.version() > before);

        int after = presence.version();
        presence.setEditing(BOB, FILE, true);
        assertEquals("an unchanged flag is not a change", after, presence.version());
    }

    // ── The audit ───────────────────────────────────────────────────────────────────────────────

    /**
     * <b>D22.</b> This server hands a remote peer write access to a directory on somebody's machine and
     * kept no record of what was done — a write was authorised, performed, and left no trace beyond the
     * file's own mtime.
     */
    @Test
    public void everyMutationIsAudited() {
        AtomicLong clock = new AtomicLong(1000);
        WorkspaceAudit audit = new WorkspaceAudit(clock::get, WorkspaceAudit.DEFAULT_LIMIT);

        audit.record(ALICE, WorkspaceOperation.WRITE, FILE);
        audit.record(BOB, WorkspaceOperation.WRITE, OTHER);

        List<WorkspaceAudit.Entry> entries = audit.recent();
        assertEquals(2, entries.size());
        assertEquals("alice", entries.get(0).actor());
        assertEquals("proj:src/Main.java", entries.get(0).path());
        assertEquals(WorkspaceOperation.WRITE, entries.get(1).operation());
        assertFalse(entries.get(0).refused());
    }

    /** A refusal is the half worth having at three in the morning, and it carries its reason. */
    @Test
    public void aRefusalIsAuditedWithItsReason() {
        WorkspaceAudit audit = new WorkspaceAudit(() -> 0L, WorkspaceAudit.DEFAULT_LIMIT);

        audit.refused(BOB, WorkspaceOperation.WRITE, FILE, "not an operator");

        WorkspaceAudit.Entry entry = audit.recent().get(0);
        assertTrue(entry.refused());
        assertEquals("not an operator", entry.detail());
    }

    /** A refusal is not a mutation, so it must not count against the limit. */
    @Test
    public void aRefusalDoesNotCountTowardTheRate() {
        WorkspaceAudit audit = new WorkspaceAudit(() -> 0L, 2);

        audit.refused(BOB, WorkspaceOperation.WRITE, FILE, "no");
        audit.refused(BOB, WorkspaceOperation.WRITE, FILE, "no");
        audit.refused(BOB, WorkspaceOperation.WRITE, FILE, "no");

        assertTrue("being refused three times must not then lock somebody out", audit.allow(BOB));
    }

    // ── The rate limit ──────────────────────────────────────────────────────────────────────────

    @Test
    public void aFloodIsRefusedAndOnlyForTheActorFlooding() {
        AtomicLong clock = new AtomicLong(0);
        WorkspaceAudit audit = new WorkspaceAudit(clock::get, 3);

        for (int i = 0; i < 3; i++) {
            assertTrue(audit.allow(BOB));
            audit.record(BOB, WorkspaceOperation.WRITE, FILE);
        }

        assertFalse("the fourth inside the window is refused", audit.allow(BOB));
        assertTrue("and Alice is unaffected", audit.allow(ALICE));
    }

    @Test
    public void theWindowExpiresSoALimitIsNotAPermanentBan() {
        AtomicLong clock = new AtomicLong(0);
        WorkspaceAudit audit = new WorkspaceAudit(clock::get, 2);
        audit.record(BOB, WorkspaceOperation.WRITE, FILE);
        audit.record(BOB, WorkspaceOperation.WRITE, FILE);
        assertFalse(audit.allow(BOB));

        clock.set(WorkspaceAudit.WINDOW_MILLIS + 1);

        assertTrue(audit.allow(BOB));
        assertEquals(0, audit.rateFor(BOB));
    }

    /** The retained log is bounded; the limit's own counter is separate and is bounded by the window. */
    @Test
    public void theLogIsBounded() {
        WorkspaceAudit audit = new WorkspaceAudit(() -> 0L, Integer.MAX_VALUE);
        for (int i = 0; i < WorkspaceAudit.RETAINED + 50; i++) {
            audit.record(ALICE, WorkspaceOperation.WRITE, FILE);
        }
        assertEquals(WorkspaceAudit.RETAINED, audit.recent().size());
    }

    // ── Idempotent retries ──────────────────────────────────────────────────────────────────────

    /**
     * <b>D17.</b> A write whose answer is lost is retried, and the file now holds the etag the client's
     * own write produced — so the conditional write is refused as a conflict <b>against itself</b>, and
     * the person is shown a merge dialog for a change nobody else made.
     */
    @Test
    public void aWriteRetriedAfterATimeoutIsNotAConflict() {
        WorkspaceService service = service();
        RecentOperations operations = new RecentOperations();
        String before = service.stat(ALICE, FILE).etag();

        // The write happens and its answer is recorded. Then the answer is lost on the wire.
        String etag = service.write(ALICE, FILE, "changed".getBytes(), before);
        operations.record("op-1", etag);

        // The client retries with the SAME id and the SAME stale etag.
        assertTrue(operations.isRepeat("op-1"));
        assertEquals("answered from the table rather than performed again",
                etag, operations.answerFor("op-1"));
    }

    @Test
    public void anUnseenOperationIsNotARepeat() {
        RecentOperations operations = new RecentOperations();
        operations.record("op-1", "1:2");

        assertNull(operations.answerFor("op-2"));
        assertFalse(operations.isRepeat("op-2"));
    }

    /** A read carries no id, and an operation with no id is one nobody is prepared to retry. */
    @Test
    public void anOperationWithNoIdIsNeverARepeat() {
        RecentOperations operations = new RecentOperations();
        operations.record("", "1:2");
        operations.record(null, "1:2");

        assertFalse(operations.isRepeat(""));
        assertFalse(operations.isRepeat(null));
        assertEquals(0, operations.size());
    }

    @Test
    public void theTableIsBoundedAndDropsTheOldestFirst() {
        RecentOperations operations = new RecentOperations(4);
        for (int i = 0; i < 6; i++) operations.record("op-" + i, "etag-" + i);

        assertEquals(4, operations.size());
        assertNull("the oldest is gone", operations.answerFor("op-0"));
        assertEquals("etag-5", operations.answerFor("op-5"));
    }
}
