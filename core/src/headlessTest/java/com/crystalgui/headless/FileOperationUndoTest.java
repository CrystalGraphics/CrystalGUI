package com.crystalgui.headless;

import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.fs.server.WorkspaceTrash;
import com.crystalgui.fs.client.FileOperations;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Ctrl+Z in the explorer: a file operation is an undoable step on the workspace's own stack.
 *
 * <p>The stack belongs to the workspace rather than to any document, because a file operation changes
 * the workspace and not a file's contents — which is why Ctrl+Z in an editor still reaches the editor's
 * own history.</p>
 *
 * <p>Both halves of a step only <b>issue</b> a call; neither waits for one. Undo is therefore not a
 * second way for a tree to learn about a change: the view updates when the answer arrives, through the
 * same announcement every other change takes.</p>
 */
public class FileOperationUndoTest {

    private static final String PROJECT = "p";
    private static final Object PEER = new Object();

    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverSide;
    private ProtocolConnection<Object> clientSide;
    private InMemoryFileSystem files;
    private WorkspaceService service;
    private Workspace workspace;
    private FileOperations ops;

    @Before
    public void setUp() {
        Protocols.resetForTesting();
        files = new InMemoryFileSystem().seed("p:a.txt", "one").seed("p:dir/b.txt", "two");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject(PROJECT, "P", Paths.get("/srv/p"))));
        service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL,
                new WorkspaceTrash.InMemory());

        link = InMemoryTransport.pair();
        serverSide = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "alice");
        clientSide = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, PEER,
                PlainOps.INSTANCE).installOn(serverSide::onRequest);

        workspace = Workspace.of(clientSide);
        ops = workspace.files();
        pump();
    }

    @After
    public void tearDown() {
        Protocols.resetForTesting();
    }

    private void pump() {
        for (int i = 0; i < 8; i++) {
            link[0].deliver();
            link[1].deliver();
            serverSide.tick();
            clientSide.tick();
        }
    }

    private static Resource at(String path) {
        return Resource.of(CgPath.of(PROJECT, path));
    }

    private boolean exists(String path) {
        try {
            files.stat(CgPath.of(PROJECT, path));
            return true;
        } catch (RuntimeException absent) {
            return false;
        }
    }

    @Test
    public void creatingIsUndoable() {
        ops.create(at("new.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        pump();
        assertTrue(exists("new.txt"));

        assertTrue("a create leaves something to undo", ops.undoStack().canUndo());
        ops.undoStack().undo();
        pump();

        assertFalse("undoing a create deletes what it made", exists("new.txt"));
    }

    @Test
    public void aCreateCanBeRedone() {
        ops.create(at("new.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        pump();
        ops.undoStack().undo();
        pump();

        assertTrue(ops.undoStack().canRedo());
        ops.undoStack().redo();
        pump();

        assertTrue("and redoing it makes it again", exists("new.txt"));
    }

    @Test
    public void movingIsUndoable() {
        ops.rename(at("a.txt"), at("moved.txt"), false);
        pump();
        assertTrue(exists("moved.txt"));
        assertFalse(exists("a.txt"));

        ops.undoStack().undo();
        pump();

        assertTrue("undoing a move puts it back", exists("a.txt"));
        assertFalse(exists("moved.txt"));
    }

    /**
     * A delete is undoable through the trash, and the trash id is what makes it so.
     *
     * <p>The id arrives with the answer rather than before it, so the step is recorded when the delete
     * <b>succeeds</b>.</p>
     */
    @Test
    public void deletingIsUndoableThroughTheTrash() {
        ops.delete(at("a.txt"));
        pump();
        assertFalse(exists("a.txt"));

        assertTrue("a successful delete leaves something to undo", ops.undoStack().canUndo());
        ops.undoStack().undo();
        pump();

        assertTrue("and undoing it restores the file", exists("a.txt"));
    }

    /**
     * The counter-control. A delete the server refused must leave nothing on the stack: Ctrl+Z offering
     * to take back something that never happened is worse than offering nothing.
     */
    @Test
    public void aRefusedDeleteIsNotUndoable() {
        ops.delete(at("does-not-exist.txt"));
        pump();

        assertFalse("nothing happened, so there is nothing to undo", ops.undoStack().canUndo());
    }

    /**
     * A step replayed by the stack does not record itself.
     *
     * <p>Undo issues the inverse and redo issues the original, both through the same methods — so
     * without a guard each replay would push another entry and the stack would grow on every Ctrl+Z.
     */
    @Test
    public void replayingAStepDoesNotRecordASecondOne() {
        ops.create(at("new.txt"), "hello".getBytes(StandardCharsets.UTF_8));
        pump();
        assertEquals(1, ops.undoStack().undoDepth());

        ops.undoStack().undo();
        pump();
        assertEquals("undoing empties the stack rather than adding to it",
                0, ops.undoStack().undoDepth());

        ops.undoStack().redo();
        pump();
        assertEquals("and redoing puts back exactly one", 1, ops.undoStack().undoDepth());
    }
}
