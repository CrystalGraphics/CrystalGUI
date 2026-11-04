package com.crystalgui.desktop;

import com.crystalgui.ui.data.UiDataKeys;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuEntry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.desktop.app.Application;
import com.crystalgui.desktop.app.ApplicationKind;
import com.crystalgui.desktop.taskbar.TaskbarCombine;
import com.crystalgui.desktop.taskbar.TaskbarDesigner;
import com.crystalgui.desktop.window.WindowCommands;
import com.crystalgui.desktop.window.WindowFrame;
import com.crystalgui.fs.client.Workspace;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.display.FrameStatsOverlay;

import javax.annotation.Nullable;

import java.util.List;

/**
 * The desktop's own commands — the ones that are about the <em>set</em> of windows rather than any one
 * of them.
 *
 * <h3>The chord, resolved against the live keymap rather than assumed</h3>
 *
 * <p>{@code plan/shell-windowing.md} left this open deliberately: {@code Ctrl+Tab} is the OS-conventional pick
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
    /** The prefix of the row that opens an application from its menu, followed by the application's id. */
    public static final String OPEN_APPLICATION = "desktop.openApplication";
    public static final String PIN_TO_TASKBAR = "desktop.pinToTaskbar";
    public static final String COMBINE_ALWAYS = "desktop.combineAlways";
    public static final String COMBINE_WHEN_FULL = "desktop.combineWhenFull";
    public static final String COMBINE_NEVER = "desktop.combineNever";
    public static final String TASKBAR_DESIGNER = "desktop.taskbarDesigner";
    public static final String FRAME_STATS = "desktop.frameStats";
    public static final String FRAME_STATS_DETAIL = "desktop.frameStatsDetail";

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
                .menu(MenuId.DESKTOP_CONTEXT, "2_windows", 10)
                .run(context -> cycle(context, true))
                .enabledWhen(DesktopCommands::hasSomethingToSwitchTo));

        registry.register(Command.of(SWITCH_WINDOW_BACK, "Switch Window (Back)")
                .binding("Mod+Shift+Tab")
                .run(context -> cycle(context, false))
                .enabledWhen(DesktopCommands::hasSomethingToSwitchTo));

        // THE APPLICATION ITSELF, by its icon and name, first in its menu and in a running window's: Windows'
        // jump list opens with it. A row made per menu, since its label is the application's.
        registry.contributeMenu(MenuId.APPLICATION, DesktopCommands::applicationRow);
        registry.contributeMenu(MenuId.TASKBAR_ENTRY, DesktopCommands::applicationRow);

        // ONE ROW THAT NAMES WHAT IT WILL DO, Pin or Unpin, on a running window's entry and in the
        // application's menu alike.
        registry.register(Command.of(PIN_TO_TASKBAR, "Pin to taskbar")
                .icon("crystalgui:general/action/pin")
                .menu(MenuId.APPLICATION, WindowCommands.GROUP_CLOSE, 5)
                .menu(MenuId.TASKBAR_ENTRY, WindowCommands.GROUP_CLOSE, 5)
                .run(context -> {
                    Desktop desktop = desktopFor(context);
                    ApplicationKind kind = kindFor(context);
                    if (desktop == null || kind == null) return;
                    if (desktop.pins().isPinned(kind)) desktop.pins().unpin(kind);
                    else desktop.pins().pin(kind);
                })
                // NO ROW for what a pin could not start again: a window without an installed, launchable
                // application, or one that opts out -- Windows offers only Close on those
                .shownWhen(DesktopCommands::canPin)
                .enabledWhen(DesktopCommands::canPin)
                .toggledWhen(context -> {
                    Desktop desktop = desktopFor(context);
                    ApplicationKind kind = kindFor(context);
                    return desktop != null && kind != null && desktop.pins().isPinned(kind);
                })
                .whenToggled("crystalgui:general/action/pin", "Unpin from taskbar"));

        // NO CHORD -- W13c. The plan says this "earns its keybind exactly when floats and torn-out
        // editors multiply", and Win+D is unavailable for the reason Alt+Tab is: Super belongs to the
        // host, and a Minecraft client never sees it.
        //
        // BUT NO CHORD IS NOT NO AFFORDANCE. It shipped reachable only from the command palette --
        // registered, enabled, working, and findable by nobody, which was reported as the feature not
        // existing. The taskbar's own context menu is where Windows puts it and where somebody looking
        // for it would look.
        // A DESIGN TOOL, and it ships behind a menu entry as well as a chord for the reason SHOW_DESKTOP
        // records below: a command reachable only from the palette is a command findable by nobody. The
        // strip's own context menu is where somebody tuning the strip would look.
        //
        // It TOGGLES on its key rather than opening a second one, because two designers would fight for
        // the same IMPORTANT slots on the same island and the loser's sliders would silently stop doing
        // anything -- which reads as the tool being broken rather than as there being two of it.
        // COMBINE TASKBAR BUTTONS, Windows' three choices, one of them ticked
        combineCommand(registry, COMBINE_ALWAYS, "Combine buttons: Always", TaskbarCombine.ALWAYS, 10);
        combineCommand(registry, COMBINE_WHEN_FULL, "Combine buttons: When taskbar is full",
                TaskbarCombine.WHEN_FULL, 20);
        combineCommand(registry, COMBINE_NEVER, "Combine buttons: Never", TaskbarCombine.NEVER, 30);

        registry.register(Command.of(TASKBAR_DESIGNER, "Design Taskbar…")
                .binding("Mod+Alt+T")
                .menu(MenuId.TASKBAR_CONTEXT, "9_design", 10)
                .run(context -> {
                    Desktop desktop = desktopFor(context);
                    if (desktop == null) return;
                    UIDocument window = desktop.document();
                    if (window == null) return;
                    WindowFrame existing = desktop.registry().byKey("taskbar-designer");
                    if (existing != null) existing.requestClose();
                    else TaskbarDesigner.open(window);
                })
                .enabledWhen(context -> desktopFor(context) != null));

        // THE DIAGNOSTIC BELONGS TO THE DESKTOP, NOT TO THE HARNESS. It shipped attachable only from a
        // harness scene, which is the one place a frame is already understood -- the frames worth
        // measuring are an editor's and a Minecraft client's, and neither could reach it. One command
        // here gives both, since every surface that has windows has a desktop.
        //
        // F7 AND F8 ARE THE HARNESS SCENE'S OWN KEYS, deliberately: a gesture learned while profiling a
        // scene should be the gesture in game. Both are free in every bundle -- F2, F5, F10, F11 and
        // Shift+F4 are the F-keys this engine binds.
        //
        // NO MENU ENTRY, against this file's own rule for SHOW_DESKTOP. That rule is about a feature
        // nobody can find; a frame readout is not a feature of the application but an instrument turned
        // on it, and the palette plus a documented key is where every other one lives (F3 in Minecraft,
        // F12 in a browser). A row in the window menu would be offering to profile the thing to somebody
        // who has no use for the answer.
        registry.register(Command.of(FRAME_STATS, "Toggle Frame Stats")
                .binding("F7")
                .run(context -> {
                    UIDocument window = documentFor(context);
                    if (window != null) FrameStatsOverlay.toggleOn(window);
                })
                .toggledWhen(context -> {
                    UIDocument window = documentFor(context);
                    if (window == null) return false;
                    FrameStatsOverlay hud = FrameStatsOverlay.of(window);
                    return hud != null && hud.isShowing();
                })
                .enabledWhen(context -> documentFor(context) != null));

        registry.register(Command.of(FRAME_STATS_DETAIL, "Frame Stats: Phase Breakdown")
                .binding("F8")
                .run(context -> {
                    UIDocument window = documentFor(context);
                    if (window != null) FrameStatsOverlay.expandOn(window);
                })
                .toggledWhen(context -> {
                    UIDocument window = documentFor(context);
                    if (window == null) return false;
                    FrameStatsOverlay hud = FrameStatsOverlay.of(window);
                    return hud != null && hud.isShowing() && hud.isDetailed();
                })
                .enabledWhen(context -> documentFor(context) != null));

        registry.register(Command.of(SHOW_DESKTOP, "Show Desktop")
                .menu(MenuId.TASKBAR_CONTEXT, "1_desktop", 10)
                // AND ON THE DESKTOP ITSELF, which is where Windows also puts it and the only
                // menu a desktop with every window minimised still has.
                .menu(MenuId.DESKTOP_CONTEXT, "1_desktop", 10)
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
        // One window is enough, as for Alt+Tab. A disabled chord falls through to Tab traversal.
        return desktop != null && desktop.registry().size() > 0;
    }

    /** The row that opens the application a menu is about, under its own name and icon; none without one. */
    private static List<MenuEntry> applicationRow(MenuId menu, CommandContext context) {
        Desktop desktop = desktopFor(context);
        ApplicationKind kind = kindFor(context);
        if (desktop == null || kind == null || desktop.applications().byId(kind.id()) == null) return List.of();
        return List.of(MenuEntry.Item.of(
                Command.of(OPEN_APPLICATION + "." + kind.id(), kind.displayName())
                        .icon(kind.icon())
                        .run(() -> open(desktop, kind)),
                WindowCommands.GROUP_CLOSE, 1));
    }

    private static void combineCommand(CommandRegistry registry, String id, String label, TaskbarCombine mode,
                                       int order) {
        registry.register(Command.of(id, label)
                .menu(MenuId.TASKBAR_CONTEXT, "5_combine", order)
                .run(context -> {
                    Desktop desktop = desktopFor(context);
                    if (desktop != null) desktop.desktopSettings().setTaskbarCombine(mode);
                })
                .enabledWhen(context -> desktopFor(context) != null)
                .toggledWhen(context -> {
                    Desktop desktop = desktopFor(context);
                    return desktop != null && desktop.desktopSettings().taskbarCombine() == mode;
                }));
    }

    /** Whether the application the command is about could be pinned: installed here, and pinnable. */
    private static boolean canPin(CommandContext context) {
        Desktop desktop = desktopFor(context);
        ApplicationKind kind = kindFor(context);
        if (desktop == null || kind == null) return false;
        ApplicationKind installed = desktop.applications().byId(kind.id());
        return installed != null && installed.isPinnable();
    }

    /** The application the command was invoked about, or null. @see UiDataKeys#APPLICATION_KIND */
    @Nullable
    private static ApplicationKind kindFor(CommandContext context) {
        return context.data().get(UiDataKeys.APPLICATION_KIND);
    }

    /**
     * Opens {@code kind} as a launcher does: a new instance, or the running one raised when it is single
     * instance.
     *
     * <pre>{@code
     * DesktopCommands.open(desktop, VfxLab.KIND);
     * }</pre>
     */
    public static void open(Desktop desktop, ApplicationKind kind) {
        // THE HOST'S WORKSPACE, or an application that needs a server is refused even with one connected
        Workspace workspace = desktop.workspace();
        Application running = desktop.applications().launch(kind, workspace);
        if (running != null) running.activate();
    }

    /**
     * The desktop the command was invoked against.
     *
     * <p>Resolved from the window rather than from the focused element's ancestors, because the focused
     * element is nearly always <em>inside a frame</em> and a frame's ancestor chain reaches the desktop
     * anyway — but a command can also be invoked from the palette with nothing focused at all, and there
     * is exactly one desktop per {@link UIDocument} either way.</p>
     *
     * <p><b>Never {@code Desktop.of}</b>, which builds one on first use: asking whether a command is
     * enabled must not be the thing that attaches a compositor to an application that has never opened
     * a window. {@link Desktop#ifPresent} is the non-building read.</p>
     */
    @Nullable
    private static Desktop desktopFor(CommandContext context) {
        UIElement element = UIElement.sourceOf(context);
        return element == null ? null : Desktop.ifPresent(element.document());
    }

    /**
     * The surface the command was invoked on.
     *
     * <p>Not {@link #desktopFor}: the frame readout measures a <em>document</em>, and asking for a
     * compositor first would make it unavailable on a surface that has a frame and no windows — which is
     * every Minecraft screen before the first one opens, and exactly when a frame cost is worth reading.</p>
     */
    @Nullable
    private static UIDocument documentFor(CommandContext context) {
        UIElement element = UIElement.sourceOf(context);
        return element == null ? null : element.document();
    }
}
