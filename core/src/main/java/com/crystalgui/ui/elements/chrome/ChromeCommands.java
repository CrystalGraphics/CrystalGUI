package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.keymap.Keymap;

/**
 * The workbench's own commands — currently the one that opens the command palette.
 *
 * <h3>Installed explicitly, like every other command set here</h3>
 *
 * <p>Same rule as {@code DockCommands}, {@code GraphCommands} and {@code UndoCommands}: this engine does
 * not inject its own defaults, because a registry that quietly acquired commands nobody registered
 * surprises anything that enumerates it — and a command palette is precisely a thing that enumerates it.</p>
 *
 * <h3>The palette lists itself, deliberately</h3>
 *
 * <p>{@code workbench.showCommands} is an ordinary registered command, so it appears in its own list. VS
 * Code's does too. Excluding it would need a special case in the enumeration, and the row is harmless —
 * running it from inside the palette simply reopens the palette.</p>
 */
public final class ChromeCommands {

    public static final String SHOW_COMMANDS = "workbench.showCommands";

    private ChromeCommands() {
    }

    /**
     * Needs the window, unlike the other command sets, because opening a palette is a window-level act —
     * it promotes an element into the top layer and reads the focused element. The others resolve their
     * target by walking up from {@code context.source()}; there is nothing to walk up to here.
     */
    public static void register(CommandRegistry registry, UIWindow window) {
        if (registry.contains(SHOW_COMMANDS)) return;
        registry.register(Command.of(SHOW_COMMANDS, "Show All Commands")
                .run(context -> CommandPalette.open(window)));
    }

    /**
     * {@code Mod+Shift+P} from VS Code, {@code Mod+Shift+A} from IntelliJ's Find Action.
     *
     * <p>Both, because they cost one line each and the muscle memory people arrive with is split between
     * them. IntelliJ's other opener — double-Shift for Search Everywhere — is a <b>double-tap</b> rather
     * than a chord, which {@code KeyChord} has no way to express; it would need a timing-aware resolver,
     * and that is a keymap feature rather than a palette one.</p>
     */
    public static void bindDefaults(Keymap keymap) {
        keymap.bind("Mod+Shift+P", SHOW_COMMANDS);
        keymap.bind("Mod+Shift+A", SHOW_COMMANDS);
    }

    public static void install(CommandRegistry registry, UIWindow window, UIElement root) {
        register(registry, window);
        bindDefaults(root.keymap());
    }

    public static void install(UIWindow window) {
        install(window.getCommands(), window, window.ui.rootElement);
    }
}
