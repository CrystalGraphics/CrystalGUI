package com.crystalgui.ui;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgraphics.platform.service.CgInputService;

import com.crystalgui.editor.CrystalEditor;
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
import com.crystalgui.testsupport.TestPlatformService;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.elements.chrome.Preferences;
import com.crystalgui.ui.elements.workbench.ExplorerCommands;

import org.junit.Before;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The global shortcuts, against the shape the application actually has.
 *
 * <h3>{@link CrystalEditor} is the root here, and that is the whole point</h3>
 *
 * <p>{@code ExplorerCommandsTest} puts a bare {@code Workbench} under a permissive {@code UIElement}, and
 * that fixture has already been wrong twice in ways that mattered: it accepts public children, so
 * {@code addOverlay} parents an overlay to a full-size element rather than to the window's zero-sized
 * layer, and it installs none of the editor's own commands or keymaps. Both differences hide real
 * failures. This one builds what the harness builds.</p>
 */
public class PreferencesKeyTest extends UiTestBase {

    private UIWindow window;
    private CrystalEditor editor;
    private InMemoryTransport<Object> serverSide;
    private InMemoryTransport<Object> clientSide;
    private ClientUiSession<Object> clientSession;
    private ServerUiSession<Object> serverSession;

    private int heldModifiers;

    @Before
    public void setUp() {
        InMemoryFileSystem files = new InMemoryFileSystem().seed("mymod.proj:README.md", "# hello");
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

        editor = new CrystalEditor(new WorkspaceClient<>(clientSession, PlainOps.INSTANCE));
        window = new UIWindow(Ui.of(editor));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(1200, 800);
        editor.install(window);

        TestPlatformService.get().input(new CgInputService() {
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
        editor.workbench().fileTree().loadProjects();
        settle();
        editor.giveInitialFocus();
        settle();
    }

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

    private void chord(int keyCode, int modifiers) {
        heldModifiers = modifiers;
        try {
            window.getInputHandler().consumeKeyboardEvent(
                    new CgSystemInput.Keyboard.Event((char) 0, keyCode, true, false, 20L));
        } finally {
            heldModifiers = 0;
        }
        settle();
    }

    private boolean preferencesOpen() {
        return !editor.querySelectorAll("." + Preferences.DIALOG_CLASS).isEmpty()
                || !window.ui.rootElement.querySelectorAll("." + Preferences.DIALOG_CLASS).isEmpty();
    }

    /** The control: a binding on the same keymap, one line above, that is known to work by hand. */
    @Test
    public void modNIsReachableFromTheEditorRoot() {
        assertTrue("the commands were never installed, so neither assertion here means anything",
                window.getCommands().get(ExplorerCommands.PREFERENCES) != null);
        chord(CgKeyCodes.KEY_N, CgModifiers.CTRL);
        assertTrue("Mod+N did not reach a command from the editor root",
                !window.ui.rootElement.querySelectorAll("popover").isEmpty()
                        || !window.ui.rootElement.querySelectorAll("dialog").isEmpty());
    }

    /** And the one being reported as dead. */
    @Test
    public void modCommaOpensPreferencesFromTheEditorRoot() {
        assertFalse("nothing should be open yet", preferencesOpen());
        chord(CgKeyCodes.KEY_COMMA, CgModifiers.CTRL);
        assertTrue("Ctrl+, did not open the preferences window from the application root",
                preferencesOpen());
    }

    /**
     * <b>The letter-based alternative works too.</b>
     *
     * <p>A key code is a physical key — LWJGL2 reports DirectInput scancodes — and punctuation moves
     * between keyboard layouts while letters largely do not. A settings window reachable only through a
     * comma is one that some keyboards cannot open, and the symptom is indistinguishable from a broken
     * binding because every letter shortcut around it still works.</p>
     */
    @Test
    public void modAltSAlsoOpensPreferences() {
        assertFalse(preferencesOpen());
        chord(CgKeyCodes.KEY_S, CgModifiers.CTRL | CgModifiers.ALT);
        assertTrue("the layout-independent alternative did not open the preferences window",
                preferencesOpen());
    }
}
