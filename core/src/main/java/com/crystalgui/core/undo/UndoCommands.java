package com.crystalgui.core.undo;

import com.crystalgui.ui.UiDataKeys;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;

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
 * <h3>A widget binds these, it does not re-implement them</h3>
 * <p>{@code TextEditor} used to handle Ctrl+Z in its own key handler and consume the keystroke before the
 * resolver ever ran. That worked, and it made {@code edit.undo} <b>the one command in the engine that
 * could be remapped and still not move</b> — the resolver only sees an unconsumed event, so the editor ate
 * the key whatever the keymap said. It now calls {@link #register} and leaves the chords alone, so focus
 * scoping does the work: {@link #UNDO_CHORD} is declared on the command and therefore applies everywhere,
 * any element is free to bind {@link #UNDO} to something else and win while focus is inside it, and both
 * routes end at the same {@link UndoScope#nearest} lookup.</p>
 *
 * <p>That is the general rule for any widget with a history — <b>bind these ids, do not invent your
 * own.</b> An {@code editor.undo} beside {@code edit.undo} would put two entries for one concept in every
 * menu and palette, and nothing would say which the keystroke ran.</p>
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

    /**
     * Registers both actions globally. Idempotent.
     *
     * <h3>Explicit, and global</h3>
     *
     * <p>Two separate things changed here and only one of them is the interesting one.</p>
     *
     * <p><b>Global</b> is the fix: these used to be registered per {@code UIWindow}, so undo existed
     * only where somebody had installed it, and {@code install(registry, fileTree)} named a
     * <em>specific element</em> even though the stack was always resolved from focus. The element
     * argument decided nothing but where the bindings landed.</p>
     *
     * <p><b>Explicit</b> is deliberately unchanged. A static initialiser would have made registration
     * depend on whether anything had touched this class, so the command palette's contents would vary
     * with class-loading order — and this engine already refuses that: {@code CrystalEditorCommands}
     * states that "a registry that quietly acquired commands nobody registered surprises anything that
     * enumerates it", which is exactly what a palette does.</p>
     *
     * <p>The subject comes from {@link UiDataKeys#UNDO_STACK}, which every {@code UndoScope} answers,
     * so this reaches the innermost history from wherever it is invoked with nothing wired to
     * anything.</p>
     */
    public static void register() {
        CommandRegistry.global().contribute(UndoCommands.class, UndoCommands::declare);
    }

    private static void declare(CommandRegistry registry) {
        registry.register(Command.of(UNDO, "Undo")
                        .binding(UNDO_CHORD)
                        // THE MENU BAR IS A QUERY, so a command states where it appears and the bar never
                        // hears about it. One line here is the whole of "Edit > Undo existing".
                        .menu(MenuId.MAIN_EDIT, "1_undo", 10)
                        .enabledWhereData(context -> {
                            UndoStack history = context.get(UiDataKeys.UNDO_STACK);
                            return history != null && history.canUndo();
                        })
                        .runWithData(context -> context.require(UiDataKeys.UNDO_STACK).undo()));
        registry.register(Command.of(REDO, "Redo")
                        .binding(REDO_CHORD, REDO_CHORD_ALT)
                        .menu(MenuId.MAIN_EDIT, "1_undo", 20)
                        .enabledWhereData(context -> {
                            UndoStack history = context.get(UiDataKeys.UNDO_STACK);
                            return history != null && history.canRedo();
                        })
                        .runWithData(context -> context.require(UiDataKeys.UNDO_STACK).redo()));
    }

    // There is deliberately no bindDefaults/install pair here any more.
    //
    // Both chords are declared on the commands themselves (see .binding above), and CommandRegistry's
    // declaredBindings() is the resolver's outermost scope -- so undo is application-wide with nothing
    // binding it to any element. The old pair bound the same three chords onto a root keymap, which is
    // now a second copy of one fact: rebinding undo through a keymap would leave the declared chord
    // live, so the two would disagree about what Mod+Z does.
    //
    // An element that wants undo on a DIFFERENT chord still binds edit.undo on its own keymap, and the
    // innermost match wins as it always did.
}
