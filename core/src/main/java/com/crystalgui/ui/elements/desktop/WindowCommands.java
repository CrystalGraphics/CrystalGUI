package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;

import javax.annotation.Nullable;

/**
 * What can be done to <em>one</em> window — CrystalOS <b>W13</b>.
 *
 * <h3>A command first, chrome second</h3>
 *
 * <p>The system menu — reached three ways, all rendering {@link MenuId#WINDOW_SYSTEM} — the keymap and
 * the palette are <b>renderers of one set of ids</b>, which is the only thing that keeps them from
 * drifting. Before this a window operation was reachable only by pointing at a specific pixel: the ✕
 * called {@code requestClose}, the taskbar's middle-click called it separately, and a keyboard user
 * could reach neither.</p>
 *
 * <p><b>The caption's buttons are deliberately NOT routed through these.</b> They end in the same
 * {@code WindowFrame} methods the commands do, so they cannot diverge in behaviour — and one of them
 * genuinely does not map onto one id: the maximise button is a <em>toggle</em> whose glyph swaps, while
 * the menu wants Restore and Maximize as two rows (see below). Making a button look up a command to
 * invoke a method it could call directly would add a registry hop to every caption click and change
 * nothing observable. The ids are the vocabulary; the buttons are the direct affordance for the same
 * verbs.</p>
 *
 * <p>Registered in {@link CommandRegistry#global()} because they are facts about the application rather
 * than about any window: what varies per invocation is <em>which</em> window, and that is one
 * {@link com.crystalgui.core.data.DataContext} question — see {@link WindowFrame#WINDOW_FRAME}.</p>
 *
 * <h3>Restore and Maximize are two rows, not one that changes its label</h3>
 *
 * <p>Win32's system menu has both, each greyed in the state where it does not apply, and that is worth
 * copying rather than collapsing. A row whose <em>label</em> changes is a row that is never in the same
 * place twice — the failure the menu renderers already refuse to make by dimming rather than hiding —
 * and muscle memory is the whole reason a system menu exists.</p>
 *
 * <h3>Close has no accelerator, deliberately</h3>
 *
 * <p>{@code Ctrl+W} closes an editor <em>tab</em>: frequent, cheap, undoable. Closing a window is rare
 * and takes its content with it, so putting it one keystroke away is how somebody loses a window they
 * meant to lose a tab from. Three routes reach it — this menu, the ✕, and middle-click on its taskbar
 * entry — and none of them is a slip. The command still exists and is still what every route calls;
 * it simply has no binding, which the menu renders as an empty accelerator column rather than a lie.</p>
 *
 * <h3>What is deliberately absent</h3>
 *
 * <p>{@code window.pin} is W14's and {@code desktop.taskManager} is W15's. <b>A command lands with its
 * feature, never ahead of it</b>: the registry carries {@code enabled} and both menu renderers dim rather
 * than hide, so registering one whose feature does not exist puts a permanently grey row in every menu
 * that shows it. Grey means "not right now"; a row that can never be anything else misdescribes the
 * application. {@code window.fullscreen} arrived with W13b and {@code window.move}/{@code window.size}
 * with W13c, each in the slot the numbering had been leaving for it.</p>
 */
public final class WindowCommands {

    public static final String CLOSE = "window.close";
    public static final String MINIMIZE = "window.minimize";
    public static final String MAXIMIZE = "window.maximize";
    public static final String RESTORE = "window.restore";
    public static final String FULLSCREEN = "window.fullscreen";
    public static final String MOVE = "window.move";
    public static final String SIZE = "window.size";
    public static final String PIN = "window.pin";
    public static final String SYSTEM_MENU = "window.systemMenu";

    /**
     * The two groups, and the numeric prefixes are load-bearing.
     *
     * <p>{@code CommandRegistry.sections} sorts by {@code (group, order)} with the group compared as a
     * <b>string</b>, so a group named {@code "close"} sorts before one named {@code "state"} and the menu
     * came out Close-first with the separator above the state rows — the exact inversion of Win32's. The
     * prefix convention is what every other menu in the application already uses
     * ({@code 1_appearance}, {@code 2_clipboard}); it looked like decoration until this got it wrong.</p>
     */
    private static final String GROUP_STATE = "1_state";
    private static final String GROUP_CLOSE = "2_close";

    private static boolean registered;

    private WindowCommands() {
    }

