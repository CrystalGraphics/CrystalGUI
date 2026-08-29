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
import static org.junit.Assert.assertNull;
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

    /**
     * <b>...and it must not also start a window move.</b>
     *
     * <p>Reported from the harness: right-click a caption once and the window follows the cursor
     * indefinitely with nothing held down. A drag ends when <em>the button that started it</em> is
     * released and {@code startDrag} defaults to the left one — so a right-press began a move registered
     * against a button that was never coming up, and there is no way out of that state short of
     * left-clicking somewhere.</p>
     *
     * <p>The defect is <b>older than the gesture that exposed it</b>: the caption's move listener never
     * checked a button, and nothing right-clicked a title bar until W13a put the system menu there.
     * Asserted on the drag controller rather than on the window's position, because a press alone moves
     * nothing — the window only runs away once the pointer does, and by then the test has stopped
     * looking.</p>
     */
    @Test
    public void rightClickingATitleBarDoesNotStartAMove() {
        rightClickTitleBar();

        assertFalse("a right-click on the caption started a window move that nothing can end",
                window.getInputHandler().getDragController().isDragging());
    }

    /**
     * <b>A LEFT press on the caption still starts one.</b>
     *
     * <p>The counter-assertion, and not a formality: a guard written as "ignore anything that is not the
     * left button" and a guard written as "ignore everything" both make the test above pass, and the
     * second one makes windows undraggable.</p>
     */
    @Test
    public void aLeftPressOnATitleBarStillStartsAMove() {
        var box = first.titleBar().getRuntimeCache();
        pressTitleBar(box, CgMouseCodes.LEFT_BUTTON);

        assertTrue("a left press on the caption no longer moves the window",
                window.getInputHandler().getDragController().isDragging());
    }

    /**
     * <b>Win32's row order: the state rows, a separator, then Close.</b>
     *
     * <p>Sections sort by group name as a <em>string</em>, so the first spelling put {@code "close"}
     * before {@code "state"} and the menu came out inverted — Close at the top with the separator under
     * it. Every other menu in the application already uses numeric prefixes for exactly this, which
     * reads as decoration right up until it does not.</p>
     */
    @Test
    public void closeIsTheLastRow() {
        List<String> ids = new java.util.ArrayList<>();
        CommandContext context = CommandContext.of(first);
        for (MenuSection section : registry().sections(MenuId.WINDOW_SYSTEM, context)) {
            for (MenuEntry entry : section.entries()) {
                if (entry instanceof MenuEntry.Item item) ids.add(item.command().getId());
            }
        }

        assertEquals("Close is not the last row", WindowCommands.CLOSE, ids.get(ids.size() - 1));
        assertEquals("Restore is not the first row", WindowCommands.RESTORE, ids.get(0));
    }

    // ── The taskbar's jump list ─────────────────────────────────────────────────────────────────

    /**
     * <b>A taskbar entry's menu is CENTRED over its entry and sits above the strip.</b>
     *
     * <p>{@code ContextMenu.attach} anchors at the pointer, which for a strip along the bottom of the
     * screen puts the menu at the very edge, left-aligned from wherever the press landed and drifting
     * further from its entry the wider it gets. Windows' jump list is centred over its button — the same
     * rule the hover previews above these entries already follow, because an anchor that is a
     * <em>label</em> for the thing beneath it wants centring rather than left alignment.</p>
     */
    @Test
    public void aJumpListIsCentredAboveItsEntry() {
        Button entry = window.desktop().taskbar().entryFor(first);
        assertNotNull(entry);

        SystemMenu.showJumpList(first, entry);
        settle();

        UIElement menu = openMenu();
        assertNotNull("the jump list did not open", menu);
        var on = entry.getRuntimeCache();
        var box = menu.getRuntimeCache();
        assertEquals("the jump list is not centred on its entry",
                on.getX() + on.getWidth() / 2f, box.getX() + box.getWidth() / 2f, 1.5f);
        assertTrue("the jump list is not above the strip", box.getY() + box.getHeight() <= on.getY() + 1f);
    }

    /**
     * <b>...and it wears the hover preview's surface rather than a bare menu's.</b>
     *
     * <p>From the strip's point of view a jump list and a preview are the same object — a panel belonging
     * to one entry, floating above it — so drawn as a plain menu it reads as a context menu that happens
     * to be nearby. Asserted on the class, since what the class buys is a stylesheet's business.</p>
     */
    @Test
    public void aJumpListWearsThePreviewSurface() {
        Button entry = window.desktop().taskbar().entryFor(first);
        SystemMenu.showJumpList(first, entry);
        settle();

        UIElement menu = openMenu();
        assertNotNull(menu);
        assertTrue("the jump list is drawn as a bare menu",
                menu.hasClass(SystemMenu.JUMP_LIST_CLASS));

        // ...and the keyboard route is NOT a jump list: it hangs under a caption, where the preview
        // surface would be wrong.
        SystemMenu.discardFor(first);
        settle();
        SystemMenu.showFor(first);
        settle();
        assertFalse("the caption menu took the taskbar panel's surface",
                openMenu().hasClass(SystemMenu.JUMP_LIST_CLASS));
    }

    /**
     * <b>An application can contribute its own rows, per window.</b>
     *
     * <p>Windows' jump list carries the app's own Tasks and Recent sections above the window-management
     * rows, and this is the mechanism: {@code MenuContributor} computes rows <em>at open time</em>, so it
     * can read the invoking context and answer for whichever window the menu was opened on. A contributed
     * {@code Command} need not be registered anywhere, which is what lets "reopen this specific file"
     * exist without one palette entry per file.</p>
     *
     * <p>Asserted with two windows and a contributor that names the window it was asked about, because a
     * contributor that ignored the context entirely would produce identical rows for both and a
     * single-window fixture could not tell.</p>
     */
    @Test
    public void anApplicationCanContributeRowsPerWindow() {
        registry().contributeMenu(MenuId.WINDOW_SYSTEM, (menu, context) -> {
            WindowFrame frame = WindowCommands.frameFor(context);
            if (frame == null) return List.of();
            return List.of(MenuEntry.Item.of(
                    Command.of("test.jump." + frame.getTitle(), "Reopen " + frame.getTitle())
                            .run(() -> { }),
                    "0_app", 10));
        });

        assertTrue("the contributor's row did not reach the first window's menu",
                rowLabels(first).contains("Reopen First"));
        assertTrue("the contributor answered about the wrong window",
                rowLabels(second).contains("Reopen Second"));
        assertFalse("a contributed row leaked between windows",
                rowLabels(first).contains("Reopen Second"));

        // ABOVE the window rows, because "0_app" sorts before "1_state" -- the app's own verbs first, as
        // Windows puts Tasks and Recent above Pin and Close.
        assertEquals("a contributed section did not come first",
                "Reopen First", rowLabels(first).get(0));
    }

    /**
     * <b>A preview and a jump list are alternatives — opening the menu cancels the preview.</b>
     *
     * <p>Windows shows one or the other. Both at once is not merely busy: they occupy the same space
     * above the same entry, so the panel sits over the menu that was supposed to have replaced it.</p>
     *
     * <p>The first attempt relied on the preview's own mouse-down dismissal, which the jump list's
     * handler silenced: {@code stopPropagation()} halts the remaining listeners <b>on the same element
     * and phase</b>, and the jump list's was attached first. Suppression is also not a single dismissal
     * — the pointer never leaves the entry, so the hover stays live and the delay would elapse again
     * under the open menu.</p>
     */
    @Test
    public void openingAJumpListCancelsThePreviewAndKeepsItAway() {
        Taskbar taskbar = window.desktop().taskbar();
        Button entry = taskbar.entryFor(first);

        SystemMenu.showJumpList(first, entry);
        settle();

        // THE FLAG, not "no panel appeared". TaskbarPreviews advances on System.nanoTime(), so a settle
        // loop cannot reach its 500ms delay at all -- a test that hovers and waits sixty frames sees no
        // preview whether or not anything is suppressing it, and passes against a build that suppresses
        // nothing. It did: extracting the shared open sequence briefly moved the suppression BEFORE the
        // discard that lifts it, and this test was green throughout.
        assertTrue("the jump list did not silence the hover previews",
                taskbar.previewsSuppressedForTesting());
        assertNull("the hover preview survived the menu that replaced it", taskbar.previewedWindow());

        // A hover while the menu is open must not re-arm it.
        hoverEntry(entry);
        settle();
        assertTrue("hovering the entry re-armed previews under an open jump list",
                taskbar.previewsSuppressedForTesting());

        SystemMenu.discardFor(first);
        settle();
        // ...and previews are not disabled for good once the menu has gone.
        assertFalse("closing the jump list left the strip unable to preview",
                taskbar.previewsSuppressedForTesting());
    }

    /**
     * <b>Right-clicking an entry opens its menu and does NOT also activate the window.</b>
     *
     * <p>{@code Button}'s activation checked no button at all, so a right-click pressed the button as
     * well — and on a taskbar entry "pressed" means activate-or-minimise. The menu therefore appeared
     * over a window that had just minimised itself out from under it. No toolkit activates a button on a
     * right-click; nothing here noticed until something put a context menu on one.</p>
     *
     * <p>Driven through the real up/down pair, because the activation is on the UP and a fixture that
     * only presses cannot see it — which is exactly why the middle-click test's own counter-assertion
     * had to complete the click.</p>
     */
    /**
     * <b>A right-click on a taskbar entry opens its jump list.</b>
     *
     * <p>The obvious assertion, and it was missing: the entry's right-click was covered only by
     * {@link #rightClickingAnEntryDoesNotAlsoActivateTheWindow} below, which asserts what must NOT
     * happen. That passes just as well when the press opens nothing at all, so the whole route could
     * fall over without a red test — the counter-assertion needs a positive one beside it, the same
     * pairing the middle-click test already has.</p>
     *
     * <p>Through {@code consumeMouseEvent} at a point rather than {@code sendInputEvent}: the listener
     * is on mouse-DOWN and reads the button, so dispatching straight at the element skips the button
     * resolution the route is written against.</p>
     */
    @Test
    public void rightClickingAnEntryOpensItsJumpList() {
        assertFalse(menuIsOpen());
        Button entry = window.desktop().taskbar().entryFor(first);

        clickEntry(entry, CgMouseCodes.RIGHT_BUTTON);

        assertTrue("right-clicking a taskbar entry opened no menu", menuIsOpen());
    }

    /**
     * <b>...and it works on a MINIMISED window, which is the case the route exists for.</b>
     *
     * <p>{@code showJumpList} opened into {@code frame.getAttachedWindow()}, and hide is detach — so a
     * minimised window answered null and the method returned having done nothing. Every visible window
     * was fine, which is why the route read as correct and why the test above passes without this one:
     * reported as <em>"right click only works when the window is not minimized"</em>.</p>
     *
     * <p>The entry is the reliable end of the pair — the strip is up whenever any of this can be
     * clicked — so the menu opens into the ANCHOR's window.</p>
     */
    @Test
    public void rightClickingAMinimisedEntryStillOpensItsJumpList() {
        first.minimize();
        settle();
        assertEquals(WindowState.HIDDEN, first.state());
        assertNull("precondition: a minimised window is out of the tree", first.getAttachedWindow());
        assertFalse(menuIsOpen());

        clickEntry(window.desktop().taskbar().entryFor(first), CgMouseCodes.RIGHT_BUTTON);

        assertTrue("a minimised window's entry opened no menu — the one case the jump list is for",
                menuIsOpen());
    }

    @Test
    public void rightClickingAnEntryDoesNotAlsoActivateTheWindow() {
        window.desktop().activate(second);
        settle();
        Button entry = window.desktop().taskbar().entryFor(first);

        clickEntry(entry, CgMouseCodes.RIGHT_BUTTON);

        assertSame("a right-click on an entry activated its window as well as opening the menu",
                second, window.desktop().activeWindow());
        assertEquals("a right-click minimised the window its menu is about",
                WindowState.VISIBLE, first.state());
    }

    /** <b>...and a left click still does.</b> The guard must name the left button, not reject all of them. */
    @Test
    public void leftClickingAnEntryStillActivatesTheWindow() {
        window.desktop().activate(second);
        settle();
        Button entry = window.desktop().taskbar().entryFor(first);

        clickEntry(entry, CgMouseCodes.LEFT_BUTTON);

        assertSame("a left click on an entry no longer activates its window",
                first, window.desktop().activeWindow());
    }

    /** A full press/release pair on {@code entry} — activation is on the UP. */
    private void clickEntry(Button entry, int button) {
        var box = entry.getRuntimeCache();
        int x = Math.round((box.getX() + box.getWidth() / 2f) * 2f);
        int y = Math.round((box.getY() + box.getHeight() / 2f) * 2f);
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                x, y, 0, 0, button, true, 0f, 0L));
        settle();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                x, y, 0, 0, button, false, 0f, 0L));
        settle();
    }

    private void hoverEntry(Button entry) {
        var box = entry.getRuntimeCache();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((box.getX() + box.getWidth() / 2f) * 2f),
                Math.round((box.getY() + box.getHeight() / 2f) * 2f),
                0, 0, 0, false, 0f, 0L));
        settle();
    }

    /** Every row label the system menu would show for {@code frame}, in order. */
    private List<String> rowLabels(WindowFrame frame) {
        List<String> labels = new java.util.ArrayList<>();
        CommandContext context = CommandContext.of(frame);
        for (MenuSection section : registry().sections(MenuId.WINDOW_SYSTEM, context)) {
            for (MenuEntry entry : section.entries()) {
                if (entry instanceof MenuEntry.Item item) labels.add(item.command().getLabel());
            }
        }
        return labels;
    }

    /** The open menu, or null. */
    private UIElement openMenu() {
        List<UIElement> menus = window.ui.rootElement.querySelectorAll("menu");
        return menus.isEmpty() ? null : menus.get(0);
    }

    private void rightClickTitleBar() {
        pressTitleBar(first.titleBar().getRuntimeCache(), CgMouseCodes.RIGHT_BUTTON);
    }

    private void pressTitleBar(com.crystalgui.ui.UIElement.RuntimeCache box, int button) {
        window.getInputHandler().beginFrame();
        window.getInputHandler().endFrame();
        window.getInputHandler().consumeMouseEvent(new CgSystemInput.Mouse.Event(
                Math.round((box.getX() + box.getWidth() / 2f) * 2f),
                Math.round((box.getY() + box.getHeight() / 2f) * 2f),
                0, 0, button, true, 0f, 0L));
        settle();
    }
}
