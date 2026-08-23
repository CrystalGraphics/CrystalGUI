package com.crystalgui.ui.elements.desktop;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;

import javax.annotation.Nullable;

/**
 * The desktop's own commands — the ones that are about the <em>set</em> of windows rather than any one
 * of them.
 *
 * <h3>The chord, resolved against the live keymap rather than assumed</h3>
 *
 * <p>{@code plan_windowing.md} left this open deliberately: {@code Ctrl+Tab} is the OS-conventional pick
 * and is "plausibly claimed" by a recent-files list, so the plan refused to name it in a document and
 * deferred to whatever the keymap actually held when the switcher was built. It holds nothing on
 * {@code Tab} at all — the dock's Next/Previous Tab are on {@code Mod+PageDown}/{@code Mod+PageUp}, and
 * no other bundle binds a Tab stroke — so the conventional pick is free and is what ships.</p>
 *
 * <p><b>{@code Alt+Tab} is not available and never will be.</b> It is the host operating system's, and a
 * Minecraft client never sees it. That is the whole reason a desktop metaphor inside an application needs
 * a chord of its own rather than inheriting the one everybody already knows.</p>
 *
 * <h3>Two commands, not one command reading Shift</h3>
 *
 * <p>Forward and backward are separate ids, as they are in GNOME ({@code switch-windows} /
 * {@code switch-windows-backward}) and in every keymap file worth editing. One command that read the
 * Shift bit off the live modifier state would be unrebindable in the direction that matters: a user who
 * moved the switcher to a chord that already contains Shift would lose the reverse gesture entirely, and
 * nothing would report it.</p>
 */
public final class DesktopCommands {

    public static final String SWITCH_WINDOW = "desktop.switchWindow";
    public static final String SWITCH_WINDOW_BACK = "desktop.switchWindowBack";
    public static final String SHOW_DESKTOP = "desktop.showDesktop";

    private static boolean registered;

    private DesktopCommands() {
    }

    /**
     * Idempotent, and called from {@link Desktop}'s constructor.
     *
     * <p>The widget that owns the commands registers them, which is the pattern {@code DockArea} follows
     * for {@code DockCommands}. A command registered from anywhere else is a command that exists only
     * once something unrelated has been constructed — which is how one ends up registered but
     * unreachable, or bound but pointing at nothing.</p>
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        CommandRegistry registry = CommandRegistry.global();

        registry.register(Command.of(SWITCH_WINDOW, "Switch Window")
                .binding("Mod+Tab")
                .run(context -> cycle(context, true))
                .enabledWhen(DesktopCommands::hasSomethingToSwitchTo));

        registry.register(Command.of(SWITCH_WINDOW_BACK, "Switch Window (Back)")
                .binding("Mod+Shift+Tab")
                .run(context -> cycle(context, false))
                .enabledWhen(DesktopCommands::hasSomethingToSwitchTo));

        // NO CHORD -- W13c. The plan says this "earns its keybind exactly when floats and torn-out
        // editors multiply", and Win+D is unavailable for the reason Alt+Tab is: Super belongs to the
        // host, and a Minecraft client never sees it.
        //
        // BUT NO CHORD IS NOT NO AFFORDANCE. It shipped reachable only from the command palette --
        // registered, enabled, working, and findable by nobody, which was reported as the feature not
        // existing. The taskbar's own context menu is where Windows puts it and where somebody looking
        // for it would look.
        registry.register(Command.of(SHOW_DESKTOP, "Show Desktop")
                .menu(MenuId.TASKBAR_CONTEXT, "1_desktop", 10)
                .run(context -> {
                    Desktop desktop = desktopFor(context);
                    if (desktop != null) desktop.toggleShowDesktop();
                })
                .toggledWhen(context -> {
                    Desktop desktop = desktopFor(context);
                    return desktop != null && desktop.isShowingDesktop();
                })
                .enabledWhen(context -> {
                    Desktop desktop = desktopFor(context);
                    // Nothing to show and nothing to put back is not a state worth offering: with no
                    // windows at all the desktop IS shown, and the row would toggle nothing.
                    return desktop != null
                            && (desktop.isShowingDesktop() || desktop.registry().size() > 0);
                }));
    }

    /** Testing seam — {@code CommandRegistry.resetForTesting()} drops the registrations, not this flag. */
    public static synchronized void resetForTesting() {
        registered = false;
    }

    private static void cycle(CommandContext context, boolean forward) {
        Desktop desktop = desktopFor(context);
        if (desktop == null) return;
        desktop.switcher().cycle(forward, forward ? SWITCH_WINDOW : SWITCH_WINDOW_BACK);
    }

    private static boolean hasSomethingToSwitchTo(CommandContext context) {
        Desktop desktop = desktopFor(context);
        return desktop != null && desktop.registry().size() > 1;
    }

    /**
     * The desktop the command was invoked against.
     *
     * <p>Resolved from the window rather than from the focused element's ancestors, because the focused
     * element is nearly always <em>inside a frame</em> and a frame's ancestor chain reaches the desktop
     * anyway — but a command can also be invoked from the palette with nothing focused at all, and there
     * is exactly one desktop per {@link UIWindow} either way.</p>
     *
     * <p><b>Never {@code UIWindow.desktop()}</b>, which builds one on first use: asking whether a command
     * is enabled must not be the thing that attaches a compositor to an application that has never opened
     * a window. {@code desktopIfPresent} is the non-building read.</p>
     */
    @Nullable
    private static Desktop desktopFor(CommandContext context) {
        UIElement element = context.source();
        if (element == null) return null;
        UIWindow window = element.getAttachedWindow();
        return window == null ? null : window.desktopIfPresent();
    }
}
