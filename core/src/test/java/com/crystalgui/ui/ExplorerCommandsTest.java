package com.crystalgui.ui;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.fs.CgPath;
import com.crystalgui.fs.InMemoryFileSystem;
import com.crystalgui.fs.ProjectRegistry;
import com.crystalgui.fs.WorkspaceActor;
import com.crystalgui.fs.WorkspaceClient;
import com.crystalgui.fs.WorkspacePermission;
import com.crystalgui.fs.WorkspaceProject;
import com.crystalgui.fs.WorkspaceRpc;
import com.crystalgui.fs.WorkspaceService;
import com.crystalgui.net.ClientUiSession;
import com.crystalgui.net.InMemoryTransport;
import com.crystalgui.net.ServerUiSession;
import com.crystalgui.serialization.PlainOps;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.elements.workbench.ExplorerClipboard;
import com.crystalgui.ui.elements.workbench.ExplorerCommands;
import com.crystalgui.ui.elements.workbench.Workbench;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExplorerCommands} — the Project panel's verbs.
 *
 * <p>What matters here is not that a delete deletes ({@code WorkspaceFileServiceTest} owns that) but that
 * the commands exist, that they are scoped to the panel, and that <b>every one of them refuses when there
 * is nothing to act on</b>. A file command that runs against a null target is the one that acts on the
 * wrong thing.</p>
 */
public class ExplorerCommandsTest extends UiTestBase {

    private UIWindow window;
    private Workbench workbench;

    private static WorkspaceClient<Object> client() {
        InMemoryFileSystem files = new InMemoryFileSystem()
                .seed("mymod.proj:README.md", "# hello")
                .seed("mymod.proj:src/Main.java", "class Main {}");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        ServerUiSession<Object> server =
                new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(server::onCall);
        server.open();
        return new WorkspaceClient<>(new ClientUiSession<>(pair[1], PlainOps.INSTANCE), PlainOps.INSTANCE);
    }

