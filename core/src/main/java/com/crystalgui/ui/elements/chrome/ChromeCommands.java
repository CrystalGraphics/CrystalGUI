package com.crystalgui.ui.elements.chrome;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.UiDataKeys;

/**
 * The workbench's own commands — currently the one that opens the command palette.
 *
 * <h3>Registered explicitly, like every other command set here</h3>
 *
 * <p>Same rule as {@code DockCommands}, {@code GraphCommands} and {@code UndoCommands}: this engine does
 * not inject its own defaults, because a registry that quietly acquired commands nobody registered
 * surprises anything that enumerates it — and a command palette is precisely a thing that enumerates it.
 * {@code CrystalEditor.registerCommands} is what calls this.</p>
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
     * Registers the palette opener. Global — the window comes from the context.
     *
     * <p>Opening a palette is a window-level act: it promotes an element into the top layer and reads
     * the focused element. That is why this set captured a {@code UIWindow} and was the last one that
     * could not be registered once. {@link UiDataKeys#WINDOW} answers it from wherever the command was
     * invoked, which is strictly better than the captured version — with two windows open, the palette
     * now opens in the one you pressed the key in rather than in whichever was built first.</p>
     *
     * <p>{@code Mod+Shift+P} from VS Code, {@code Mod+Shift+A} from IntelliJ's Find Action — both,
     * because they cost one declaration each and the muscle memory people arrive with is split between
     * them. IntelliJ's other opener, double-Shift for Search Everywhere, is a <b>double-tap</b> rather
     * than a chord; {@code KeyChord} has no way to express it, and a timing-aware resolver is a keymap
     * feature rather than a palette one.</p>
     */
    public static void register() {
        CommandRegistry.global().contribute(ChromeCommands.class, registry ->
                registry.register(Command.of(SHOW_COMMANDS, "Show All Commands")
                        .binding("Mod+Shift+P", "Mod+Shift+A")
                        .run(context -> CommandPalette.open(context.data().get(UiDataKeys.WINDOW)))
                        .enabledWhen(context -> context.data().get(UiDataKeys.WINDOW) != null)));
    }
}
