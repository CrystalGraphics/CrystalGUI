package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;

import javax.annotation.Nullable;

/**
 * What can be done to <em>one</em> window — CrystalOS <b>W13a</b>.
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
 * <p>{@code window.move}/{@code window.size} (keyboard Move/Size) are W13c and {@code window.fullscreen}
 * is W13b; {@code window.pin} is W14 and {@code desktop.taskManager} is W15. <b>A command lands with its
 * feature, never ahead of it</b>: the registry carries {@code enabled} and both menu renderers dim rather
 * than hide, so registering one whose feature does not exist puts a permanently grey row in every menu
 * that shows it. Grey means "not right now"; a row that can never be anything else misdescribes the
 * application.</p>
 */
public final class WindowCommands {

    public static final String CLOSE = "window.close";
    public static final String MINIMIZE = "window.minimize";
    public static final String MAXIMIZE = "window.maximize";
    public static final String RESTORE = "window.restore";
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

        // ORDERED AS WIN32'S: Restore, [Move, Size — W13c], Minimize, Maximize, separator, Close. The
        // gaps are left in the numbering rather than closed up, so W13c's two rows land in the right
        // places without renumbering the rest.
        registry.register(Command.of(RESTORE, "Restore")
                .menu(MenuId.WINDOW_SYSTEM, GROUP_STATE, 10)
                .run(context -> withFrame(context, WindowFrame::restore))
                .enabledWhen(context -> {
                    WindowFrame frame = frameFor(context);
                    return frame != null && frame.isMaximized();
                }));

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
