package com.crystalgui.screen;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.Commands;
import com.crystalgui.core.settings.Preferences;
import com.crystalgui.ui.dom.UIElement;
import java.util.function.Consumer;
import javax.annotation.Nullable;

/**
 * An application's zoom from the keyboard, as a browser's: Ctrl + and Ctrl − step along {@link UiScale#ZOOM_STEPS},
 * Ctrl 0 returns to 100 %. Each acts on the application whose document the key reached, and is remembered under
 * {@code appearance.zoom.<id>}.
 *
 * <pre>{@code
 * Commands.process().run(ZoomCommands.ZOOM_IN, context);   // what Ctrl + does, from a palette or a menu
 * }</pre>
 *
 * <p>Disabled outside an application's document: the desktop's own scale is a preference, not a zoom.</p>
 */
public final class ZoomCommands {

    public static final String ZOOM_IN = "view.zoomIn";
    public static final String ZOOM_OUT = "view.zoomOut";
    public static final String ZOOM_RESET = "view.zoomReset";

    private static boolean registered;

    private ZoomCommands() {
    }

    /** Idempotent. */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        Commands registry = Commands.process();
        // Both spellings: the numeric keypad's keys are other key codes.
        registry.register(Command.of(ZOOM_IN, "Zoom In")
                .binding("Mod+Equals", "Mod+Add")
                .run(context -> withApp(context, UiScale::zoomIn))
                .enabledWhen(context -> {
                    String app = appOf(context);
                    return app != null && Preferences.current().zoom(app) < UiScale.ZOOM_STEPS[UiScale.ZOOM_STEPS.length - 1];
                }));
        registry.register(Command.of(ZOOM_OUT, "Zoom Out")
                .binding("Mod+Minus", "Mod+Subtract")
                .run(context -> withApp(context, UiScale::zoomOut))
                .enabledWhen(context -> {
                    String app = appOf(context);
                    return app != null && Preferences.current().zoom(app) > UiScale.ZOOM_STEPS[0];
                }));
        registry.register(Command.of(ZOOM_RESET, "Reset Zoom")
                .binding("Mod+0", "Mod+Numpad0")
                .run(context -> withApp(context, UiScale::resetZoom))
                .enabledWhen(context -> {
                    String app = appOf(context);
                    return app != null && Preferences.current().zoom(app) != 1f;
                }));
    }

    /** Runs {@code action} on the application the key reached, then shows where its zoom now is. */
    private static void withApp(CommandContext context, Consumer<String> action) {
        String app = appOf(context);
        if (app == null) return;
        action.accept(app);
        ZoomIndicator.show(((UIElement) context.data().source()).document(), app);
    }

    @Nullable
    private static String appOf(CommandContext context) {
        Object source = context.data().source();
        return source instanceof UIElement element && element.document() != null ? UiScale.appOf(element.document()) : null;
    }
}