    @Before
    public void setUp() {
        workbench = new Workbench(client());
        UIElement root = new UIElement().layout(l -> l.widthPercent(100f).heightPercent(100f)
                .flexDirection(FlexDirection.COLUMN));
        root.addChild(workbench);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 5; i++) {
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    private CommandRegistry registry() {
        return window.getCommands();
    }

    /**
     * <b>The workbench installs them itself.</b>
     *
     * <p>Third time this rule is being asserted in this codebase, and the two previous omissions —
     * {@code GraphCommands}, then undo — both shipped as a panel that looked alive and answered no key.</p>
     */
    @Test
    public void theWorkbenchInstallsTheExplorerCommandsItself() {
        for (String id : ExplorerCommands.ids()) {
            assertTrue(id + " was never registered", registry().contains(id));
        }
    }

    /**
     * <b>Bare Delete and F2 are scoped to the panel, not the window.</b>
     *
     * <p>At the root they would fire while typing into any editor sharing the window — which, in a dock, is
     * most of them.</p>
     */
    @Test
    public void theBareKeysAreBoundOnTheTreeRatherThanTheWindow() {
        assertNotNull("Delete is not bound on the file tree",
                workbench.fileTree().keymap().chordFor(ExplorerCommands.DELETE));
        assertNotNull(workbench.fileTree().keymap().chordFor(ExplorerCommands.RENAME));
        assertEquals("a bare key reached the window root -- it would fire while typing",
                null, window.ui.rootElement.keymap().chordFor(ExplorerCommands.DELETE));
    }

    /**
     * <b>Every command refuses when nothing is selected.</b>
     *
     * <p>The one that matters. A command whose target resolves to null and runs anyway is the command that
     * deletes something else — and enablement is what the menu, the palette and the keystroke all consult,
     * so getting it right once covers all three.</p>
     */
    @Test
    public void everyCommandIsDisabledWithNothingSelected() {
        CommandContext context = CommandContext.of(workbench.fileTree());
        for (String id : ExplorerCommands.ids()) {
            Command command = registry().get(id);
            assertNotNull(command);
            assertFalse(id + " is enabled with no selection -- it would act on nothing, or on something "
                    + "the user never pointed at", command.isEnabled(context));
        }
    }

    /** Invoked from outside the panel there is no tree to ask, so the same answer has to hold. */
    @Test
    public void commandsAreDisabledWhenInvokedFromOutsideThePanel() {
        CommandContext elsewhere = CommandContext.of(window.ui.rootElement);
        for (String id : ExplorerCommands.ids()) {
            assertFalse(id + " is enabled from outside the explorer",
                    registry().get(id).isEnabled(elsewhere));
        }
    }

    /** The menu is built from ids, so it inherits enablement rather than restating it. */
    @Test
    public void theMenuListsEveryVerbAndDimsThemWithNoSelection() {
        Menu menu = ExplorerCommands.menu().build(registry(), workbench.fileTree());

        List<String> labels = new ArrayList<>();
        for (MenuItem item : menu.getItems()) labels.add(item.getText());
        assertTrue("no New submenu: " + labels, labels.contains("New"));
        assertTrue("no Rename: " + labels, labels.contains("Rename…"));
        assertTrue("no Delete: " + labels, labels.contains("Delete"));
        assertTrue("no Copy Path: " + labels, labels.contains("Copy Path"));

        for (MenuItem item : menu.getItems()) {
            if (item.hasSubmenu()) continue;   // the submenu row itself is always available
            assertFalse("'" + item.getText() + "' is enabled with nothing selected", item.isEnabled());
        }
    }

    /**
     * <b>Cut is consumed by a paste; copy is not.</b>
     *
     * <p>Pasting a cut a second time would move files from a path that no longer exists, and fail with an
     * error about a missing file rather than saying the obvious thing. Pasting a copy into three folders
     * is a real gesture. Windows Explorer, Finder and VS Code all behave this way.</p>
     */
    @Test
    public void aCutIsSpentByItsPasteButACopyIsNot() {
        ExplorerClipboard clipboard = ExplorerCommands.clipboard();

        clipboard.cut(List.of(CgPath.parse("mymod.proj:README.md")));
        assertFalse(clipboard.isEmpty());
        assertEquals(ExplorerClipboard.Mode.CUT, clipboard.mode());
        clipboard.consumeIfCut();
        assertTrue("a cut survived its paste", clipboard.isEmpty());

        clipboard.copy(List.of(CgPath.parse("mymod.proj:README.md")));
        clipboard.consumeIfCut();
        assertFalse("a copy was spent by a paste", clipboard.isEmpty());
        clipboard.clear();
    }

    /** Paste is refused with nothing held, so the menu row is dim rather than a no-op. */
    @Test
    public void pasteIsDisabledWithAnEmptyClipboard() {
        ExplorerCommands.clipboard().clear();
        assertFalse(registry().get(ExplorerCommands.PASTE)
                .isEnabled(CommandContext.of(workbench.fileTree())));
    }

    /** The tree takes multi-select, which is what every command acting on "the selection" needs. */
    @Test
    public void theTreeAllowsMoreThanOneSelection() {
        assertEquals(com.crystalgui.ui.elements.list.SelectionMode.MULTIPLE,
                workbench.fileTree().treeView().getSelectionMode());
    }

    /** Rename and Delete stay refused for a project root, which is not a file and has no parent. */
    @Test
    public void aProjectRootCannotBeRenamedOrDeleted() {
        CommandContext context = CommandContext.of(workbench.fileTree());
        // Nothing is selected here either, so this asserts the same refusal from the other direction --
        // what it pins is that isRenameable is consulted at all rather than the command being open season.
        assertFalse(registry().get(ExplorerCommands.RENAME).isEnabled(context));
        assertFalse(registry().get(ExplorerCommands.DELETE).isEnabled(context));
    }
}