    /**
     * Idempotent, and called from {@link Desktop}'s constructor.
     *
     * <p>The widget that owns the commands registers them — {@code DesktopCommands} and
     * {@code DockCommands} both do it this way. A command registered from anywhere else exists only once
     * something unrelated has been constructed, which is how one ends up registered but unreachable.</p>
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CommandRegistry registry = CommandRegistry.global();

        // ORDERED AS WIN32'S: Restore, Move, Size, Minimize, Maximize, Full Screen, separator, Close.
        // The order numbers are spaced by ten rather than consecutive, which is what let W13b's Full
        // Screen and W13c's Move and Size land in their own slots without renumbering anything already
        // here -- and what will let W14's Pin do the same.
        registry.register(Command.of(RESTORE, "Restore")
                .menu(MenuId.WINDOW_SYSTEM, GROUP_STATE, 10)
                .run(context -> withFrame(context, WindowFrame::restore))
                .enabledWhen(context -> {
                    WindowFrame frame = frameFor(context);
                    return frame != null && frame.isMaximized();
                }));

        // MOVE AND SIZE, in Win32's own slots between Restore and Minimize -- W13c. No chord: they are
        // reached from the menu, which is the only place they make sense. A window whose title bar has
        // ended up off the work area is exactly what they are for, and that window cannot be right-
        // clicked either -- so Alt+- is the route that matters and it already exists.
        registry.register(Command.of(MOVE, "Move")
                .menu(MenuId.WINDOW_SYSTEM, GROUP_STATE, 20)
                .run(context -> beginKeyboard(context, WindowKeyboardMove.Mode.MOVE))
                .enabledWhen(context -> canNudge(frameFor(context))));

        registry.register(Command.of(SIZE, "Size")
                .menu(MenuId.WINDOW_SYSTEM, GROUP_STATE, 30)
                .run(context -> beginKeyboard(context, WindowKeyboardMove.Mode.SIZE))
                .enabledWhen(context -> canNudge(frameFor(context))));

        registry.register(Command.of(MINIMIZE, "Minimize")
                .menu(MenuId.WINDOW_SYSTEM, GROUP_STATE, 40)
                .run(context -> withFrame(context, WindowFrame::minimize))
                .enabledWhen(context -> {
                    WindowFrame frame = frameFor(context);
                    return frame != null && frame.state() == WindowState.VISIBLE;
                }));

        registry.register(Command.of(MAXIMIZE, "Maximize")
                .menu(MenuId.WINDOW_SYSTEM, GROUP_STATE, 50)
                .run(context -> withFrame(context, WindowFrame::maximize))
                .enabledWhen(context -> {
                    WindowFrame frame = frameFor(context);
                    // A TOOL WINDOW HAS NO MAXIMISE, and the reason is not that it looks wrong: it has no
                    // taskbar entry, so a maximised one could not be un-maximised from anywhere the
                    // pointer can reach. @see WindowFrame#isToolWindow()
                    return frame != null && !frame.isMaximized() && !frame.isToolWindow()
                            && frame.state() == WindowState.VISIBLE;
                }));

        // PIN -- W14. Always-on-top on the desktop, and the thing Win32's WS_EX_TOPMOST has no way to
        // express because it has no desktop to close: a pinned window keeps painting on the HUD over the
        // running game after the screen is put away. Discord's and Steam's overlays are the precedent.
        //
        // NO CHORD, deliberately. Every unclaimed function key here is worth more to something a player
        // reaches for mid-game, and pinning is a thing done once from the desktop and then lived with.
        // The system menu is where it belongs, alongside the other state toggles.
        registry.register(Command.of(PIN, "Pin")
                .menu(MenuId.WINDOW_SYSTEM, GROUP_STATE, 70)
                .run(context -> withFrame(context, frame -> frame.setPinned(!frame.isPinned())))
                .toggledWhen(context -> {
                    WindowFrame frame = frameFor(context);
                    return frame != null && frame.isPinned();
                })
                .enabledWhen(context -> {
                    WindowFrame frame = frameFor(context);
                    // A DESKTOP CITIZEN ONLY, and the refusal is the honest half of "pin implies
                    // top-level". An OWNED window -- a floating tool window, a window's own modal -- is
                    // parented into its owner's overlay slot rather than into the window layer, so it
                    // hides with its owner by definition. That contract and a pin's ("survives the whole
                    // desktop going away") cannot both hold.
                    //
                    // The plan's answer is to PROMOTE such a window to top-level first, IntelliJ's
                    // Window mode, and that is not reachable from here: promoting a tool window runs
                    // through ToolWindowManager.setType, which DESTROYS the frame and builds a new one,
                    // so the pin cannot be carried on the frame at all -- it would have to live on the
                    // placement record beside the mode. Left undone rather than half-done; a WINDOWED
                    // tool window is already top-level and pins like any other window, so the route
                    // exists, it just takes two steps. @see plan_windowing.md W14
                    return frame != null && frame.state() == WindowState.VISIBLE
                            && frame.desktop() != null
                            && frame.desktop().registry().windows().contains(frame);
                }));

        // FULLSCREEN IS MAXIMISE'S SIBLING and sits with it in the state group -- W13b. F11 is
        // unclaimed here and is what every browser and every editor uses; unlike Alt+Space it is not
        // the host's, because a Minecraft client does nothing with it.
        registry.register(Command.of(FULLSCREEN, "Full Screen")
                .binding("F11")
                .menu(MenuId.WINDOW_SYSTEM, GROUP_STATE, 60)
                .run(context -> withFrame(context, WindowFrame::toggleFullscreen))
                .toggledWhen(context -> {
                    WindowFrame frame = frameFor(context);
                    return frame != null && frame.isFullscreen();
                })
                .enabledWhen(context -> {
                    WindowFrame frame = frameFor(context);
                    // A TOOL WINDOW IS REFUSED for the reason Maximize is: it has no taskbar entry, and a
                    // fullscreen one would additionally have hidden the strip that is not its way back.
                    return frame != null && !frame.isToolWindow()
                            && frame.state() == WindowState.VISIBLE;
                }));

        registry.register(Command.of(CLOSE, "Close")
                .menu(MenuId.WINDOW_SYSTEM, GROUP_CLOSE, 10)
                .run(context -> withFrame(context, WindowFrame::requestClose))
                .enabledWhen(context -> frameFor(context) != null));

        // NOT IN THE MENU IT OPENS. A row that reopens the menu it is in is a loop with a label on it;
        // Win32's system menu has no such entry either. It exists so the gesture is REBINDABLE and so the
        // palette can reach it, which is the whole argument for every window operation being a command.
        // ALT+MINUS, NOT ALT+SPACE -- and this is a port rather than a workaround.
        //
        // Win32 has two system-menu chords, and the distinction is exactly ours: Alt+Space opens the
        // system menu of a TOP-LEVEL window, and Alt+Hyphen opens the system menu of an MDI CHILD --
        // a window living inside another application's frame. CrystalOS windows are MDI children in the
        // most literal sense available: they are elements inside one UIWindow. So Alt+- is the chord
        // that names what these actually are.
        //
        // It is also the only one that can work. Alt+Space belongs to the host: Windows opens the real
        // window's system menu with it, and PowerToys Run takes it outright on a great many machines --
        // reported here as "Alt+Space does nothing", with a screenshot of PowerToys. Same rule the
        // switcher already records for Alt+Tab, which is the host's and always will be, and the reason
        // a desktop metaphor inside an application cannot simply inherit the chords everybody knows.
        registry.register(Command.of(SYSTEM_MENU, "Window Menu")
                .binding("Alt+Minus")
                .run(WindowCommands::openSystemMenu)
                .enabledWhen(context -> frameFor(context) != null));
    }

    /** Testing seam — {@code CommandRegistry.resetForTesting()} drops the registrations, not this flag. */
    public static synchronized void resetForTesting() {
        registered = false;
    }

