package com.crystalgui.ui.elements.editor;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The actions that need no engine — keyed on a diagnostic's <b>shape</b> rather than its identity.
 *
 * <p>The third kind of contributor. A quick fix has to know what {@code IProblem.UnusedImport} means and
 * therefore has to live with the compiler that reported it; these read only whether a {@code code} is
 * present and whether there is {@code related} information, so they answer for GLSL, Java and anything
 * else without knowing any of them. A large share of IntelliJ's Alt+Enter list is exactly this.</p>
 *
 * <p><b>No table, and there never will be one.</b> That is the whole reason this is separate: an engine's
 * fixes grow one problem id at a time and are never finished, while these are complete the moment they are
 * written because they do not depend on which problem it is.</p>
 *
 * <p>They carry a command id rather than an edit, so {@link CodeAction#isApplicableTo} is trivially true
 * for them — a command names what it acts on rather than where, and has nothing to go stale.</p>
 */
final class DiagnosticActions {

    /** Puts the problem's message on the clipboard. */
    static final String COPY_MESSAGE = "problem.copyMessage";

    /** The argument {@link #COPY_MESSAGE} carries: the text to copy, decided when the action was offered. */
    static final String MESSAGE_ARGUMENT = "message";

    private DiagnosticActions() {
    }

    /**
     * Everything offered about {@code problems} regardless of what they are.
     *
     * <p>Empty when there are no problems, which is the ordinary case: hovering a name that is perfectly
     * fine must not grow an actions row.</p>
     *
     * <p><b>The message travels with the action.</b> It used to be re-read from the caret when the command
     * ran, and the popup this row sits in can be opened from an error-stripe mark or a hover nowhere near
     * the caret — so it copied whatever problem happened to be under the cursor instead of the one being
     * looked at. An action that carries what it is about cannot be wrong about it later.</p>
     */
    static List<CodeAction> forProblems(List<Diagnostic> problems) {
        List<CodeAction> actions = new ArrayList<>();
        if (problems == null || problems.isEmpty()) return actions;
        StringBuilder text = new StringBuilder();
        for (Diagnostic problem : problems) {
            if (text.length() > 0) text.append('\n');
            text.append(problem.message());
        }
        // The action's id and the command's id are the same string here, and that is a coincidence of
        // this one action rather than a rule: an action names a row, a command names what running it
        // does, and the shape-derived contributors happen to be one row per command.
        actions.add(CodeAction.command(COPY_MESSAGE, "Copy problem message", CodeActionKind.SOURCE,
                COPY_MESSAGE, Map.of(MESSAGE_ARGUMENT, text.toString())));
        return actions;
    }

    /**
     * Runs one, against the editor it was offered in.
     *
     * <p>Dispatched here rather than through {@code CommandRegistry} because these are <b>about a
     * problem</b> and the registry resolves against the focused element, which during a hover is not
     * necessarily the editor. An id that is not one of ours returns false, so a future contributor can
     * route through the registry without this having to know.</p>
     *
     * <p>Falls back to the caret only for an action built without the argument — one from before the
     * argument existed, or one somebody assembled by hand — so the fallback is a compatibility path and
     * not the mechanism.</p>
     */
    static boolean run(TextEditor editor, CodeAction action) {
        if (COPY_MESSAGE.equals(action.commandId())) {
            String text = action.arguments().get(MESSAGE_ARGUMENT);
            if (text == null) {
                List<Diagnostic> problems = editor.diagnosticsAt(editor.getCaret());
                if (problems.isEmpty()) return false;
                StringBuilder joined = new StringBuilder();
                for (Diagnostic problem : problems) {
                    if (joined.length() > 0) joined.append('\n');
                    joined.append(problem.message());
                }
                text = joined.toString();
            }
            CgPlatform.input().setClipboard(text);
            return true;
        }
        return false;
    }
}
