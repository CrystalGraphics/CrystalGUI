package com.crystalgui.ui.elements.editor;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.input.keymap.Keymap;

import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The editor's named actions and their default chords — P6.1.7b §H.
 *
 * <p>Shaped after {@link com.crystalgui.core.undo.UndoCommands}, which is the established pattern here:
 * a {@code register} that names the actions, a {@code bindDefaults} that binds them, and an
 * {@code install} that does both. Bindings name a {@code String} id and never a lambda, which is what
 * makes a keymap data — parseable from a resource, shippable as a preset, remappable by a user.</p>
 *
 * <h3>What is a command, and what stays a keystroke</h3>
 * <p>The line is drawn where {@link com.crystalgui.ui.input.keymap.KeymapResolver}'s own typing guard
 * draws it, not arbitrarily:</p>
 * <table>
 *   <caption>The split</caption>
 *   <tr><th></th><th>Handled by</th><th>Why</th></tr>
 *   <tr><td><b>Modified chords</b> — {@code Mod+D}, {@code Alt+Up}, {@code Mod+Shift+K}</td>
 *       <td>commands, here</td>
 *       <td>They are <em>actions</em> with names, and a binding carrying a non-Shift modifier fires
 *           unambiguously inside a text field.</td></tr>
 *   <tr><td><b>Bare keys</b> — arrows, Home, End, Backspace, Enter, Tab, typing</td>
 *       <td>{@code TextEditor}'s own key handler</td>
 *       <td>They are what the widget <em>is</em>, the same way Space activating a {@code Button} is.
 *           The resolver skips bare bindings while typing precisely because a bare key belongs to the
 *           thing being typed into.</td></tr>
 * </table>
 *
 * <p>So this is not "half the keys are rebindable by accident". It is: every <b>named action</b> is a
 * command, and cursor movement is not an action, it is text input.</p>
 *
 * <h3>Why the editor installs these itself</h3>
 * <p>{@code UndoCommands} is deliberately never installed automatically, because undo is an
 * <em>application</em> concern bound at the root and a window that silently acquired it would surprise
 * anything enumerating commands. This is the other case: these are the widget's own keys, bound on the
 * widget's own element, under an {@code editor.} prefix. A text editor that does nothing when you press
 * {@code Mod+D} is broken, not neutral — so {@code TextEditor} installs them when it attaches, and a host
 * that wants different chords rebinds them on {@code editor.keymap()}.</p>
 */
public final class EditorCommands {

    public static final String PREFIX = "editor.";

    private EditorCommands() {
    }

    /** Runs {@code action} against the editor the command was invoked from, if there is one. */
    private static Consumer<CommandContext> on(Consumer<TextEditor> action) {
        return context -> {
            TextEditor editor = nearest(context.source());
            if (editor != null) action.accept(editor);
        };
    }

    private static Predicate<CommandContext> when(Predicate<TextEditor> test) {
        return context -> {
            TextEditor editor = nearest(context.source());
            return editor != null && test.test(editor);
        };
    }

    /** An editable editor — the enablement almost every mutating command wants. */
    private static Predicate<CommandContext> whenEditable() {
        return when(editor -> !editor.isReadOnly());
    }

    /**
     * The nearest {@code TextEditor} at or above {@code from}.
     *
     * <p>Walks up rather than requiring the source to <em>be</em> the editor, because a command invoked
     * from a menu item carries that item as its source. The same shape as {@code UndoScope.nearest}.</p>
     */
    @Nullable
    public static TextEditor nearest(@Nullable UIElement from) {
        for (UIElement element = from; element != null; element = element.getParent()) {
            if (element instanceof TextEditor editor) return editor;
        }
        return null;
    }

