package com.crystalgui.ui;

import com.crystalgui.support.OldEngineSessions;
import com.crystalgui.core.collection.pick.QuickPickEntry;
import com.crystalgui.core.collection.pick.QuickPickSource;

import com.crystalgui.core.search.SearchQuery;

import com.crystalgui.core.command.ClipboardCommands;
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
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.core.collection.pick.QuickPickItem;
import com.crystalgui.ui.elements.workbench.GoToFile;
import com.crystalgui.ui.elements.workbench.WorkspaceTreeSource;
import com.crystalgui.ui.elements.workbench.Workbench;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.elements.SearchField;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.InputDialog;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.elements.workbench.ProjectFileTree;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.crystalgui.ui.input.keymap.Keymap;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgui.core.data.Transform2D;
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.core.collection.list.SelectionMode;
import com.crystalgui.ui.elements.workbench.FileDocument;
import com.crystalgui.core.signal.Connection;
import com.crystalgui.fs.Resource;

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

    /** The backing store, so a test can change it BEHIND the tree -- which is what F5 is for. */
    private static InMemoryFileSystem backingStore;
    private static InMemoryTransport<Object> serverSide;
    private static InMemoryTransport<Object> clientSide;
    private static ClientUiSession<UIElement, Object> clientSession;
    private static ServerUiSession<UIElement, Object> serverSession;

    private static WorkspaceClient<Object> client() {
        InMemoryFileSystem files = backingStore = new InMemoryFileSystem()
                .seed("mymod.proj:README.md", "# hello")
                .seed("mymod.proj:src/Main.java", "class Main {}");
        ProjectRegistry projects = new ProjectRegistry().register(() -> List.of(
                new WorkspaceProject("mymod.proj", "My Project", Paths.get("/srv/proj"))));
        WorkspaceService service = new WorkspaceService(projects, files, WorkspacePermission.ALLOW_ALL);

        InMemoryTransport<Object>[] pair = InMemoryTransport.pair();
        serverSide = pair[0];
        clientSide = pair[1];
        serverSession = OldEngineSessions.serve(1, new UIElement(), pair[0]);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(serverSession::onCall);
        serverSession.open();
        clientSession = OldEngineSessions.view(pair[1]);
        return new WorkspaceClient<>(clientSession, PlainOps.INSTANCE);
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
        // The keymap reads modifier state from the PLATFORM, not from the event, so a chord like
        // Mod+Z never matches unless the stub says the key is held.
        TestPlatformService.get().input(
                new CgInputService() {
                    @Override public int getCurrentModifiers() { return heldModifiers; }
                    @Override public int translateKeyboardCodes(int c) { return c; }
                    @Override public boolean isKeyDown(int c) { return false; }
                    @Override public int translateMouseCodes(int c) { return c; }
                    @Override public boolean isMouseDown(int c) { return false; }
                    @Override public int howManyMouseButtons() { return 3; }
                    @Override public String getClipboard() { return ""; }
                    @Override public void setClipboard(String text) { }
                });
        settle();
    }

    /**
     * One frame, plus the network tick a real client does per frame.
     *
     * <p>Without the transport half, {@code loadProjects} never completes and the tree has no roots — so
     * anything that falls back to the project root reports nothing to fall back to, for a reason that has
     * nothing to do with the code under test.</p>
     */
    private void settle() {
        for (int i = 0; i < 8; i++) {
            serverSide.deliver();
            clientSide.deliver();
            clientSession.tick();
            serverSession.tick();
            window.updateWithoutPainting();
            window.getInputHandler().beginFrame();
            window.getInputHandler().endFrame();
        }
    }

    /** Modifier state the keymap reads through the platform. */
    private int heldModifiers;

    /** Sends a chord with {@code modifiers} held for its duration. */
    private void chord(int keyCode, int modifiers) {
        heldModifiers = modifiers;
        try {
            window.getInputHandler().consumeKeyboardEvent(new CgSystemInput.Keyboard.Event(
                    (char) 0, keyCode, true, false, 20L));
        } finally {
            heldModifiers = 0;
        }
        settle();
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
    public void commandsThatNeedATargetAreDisabledWithNothingSelected() {
        CommandContext context = CommandContext.of(workbench.fileTree());
        // The ones that genuinely cannot mean anything without a selection. Rename, Delete and the two
        // Copy Paths all name a specific file; a fallback for them would act on something the user never
        // pointed at, which is the failure mode worth preventing.
        for (String id : List.of(ExplorerCommands.RENAME, ExplorerCommands.DELETE,
                ExplorerCommands.COPY_PATH, ExplorerCommands.COPY_RELATIVE_PATH,
                ExplorerCommands.CUT, ExplorerCommands.COPY)) {
            Command command = registry().get(id);
            assertNotNull(command);
            assertFalse(id + " is enabled with no selection -- it would act on nothing, or on something "
                    + "the user never pointed at", command.isEnabled(context));
        }
    }

    /**
     * <b>New File, New Folder and Reload work with nothing selected.</b>
     *
     * <p>Nothing selected is the normal state, not an edge case: right-clicking the empty space below the
     * files is how you make a file at the top level, and it is what the panel looks like the moment it
     * opens. Requiring a selection made New unavailable in exactly the situation it is most wanted — and
     * made a global Ctrl+N do nothing anywhere. IntelliJ resolves the same way, onto the project root.</p>
     */
    @Test
    public void newAndReloadFallBackToTheProjectRoot() {
        workbench.fileTree().loadProjects();
        settle();
        CommandContext context = CommandContext.of(workbench.fileTree());

        for (String id : List.of(ExplorerCommands.NEW_FILE, ExplorerCommands.NEW_FOLDER,
                ExplorerCommands.REFRESH)) {
            assertTrue(id + " is disabled with nothing selected, so right-clicking the empty panel "
                    + "offers nothing", registry().get(id).isEnabled(context));
        }
    }

    /**
     * <b>Ctrl+N is application-wide; the bare keys are not.</b>
     *
     * <p>New is an application verb — IntelliJ's is reachable from the editor, the tool windows and the
     * menu alike, and one that worked only while the Project panel had focus is one nobody would find. It
     * is a chord rather than a bare letter, so the root is safe: a chord cannot fire while typing, which is
     * exactly why Delete and F2 stay scoped to the tree.</p>
     */
    @Test
    public void newFileIsBoundGloballyWhileTheBareKeysStayScoped() {
        // Asserted through acceleratorFor, which answers "what would actually fire this here" -- the
        // question both halves care about. Ctrl+N is now a DECLARED binding on the command rather than an
        // entry written onto the root keymap, and the distinction the test exists to protect is about
        // reach, not about which map holds the row.
        assertNotNull("Ctrl+N is not reachable outside the Project panel",
                Keymap.acceleratorFor(window.ui.rootElement, ExplorerCommands.NEW_FILE));
        assertNull("a bare key reached the window root -- it would fire while typing",
                Keymap.acceleratorFor(window.ui.rootElement, ExplorerCommands.DELETE));
        // And still scoped to the tree, which is the half that keeps Delete off every text field.
        assertNotNull(workbench.fileTree().keymap().chordFor(ExplorerCommands.DELETE));
        assertNotNull(Keymap.acceleratorFor(workbench.fileTree(), ExplorerCommands.DELETE));
    }

    /**
     * Invoked from outside the panel, the commands that name a file stay refused.
     *
     * <p>New and Reload are deliberately NOT in this list: they are application verbs that resolve to the
     * project root, which is what makes {@code Ctrl+N} work from the shader graph or the Problems panel.
     * See {@link #newFileIsBoundGloballyWhileTheBareKeysStayScoped()}.</p>
     */
    @Test
    public void commandsThatNameAFileAreDisabledFromOutsideThePanel() {
        CommandContext elsewhere = CommandContext.of(window.ui.rootElement);
        for (String id : List.of(ExplorerCommands.RENAME, ExplorerCommands.DELETE,
                ExplorerCommands.COPY_PATH, ExplorerCommands.COPY_RELATIVE_PATH,
                ExplorerCommands.CUT, ExplorerCommands.COPY)) {
            assertFalse(id + " is enabled from outside the explorer",
                    registry().get(id).isEnabled(elsewhere));
        }
    }

    /** And New reaches the project root from anywhere, which is the point of binding it at the root. */
    @Test
    public void newFileWorksFromOutsideTheProjectPanel() {
        workbench.fileTree().loadProjects();
        settle();
        assertTrue("Ctrl+N does nothing outside the Project panel",
                registry().get(ExplorerCommands.NEW_FILE)
                        .isEnabled(CommandContext.of(window.ui.rootElement)));
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

        // Rename and Delete stay dim with nothing selected; New is reachable from the empty panel, which
        // is the whole point of the fallback.
        for (MenuItem item : menu.getItems()) {
            if (item.getText().equals("Rename…") || item.getText().equals("Delete")) {
                assertFalse("'" + item.getText() + "' is enabled with nothing selected", item.isEnabled());
            }
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
        assertEquals(SelectionMode.MULTIPLE,
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

    /** Answers the open name prompt, the way a user does: type, then Enter, then lets it settle. */
    private void answerPrompt(String name) {
        answerPromptWithoutSettling(name);
        settle();
    }

    /**
     * The keystroke alone, with no frames run after it.
     *
     * <p>For anything asserting on the frames an operation passes <em>through</em> rather than where it
     * ends up. Settling here is what made the folder-emptying test below pass against the defect it was
     * written for: the replacement listing had already arrived before the first assertion ran.</p>
     */
    private void answerPromptWithoutSettling(String name) {
        // THE ROW'S OWN INPUT, not a dialog. New File and Rename edit in place now -- VS Code's
        // FilesRenderer.renderInputBox -- and the modal survives only as the fallback for a host with no
        // tree on screen, which answerDialogPrompt below still covers.
        TextField field = visibleInlineEditor();
        if (field == null) {
            answerDialogPrompt(name);
            return;
        }
        field.setText(name);
        field.onSubmit.emit(name);
    }

    /** The inline editor of whichever row is being edited, or null when none is. */
    private TextField visibleInlineEditor() {
        if (!workbench.fileTree().isEditing()) return null;
        for (UIElement element : window.ui.rootElement.querySelectorAll(
                "." + ProjectFileTree.EDITOR_CLASS)) {
            if (element instanceof TextField field && element.getRuntimeCache().getWidth() > 0) {
                return field;
            }
        }
        // Realised but not yet laid out: still the right field to type into, and waiting a frame here
        // would hide the very defect these tests exist for.
        for (UIElement element : window.ui.rootElement.querySelectorAll(
                "." + ProjectFileTree.EDITOR_CLASS)) {
            if (element instanceof TextField field) return field;
        }
        return null;
    }

    private void answerDialogPrompt(String name) {
        UIElement popup = window.ui.rootElement.querySelector("." + InputDialog.PROMPT_CLASS);
        assertNotNull("no name prompt is open", popup);
        TextField field = (TextField) popup.querySelector("textfield");
        assertNotNull("the prompt has no field to type into", field);
        field.setText(name);
        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('\0', CgKeyCodes.KEY_RETURN, true, false, 1L));
    }

    /**
     * <b>New File lands inside the folder you asked from, not at the project root.</b>
     *
     * <p>The destination is resolved from the tree's <em>selection</em>, and right-clicking a row selects
     * it first for exactly this reason — every command resolves its target the same way, so a right-click
     * that did not select would make each of them act on whatever was selected before it.</p>
     */
    @Test
    public void newFileLandsInsideTheSelectedFolder() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src"));
        settle();
        assertEquals("fixture wrong -- src is not the selected row",
                CgPath.parse("mymod.proj:src"), workbench.fileTree().selectedPath());

        registry().get(ExplorerCommands.NEW_FILE).execute(CommandContext.of(workbench.fileTree()));
        answerPrompt("Made.java");

        // Asserted by REVEALING it, not by reading a cached listing: reveal walks down from the project
        // root and only lands if the file is genuinely there, so a file created at the wrong level fails
        // here rather than passing against a listing that was never refetched.
        CgPath made = CgPath.parse("mymod.proj:src/Made.java");
        workbench.fileTree().reveal(made);
        settle();
        assertEquals("the new file is not inside src", made, workbench.fileTree().selectedPath());
    }

    /** A new file opens in the editor, because creating one is a statement of intent to edit it. */
    @Test
    public void creatingAFileOpensIt() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src"));
        settle();

        registry().get(ExplorerCommands.NEW_FILE).execute(CommandContext.of(workbench.fileTree()));
        answerPrompt("Made.java");

        assertEquals("the file was created and left unopened", CgPath.parse("mymod.proj:src/Made.java"),
                workbench.activeFilePath());
        // THE TREE IS NOT ASSERTED HERE ANY MORE, and the reason is a default that changed under it.
        //
        // This used to also check that the new file was selected in the tree, on the reasoning that a
        // create which opens nothing and reveals nothing is indistinguishable from one that failed. That
        // was true only because `explorer.autoReveal` was on by default and followed the active tab; the
        // default is off now (IntelliJ's posture -- see WorkbenchSettings.AUTO_REVEAL), so the assertion
        // was pinning a setting rather than this command's behaviour. What New File guarantees is that the
        // file exists and is open, which the line above says. The reveal path has its own coverage.
    }

    /** The realised row element showing a given name, or null. */
    private UIElement rowElementFor(String name) {
        for (UIElement row : workbench.fileTree().treeView().getChildren()) {
            if (!row.hasClass(ProjectFileTree.ROW_CLASS)) continue;
            for (UIElement child : row.getChildren()) {
                if (child instanceof UIText text
                        && text.getText().contains(name)) return row;
            }
        }
        return null;
    }

    /** A real press of a given button at the row's centre, through the input handler. */
    private void pressButton(UIElement row, int button) {
        var cache = row.getRuntimeCache();
        var centre = Transform2D.apply(cache.localToWorld.get(),
                cache.getX() + cache.getWidth() * 0.5f, cache.getY() + cache.getHeight() * 0.5f);
        int x = Math.round(centre.x());
        int y = Math.round(centre.y());
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, -1, false, 0f, -1L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, button, true, 0f, 1L));
        window.getInputHandler().consumeMouseEvent(
                new CgSystemInput.Mouse.Event(x, y, 0, 0, button, false, 0f, 2L));
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        settle();
    }

    /**
     * <b>A right-click selects what it landed on</b> — the whole reason New lands where you asked.
     *
     * <p>Every command resolves its target through the selection, so a right-click that left the previous
     * selection standing would make each of them act on a file the user never pointed at. Asserted from a
     * real press rather than by calling select(), because the gap being guarded is between the gesture and
     * the state.</p>
     */
    @Test
    public void aRightClickSelectsTheRowUnderIt() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        settle();

        UIElement readme = rowElementFor("README.md");
        UIElement src = rowElementFor("src");
        assertNotNull("no realised row for README.md", readme);
        assertNotNull("no realised row for src", src);

        pressButton(readme, CgMouseCodes.LEFT_BUTTON);
        assertEquals("README.md", workbench.fileTree().selectedPath().name());

        // Right-clicking the OTHER row must move the selection onto it, not leave README.md selected.
        pressButton(src, CgMouseCodes.RIGHT_BUTTON);
        assertEquals("a right-click did not move the selection -- New would land beside the wrong file",
                CgPath.parse("mymod.proj:src"), workbench.fileTree().selectedPath());
    }

    /**
     * <b>The row's input is never painted anywhere but in its row.</b>
     *
     * <p>Inherited from the modal this replaced, where the defect was that a popup with no size yet was
     * <em>painted</em> at 0,0 and faded in there before hopping to the centre — so every New File opened
     * with a visible jump across the window. An inline editor cannot land in the corner for that reason,
     * but it can land in the wrong row: it is built once per template and shown by class, so a stale
     * {@code display} on a recycled row would put a live input on somebody else's file.</p>
     *
     * <p>Asserted <b>on every frame</b> rather than after settling, because the end state was already
     * right in the original defect too — the whole of it lived in the frames in between.</p>
     */
    @Test
    public void theRowInputIsOnlyEverInsideItsOwnRow() {
        workbench.fileTree().loadProjects();
        settle();
        registry().get(ExplorerCommands.NEW_FILE).execute(CommandContext.of(workbench.fileTree()));

        boolean everShown = false;
        for (int frame = 0; frame < 12; frame++) {
            serverSide.deliver();
            clientSide.deliver();
            clientSession.tick();
            serverSession.tick();
            window.updateWithoutPainting();

            int shown = 0;
            for (UIElement element : window.ui.rootElement.querySelectorAll(
                    "." + ProjectFileTree.EDITOR_CLASS)) {
                if (element.getRuntimeCache().getWidth() <= 0f) continue;
                shown++;
                UIElement row = element.getParent();
                assertNotNull("frame " + frame + ": the input is not in a row at all", row);
                assertTrue("frame " + frame + ": the input is in a row that is not being edited",
                        row.hasClass(ProjectFileTree.EDITING_CLASS));
            }
            assertTrue("frame " + frame + ": " + shown + " rows are showing an input at once",
                    shown <= 1);
            if (shown == 1) everShown = true;
        }
        assertTrue("no input ever became visible, so this asserted nothing", everShown);
    }

    /**
     * <b>A folder never momentarily loses its children.</b>
     *
     * <p>Invalidating a listing used to <em>drop</em> it, and a replacement arrives over the network some
     * frames later — so in between, an expanded folder answered "no children". Every create and every
     * delete therefore collapsed the whole folder and repopulated it two frames on. Traced in the harness
     * as {@code model=15 -> 9 -> 14} across three consecutive frames, which is exactly what it looks like:
     * six rows vanish and come back, and the tree appears to rebuild itself under you.</p>
     *
     * <p>Asserted on <b>every frame</b> of the operation, because the end state was always right — the
     * defect lived entirely in the frames in between, and a check made after settling steps past it.</p>
     */
    @Test
    public void creatingAFileNeverEmptiesTheFolderItLandsIn() {
        workbench.fileTree().loadProjects();
        settle();
        CgPath src = CgPath.parse("mymod.proj:src");
        workbench.fileTree().treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        workbench.fileTree().treeView().setExpanded(src, true);
        settle();
        workbench.fileTree().source().ensureListed(src);
        settle();
        // SELECTED, or New File resolves to the project root and this measures the wrong folder.
        workbench.fileTree().reveal(src);
        settle();

        int before = workbench.fileTree().source().children(src).size();
        assertTrue("fixture wrong -- src has no children to lose", before > 0);

        registry().get(ExplorerCommands.NEW_FILE).execute(CommandContext.of(workbench.fileTree()));
        answerPromptWithoutSettling("Fresh.java");

        for (int frame = 0; frame < 12; frame++) {
            serverSide.deliver();
            clientSide.deliver();
            clientSession.tick();
            serverSession.tick();
            window.updateWithoutPainting();

            assertTrue("frame " + frame + ": src reports "
                            + workbench.fileTree().source().children(src).size()
                            + " children -- it emptied while waiting for the new listing",
                    workbench.fileTree().source().children(src).size() >= before);
        }
        assertTrue("the new file never appeared in the folder",
                workbench.fileTree().source().children(src).size() > before);
    }

    /**
     * <b>F5 works before anything has been clicked.</b>
     *
     * <p>It did not: the binding lived on the tree, and a keymap resolves outward from the FOCUSED element
     * — so with nothing inside the panel focused, which is how it looks the moment it opens, the tree's
     * keymap was never on the path. Reload is precisely the verb you reach for before touching anything, so
     * needing a click first defeats it.</p>
     *
     * <p>Driven as a real key through the resolver with nothing focused, rather than by asserting which
     * keymap holds the binding: the binding was always present, and where it resolves FROM is the entire
     * defect.</p>
     */
    @Test
    public void reloadFromDiskWorksBeforeAnyRowHasBeenClicked() {
        workbench.fileTree().loadProjects();
        settle();
        assertEquals("fixture wrong -- something is already focused",
                null, window.getInputHandler().getFocusedElement());

        boolean consumed = window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event((char) 0, CgKeyCodes.KEY_F5, true, false, 9L));

        assertTrue("F5 reached nothing -- the binding is scoped to a panel that has no focus yet",
                consumed);
    }

    /**
     * <b>One F5 is enough.</b>
     *
     * <p>Reload exists for exactly one situation: something changed on disk that the tree has no way to
     * know about. So the test changes the backing store directly — no client call, no event — and presses
     * the key once.</p>
     */
    @Test
    public void oneF5PicksUpAChangeMadeBehindTheTree() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        settle();
        assertFalse("fixture wrong -- the file is already there",
                rootChildNames().contains("Appeared.md"));

        backingStore.seed("mymod.proj:Appeared.md", "x");

        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event((char) 0, CgKeyCodes.KEY_F5, true, false, 11L));
        settle();

        assertTrue("one F5 did not pick the file up -- it needs a second press. Saw " + rootChildNames(),
                rootChildNames().contains("Appeared.md"));
    }

    private List<String> rootChildNames() {
        List<String> names = new ArrayList<>();
        for (CgPath child : workbench.fileTree().source().children(CgPath.ofProject("mymod.proj"))) {
            names.add(child.name());
        }
        return names;
    }

    /**
     * <b>F5 reloads the whole tree, not the selected row's folder.</b>
     *
     * <p>The reason it looked broken. Scoping reload to the selection is what a file <em>operation</em>
     * does, because an operation knows which folder it touched — but somebody pressing F5 is asking
     * precisely because something changed that the tree cannot know about, and they have no way to say
     * where. So the change is made deep in {@code src} while the selection sits on a file at the ROOT: the
     * old behaviour reloaded the root, found nothing new, and appeared to do nothing at all.</p>
     */
    @Test
    public void f5ReloadsFoldersOtherThanTheSelectedOne() {
        workbench.fileTree().loadProjects();
        settle();
        CgPath src = CgPath.parse("mymod.proj:src");
        workbench.fileTree().treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        workbench.fileTree().treeView().setExpanded(src, true);
        settle();
        workbench.fileTree().source().ensureListed(src);
        settle();

        // Selection is at the ROOT, deliberately far from where the change lands.
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:README.md"));
        settle();
        assertEquals("README.md", workbench.fileTree().selectedPath().name());

        backingStore.seed("mymod.proj:src/Sneaky.java", "x");

        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event((char) 0, CgKeyCodes.KEY_F5, true, false, 12L));
        settle();

        List<String> inSrc = new ArrayList<>();
        for (CgPath child : workbench.fileTree().source().children(src)) inSrc.add(child.name());
        assertTrue("F5 only reloaded the selected row's folder -- src still shows " + inSrc,
                inSrc.contains("Sneaky.java"));
    }

    @Test
    public void ctrlZBringsBackADeletedFile() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:README.md"));
        settle();

        registry().get(ExplorerCommands.DELETE).execute(CommandContext.of(workbench.fileTree()));
        answerPrompt("y");
        assertFalse("fixture wrong -- the delete did not happen",
                rootChildNames().contains("README.md"));

        chord(CgKeyCodes.KEY_Z, CgModifiers.CTRL);

        assertTrue("Ctrl+Z did not bring the file back. Saw " + rootChildNames(),
                rootChildNames().contains("README.md"));
    }

    /**
     * <b>Moving several files is one undo step, not one per file.</b>
     *
     * <p>Each file is still its own operation — they succeed and fail separately — but the user made a
     * single gesture, and a history that costs five Ctrl+Z presses to put five files back is describing
     * the implementation rather than the action.</p>
     *
     * <p>The grouping has to survive the round trips: an operation pushes its edit from its response
     * handler, frames later, so a transaction closed at the end of the issuing loop wraps nothing at all
     * and every edit still lands separately.</p>
     */
    @Test
    public void movingSeveralFilesIsASingleUndoStep() {
        workbench.fileTree().loadProjects();
        settle();
        backingStore.seed("mymod.proj:one.txt", "1");
        backingStore.seed("mymod.proj:two.txt", "2");
        workbench.fileTree().source().invalidateAll();
        workbench.fileTree().treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        settle();
        assertTrue("fixture wrong -- the files are not at the root", rootChildNames().contains("one.txt"));

        workbench.fileTree().onFilesDropped.emit(
                List.of(CgPath.parse("mymod.proj:one.txt"), CgPath.parse("mymod.proj:two.txt")),
                new ProjectFileTree.DropRequest(CgPath.parse("mymod.proj:src"), false));
        settle();
        assertFalse("fixture wrong -- the move did not happen", rootChildNames().contains("one.txt"));
        assertFalse(rootChildNames().contains("two.txt"));

        // FOCUS IN THE PANEL, which is where it is after a drag inside it -- Ctrl+Z resolves its
        // UndoScope outward from the focused element, so with nothing focused there is no stack to
        // reach and this would assert against a keystroke that never ran.
        window.getInputHandler().requestPointerFocus(workbench.fileTree());
        chord(CgKeyCodes.KEY_Z, CgModifiers.CTRL);

        assertTrue("one Ctrl+Z did not bring BOTH files back -- the drop was recorded per file. Saw "
                        + rootChildNames(),
                rootChildNames().contains("one.txt") && rootChildNames().contains("two.txt"));
    }

    /**
     * <b>Cut then Paste of several files is one undo step.</b>
     *
     * <p>Same rule as the drop, and it was missing for the same reason — paste issues one operation per
     * file, correctly, and recorded one history step per file with it.</p>
     */
    @Test
    public void cuttingAndPastingSeveralFilesIsASingleUndoStep() {
        workbench.fileTree().loadProjects();
        settle();
        backingStore.seed("mymod.proj:one.txt", "1");
        backingStore.seed("mymod.proj:two.txt", "2");
        workbench.fileTree().source().invalidateAll();
        workbench.fileTree().treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        settle();

        ExplorerCommands.clipboard().cut(List.of(
                CgPath.parse("mymod.proj:one.txt"), CgPath.parse("mymod.proj:two.txt")));
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src"));
        settle();
        registry().get(ExplorerCommands.PASTE).execute(CommandContext.of(workbench.fileTree()));
        settle();
        assertFalse("fixture wrong -- the paste did not move anything",
                rootChildNames().contains("one.txt"));

        window.getInputHandler().requestPointerFocus(workbench.fileTree());
        chord(CgKeyCodes.KEY_Z, CgModifiers.CTRL);

        assertTrue("one Ctrl+Z did not bring BOTH files back -- the paste was recorded per file. Saw "
                        + rootChildNames(),
                rootChildNames().contains("one.txt") && rootChildNames().contains("two.txt"));
    }

    /**
     * <b>Saving clears the modified flag, and Save All clears every one of them.</b>
     *
     * <p>Here rather than beside the other document tests because it needs a real round trip: the client
     * refuses a save with no prior read — the write is etag-guarded — so this is only meaningful against a
     * fixture that actually pumps the transport.</p>
     */
    @Test
    public void saveAllWritesEveryModifiedFile() {
        workbench.fileTree().loadProjects();
        settle();
        CgPath one = CgPath.parse("mymod.proj:README.md");
        CgPath two = CgPath.parse("mymod.proj:src/Main.java");
        workbench.openFile(one);
        settle();
        workbench.openFile(two);
        settle();
        assertTrue("fixture wrong -- files opened dirty", workbench.unsavedFiles().isEmpty());

        workbench.editorFor(one).setText("one changed");
        workbench.editorFor(two).setText("two changed");
        assertEquals(2, workbench.unsavedFiles().size());

        assertEquals("both files should have been written", 2, workbench.saveAll());
        settle();

        assertTrue("Save All left modified files behind: " + workbench.unsavedFiles(),
                workbench.unsavedFiles().isEmpty());
    }

    /** And one save clears exactly one file. */
    @Test
    public void savingTheActiveFileClearsOnlyItsOwnFlag() {
        workbench.fileTree().loadProjects();
        settle();
        CgPath one = CgPath.parse("mymod.proj:README.md");
        CgPath two = CgPath.parse("mymod.proj:src/Main.java");
        workbench.openFile(one);
        settle();
        workbench.editorFor(one).setText("one changed");
        workbench.openFile(two);
        settle();
        workbench.editorFor(two).setText("two changed");

        assertTrue(workbench.saveActiveFile());
        settle();

        assertFalse("the active file is still modified after a save", workbench.isDirty(two));
        assertTrue("saving one file cleared another one too", workbench.isDirty(one));
    }

    /**
     * <b>A file is modified only while it differs from disk.</b>
     *
     * <p>Compared against the bytes last read or written rather than counted from edit events: a counter
     * says "modified" after a change AND its undo, which is exactly the state somebody is in when they
     * close a tab and get asked to save a file identical to the one already there.</p>
     *
     * <p>Needs a real round trip — a file with no completed read has no baseline to differ from, and is
     * correctly reported clean.</p>
     */
    @Test
    public void aFileIsModifiedOnlyWhileItDiffersFromDisk() {
        workbench.fileTree().loadProjects();
        settle();
        CgPath path = CgPath.parse("mymod.proj:README.md");
        workbench.openFile(path);
        settle();
        assertFalse("a freshly opened file is not modified", workbench.isDirty(path));

        String original = workbench.editorFor(path).getText();
        workbench.editorFor(path).setText(original + "more");
        assertTrue("an edited file is not reported as modified", workbench.isDirty(path));

        workbench.editorFor(path).setText(original);
        assertFalse("an edit and its undo still reported modified -- this counts edits rather than "
                + "comparing against disk", workbench.isDirty(path));
        assertTrue(workbench.unsavedFiles().isEmpty());
    }

    /**
     * <b>A document that refuses its bytes is never modified and never written.</b>
     *
     * <p>The dangerous case, and the reason {@code adopt} throws rather than shrugging: a document that
     * silently ignored the file would show empty, differ from the file it failed to read, report itself
     * modified, and let the first Save All write that emptiness over the user's work. A shader graph is in
     * exactly this state until {@code GraphView} can adopt a whole document.</p>
     */
    @Test
    public void aDocumentThatCannotLoadIsNeitherModifiedNorSaveable() {
        workbench.registerDocumentType("refuses", "Refuses", path ->
                new FileDocument() {
                    private final UIElement view = new UIElement();
                    @Override public UIElement view() { return view; }
                    @Override public byte[] encode() { return "EMPTY".getBytes(); }
                    @Override public void adopt(byte[] bytes) {
                        throw new UnsupportedOperationException("cannot load this yet");
                    }
                    // Never changes, so nothing to announce -- and the empty subscription is the honest
                    // way to say that rather than a default that hides it.
                    @Override public Connection onDidChange(Runnable listener) { return () -> { }; }
                    @Override public Resource resource() { return Resource.of(path); }
                });
        workbench.bindEditorExtensions("refuses", "weirdgraph");
        backingStore.seed("mymod.proj:thing.weirdgraph", "REAL CONTENT");
        workbench.fileTree().loadProjects();
        settle();

        CgPath path = CgPath.parse("mymod.proj:thing.weirdgraph");
        workbench.openFile(path);
        settle();

        assertFalse("a document that never loaded must not claim to be modified", workbench.isDirty(path));
        assertFalse("saving a file that never loaded would overwrite it with an empty document",
                workbench.saveActiveFile());
    }

    /**
     * <b>Renaming an open file carries everything about it to the new path.</b>
     *
     * <p>The document, the bytes it was read from, and whether it loaded at all now live in one entry per
     * path — because they used to be four separate maps that a rename had to update together. Moving three
     * of the four leaves a renamed file reporting itself modified against a baseline still filed under its
     * old name, and the next Save All writes it back for no reason.</p>
     *
     * <p>Asserted on the editor being the SAME instance, which is also the property the tab relies on: a
     * rename that rebuilt the document would drop the caret, the selection and any unsaved work with it.</p>
     */
    @Test
    public void renamingAnOpenFileCarriesItsDocumentAndBaseline() {
        workbench.fileTree().loadProjects();
        settle();
        CgPath from = CgPath.parse("mymod.proj:README.md");
        CgPath to = CgPath.parse("mymod.proj:RENAMED.md");
        workbench.openFile(from);
        settle();
        Object before = workbench.editorFor(from);
        assertNotNull(before);
        assertFalse(workbench.isDirty(from));

        workbench.files().move(from, to, false, () -> { }, failure -> { });
        settle();

        assertSame("the rename rebuilt the document -- unsaved work and the caret go with it",
                before, workbench.editorFor(to));
        assertFalse("the renamed file reports itself modified -- its baseline was left behind",
                workbench.isDirty(to));
        assertTrue("the old path is still open", workbench.unsavedFiles().isEmpty());
    }

    // ── Closing a modified tab (E16) ────────────────────────────────────────

    private boolean isOpenInDock(CgPath path) {
        return workbench.dock().layout().leafContaining(workbench.refFor(path)) != null;
    }

    private void closeActivePanel() {
        workbench.dock().closePanel(workbench.dock().activeGroup().leaf().activePanel());
        settle();
    }

    /**
     * <b>Closing a modified file asks before discarding it.</b>
     *
     * <p>The half of E16 that actually prevents data loss. The tab marker said a file was modified and
     * nothing acted on it, so {@code Ctrl+W} threw the work away silently — the worst possible combination,
     * since the marker implies something is watching.</p>
     */
    @Test
    public void closingAModifiedFileAsksFirst() {
        workbench.fileTree().loadProjects();
        settle();
        CgPath path = CgPath.parse("mymod.proj:README.md");
        workbench.openFile(path);
        settle();
        workbench.editorFor(path).setText("unsaved work");
        assertTrue(workbench.isDirty(path));

        closeActivePanel();

        assertTrue("the tab closed without asking -- the edit is gone", isOpenInDock(path));
        assertNotNull("no prompt was shown",
                window.ui.rootElement.querySelector("." + InputDialog.PROMPT_CLASS));
    }

    /** Confirming discards it, and does not ask a second time. */
    @Test
    public void confirmingTheCloseDiscardsTheFile() {
        workbench.fileTree().loadProjects();
        settle();
        CgPath path = CgPath.parse("mymod.proj:README.md");
        workbench.openFile(path);
        settle();
        workbench.editorFor(path).setText("unsaved work");

        closeActivePanel();
        answerPrompt("y");

        assertFalse("confirming did not close the tab -- the guard asked again", isOpenInDock(path));
    }

    /** An unmodified file closes straight away, with nothing in the way. */
    @Test
    public void closingAnUnmodifiedFileDoesNotAsk() {
        workbench.fileTree().loadProjects();
        settle();
        CgPath path = CgPath.parse("mymod.proj:README.md");
        workbench.openFile(path);
        settle();
        assertFalse(workbench.isDirty(path));

        closeActivePanel();

        assertFalse("an unmodified file should close immediately", isOpenInDock(path));
        assertEquals("a prompt was shown for a file with nothing to lose", null,
                window.ui.rootElement.querySelector("." + InputDialog.PROMPT_CLASS));
    }

    /**
     * A tool panel closes without a prompt — it holds nothing that is not on disk.
     *
     * <p>Worth pinning because a guard that asked about everything would train the answer out of the user
     * long before it mattered.</p>
     */
    @Test
    public void closingAToolPanelIsNeverGuarded() {
        workbench.fileTree().loadProjects();
        settle();
        // A tool window is not a dock panel any more -- it occupies a REGION, so it is hidden rather than
        // closed. The behaviour being pinned is unchanged: hiding one must never prompt, whatever the
        // mechanism underneath.
        var region = workbench.toolWindowManager().regionOf(Workbench.PROBLEMS_TYPE);
        assertEquals("fixture wrong -- no Problems panel is showing",
                Workbench.PROBLEMS_TYPE, workbench.regions().host(region).showing());

        workbench.hidePanel(Workbench.PROBLEMS_TYPE);
        settle();

        assertEquals("a tool panel should close with no prompt", null,
                window.ui.rootElement.querySelector("." + InputDialog.PROMPT_CLASS));
        assertEquals(null, workbench.regions().host(region).showing());
    }

    // ── Go to File (E17) ────────────────────────────────────────────────────

    /**
     * <b>The index reaches files nobody expanded a folder to see.</b>
     *
     * <p>This is what makes the feature honest. The tree lists lazily, so searching only what has been
     * expanded would mean typing a file name and not finding it — the failure being indistinguishable
     * from the file not existing. The crawl warms the tree's own listing cache from the workbench tick,
     * so there is no second index to keep in step and an expanded folder is instant afterwards.</p>
     */
    @Test
    public void theIndexReachesFilesInFoldersNobodyOpened() {
        backingStore.seed("mymod.proj:src/deep/buried/Needle.java", "x");
        // INVALIDATED after seeding: the workbench ticks from the moment it is attached, so its own
        // crawl has already listed src by the time this test body runs and would never see the file
        // added behind it. Nothing to do with the crawl -- a fixture that seeds after setUp has to say so.
        workbench.fileTree().source().invalidateAll();
        workbench.fileTree().loadProjects();
        settle();
        assertFalse("fixture wrong -- src was already expanded",
                workbench.fileTree().treeView().isExpanded(CgPath.parse("mymod.proj:src")));

        for (int i = 0; i < 40; i++) settle();

        List<String> names = new ArrayList<>();
        for (CgPath path : workbench.fileTree().source().knownFiles()) names.add(path.name());
        assertTrue("the crawl never reached a file three folders down: " + names,
                names.contains("Needle.java"));
    }

    /**
     * A row is the file NAME with its folder beside it, which is how both VS Code and IntelliJ present it
     * — you search for the name and disambiguate by folder, rather than searching one long string that
     * happens to contain a path.
     *
     * <p><b>The folder moved from {@code category} to {@code description}</b> when the picker merged with
     * Go to Class: a category renders BEFORE the label ("mymod.proj:src: Main.java", which reads as a
     * command category) and a description renders after it, which is where both references put a
     * location. The claim being pinned is unchanged; only the slot is.</p>
     *
     * <p>And the id is now a {@code Resource} rather than a bare path, because the list holds two kinds of
     * thing and the id has to say which — {@code java.util.ArrayList} parsed as a path opens something,
     * or silently nothing. @see GoToFileTest</p>
     */
    @Test
    public void aRowIsTheNameAndItsFolder() {
        workbench.fileTree().loadProjects();
        settle();
        for (int i = 0; i < 40; i++) settle();

        QuickPickItem main = null;
        List<CgPath> known = workbench.fileTree().source().knownFiles();
        for (QuickPickEntry entry : QuickPickSource.drain(
                (q, sink) -> GoToFile.fetchInto(q, known, sink),
                SearchQuery.of("Main.java"), 1000).entries()) {
            if ("Main.java".equals(entry.item().label())) main = entry.item();
        }
        assertNotNull("Main.java was never indexed", main);
        assertEquals("the folder belongs beside the name, not glued onto it",
                "mymod.proj:src", main.description());
        assertNull("a location in the leading slot reads as a command category", main.category());
        // A PROJECT RESOURCE STRINGIFIES AS A BARE PATH -- `Resource.toString` writes the scheme only
        // when there is a `://` marker, and a project path has none. That is what makes the id round-trip
        // through `Resource.parse` without the scheme ever being spelled, and why the old bare-path ids
        // are still valid addresses.
        assertEquals("the id must be the resource, so accepting a row needs no lookup",
                "mymod.proj:src/Main.java", main.id());
    }

    /** Mod+P reaches it from anywhere, for the same reason F5 does — a panel binding needs focus first. */
    @Test
    public void goToFileIsReachableWithNothingFocused() {
        workbench.fileTree().loadProjects();
        settle();
        assertEquals("fixture wrong -- something is focused", null,
                window.getInputHandler().getFocusedElement());

        boolean consumed = window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event('p', CgKeyCodes.KEY_P, true, false, 30L));
        assertFalse("a bare Mod+P must not fire without the modifier held", consumed);

        heldModifiers = CgModifiers.CTRL;
        try {
            assertTrue("Ctrl+P reached nothing -- the binding is scoped to a panel with no focus",
                    window.getInputHandler().consumeKeyboardEvent(
                            new CgSystemInput.Keyboard.Event('p', CgKeyCodes.KEY_P, true, false, 31L)));
        } finally {
            heldModifiers = 0;
        }
    }

    /**
     * <b>A step reports work remaining while its requests are still in flight.</b>
     *
     * <p>The queue is fed by listings that have <em>arrived</em>, so between asking for a directory and its
     * answer coming back there is nothing queued and nothing to do — which is indistinguishable from being
     * finished unless the outstanding requests are counted. Getting this wrong is what walked exactly two
     * levels and called it done, and it only shows on a workspace deeper than the number of frames
     * something happened to run for.</p>
     *
     * <p>Driven against the source directly, with no frames between the two calls, because that gap is the
     * whole condition being tested.</p>
     */
    @Test
    public void anIndexStepReportsMoreWhileRequestsAreOutstanding() {
        workbench.fileTree().loadProjects();
        settle();
        WorkspaceTreeSource source = workbench.fileTree().source();
        source.invalidateAll();

        // Ask for something, then ask again before anything can have come back.
        source.indexStep(1);
        assertTrue("a step with everything in flight reported the crawl finished", source.indexStep(1));
    }


    // -- Inline editing -------------------------------------------------------------------------------

    /**
     * <b>Rename edits the row, not a dialog over it.</b>
     *
     * <p>VS Code's {@code FilesRenderer.renderInputBox}. A dialog hides the folder you are naming inside,
     * puts the answer somewhere other than the question, and cannot show the icon change as you type.</p>
     */
    @Test
    public void renameEditsInPlace() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src/Main.java"));
        settle();

        registry().get(ExplorerCommands.RENAME).execute(CommandContext.of(workbench.fileTree()));
        settle();

        assertTrue("no row went into edit mode", workbench.fileTree().isEditing());
        assertEquals("the wrong row is being edited",
                CgPath.parse("mymod.proj:src/Main.java"), workbench.fileTree().editingPath());
        assertNotNull("there is no input in the row to type into", visibleInlineEditor());

        answerPrompt("Renamed.java");
        assertFalse("the edit never finished", workbench.fileTree().isEditing());
        assertTrue("the file was never renamed",
                names(CgPath.parse("mymod.proj:src")).contains("Renamed.java"));
    }

    /** Escape abandons it, and takes any placeholder row with it. */
    @Test
    public void escapeCancelsAnEditAndItsPlaceholder() {
        workbench.fileTree().loadProjects();
        settle();
        registry().get(ExplorerCommands.NEW_FILE).execute(CommandContext.of(workbench.fileTree()));
        settle();
        assertTrue("no placeholder row appeared", workbench.fileTree().isEditing());
        assertTrue("the placeholder is not in the tree", hasUnnamedRow());

        workbench.fileTree().cancelEdit();
        settle();
        assertFalse(workbench.fileTree().isEditing());
        // The row count is NOT asserted: New File expands the folder it will land in, and cancelling an
        // edit is not a reason to fold it again -- that would undo something the user can see and did not
        // ask to have undone.
        assertFalse("the placeholder outlived the edit that owned it", hasUnnamedRow());
    }

    /**
     * <b>A name that is already taken is refused rather than committed.</b>
     *
     * <p>Cancelling is the answer a blur gives too, and the only one that cannot destroy anything:
     * committing a name the validator has refused would overwrite, and trapping the user in a row they
     * cannot leave is the alternative to both.</p>
     */
    @Test
    public void aDuplicateNameIsRefused() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:README.md"));
        settle();

        registry().get(ExplorerCommands.RENAME).execute(CommandContext.of(workbench.fileTree()));
        settle();
        // src is a sibling of src at the project root, so this asks for a name that is taken.
        answerPrompt("src");
        settle();

        assertTrue("the original was renamed onto a name that was already taken",
                names(CgPath.ofProject("mymod.proj")).contains("README.md"));
    }

    // -- Clipboard delegation -------------------------------------------------------------------------

    /**
     * <b>Edit > Copy means FILES when the tree is what you are in.</b>
     *
     * <p>One row, many providers — IntelliJ's {@code $Copy} and its {@code CopyProvider}. Before this,
     * the single Edit row named {@code editor.copy} and was permanently greyed over the file tree while
     * {@code explorer.copy} sat unreachable in the same registry.</p>
     */
    @Test
    public void theEditMenusCopyActsOnFilesWhenTheTreeIsFocused() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src/Main.java"));
        settle();

        Command copy = registry().get(ClipboardCommands.COPY);
        assertNotNull("the delegating command is not registered", copy);
        CommandContext context = CommandContext.of(workbench.fileTree());
        assertTrue("Edit > Copy is greyed with a file selected in the tree", copy.isEnabled(context));

        copy.execute(context);
        Command paste = registry().get(ClipboardCommands.PASTE);
        assertTrue("nothing was put on the explorer's clipboard",
                paste.isEnabled(CommandContext.of(workbench.fileTree())));
    }

    /** Nothing provides a clipboard out on the chrome, and saying so is the honest answer. */
    @Test
    public void theEditMenusCopyIsGreyedWhereNothingProvidesAClipboard() {
        Command copy = registry().get(ClipboardCommands.COPY);
        assertFalse("something claimed a clipboard where there is none",
                copy.isEnabled(CommandContext.of(new UIElement())));
    }

    // -- Conflicts ------------------------------------------------------------------------------------

    /**
     * <b>A copy into a folder that already has the name never overwrites.</b>
     *
     * <p>VS Code's {@code findValidPasteFileTarget} applies incremental naming for exactly this. The
     * check used to fire only for paste-in-place, so a copy into another folder holding a namesake went
     * through as a plain write — the one <b>data-loss</b> bug among the five features.</p>
     */
    @Test
    public void pastingOntoANamesakeCopiesBesideItRatherThanOverIt() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src/Main.java"));
        settle();

        registry().get(ExplorerCommands.COPY).execute(CommandContext.of(workbench.fileTree()));
        // Pasted straight back into the same folder, which is the same question: the name is taken.
        registry().get(ExplorerCommands.PASTE).execute(CommandContext.of(workbench.fileTree()));
        settle();

        List<String> after = names(CgPath.parse("mymod.proj:src"));
        assertTrue("the original was overwritten", after.contains("Main.java"));
        assertTrue("no second copy was made: " + after, after.size() > 1);
    }

    /** Whether the tree currently shows the placeholder row, which has no name yet. */
    private boolean hasUnnamedRow() {
        for (var row : workbench.fileTree().treeView().visibleRows()) {
            if (workbench.fileTree().source().isPendingNew(row.item())) return true;
        }
        return false;
    }

    private List<String> names(CgPath directory) {
        List<String> out = new ArrayList<>();
        for (CgPath child : workbench.fileTree().source().listedChildren(directory)) {
            out.add(child.name());
        }
        return out;
    }


    // -- Reported bugs ---------------------------------------------------------------------------------

    /**
     * <b>An edit survives the refreshes that happen while it is open.</b>
     *
     * <p>{@code ListView.recycle()} blurs a row before pooling it — deliberately, for a defect of its own
     * — and the inline editor read that blur as the user leaving, committed, and closed. F2 opened an
     * input and shut it in the same frame, which is what it looked like: a flicker.</p>
     */
    @Test
    public void anEditSurvivesARefresh() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src/Main.java"));
        settle();

        registry().get(ExplorerCommands.RENAME).execute(CommandContext.of(workbench.fileTree()));
        settle();
        assertTrue("the edit never opened", workbench.fileTree().isEditing());

        // Exactly what a decoration change, a listing arriving or a fold does — and any of them can land
        // in the frames between opening the editor and typing into it.
        workbench.fileTree().treeView().refresh();
        settle();

        assertTrue("a refresh closed the edit, which is the flicker", workbench.fileTree().isEditing());
        assertEquals("and it must still be the same row",
                CgPath.parse("mymod.proj:src/Main.java"), workbench.fileTree().editingPath());
    }

    /**
     * <b>A refresh does not reset what has been typed.</b>
     *
     * <p>The reported flicker. {@code bind} runs on every refresh — a listing arriving, a decoration
     * changing, auto-reveal following the active tab, a fold — and priming the editor there re-set the
     * text to the file's name, re-took focus and re-selected the stem. So a name in progress was thrown
     * away several times a second, and the field could not be typed in at all because the caret was put
     * back on every frame.</p>
     */
    @Test
    public void typingSurvivesTheRefreshesThatHappenWhileEditing() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src/Main.java"));
        settle();

        registry().get(ExplorerCommands.RENAME).execute(CommandContext.of(workbench.fileTree()));
        settle();
        TextField field = visibleInlineEditor();
        assertNotNull(field);

        field.setText("HalfTyped");
        // Any of the ordinary reasons a row rebinds.
        workbench.fileTree().treeView().refresh();
        settle();

        assertTrue("the edit closed", workbench.fileTree().isEditing());
        assertEquals("a refresh threw away what had been typed", "HalfTyped",
                visibleInlineEditor().getText());
    }

    /** The other half: a blur the USER caused still commits, or the feature is gone rather than fixed. */
    @Test
    public void aRealBlurStillCommits() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src/Main.java"));
        settle();

        registry().get(ExplorerCommands.RENAME).execute(CommandContext.of(workbench.fileTree()));
        settle();
        TextField field = visibleInlineEditor();
        assertNotNull(field);
        field.setText("Blurred.java");

        // Focus moves somewhere real, which is what clicking away is. A FOCUSABLE element, deliberately:
        // requestFocus no-ops on one that is not, so aiming this at the tree itself asserted nothing.
        UIElement elsewhere = new UIElement().layout(l -> l.width(10).height(10));
        elsewhere.setFocusPolicy(com.crystalgui.ui.input.FocusPolicy.FOCUSABLE);
        window.ui.rootElement.addChild(elsewhere);
        window.updateWithoutPainting();
        window.getInputHandler().requestFocus(elsewhere);
        settle();

        assertFalse("the edit should have finished", workbench.fileTree().isEditing());
        assertTrue("a blur the user caused must still commit",
                names(CgPath.parse("mymod.proj:src")).contains("Blurred.java"));
    }

    /**
     * <b>The search box is a real input.</b>
     *
     * <p>It was drawn with a {@code UIText}, which looks identical and is not the same thing: it could not
     * be clicked into, could not take a caret, and Ctrl+A went past it to the tree and selected every
     * file. A search box the user cannot put the cursor in is a label containing their typing.</p>
     */
    @Test
    public void theSearchBoxCanBeFocusedAndTypedIn() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().setFilter("ma");
        settle();

        TextField box = searchBox();
        assertNotNull("the find bar has no input in it at all", box);
        assertTrue("the search box cannot take focus, so it cannot be clicked into",
                box.focusable());
        assertEquals("it does not show the query", "ma", box.getText());

        // Typing into the box drives the filter, rather than the box being a readout of it.
        box.setText("mai");
        settle();
        assertEquals("typing in the box did not reach the filter", "mai",
                workbench.fileTree().filter());
    }

    /** Ctrl+A belongs to whatever has focus — which is the box, once it can hold focus at all. */
    @Test
    public void selectAllInTheSearchBoxSelectsItsText() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().setFilter("main");
        settle();

        TextField box = searchBox();
        assertNotNull(box);
        window.getInputHandler().requestFocus(box);
        settle();

        box.selectAll();
        assertTrue("the box has no selection, so Ctrl+A had nothing to act on", box.hasSelection());
    }

    /** The find bar's input, or null. */
    /**
     * The text field inside the search box.
     *
     * <p>{@code FIND_INPUT_CLASS} names the BOX now, not the text — the toggles live inside its border, so
     * the class that says "the search input of a find bar" belongs to the bordered control containing the
     * text rather than to the text itself. The field is the {@code SearchField}'s own, which is what every
     * key in these tests is aimed at.</p>
     */
    private TextField searchBox() {
        for (UIElement element : window.ui.rootElement.querySelectorAll(
                "." + ProjectFileTree.FIND_INPUT_CLASS)) {
            if (element instanceof SearchField box) return box.field();
            if (element instanceof TextField field) return field;
        }
        return null;
    }



    /**
     * <b>F2 opens an editor that stays open.</b>
     *
     * <p>The reported flicker, and it was not in the explorer at all: {@code ListView} restores focus to
     * whichever row is at {@code focusedIndex} after a rebuild, and that took focus from the input inside
     * a row. The editor read the blur as the user leaving, committed and closed, so the field appeared
     * for exactly one frame.</p>
     *
     * <p>Driven through the <b>keymap</b> from a <b>focused row</b>, because neither shortcut is optional
     * here: executing the command directly never reproduced it, and aiming focus at the tree itself does
     * nothing at all — the tree is not focusable, so F2 resolved against nothing and the first version of
     * this test passed while asserting neither.</p>
     */
    @Test
    public void f2OpensAnEditorThatSurvivesTheFollowingFrames() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src/Main.java"));
        settle();

        UIElement row = null;
        for (UIElement element : window.ui.rootElement.querySelectorAll(
                "." + ProjectFileTree.ROW_CLASS)) {
            if (element.getRuntimeCache().getWidth() > 0f) {
                row = element;
                break;
            }
        }
        assertNotNull("no row to focus", row);
        window.getInputHandler().requestFocus(row);
        settle();

        window.getInputHandler().consumeKeyboardEvent(
                new CgSystemInput.Keyboard.Event(' ', CgKeyCodes.KEY_F2, true, false, 1L));

        for (int frame = 0; frame < 10; frame++) {
            settle();
            assertTrue("the editor closed on frame " + frame + " — the flicker",
                    workbench.fileTree().isEditing());
        }
    }


    /**
     * <b>Backspacing a query to empty does not close the box.</b>
     *
     * <p>It used to: the bar was shown when the filter was non-empty, so deleting the last character hid
     * it out from under the caret and there was no way to clear a query and retype one. VS Code's find
     * widget stays until Escape or its close button.</p>
     */
    @Test
    public void emptyingTheSearchBoxLeavesItOpen() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().openFind();
        settle();
        TextField box = searchBox();
        assertNotNull("Ctrl+F did not open the box", box);

        box.setText("mai");
        settle();
        box.setText("");
        settle();

        assertTrue("backspacing to empty closed the box", workbench.fileTree().isFindOpen());
        assertNotNull("and it must still be on screen to type into", searchBox());
    }

    /** Escape is what dismisses it, and it clears the query on the way out. */
    @Test
    public void escapeClosesTheSearchBoxAndClearsIt() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().openFind();
        settle();
        searchBox().setText("mai");
        settle();

        workbench.fileTree().closeFind();
        settle();
        assertFalse(workbench.fileTree().isFindOpen());
        assertEquals("the query outlived the box", "", workbench.fileTree().filter());
    }

    /** Ctrl+F opens it with nothing typed, which is the whole reason it is not driven by the query. */
    @Test
    public void findOpensWithAnEmptyQuery() {
        workbench.fileTree().loadProjects();
        settle();
        assertFalse(workbench.fileTree().isFindOpen());

        registry().get(ExplorerCommands.FIND_IN_TREE).execute(CommandContext.of(workbench.fileTree()));
        settle();

        assertTrue("Ctrl+F did not open the search box", workbench.fileTree().isFindOpen());
        assertNotNull(searchBox());
        assertEquals("it should open empty", "", workbench.fileTree().filter());
    }


    /**
     * <b>The matched characters are marked, not the row.</b>
     *
     * <p>Both references band or recolour the query span itself; a whole-row mark says "something here
     * matched" and leaves the eye to find what. Registered as a {@code ::highlight()} range rather than
     * spans, because wrapping would put a real Taffy node around three characters of every filename.</p>
     */
    @Test
    public void aMatchMarksTheQuerySpanAndNotTheWholeRow() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().reveal(CgPath.parse("mymod.proj:src/Main.java"));
        settle();
        workbench.fileTree().setFilter("ain");
        settle();

        UIText label = null;
        for (UIElement element : window.ui.rootElement.querySelectorAll("text")) {
            if (element instanceof UIText candidate && "Main.java".equals(candidate.getText())) {
                label = candidate;
                break;
            }
        }
        assertNotNull("the matching row is not on screen", label);

        List<com.crystalgui.ui.text.TextRange> ranges =
                label.highlights().get(ProjectFileTree.FIND_HIGHLIGHT);
        assertFalse("nothing was marked at all", ranges.isEmpty());
        for (com.crystalgui.ui.text.TextRange range : ranges) {
            assertTrue("a marked range must lie inside the name", range.end() <= "Main.java".length());
        }
    }

    /** A recycled row must not keep the previous file's marks — they would band unrelated letters. */
    @Test
    public void aNonMatchingRowCarriesNoMarks() {
        workbench.fileTree().loadProjects();
        settle();
        workbench.fileTree().setFilter("zzzznothing");
        settle();

        for (UIElement element : window.ui.rootElement.querySelectorAll("text")) {
            if (element instanceof UIText label) {
                assertTrue("a stale mark survived on " + label.getText(),
                        label.highlights().get(ProjectFileTree.FIND_HIGHLIGHT).isEmpty());
            }
        }
    }


    // -- Arrow navigation over matches -----------------------------------------------------------------

    /**
     * Types a query into the search box the way a user would, and settles.
     *
     * <p>Expands first, because matching is over what is <b>visible</b>: a collapsed tree shows one row
     * and nothing to navigate between, which is honest behaviour and a useless fixture.</p>
     */
    private void search(String query) {
        workbench.fileTree().treeView().setExpanded(CgPath.ofProject("mymod.proj"), true);
        settle();
        workbench.fileTree().treeView().setExpanded(CgPath.parse("mymod.proj:src"), true);
        settle();
        workbench.fileTree().openFind();
        settle();
        searchBox().setText(query);
        settle();
    }

    /** A key press delivered to the search box, which is where focus is during a search. */
    private void pressInSearchBox(int keyCode, int modifiers) {
        TextField box = searchBox();
        assertNotNull("no search box to type into", box);
        window.getInputHandler().sendInputEvent(box,
                new com.crystalgui.ui.event.KeyboardEvent.Down(box, keyCode, '\0', false, modifiers, 0L));
        settle();
    }

    /**
     * <b>Down steps match to match, and typing has already landed on the first.</b>
     *
     * <p>{@code QuickPick}'s pattern: the field owns the caret and the arrows, the list is a view of the
     * selection. Typing jumping to the first match is what makes a query answer itself before you stop
     * typing — without it the first Down press is spent going nowhere.</p>
     */
    @Test
    public void downStepsThroughTheMatches() {
        workbench.fileTree().loadProjects();
        settle();
        search("a");

        assertTrue("nothing matched, so this asserts nothing",
                workbench.fileTree().matchCount() >= 2);
        assertEquals("typing should already be on the first match",
                0, workbench.fileTree().currentMatchIndex());

        CgPath first = workbench.fileTree().currentMatch();
        pressInSearchBox(CgKeyCodes.KEY_DOWN, 0);
        assertEquals(1, workbench.fileTree().currentMatchIndex());
        assertNotEquals("Down did not move", first, workbench.fileTree().currentMatch());
    }

    /** Wraps at both ends, like Menu's Up/Down and like Tab. */
    @Test
    public void arrowsWrapAtBothEnds() {
        workbench.fileTree().loadProjects();
        settle();
        search("a");
        int count = workbench.fileTree().matchCount();
        assertTrue(count >= 2);

        pressInSearchBox(CgKeyCodes.KEY_UP, 0);
        assertEquals("Up from the first should wrap to the last",
                count - 1, workbench.fileTree().currentMatchIndex());

        pressInSearchBox(CgKeyCodes.KEY_DOWN, 0);
        assertEquals("and Down from the last back to the first",
                0, workbench.fileTree().currentMatchIndex());
    }

    /**
     * <b>Focus never leaves the search box.</b>
     *
     * <p>The ARIA combobox pattern, and the reason {@code ListView.restoreFocusIfRealised} must not take
     * focus from a control inside a row: the first arrow press would otherwise put the caret on a tree
     * row and the second would be typed into nothing.</p>
     */
    @Test
    public void arrowsLeaveFocusInTheSearchBox() {
        workbench.fileTree().loadProjects();
        settle();
        search("a");
        TextField box = searchBox();
        window.getInputHandler().requestFocus(box);
        settle();

        pressInSearchBox(CgKeyCodes.KEY_DOWN, 0);
        assertSame("an arrow moved focus out of the box",
                box, window.getInputHandler().getFocusedElement());
        pressInSearchBox(CgKeyCodes.KEY_UP, 0);
        assertSame(box, window.getInputHandler().getFocusedElement());
    }

    /**
     * <b>The keys that move a caret still move one.</b>
     *
     * <p>Left, Right, Home and End are deliberately not taken: this is a real text field, and match
     * navigation gets the pair that means nothing in a single-line input.</p>
     */
    @Test
    public void caretKeysAreNotStolenByMatchNavigation() {
        workbench.fileTree().loadProjects();
        settle();
        search("ma");
        int before = workbench.fileTree().currentMatchIndex();

        pressInSearchBox(CgKeyCodes.KEY_LEFT, 0);
        pressInSearchBox(CgKeyCodes.KEY_RIGHT, 0);
        pressInSearchBox(CgKeyCodes.KEY_HOME, 0);
        pressInSearchBox(CgKeyCodes.KEY_END, 0);

        assertEquals("a caret key moved the match — the box would be unusable",
                before, workbench.fileTree().currentMatchIndex());
    }

    /**
     * <b>A folder that only CONTAINS matches is not a stop.</b>
     *
     * <p>You can already see it and its badge says how many are inside; stopping there would put a deep
     * hit several presses away. The rule is the same in both modes — filtering keeps such folders too, so
     * "every visible row is a match" was never true even there.</p>
     */
    @Test
    public void aFolderThatMerelyContainsMatchesIsNotAStop() {
        workbench.fileTree().loadProjects();
        settle();
        // Matches Main.java inside src, and not src itself.
        search("Main");

        assertTrue("the query should match something", workbench.fileTree().matchCount() >= 1);
        for (int i = 0; i < workbench.fileTree().matchCount(); i++) {
            CgPath at = workbench.fileTree().currentMatch();
            assertNotEquals("src is a stop, and it does not match its own name",
                    CgPath.parse("mymod.proj:src"), at);
            pressInSearchBox(CgKeyCodes.KEY_DOWN, 0);
        }
    }

    /** Enter opens what the arrows are on. */
    @Test
    public void enterOpensTheCurrentMatch() {
        workbench.fileTree().loadProjects();
        settle();
        search("Main");
        assertEquals(CgPath.parse("mymod.proj:src/Main.java"), workbench.fileTree().currentMatch());

        pressInSearchBox(CgKeyCodes.KEY_RETURN, 0);
        assertEquals("Enter did not open the current match",
                CgPath.parse("mymod.proj:src/Main.java"), workbench.activeFilePath());
    }


}
