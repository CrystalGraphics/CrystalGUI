package com.crystalgui.ui.elements.editor;

import com.crystalgui.core.command.ClipboardCommands;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.command.Command;
import com.crystalgui.core.command.CommandContext;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.command.MenuId;
import com.crystalgui.text.TextPoint;
import com.crystalgui.ui.elements.InputDialog;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.input.keymap.Keymap;

import javax.annotation.Nullable;
import java.util.function.Consumer;
import java.util.function.Predicate;
import com.crystalgui.core.undo.UndoCommands;

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
    /**
     * Registers into {@link CommandRegistry#global()}.
     *
     * <p>Commands are global; a command is a fact about the application, and what varies per window is
     * what is <em>focused</em> — which is {@code DataContext}'s job. Registering per window meant every
     * window re-registered everything, and a widget had to find "its" window before it could contribute.</p>
     *
     * <p>Still <b>explicit</b>: a host calls this. Nothing self-registers, because a registry that
     * quietly acquired commands nobody asked for surprises anything that enumerates it — which the
     * command palette does.</p>
     */
    public static void register() {
        // edit.undo/edit.redo are UndoCommands' ids, not ours, and they already resolve the right history
        // through UndoScope.nearest -- which from a focused editor finds that editor's own buffer. Calling
        // its registrar rather than declaring editor.undo beside it keeps ONE command per concept, so a
        // menu and the palette cannot show two entries that do the same thing.
        UndoCommands.register();
        // Edit > Cut/Copy/Paste, which are NOT ours: they resolve the position's own provider, so the one
        // menu row means files in the tree and text here. @see com.crystalgui.core.command.ClipboardCommands
        ClipboardCommands.register();
        CommandRegistry.global().contribute(EditorCommands.class, EditorCommands::declare);
    }

    /** {@code Navigate ▸ Line/Column} — IntelliJ's own wording, binding and placeholder. */
    public static final String GO_TO_LINE = PREFIX + "goToLine";

    /** {@code Navigate ▸ Declaration or Usages} — {@code Ctrl+B}, and Ctrl+Click in the editor. */
    public static final String GO_TO_DEFINITION = PREFIX + "goToDefinition";

    /** {@code View ▸ Quick Documentation} — IntelliJ's own name for it, and its binding. */
    public static final String QUICK_DOCUMENTATION = PREFIX + "quickDocumentation";

    /** Alt+Enter. Named for what IntelliJ calls it, since that is what people search for. */
    public static final String SHOW_CODE_ACTIONS = PREFIX + "showCodeActions";

    /**
     * {@code 40}, {@code 40:8}, or {@code :8} for a column on the line the caret is already on.
     *
     * <p><b>Clamped, never refused.</b> A line past the end goes to the last line — every editor does
     * this, and refusing means retyping a number whose only fault is being optimistic. Unparseable input
     * is a no-op rather than an error dialog: the field is the error message, exactly as the rename
     * prompt treats a blank name.</p>
     *
     * <p>Package-visible so it can be tested without a prompt: the parsing and the clamping are the part
     * with rules, and a dialog is not needed to state them.</p>
     */
    static void goTo(TextEditor editor, String typed) {
        String text = typed.trim();
        if (text.isEmpty()) return;
        int colon = text.indexOf(':');
        String linePart = colon < 0 ? text : text.substring(0, colon);
        String columnPart = colon < 0 ? "" : text.substring(colon + 1);
        int row = editor.caretPoint().row();
        try {
            // ONE-BASED on the way in, because that is what the gutter shows and what the user typed.
            if (!linePart.isEmpty()) row = Integer.parseInt(linePart.trim()) - 1;
        } catch (NumberFormatException malformed) {
            return;
        }
        int column = 0;
        try {
            if (!columnPart.isEmpty()) column = Integer.parseInt(columnPart.trim()) - 1;
        } catch (NumberFormatException malformed) {
            return;
        }
        row = Math.max(0, Math.min(row, editor.buffer().lineCount() - 1));
        column = Math.max(0, Math.min(column, editor.buffer().line(row).length()));
        editor.setCaret(editor.buffer().pointToOffset(new TextPoint(row, column)));
        // CENTRED, not merely visible. setCaret does not scroll at all, and scrolling the minimum would
        // put the line you asked for hard against the top or bottom edge with all its context on one
        // side -- which is the worst framing for a line you have just been sent to.
        editor.revealCaretCentred();
    }

    private static void declare(CommandRegistry registry) {
        // ── Navigation ──────────────────────────────────────────────────────────────────────────
        // AN EDITOR COMMAND, though it opens a prompt. It was briefly in ChromeCommands on the grounds
        // that InputDialog lived in `chrome` -- which put an editor action in the shell's command set and
        // made `chrome` depend on `editor` for the first time. InputDialog imports nothing from chrome,
        // so it moved to `ui.elements` beside Popover and TextField, and this came home.
        registry.register(Command.of(GO_TO_LINE, "Go To Line…")
                .binding("Mod+G")
                .menu(MenuId.MAIN_VIEW, "1_appearance", 20)
                .run(on(editor -> InputDialog.ask(editor, "Go To Line", "[Line][:column]", "",
                        typed -> goTo(editor, typed)))));

        // ENABLED WHENEVER AN EDITOR IS FOCUSED, deliberately not "when the caret resolves to something".
        // Whether a name has a declaration is an ASYNCHRONOUS question, so an enablement predicate could
        // only answer it from a cached previous resolve -- and a menu row that greys and ungreys as
        // compiles land is worse than one that is always live and sometimes does nothing. Both references
        // keep this entry enabled. @see TextEditor#goToDefinition
        registry.register(Command.of(GO_TO_DEFINITION, "Go To Declaration")
                .binding("Mod+B")
                .menu(MenuId.MAIN_VIEW, "1_appearance", 10)
                .run(on(TextEditor::goToDefinition)));

        // NAMED FOR THE FEATURE, not for the trigger. Hovering is a setting on this popup rather than a
        // separate affordance, and pressing the key again promotes the same content into a tool window --
        // an id called `editor.hover` would make both of those look like new features when they land.
        registry.register(Command.of(QUICK_DOCUMENTATION, "Quick Documentation")
                .binding("Mod+Q")
                .menu(MenuId.MAIN_VIEW, "1_appearance", 30)
                .run(on(TextEditor::showQuickDocumentation)));

        // ALT+ENTER, which is IntelliJ's and is deliberately not Mod+. VS Code puts code actions on
        // Ctrl+. and IntelliJ on Alt+Enter; the editor's own key handler returns false for any Alt chord
        // precisely so bindings like this one can exist, while Ctrl+Enter it has to keep.
        registry.register(Command.of(SHOW_CODE_ACTIONS, "Show Context Actions")
                .binding("Alt+Enter")
                .menu(MenuId.MAIN_VIEW, "1_appearance", 40)
                .run(on(editor -> editor.showCodeActionsAt(editor.getCaret()))));

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
                .menu(MenuId.MAIN_EDIT, "5_comment", 10)
                .run(on(TextEditor::toggleLineComment)).enabledWhen(whenEditable()));
        registry.register(Command.of(PREFIX + "toggleBlockComment", "Toggle Block Comment")
                .menu(MenuId.MAIN_EDIT, "5_comment", 20)
                .run(on(TextEditor::toggleBlockComment)).enabledWhen(whenEditable()));

        // ── Selection and clipboard ─────────────────────────────────────────────────────────────
        //
        // THE EDIT MENU IS THESE PLACEMENTS AND NOTHING ELSE. There is no list of Edit's contents
        // anywhere: each command says which section it belongs to, MenuBarView asks the registry, and the
        // separators fall out of the section boundaries. Adding one here is the entire act of adding it to
        // the menu -- which is the property the whole MenuId design exists to buy.
        registry.register(Command.of(PREFIX + "selectAll", "Select All")
                .menu(MenuId.MAIN_EDIT, "3_select", 10)
                // GUARDED, though it takes no state of its own. Every other command here resolves an
                // editor and so greys without one; this had no enabledWhen at all, which made it the one
                // white row in an Edit menu opened over the file tree -- and pressing it did nothing,
                // because `on` returns when there is no editor. "A command that does nothing visible is
                // worse than one that is greyed out" is already stated on DockCommands.TOGGLE_MAXIMIZE.
                .enabledWhen(when(editor -> true))
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
        registry.register(Command.of(PREFIX + "find", "Find…")
                .menu(MenuId.MAIN_EDIT, "4_find", 5)
                .run(on(TextEditor::openFind)));
        registry.register(Command.of(PREFIX + "replace", "Replace…")
                .menu(MenuId.MAIN_EDIT, "4_find", 6)
                .run(on(TextEditor::openReplace)));
        // THE BAR'S OWN CHORDS, as commands. They were listeners on its text fields -- six shortcuts in a
        // place no keymap could see and nobody could rebind, in an application that has a command layer and
        // an element-scoped resolver for exactly this. Every one resolves from the focused element, so they
        // only mean anything while the bar (which lives inside the editor) holds the caret.
        registry.register(Command.of(PREFIX + "toggleMatchCase", "Match Case")
                .run(on(e -> e.searchBar().toggleMatchCase())));
        registry.register(Command.of(PREFIX + "toggleWholeWords", "Words")
                .run(on(e -> e.searchBar().toggleWholeWords())));
        registry.register(Command.of(PREFIX + "toggleRegex", "Regex")
                .run(on(e -> e.searchBar().toggleRegex())));
        registry.register(Command.of(PREFIX + "togglePreserveCase", "Preserve Case")
                .run(on(e -> e.searchBar().togglePreserveCase())));
        registry.register(Command.of(PREFIX + "replaceCurrent", "Replace")
                .menu(MenuId.MAIN_EDIT, "4_find", 7)
                .run(on(e -> e.searchBar().replaceCurrent()))
                .enabledWhen(when(editor -> editor.matchCount() > 0)));
        registry.register(Command.of(PREFIX + "replaceAll", "Replace All")
                .menu(MenuId.MAIN_EDIT, "4_find", 8)
                .run(on(e -> e.searchBar().replaceEvery()))
                .enabledWhen(when(editor -> editor.matchCount() > 0)));
        registry.register(Command.of(PREFIX + "excludeMatch", "Exclude Match")
                .run(on(e -> e.searchBar().toggleExclude()))
                .enabledWhen(when(editor -> editor.matchCount() > 0)));

        registry.register(Command.of(PREFIX + "find.close", "Close Find Bar")
                .run(on(e -> e.searchBar().close())));

        registry.register(Command.of(PREFIX + "findNext", "Find Next")
                .menu(MenuId.MAIN_EDIT, "4_find", 10)
                .run(on(TextEditor::findNext)).enabledWhen(when(editor -> editor.matchCount() > 0)));
        registry.register(Command.of(PREFIX + "findPrevious", "Find Previous")
                .menu(MenuId.MAIN_EDIT, "4_find", 20)
                .run(on(TextEditor::findPrevious)).enabledWhen(when(editor -> editor.matchCount() > 0)));
        registry.register(Command.of(PREFIX + "findWordUnderCaret", "Find Word Under Caret")
                .run(on(TextEditor::findWordUnderCaret)));

        // ── Problems ────────────────────────────────────────────────────────────────────────────
        //
        // Navigation is a CYCLE, not a walk to the end: repeatedly pressing next visits every problem and
        // returns to the first. IntelliJ's F2 and VS Code's F8 both work this way, and the alternative --
        // stopping at the last one -- makes the most common gesture (keep pressing until the file is
        // clean) end at a dead key that gives no feedback.
        registry.register(Command.of(PREFIX + "nextProblem", "Next Problem")
                .run(on(TextEditor::goToNextProblem))
                .enabledWhen(when(editor -> !editor.diagnostics().isEmpty())));
        registry.register(Command.of(PREFIX + "previousProblem", "Previous Problem")
                .run(on(TextEditor::goToPreviousProblem))
                .run(on(TextEditor::goToPreviousProblem))
                .enabledWhen(when(editor -> !editor.diagnostics().isEmpty())));

        // ── View ────────────────────────────────────────────────────────────────────────────────
        registry.register(Command.of(PREFIX + "zoomIn", "Zoom In")
                .menu(MenuId.MAIN_VIEW, "3_editor", 10)
                .run(on(editor -> editor.zoomBy(1)))
                .enabledWhen(when(editor -> editor.getFontSize() < TextEditor.MAX_FONT_SIZE)));
        registry.register(Command.of(PREFIX + "zoomOut", "Zoom Out")
                .menu(MenuId.MAIN_VIEW, "3_editor", 20)
                .run(on(editor -> editor.zoomBy(-1)))
                .enabledWhen(when(editor -> editor.getFontSize() > TextEditor.MIN_FONT_SIZE)));
        registry.register(Command.of(PREFIX + "zoomReset", "Reset Zoom")
                .menu(MenuId.MAIN_VIEW, "3_editor", 30)
                .run(on(TextEditor::resetZoom)));

        registry.register(Command.of(PREFIX + "toggleSoftWrap", "Toggle Soft Wrap")
                .menu(MenuId.MAIN_VIEW, "3_editor", 40)
                // A CHECKMARK, stated by the command rather than by whoever draws the row. The renderer
                // asks; nothing about the View menu knows what soft wrap is. Read live, so the tick is
                // right for the editor that happens to be focused when the menu opens.
                .toggledWhen(when(TextEditor::isSoftWrap))
                // Not undoable, and it must not be: wrapping is a view setting and the document is
                // byte-identical either way. See the boundary note on UndoStack.
                .run(on(editor -> editor.setSoftWrap(!editor.isSoftWrap()))));

        // FOLDING. Not undoable either, and for the same reason: which blocks are closed is how you are
        // looking at the file, not what the file says.
        registry.register(Command.of(PREFIX + "fold", "Fold")
                .run(on(TextEditor::fold)));
        registry.register(Command.of(PREFIX + "unfold", "Unfold")
                .run(on(TextEditor::unfold)));
        registry.register(Command.of(PREFIX + "foldRecursively", "Fold Recursively")
                .run(on(TextEditor::foldRecursively)));
        registry.register(Command.of(PREFIX + "foldAll", "Fold All")
                .menu(MenuId.MAIN_VIEW, "4_folding", 10)
                .run(on(TextEditor::foldAll)));
        registry.register(Command.of(PREFIX + "unfoldAll", "Unfold All")
                .menu(MenuId.MAIN_VIEW, "4_folding", 20)
                .run(on(TextEditor::unfoldAll)));
        for (int level = 1; level <= 7; level++) {
            final int foldLevel = level;
            registry.register(Command.of(PREFIX + "foldLevel" + level, "Fold Level " + level)
                    .run(on(editor -> editor.foldLevel(foldLevel))));
        }
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

        // ELEMENT-SCOPED, on the editor. Ctrl+F means Find in a tree too, and the resolver walks outward
        // from focus -- so whichever of them holds the caret answers, and neither takes it from the other.
        keymap.bind("Mod+F", PREFIX + "find").allowWhileTyping();
        keymap.bind("Mod+R", PREFIX + "replace").allowWhileTyping();
        // IntelliJ's own accelerators, and the ones the tooltips name. `allowWhileTyping` because the whole
        // point of them is to be pressed while the caret is in the query.
        keymap.bind("Alt+C", PREFIX + "toggleMatchCase").allowWhileTyping();
        keymap.bind("Alt+W", PREFIX + "toggleWholeWords").allowWhileTyping();
        keymap.bind("Alt+X", PREFIX + "toggleRegex").allowWhileTyping();
        keymap.bind("Alt+E", PREFIX + "togglePreserveCase").allowWhileTyping();
        keymap.bind("Mod+Alt+E", PREFIX + "excludeMatch").allowWhileTyping();
        keymap.bind("Escape", PREFIX + "find.close").allowWhileTyping();
        keymap.bind("F3", PREFIX + "findNext").allowWhileTyping();
        keymap.bind("Shift+F3", PREFIX + "findPrevious").allowWhileTyping();
        keymap.bind("Mod+F3", PREFIX + "findWordUnderCaret");

        // BOTH vendors' keys, because the muscle memory people arrive with is genuinely split and each
        // costs one line. IntelliJ's F2/Shift+F2 and VS Code's F8/Shift+F8. F2 is Rename in VS Code, which
        // is not a conflict here because there is nothing to rename -- if a rename ever lands, F2 belongs
        // to it and this keeps F8.
        //
        // allowWhileTyping, like find: jumping between errors is exactly what you do WHILE editing, and a
        // bare function key carries no character so it cannot be swallowed as text.
        // Four binds rather than two bindAll calls: bindAll returns the Keymap for chaining more bindings,
        // so there is no single KeyBinding to mark. A bare function key has no non-shift modifier, which
        // is exactly what the typing guard blocks -- without allowWhileTyping these are dead in the one
        // widget they exist for.
        keymap.bind("F2", PREFIX + "nextProblem").allowWhileTyping();
        keymap.bind("F8", PREFIX + "nextProblem").allowWhileTyping();
        keymap.bind("Shift+F2", PREFIX + "previousProblem").allowWhileTyping();
        keymap.bind("Shift+F8", PREFIX + "previousProblem").allowWhileTyping();

        keymap.bind("Alt+Z", PREFIX + "toggleSoftWrap");

        // VS Code's folding chords verbatim. Mod+Shift+bracket for the region at the caret, Mod+K as a
        // prefix for the rest -- except that this keymap has no chord sequences, so the Mod+K family is
        // spelled as the single stroke it resolves to. Mod+Shift+Digit is IntelliJ's fold-to-level, which
        // VS Code spells Mod+K Mod+<digit>; the single-stroke form is the one that can be bound here.
        keymap.bind("Mod+Shift+LBracket", PREFIX + "fold");
        keymap.bind("Mod+Shift+RBracket", PREFIX + "unfold");
        keymap.bind("Mod+Shift+Multiply", PREFIX + "foldRecursively");
        // Both spellings of each, exactly as the zoom chords do it, and for the same reason: the numeric
        // keypad is a different key code and IntelliJ's own collapse-all/expand-all ARE the numpad pair.
        // One call rather than two, so the pair cannot drift apart when somebody edits the line they were
        // looking at.
        keymap.bindAll("Mod+Shift+Minus, Mod+Shift+Subtract", PREFIX + "foldAll");
        keymap.bindAll("Mod+Shift+Equals, Mod+Shift+Add", PREFIX + "unfoldAll");
        for (int level = 1; level <= 7; level++) {
            keymap.bind("Mod+Shift+" + level, PREFIX + "foldLevel" + level);
        }

        // VS Code's chords. Both spellings of each, because the numeric keypad is a different key code
        // and a laptop without one is the common case -- and one call rather than two, so the pair cannot
        // drift apart when somebody edits only the line they were looking at.
        keymap.bindAll("Mod+WheelUp, Mod+Equals, Mod+Add", PREFIX + "zoomIn");
        keymap.bindAll("Mod+WheelDown, Mod+Minus, Mod+Subtract", PREFIX + "zoomOut");
        keymap.bindAll("Mod+0, Mod+Numpad0", PREFIX + "zoomReset");

        // Undo is deliberately NOT re-bound here. UndoCommands declares its own chords, so Mod+Z reaches
        // edit.undo everywhere including inside an editor, and both routes end at UndoScope.nearest --
        // which from a focused editor is that editor's buffer. Binding it again on the editor would be a
        // second copy of one fact, and the copy would win: an element that binds a command explicitly
        // suppresses that command's declared default, so remapping undo would stop working in editors
        // and nowhere else.
    }


}
