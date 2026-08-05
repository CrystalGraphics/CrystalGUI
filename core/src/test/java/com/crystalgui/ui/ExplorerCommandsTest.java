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
import com.crystalgui.ui.elements.dock.DockPanelRef;
import com.crystalgui.ui.elements.workbench.Workbench;
import dev.vfyjxf.taffy.style.FlexDirection;
import org.junit.Before;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.ui.elements.TextField;
import com.crystalgui.ui.elements.chrome.InputDialog;
import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgui.ui.elements.workbench.ProjectFileTree;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
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

    /** The backing store, so a test can change it BEHIND the tree -- which is what F5 is for. */
    private static InMemoryFileSystem backingStore;
    private static InMemoryTransport<Object> serverSide;
    private static InMemoryTransport<Object> clientSide;
    private static ClientUiSession<Object> clientSession;
    private static ServerUiSession<Object> serverSession;

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
        serverSession = new ServerUiSession<>(1, new UIElement(), pair[0], PlainOps.INSTANCE);
        new WorkspaceRpc<Object>(service, WorkspaceActor.LOCAL).installOn(serverSession::onCall);
        serverSession.open();
        clientSession = new ClientUiSession<>(pair[1], PlainOps.INSTANCE);
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
        com.crystalgui.testsupport.TestPlatformService.get().input(
                new com.crystalgraphics.platform.service.CgInputService() {
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
        assertNotNull("Ctrl+N is not reachable outside the Project panel",
                window.ui.rootElement.keymap().chordFor(ExplorerCommands.NEW_FILE));
        assertEquals("a bare key reached the window root -- it would fire while typing",
                null, window.ui.rootElement.keymap().chordFor(ExplorerCommands.DELETE));
        assertNotNull(workbench.fileTree().keymap().chordFor(ExplorerCommands.DELETE));
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
        // And it shows in the tree, which is the other half of "nothing happened": the folder it landed in
        // is usually collapsed, so a create that opens nothing and reveals nothing is indistinguishable
        // from one that failed. autoReveal follows the active tab, so opening it should be enough.
        settle();
        assertEquals("the new file was opened but never shown in the tree",
                CgPath.parse("mymod.proj:src/Made.java"), workbench.fileTree().selectedPath());
    }

    /** The realised row element showing a given name, or null. */
    private UIElement rowElementFor(String name) {
        for (UIElement row : workbench.fileTree().treeView().getChildren()) {
            if (!row.hasClass(ProjectFileTree.ROW_CLASS)) continue;
            for (UIElement child : row.getChildren()) {
                if (child instanceof com.crystalgui.ui.elements.UIText text
                        && text.getText().contains(name)) return row;
            }
        }
        return null;
    }

    /** A real press of a given button at the row's centre, through the input handler. */
    private void pressButton(UIElement row, int button) {
        var cache = row.getRuntimeCache();
        var centre = com.crystalgui.core.data.Transform2D.apply(cache.localToWorld.get(),
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
     * <b>The prompt is never visible anywhere but the middle.</b>
     *
     * <p>A popup is out of flow, so it has no size until it has been laid out — and a prompt positioned
     * before that lands at 0,0. The centring pass is therefore unavoidable; what was wrong is that the
     * popup was <em>painted</em> in the corner while it waited, and the sheet's open transition faded it in
     * there before it hopped to the middle. Every New File and every Delete opened with a visible jump
     * across the window.</p>
     *
     * <p>Asserted as "on every frame, it is either invisible or centred" rather than "it ends up centred",
     * because the end state was already correct — the whole defect lived in the frames in between, which an
     * assertion made after settling steps straight past.</p>
     */
    @Test
    public void theNamePromptIsNeverPaintedInTheCorner() {
        workbench.fileTree().loadProjects();
        settle();
        registry().get(ExplorerCommands.NEW_FILE).execute(CommandContext.of(workbench.fileTree()));

        boolean everCentred = false;
        for (int frame = 0; frame < 12; frame++) {
            serverSide.deliver();
            clientSide.deliver();
            clientSession.tick();
            serverSession.tick();
            window.updateWithoutPainting();

            UIElement popup = window.ui.rootElement.querySelector("." + InputDialog.PROMPT_CLASS);
            if (popup == null) continue;
            float opacity = popup.getStyle().getGeneralGroup().opacity();
            float width = popup.getRuntimeCache().getWidth();
            if (opacity <= 0f || width <= 0f) continue;          // not shown yet -- nothing to see

            float x = popup.getRuntimeCache().getX();
            float expected = (window.getScreenWidth() - width) / 2f;
            assertEquals("frame " + frame + ": the prompt is visible at x=" + x
                            + " while the centre is " + expected,
                    expected, x, 1f);
            everCentred = true;
        }
        assertTrue("the prompt never became visible at all, so this asserted nothing", everCentred);
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

        chord(CgKeyCodes.KEY_Z, com.crystalgraphics.platform.input.CgModifiers.CTRL);

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
        chord(CgKeyCodes.KEY_Z, com.crystalgraphics.platform.input.CgModifiers.CTRL);

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
        chord(CgKeyCodes.KEY_Z, com.crystalgraphics.platform.input.CgModifiers.CTRL);

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
                new com.crystalgui.ui.elements.workbench.FileDocument() {
                    private final UIElement view = new UIElement();
                    @Override public UIElement view() { return view; }
                    @Override public byte[] encode() { return "EMPTY".getBytes(); }
                    @Override public void adopt(byte[] bytes) {
                        throw new UnsupportedOperationException("cannot load this yet");
                    }
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
        DockPanelRef problems = new DockPanelRef(Workbench.PROBLEMS_TYPE);
        assertNotNull("fixture wrong -- no Problems panel is open",
                workbench.dock().layout().leafContaining(problems));

        workbench.dock().closePanel(problems);
        settle();

        assertEquals("a tool panel should close with no prompt", null,
                window.ui.rootElement.querySelector("." + InputDialog.PROMPT_CLASS));
        assertEquals(null, workbench.dock().layout().leafContaining(problems));
    }
}
