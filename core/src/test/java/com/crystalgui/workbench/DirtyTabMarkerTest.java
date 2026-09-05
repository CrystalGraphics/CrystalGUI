package com.crystalgui.workbench;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Paths;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.crystalgui.document.Document;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.Resource;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.fs.project.ProjectRegistry;
import com.crystalgui.fs.project.WorkspaceProject;
import com.crystalgui.fs.provider.InMemoryFileSystem;
import com.crystalgui.fs.server.WatchHub;
import com.crystalgui.fs.server.WorkspaceActor;
import com.crystalgui.fs.server.WorkspaceBinding;
import com.crystalgui.fs.server.WorkspacePermission;
import com.crystalgui.fs.server.WorkspaceService;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.protocol.ProtocolConnection;
import com.crystalgui.net.protocol.Protocols;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiDocumentTestBase;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.texteditor.TextEditor;
import com.crystalgui.workbench.dock.DockGroup;
import com.crystalgui.workbench.dock.layout.DockLeaf;
import com.crystalgui.workbench.dock.layout.DockPanelRef;

/**
 * <b>Typing in a file puts the modified marker on its tab.</b>
 *
 * <p>Reported from the desktop scene: editing a file never grew the {@code *}. The whole chain is
 * already there — a buffer edit moves the model's version, the document notices it is dirty, the state
 * signal is relayed, and the tab's title provider appends the marker — so this walks the whole of it
 * rather than any one link, because which link is missing is exactly the question.</p>
 *
 * <p>Asserted on the TITLE the tab would draw, not on {@code isDirty()}: the document being dirty is
 * necessary and is not what was reported, and a test that stopped there would pass against a tab that
 * never refreshes.</p>
 */
public class DirtyTabMarkerTest extends UiDocumentTestBase {

    private static final String PROJECT = "scratch";
    private static final CgPath FILE = CgPath.of(PROJECT, "Main.java");

    private Workbench workbench;
    private Workspace workspace;
    private InMemoryTransport<Object>[] link;
    private ProtocolConnection<Object> serverEnd;
    private ProtocolConnection<Object> clientEnd;

    @Before
    public void openWorkbench() {
        Protocols.resetForTesting();

        InMemoryFileSystem files = new InMemoryFileSystem().seed(PROJECT + ":Main.java", "class Main { }");
        WorkspaceService service = new WorkspaceService(
                new ProjectRegistry().register(() -> List.of(
                        new WorkspaceProject(PROJECT, "Scratch", Paths.get("/srv/scratch")))),
                files, WorkspacePermission.ALLOW_ALL);

        link = InMemoryTransport.pair();
        serverEnd = Protocols.open(link[0], PlainOps.INSTANCE, () -> { }, "host");
        clientEnd = Protocols.open(link[1], PlainOps.INSTANCE, () -> { }, null);
        new WorkspaceBinding<>(service, new WatchHub(service), WorkspaceActor.LOCAL, "host",
                PlainOps.INSTANCE).installOn(serverEnd);

        workspace = Workspace.of(clientEnd);
        workbench = new Workbench(workspace);
        UIElement root = new UIElement().layout(l -> l.width(1200).height(800));
        root.append(workbench);
        document.append(root);
        document.styleEngine().addStylesheet(StyleSheet.DEFAULT);
    }

    @After
    public void closeWorkbench() {
        if (workbench != null) workbench.dispose();
        if (clientEnd != null) clientEnd.close("test over");
        if (serverEnd != null) serverEnd.close("test over");
        Protocols.resetForTesting();
    }

    /** A frame, and one tick of the wire — the harness's own loop. */
    private void frameAndPump() {
        frame();
        link[0].deliver();
        link[1].deliver();
        serverEnd.tick();
        clientEnd.tick();
    }

    /** The panel ref for the open file, or null while the read is still in flight. */
    private DockPanelRef openTab() {
        for (DockLeaf leaf : workbench.dock().layout().leaves()) {
            DockGroup group = workbench.dock().groupFor(leaf);
            if (group == null) continue;
            for (DockPanelRef panel : group.panels()) {
                if (FILE.toString().equals(panel.state(Workbench.PATH_STATE, ""))) return panel;
            }
        }
        return null;
    }

