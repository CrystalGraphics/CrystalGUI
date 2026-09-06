package com.crystalgui.app.uibuilder;

import com.crystalgui.app.uibuilder.live.PickMode;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.dispose.Disposable;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;

/**
 * The UI builder's commands.
 *
 * <p>L3 registers the one that needs no document open: <b>Inspect Element</b>, which turns the next click
 * anywhere in the window into a selection. The editing commands — duplicate, delete, wrap, nudge — arrive
 * with the canvas that performs them (L4.10).</p>
 *
 * <p>An APPLICATION-wide binding, unlike the graph's bare letters: Ctrl+Shift+C is a chord, so it cannot
 * fire while somebody is typing, and live inspect is deliberately reachable with no builder open at all.</p>
 */
public final class BuilderCommands {

    private BuilderCommands() {
    }

    public static final String INSPECT_ELEMENT = "uibuilder.inspectElement";

    /** Registers them, and hands back the way to withdraw them. */
    public static Disposable register() {
        CommandRegistry.global().contribute(BuilderCommands.class, BuilderCommands::declare);
        return () -> { };
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(INSPECT_ELEMENT, "Inspect Element")
                .binding("Ctrl+Shift+C")
                .run(context -> PickMode.start(windowOf(context)))
                .enabledWhen(context -> windowOf(context) != null));
    }

    /** The window the command was invoked in, walked out of whatever had focus. */
    private static UIDocument windowOf(CommandContext context) {
        return context.data().source() instanceof UIElement element ? element.document() : null;
    }
}