    /** Registers every editor command. Idempotent — re-registering the same ids is a no-op. */
    public static void register(CommandRegistry registry) {
        // edit.undo/edit.redo are UndoCommands' ids, not ours, and they already resolve the right history
        // through UndoScope.nearest -- which from a focused editor finds that editor's own buffer. Calling
        // its registrar rather than declaring editor.undo beside it keeps ONE command per concept, so a
        // menu and the palette cannot show two entries that do the same thing. Idempotent at both ends.
        com.crystalgui.core.undo.UndoCommands.register(registry);

        if (registry.contains(PREFIX + "deleteLines")) return;

        // ── Multi-caret ─────────────────────────────────────────────────────────────────────────
        registry.register(Command.of(PREFIX + "addCaretAtNextOccurrence", "Add Caret At Next Occurrence")
                .run(on(TextEditor::addCaretAtNextOccurrence)));
        registry.register(Command.of(PREFIX + "selectAllOccurrences", "Select All Occurrences")
                .run(on(TextEditor::selectAllOccurrences)));
        registry.register(Command.of(PREFIX + "addCaretAbove", "Add Caret Above")
                .run(on(editor -> editor.addCaretOnAdjacentLine(-1))));
        registry.register(Command.of(PREFIX + "addCaretBelow", "Add Caret Below")
                .run(on(editor -> editor.addCaretOnAdjacentLine(1))));

        // ── Line operations ─────────────────────────────────────────────────────────────────────
        registry.register(Command.of(PREFIX + "selectLine", "Select Line")
                .run(on(TextEditor::selectLine)));
        registry.register(Command.of(PREFIX + "deleteLines", "Delete Line")
                .run(on(TextEditor::deleteLines)).enabledWhen(whenEditable()));
        registry.register(Command.of(PREFIX + "moveLineUp", "Move Line Up")
                .run(on(editor -> editor.moveLines(-1))).enabledWhen(whenEditable()));
        registry.register(Command.of(PREFIX + "moveLineDown", "Move Line Down")
                .run(on(editor -> editor.moveLines(1))).enabledWhen(whenEditable()));
        registry.register(Command.of(PREFIX + "duplicateLineUp", "Duplicate Line Up")
                .run(on(editor -> editor.duplicateLines(-1))).enabledWhen(whenEditable()));
        registry.register(Command.of(PREFIX + "duplicateLineDown", "Duplicate Line Down")
                .run(on(editor -> editor.duplicateLines(1))).enabledWhen(whenEditable()));
        registry.register(Command.of(PREFIX + "insertLineBelow", "Insert Line Below")
                .run(on(editor -> editor.insertLine(1))).enabledWhen(whenEditable()));
        registry.register(Command.of(PREFIX + "insertLineAbove", "Insert Line Above")
                .run(on(editor -> editor.insertLine(-1))).enabledWhen(whenEditable()));
        registry.register(Command.of(PREFIX + "joinLines", "Join Lines")
                .run(on(TextEditor::joinLines)).enabledWhen(whenEditable()));

        // ── Comments ────────────────────────────────────────────────────────────────────────────
        registry.register(Command.of(PREFIX + "toggleLineComment", "Toggle Line Comment")
                .run(on(TextEditor::toggleLineComment)).enabledWhen(whenEditable()));
        registry.register(Command.of(PREFIX + "toggleBlockComment", "Toggle Block Comment")
                .run(on(TextEditor::toggleBlockComment)).enabledWhen(whenEditable()));

        // ── Selection and clipboard ─────────────────────────────────────────────────────────────
        registry.register(Command.of(PREFIX + "selectAll", "Select All")
                .run(on(editor -> editor.setSelection(0, editor.getText().length()))));
        registry.register(Command.of(PREFIX + "copy", "Copy")
                .run(on(editor -> CgPlatform.input().setClipboard(editor.getSelectedText())))
                .enabledWhen(when(TextEditor::hasSelection)));
        registry.register(Command.of(PREFIX + "cut", "Cut")
                .run(on(editor -> {
                    CgPlatform.input().setClipboard(editor.getSelectedText());
                    editor.deleteSelections();
                }))
                // Two conditions, and both matter: nothing to cut, or nowhere to cut from.
                .enabledWhen(when(editor -> editor.hasSelection() && !editor.isReadOnly())));
        registry.register(Command.of(PREFIX + "paste", "Paste")
                .run(on(editor -> {
                    String pasted = CgPlatform.input().getClipboard();
                    if (pasted != null && !pasted.isEmpty()) editor.insertAtCaret(pasted);
                }))
                .enabledWhen(whenEditable()));

        // ── Search ──────────────────────────────────────────────────────────────────────────────
        registry.register(Command.of(PREFIX + "findNext", "Find Next")
                .run(on(TextEditor::findNext)).enabledWhen(when(editor -> editor.matchCount() > 0)));
        registry.register(Command.of(PREFIX + "findPrevious", "Find Previous")
                .run(on(TextEditor::findPrevious)).enabledWhen(when(editor -> editor.matchCount() > 0)));
        registry.register(Command.of(PREFIX + "findWordUnderCaret", "Find Word Under Caret")
                .run(on(TextEditor::findWordUnderCaret)));

        // ── View ────────────────────────────────────────────────────────────────────────────────
        registry.register(Command.of(PREFIX + "zoomIn", "Zoom In")
                .run(on(editor -> editor.zoomBy(1)))
                .enabledWhen(when(editor -> editor.getFontSize() < TextEditor.MAX_FONT_SIZE)));
        registry.register(Command.of(PREFIX + "zoomOut", "Zoom Out")
                .run(on(editor -> editor.zoomBy(-1)))
                .enabledWhen(when(editor -> editor.getFontSize() > TextEditor.MIN_FONT_SIZE)));
        registry.register(Command.of(PREFIX + "zoomReset", "Reset Zoom")
                .run(on(TextEditor::resetZoom)));

        registry.register(Command.of(PREFIX + "toggleSoftWrap", "Toggle Soft Wrap")
                // Not undoable, and it must not be: wrapping is a view setting and the document is
                // byte-identical either way. See the boundary note on UndoStack.
                .run(on(editor -> editor.setSoftWrap(!editor.isSoftWrap()))));
    }