    @Test
    public void editingAFileMarksItsTab() {
        workbench.openFile(FILE);
        for (int i = 0; i < 12; i++) frameAndPump();

        DockPanelRef tab = openTab();
        assertNotNull("the file never opened, so this test is measuring nothing", tab);

        String clean = workbench.documentTabs.tabTitleFor(tab);
        assertNotNull("the tab has no title of its own", clean);
        assertTrue("an unedited file was already marked modified: " + clean,
                !clean.endsWith(Workbench.DIRTY_MARKER));

        TextEditor editor = workbench.editorFor(FILE);
        assertNotNull("the file opened without a text editor behind it", editor);
        // THE BUFFER, not the document: this is what typing does, and the whole question is whether an
        // edit reaches the tab from there.
        editor.buffer().edit(com.crystalgui.text.ChangeSet.of(editor.buffer().length(),
                new com.crystalgui.text.Change(0, 0, "// ")));
        for (int i = 0; i < 4; i++) frameAndPump();

        assertTrue("the document did not notice its own edit",
                workbench.saveActions.isDirty(FILE));
        String dirty = workbench.documentTabs.tabTitleFor(tab);
        assertTrue("the tab was not marked modified after an edit: " + dirty,
                dirty != null && dirty.endsWith(Workbench.DIRTY_MARKER));
    }

    /**
     * <b>...and undoing back to the saved text clears it again.</b>
     *
     * <p>Reported straight after the marker started appearing: it never went away. {@code isDirty} was
     * {@code version() != savedVersion} and the version is monotonic — undo moves the text as surely as
     * typing does, so it advances too, and one keystroke and its undo left a document permanently
     * modified. Both references clear the mark here, and the document is genuinely identical to the file.</p>
     *
     * <p>The undo DEPTH is not the answer and is the tempting one: make a different edit from the same
     * depth and it matches again, so the document would report itself clean holding text nobody saved —
     * which is the counter-case below.</p>
     */
    @Test
    public void undoingBackToTheSavedTextClearsTheMark() {
        workbench.openFile(FILE);
        for (int i = 0; i < 12; i++) frameAndPump();
        DockPanelRef tab = openTab();
        assertNotNull("the file never opened, so this test is measuring nothing", tab);

        TextEditor editor = workbench.editorFor(FILE);
        assertNotNull(editor);
        editor.buffer().edit(com.crystalgui.text.ChangeSet.of(editor.buffer().length(),
                new com.crystalgui.text.Change(0, 0, "// ")));
        for (int i = 0; i < 4; i++) frameAndPump();
        assertTrue("the edit did not mark the file at all", workbench.saveActions.isDirty(FILE));

        assertTrue("there was nothing to undo", editor.buffer().undo());
        for (int i = 0; i < 4; i++) frameAndPump();

        assertFalse("the document is identical to the file and still reports itself modified",
                workbench.saveActions.isDirty(FILE));
        String title = workbench.documentTabs.tabTitleFor(tab);
        assertTrue("the tab kept its modified marker after the edit was undone: " + title,
                title != null && !title.endsWith(Workbench.DIRTY_MARKER));
    }

    /**
     * The counter-control: a DIFFERENT edit made from the same undo depth is still modified.
     *
     * <p>What stops this being "fixed" by comparing the undo <em>depth</em>, which is the obvious cheap
     * answer and reports a document clean while it holds text nobody saved — a far worse failure than a
     * mark that will not clear.</p>
     *
     * <p><b>It has to save first</b>, and the first version of this test did not: with the save point at
     * depth 0 the divergent edit sits at depth 1 and a depth comparison gets the right answer by luck.
     * Saving at depth 1 is what makes the two states share a depth, which is the whole case.</p>
     */
    @Test
    public void aDifferentEditFromTheSameDepthIsStillModified() {
        workbench.openFile(FILE);
        for (int i = 0; i < 12; i++) frameAndPump();
        TextEditor editor = workbench.editorFor(FILE);
        Document document = workbench.documents().get(Resource.of(FILE));
        assertNotNull(editor);
        assertNotNull(document);

        editor.buffer().edit(com.crystalgui.text.ChangeSet.of(editor.buffer().length(),
                new com.crystalgui.text.Change(0, 0, "// one")));
        for (int i = 0; i < 4; i++) frameAndPump();

        // SAVED HERE, one undo step in -- so the state being returned to below is the SAVED one and the
        // divergent edit lands back on its depth.
        workbench.documents().save(document);
        for (int i = 0; i < 12; i++) frameAndPump();
        assertFalse("the save did not settle, so this test is measuring nothing",
                workbench.saveActions.isDirty(FILE));

        assertTrue("there was nothing to undo", editor.buffer().undo());
        for (int i = 0; i < 4; i++) frameAndPump();
        assertTrue("undoing away from the saved text left the document clean",
                workbench.saveActions.isDirty(FILE));

        editor.buffer().edit(com.crystalgui.text.ChangeSet.of(editor.buffer().length(),
                new com.crystalgui.text.Change(0, 0, "// two")));
        for (int i = 0; i < 4; i++) frameAndPump();

        assertTrue("a document holding unsaved text reported itself clean, which is the one answer that "
                + "loses work", workbench.saveActions.isDirty(FILE));
    }
}