    /**
     * The window this invocation is about, or null.
     *
     * <p>One lookup for every renderer: a caption button, a taskbar entry's context menu, {@code Alt+Space}
     * and the palette all arrive here with a different {@code source} element and the walk sorts it out.
     * A taskbar entry is <b>not</b> inside the window it stands for, which is why it answers the key
     * itself rather than relying on its ancestors — see {@code Taskbar}.</p>
     */
    @Nullable
    public static WindowFrame frameFor(CommandContext context) {
        return context.data().get(WindowFrame.WINDOW_FRAME);
    }

    /**
     * Whether a keyboard nudge would mean anything for {@code frame}.
     *
     * <p>Refused for a maximised or fullscreen window: both are geometries the compositor owns, and
     * nudging one would leave a window that claims to be maximised and is not. Win32 greys its own Move
     * and Size in exactly that state.</p>
     */
    private static boolean canNudge(@Nullable WindowFrame frame) {
        return frame != null && frame.state() == WindowState.VISIBLE
                && !frame.isMaximized() && !frame.isFullscreen();
    }

    private static void beginKeyboard(CommandContext context, WindowKeyboardMove.Mode mode) {
        WindowFrame frame = frameFor(context);
        if (frame == null) return;
        Desktop desktop = frame.desktop();
        if (desktop == null) return;
        // ACTIVATED FIRST. The mode takes keys ahead of dispatch, so it works on a background window
        // perfectly well -- and a window being nudged while another one is lit is exactly the sort of
        // thing that reads as the wrong window moving.
        desktop.activate(frame);
        desktop.keyboardMove().begin(frame, mode);
    }

    private static void withFrame(CommandContext context, java.util.function.Consumer<WindowFrame> action) {
        WindowFrame frame = frameFor(context);
        if (frame != null) action.accept(frame);
    }

    /**
     * Opens the system menu at the window's top-left, under its caption — where Win32 puts it.
     *
     * <p>Anchored to the <b>title bar</b> rather than to the pointer, because this is the keyboard route
     * and there is no pointer to anchor to. The right-click routes go through
     * {@code ContextMenu.attach}, which anchors at the press.</p>
     */
    private static void openSystemMenu(CommandContext context) {
        WindowFrame frame = frameFor(context);
        if (frame == null) return;
        SystemMenu.showFor(frame);
    }
}
