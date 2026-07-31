package com.crystalgui.core.undo;

import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.keymap.Keymap;

import javax.annotation.Nullable;

/**
 * The built-in {@code edit.undo} / {@code edit.redo} commands, and their default bindings.
 *
 * <h3>Why undo is a command rather than a key handler</h3>
 * <p>Because three things need the same answer and must not each invent one: the keystroke, a menu item,
 * and the command palette. {@link Command#isEnabled} is already the single enablement mechanism with
 * exactly those three consumers, so wiring undo through it means a greyed-out <i>Edit ▸ Undo</i> comes
 * free instead of being a fourth place to keep in sync. It also makes undo <b>remappable</b> — a user
 * who wants Ctrl+Y for redo edits a keymap, not Java.</p>
 *
 * <h3>Which history it reaches</h3>
 * <p>{@link UndoScope#nearest} from the element the command was invoked from, which for a keystroke is
 * the focused element. No global stack is consulted and none exists — see {@link UndoStack}'s note on
 * being per document rather than per window.</p>
 *
 * <h3>A widget may still pre-empt it</h3>
 * <p>{@code TextEditor} handles Ctrl+Z in its own key handler and calls {@code stopPropagation()}, which
 * consumes the keystroke before the keymap resolver ever runs — so the two coexist rather than firing
 * twice. That ordering is the engine's, not a coincidence to rely on quietly: the resolver deliberately
 * runs after dispatch so a focused control gets first refusal on its own keys.</p>
 */
public final class UndoCommands {

    public static final String UNDO = "edit.undo";
    public static final String REDO = "edit.redo";

    /** Ctrl on Windows/Linux, Cmd on macOS — {@code KeyStroke} resolves {@code Mod} per platform. */
    public static final String UNDO_CHORD = "Mod+Z";
    public static final String REDO_CHORD = "Mod+Shift+Z";
    /** Windows' other redo. Bound as well as {@link #REDO_CHORD} because both are muscle memory for
     * different people, and a second binding for one command costs nothing. */
    public static final String REDO_CHORD_ALT = "Mod+Y";

    private UndoCommands() {
    }

    /** Registers both commands. Idempotent — re-registering the same ids is a no-op. */
    public static void register(CommandRegistry registry) {
        if (registry.contains(UNDO)) return;
        registry.register(Command.of(UNDO, "Undo")
                .run(context -> {
                    UndoStack history = stackFor(context);
                    if (history != null) history.undo();
                })
                .enabledWhen(context -> {
                    UndoStack history = stackFor(context);
                    return history != null && history.canUndo();
                }));
        registry.register(Command.of(REDO, "Redo")
                .run(context -> {
                    UndoStack history = stackFor(context);
                    if (history != null) history.redo();
                })
                .enabledWhen(context -> {
                    UndoStack history = stackFor(context);
                    return history != null && history.canRedo();
                }));
    }

    /**
     * Binds the default chords into {@code keymap} — normally the <b>root element's</b>, which is what
     * makes them application-wide: the resolver falls back to the root when nothing holds focus, and any
     * inner scope that binds the same chord wins over them.
     */
    public static void bindDefaults(Keymap keymap) {
        keymap.bind(UNDO_CHORD, UNDO);
        keymap.bind(REDO_CHORD, REDO);
        keymap.bind(REDO_CHORD_ALT, REDO);
    }

    /** Registers the commands and binds them on {@code root}. */
    public static void install(CommandRegistry registry, UIElement root) {
        register(registry);
        bindDefaults(root.keymap());
    }

    /**
     * Installs undo into {@code window} — its command registry, bound on its root element.
     *
     * <p><b>Explicit, never automatic.</b> A host calls this exactly as it calls
     * {@code addStylesheet(StyleSheet.DEFAULT)}, and for the same reason: this engine does not inject
     * its own defaults. A window that quietly acquired two commands and three bindings nobody
     * registered would surprise anything that enumerates either — which it promptly did, in the keymap's
     * own tests.</p>
     *
     * <pre>{@code
     * UIWindow window = new UIWindow(Ui.of(root));
     * window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
     * UndoCommands.install(window);
     * }</pre>
     */
    public static void install(UIWindow window) {
        install(window.getCommands(), window.ui.rootElement);
    }

    @Nullable
    private static UndoStack stackFor(CommandContext context) {
        return UndoScope.nearest(context.source());
    }
}