    /**
     * Binds the default chords — normally on the editor's <b>own</b> element, which is what scopes them
     * to it: {@code KeymapResolver} walks focus outward, so these win over anything an ancestor binds.
     *
     * <p>Chords are VS Code's, and {@code Mod} resolves to Ctrl or Cmd per platform.</p>
     */
    public static void bindDefaults(Keymap keymap) {
        keymap.bind("Mod+D", PREFIX + "addCaretAtNextOccurrence");
        keymap.bind("Mod+Shift+L", PREFIX + "selectAllOccurrences");
        keymap.bind("Mod+Alt+Up", PREFIX + "addCaretAbove");
        keymap.bind("Mod+Alt+Down", PREFIX + "addCaretBelow");

        keymap.bind("Mod+L", PREFIX + "selectLine");
        keymap.bind("Mod+Shift+K", PREFIX + "deleteLines");
        keymap.bind("Alt+Up", PREFIX + "moveLineUp");
        keymap.bind("Alt+Down", PREFIX + "moveLineDown");
        keymap.bind("Shift+Alt+Up", PREFIX + "duplicateLineUp");
        keymap.bind("Shift+Alt+Down", PREFIX + "duplicateLineDown");
        // RETURN, not "Enter": key names come from CgKeyCodes' own constants, and KEY_ENTER does not
        // exist -- KEY_NUMPADENTER is the only one with "enter" in its name. A wrong name throws at bind
        // time rather than producing a binding that never fires, which is how this was caught.
        keymap.bind("Mod+Return", PREFIX + "insertLineBelow");
        keymap.bind("Mod+Shift+Return", PREFIX + "insertLineAbove");
        keymap.bind("Mod+J", PREFIX + "joinLines");

        keymap.bind("Mod+Slash", PREFIX + "toggleLineComment");
        keymap.bind("Shift+Alt+A", PREFIX + "toggleBlockComment");

        keymap.bind("Mod+A", PREFIX + "selectAll");
        keymap.bind("Mod+C", PREFIX + "copy");
        keymap.bind("Mod+X", PREFIX + "cut");
        keymap.bind("Mod+V", PREFIX + "paste");

        keymap.bind("F3", PREFIX + "findNext").allowWhileTyping();
        keymap.bind("Shift+F3", PREFIX + "findPrevious").allowWhileTyping();
        keymap.bind("Mod+F3", PREFIX + "findWordUnderCaret");

        keymap.bind("Alt+Z", PREFIX + "toggleSoftWrap");

        // VS Code's chords. Both spellings of each, because the numeric keypad is a different key code
        // and a laptop without one is the common case -- and one call rather than two, so the pair cannot
        // drift apart when somebody edits only the line they were looking at.
        keymap.bindAll("Mod+WheelUp, Mod+Equals, Mod+Add", PREFIX + "zoomIn");
        keymap.bindAll("Mod+WheelDown, Mod+Minus, Mod+Subtract", PREFIX + "zoomOut");
        keymap.bindAll("Mod+0, Mod+Numpad0", PREFIX + "zoomReset");

        // Bound on the EDITOR, using UndoCommands' own chords and ids. A host that also installs undo at
        // the root gets the same command either way, and the inner binding simply wins while focus is
        // here -- there is no conflict to resolve because both routes end at UndoScope.nearest.
        keymap.bind(com.crystalgui.core.undo.UndoCommands.UNDO_CHORD,
                com.crystalgui.core.undo.UndoCommands.UNDO);
        keymap.bind(com.crystalgui.core.undo.UndoCommands.REDO_CHORD,
                com.crystalgui.core.undo.UndoCommands.REDO);
        keymap.bind(com.crystalgui.core.undo.UndoCommands.REDO_CHORD_ALT,
                com.crystalgui.core.undo.UndoCommands.REDO);
    }

    /** Registers the commands and binds the defaults on {@code editor}'s own keymap. */
    public static void install(CommandRegistry registry, TextEditor editor) {
        register(registry);
        bindDefaults(editor.keymap());
    }

    /** The same, resolving the registry from the window the editor is in. */
    public static void install(UIWindow window, TextEditor editor) {
        install(window.getCommands(), editor);
    }
}
