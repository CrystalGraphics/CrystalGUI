package com.crystalgui.ui.elements.desktop;

import com.crystalgraphics.platform.input.CgMouseCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuSection;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.Ui;
import com.crystalgui.ui.elements.Button;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * The window command surface — CrystalOS <b>W13a</b>.
 *
 * <p>What these pin is <b>which window a command is about</b>. Every route into the system menu
 * resolves its subject through one {@code DataContext} walk, and the three routes start from three
 * very different elements: a control inside the frame, the title bar, and a taskbar entry that is not
 * inside the window at all. Getting that wrong is not a cosmetic failure — it closes the wrong
 * window.</p>
 */
public class WindowCommandsTest extends UiTestBase {

    private UIWindow window;
    private WindowFrame first;
    private WindowFrame second;

    @Before
    public void setUpDesktop() {
        CommandRegistry.global().resetForTesting();
        WindowCommands.resetForTesting();

        UIElement root = new UIElement().layout(l -> l.width(800).height(600));
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);

        first = window.openWindow(new WindowFrame("First"));
        first.resizeTo(300, 200).moveTo(20, 20);
        second = window.openWindow(new WindowFrame("Second"));
        second.resizeTo(300, 200).moveTo(360, 20);
        settle();
    }

    private void settle() {
        for (int i = 0; i < 3; i++) window.updateWithoutPainting();
    }

    private CommandRegistry registry() {
        return CommandRegistry.global();
    }

    /** Runs a registered command as if invoked from {@code source}. */
    private void run(String id, UIElement source) {
        Command command = registry().get(id);
        assertNotNull("command not registered: " + id, command);
        command.execute(CommandContext.of(source));
    }

    private boolean enabled(String id, UIElement source) {
        Command command = registry().get(id);
        assertNotNull("command not registered: " + id, command);
        return command.isEnabled(CommandContext.of(source));
    }

    // ── Which window is this about ──────────────────────────────────────────────────────────────

    /**
     * <b>A command invoked from inside a window is about THAT window.</b>
     *
     * <p>The ordinary case, and the one the walk handles by construction — but it is also what makes
     * the desktop's window-level answer safe to have at all. If the element chain did not win, every
     * command would act on whichever window happened to be active.</p>
     */
    @Test
    public void aCommandInsideAWindowResolvesToThatWindow() {
        Button inside = new Button("inside");
        first.content().addChild(inside);
        settle();
        window.desktop().activate(second);
        settle();
        assertSame("the fixture did not put the OTHER window in front",
                second, window.desktop().activeWindow());

        assertSame("a control inside a window resolved to the active window instead of its own",
                first, WindowCommands.frameFor(CommandContext.of(inside)));
    }

    /**
     * <b>A taskbar entry is about ITS window, not the one in front.</b>
     *
     * <p>The entry is not a descendant of the window it stands for, so the walk reaches the taskbar and
     * then the desktop — which answers with the active frame. Right-clicking a background entry and
     * choosing Close would then close whatever was in front, which is the worst failure available to a
     * menu whose whole job is to name its subject.</p>
     */
    @Test
    public void aTaskbarEntryResolvesToItsOwnWindow() {
        window.desktop().activate(second);
        settle();
        Button entry = window.desktop().taskbar().entryFor(first);
        assertNotNull("no taskbar entry for the background window", entry);

        assertSame("a taskbar entry resolved to the active window instead of its own",
                first, WindowCommands.frameFor(CommandContext.of(entry)));
    }

    /**
     * <b>With nothing focused, the desktop answers with the active window.</b>
     *
     * <p>The documented last resort, and the reason it exists: a command reached from the palette has no
     * focused element to walk from, and "no subject" would mean every window command greys out exactly
     * when a keyboard user reaches for it.</p>
     */
    @Test
    public void withNothingFocusedTheActiveWindowIsTheSubject() {
        window.desktop().activate(second);
        settle();

        assertSame(second, WindowCommands.frameFor(CommandContext.of(window.ui.rootElement)));
    }

    // ── Enablement ──────────────────────────────────────────────────────────────────────────────

    /**
     * <b>Restore and Maximize are never both enabled, and never both disabled.</b>
     *
     * <p>Two rows rather than one that changes its label, which is Win32's system menu exactly. A row
     * whose text changes is a row that is never in the same place twice, and muscle memory is most of
     * why a system menu is worth having.</p>
     */
    @Test
    public void restoreAndMaximizeAreOppositeStates() {
        assertTrue("a restored window cannot be maximised", enabled(WindowCommands.MAXIMIZE, first));
        assertFalse("a restored window offered Restore", enabled(WindowCommands.RESTORE, first));

        first.maximize();
        settle();

        assertFalse("a maximised window offered Maximize", enabled(WindowCommands.MAXIMIZE, first));
        assertTrue("a maximised window cannot be restored", enabled(WindowCommands.RESTORE, first));
    }

    /**
     * <b>A tool window is never offered Maximize.</b>
     *
     * <p>Not because it looks wrong: a tool window has no taskbar entry, so a maximised one could not be
     * un-maximised from anywhere the pointer can reach. The same reason its caption carries Dock and
     * Hide and no maximise glyph — this makes the menu agree with the chrome.</p>
     */
    @Test
    public void aToolWindowIsNotOfferedMaximize() {
        first.setToolWindow(true);
        settle();

        assertFalse("a tool window was offered Maximize", enabled(WindowCommands.MAXIMIZE, first));
        assertTrue("a tool window lost Close as well", enabled(WindowCommands.CLOSE, first));
    }

    // ── The commands act ────────────────────────────────────────────────────────────────────────

    /**
     * <b>Minimize from a background window's taskbar entry hides THAT window.</b>
     *
     * <p>The whole surface in one assertion: an entry resolves to its own window, and the command acts
     * on what it resolved. Asserted with the other window active, because with only one window every
     * resolution answers the same frame and the test would pass against a build that ignored the
     * context entirely.</p>
     */
    @Test
    public void minimizeFromABackgroundEntryHidesThatWindow() {
        window.desktop().activate(second);
        settle();
        Button entry = window.desktop().taskbar().entryFor(first);

        run(WindowCommands.MINIMIZE, entry);
        settle();

        assertEquals("the background window was not minimised", WindowState.HIDDEN, first.state());
        assertEquals("the active window was minimised instead", WindowState.VISIBLE, second.state());
    }

    // ── The menu ────────────────────────────────────────────────────────────────────────────────

    /**
     * <b>All four rows are contributed to one menu id.</b>
     *
     * <p>Three routes render {@link MenuId#WINDOW_SYSTEM}: {@code Alt+Space}, a title-bar right-click and
     * a taskbar right-click. Three separate menus would be three lists to keep in step, and the one that
     * is edited least is the one somebody reaches for when the others are unavailable.</p>
     */
    @Test
    public void theSystemMenuHasItsRows() {
        List<String> ids = new java.util.ArrayList<>();
        CommandContext context = CommandContext.of(first);
        for (MenuSection section : registry().sections(MenuId.WINDOW_SYSTEM, context)) {
            for (MenuEntry entry : section.entries()) {
                if (entry instanceof MenuEntry.Item item) ids.add(item.command().getId());
            }
        }

        assertTrue("Restore is missing", ids.contains(WindowCommands.RESTORE));
        assertTrue("Minimize is missing", ids.contains(WindowCommands.MINIMIZE));
        assertTrue("Maximize is missing", ids.contains(WindowCommands.MAXIMIZE));
        assertTrue("Close is missing", ids.contains(WindowCommands.CLOSE));
        // A ROW THAT REOPENS THE MENU IT IS IN is a loop with a label on it, and Win32's system menu has
        // no such entry either. The command exists so the gesture is rebindable, not so it can be
        // clicked from inside its own result.
        assertFalse("the menu offers a row that reopens itself",
                ids.contains(WindowCommands.SYSTEM_MENU));
    }

    /**
     * <b>Close has no accelerator, and that is asserted rather than assumed.</b>
     *
     * <p>{@code Ctrl+W} closes an editor tab: frequent, cheap, undoable. Closing a window is rare and
     * takes its content with it, so a chord one keystroke away is how somebody loses a window they meant
     * to lose a tab from. Pinned here because "we decided not to bind it" is exactly the kind of decision
     * that gets undone by someone adding a binding for symmetry.</p>
     */
    @Test
    public void closeHasNoAccelerator() {
        Command close = registry().get(WindowCommands.CLOSE);
        assertNotNull(close);
        assertTrue("window.close was given a keyboard chord", close.bindings().isEmpty());

        Command systemMenu = registry().get(WindowCommands.SYSTEM_MENU);
        assertNotNull(systemMenu);
        assertFalse("the system menu lost its chord", systemMenu.bindings().isEmpty());
    }

    /**
     * <b>Alt+Space opens the menu, and it opens on the window it was pressed in.</b>
     *
     * <p>The keyboard route has no pointer to anchor to, so it is the one that could silently open a
     * menu about the wrong window. Driven through the command rather than through a synthesised
     * keystroke: the chord's resolution is the keymap's business and is tested there, while what is new
     * here is that the command finds a frame and puts a menu on the tree.</p>
     */
    @Test
    public void theSystemMenuOpensOnTheResolvedWindow() {
        Button inside = new Button("inside");
        first.content().addChild(inside);
        settle();
        window.desktop().activate(second);
        settle();

        run(WindowCommands.SYSTEM_MENU, inside);
        settle();

        assertTrue("Alt+Space from inside a background window opened nothing",
                menuIsOpen());
        SystemMenu.discardFor(first);
        settle();
        assertFalse("the menu outlived its discard", menuIsOpen());
    }

    /** Whether any {@code Menu} is currently in the tree. */
    private boolean menuIsOpen() {
        return !window.ui.rootElement.querySelectorAll("menu").isEmpty();
    }

    /**
     * <b>A right-click on a title bar opens it too.</b>
     *
     * <p>Through {@code consumeMouseEvent} at a point, not {@code sendInputEvent}: the listener is on
     * mouse-DOWN and depends on which button, so a fixture that dispatches straight at the element skips
     * the button resolution the route is written against.</p>
     */
    @Test
    public void rightClickingATitleBarOpensTheSystemMenu() {
        assertFalse(menuIsOpen());

        var box = first.titleBar().getRuntimeCache();
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((box.getX() + box.getWidth() / 2f) * 2f),
                Math.round((box.getY() + box.getHeight() / 2f) * 2f),
                0, 0, CgMouseCodes.RIGHT_BUTTON, true, 0f, 0L));
        settle();

        assertTrue("right-clicking a title bar opened no menu", menuIsOpen());
    }
}
