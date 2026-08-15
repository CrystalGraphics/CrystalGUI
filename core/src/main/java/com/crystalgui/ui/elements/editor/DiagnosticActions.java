package com.crystalgui.ui.elements.editor;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionKind;

import java.util.ArrayList;
import java.util.List;

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

    private DiagnosticActions() {
    }

    /**
     * Everything offered about {@code problems} regardless of what they are.
     *
     * <p>Empty when there are no problems, which is the ordinary case: hovering a name that is perfectly
     * fine must not grow an actions row.</p>
     */
    static List<CodeAction> forProblems(List<Diagnostic> problems) {
        List<CodeAction> actions = new ArrayList<>();
        if (problems == null || problems.isEmpty()) return actions;
        // The action's id and the command's id are the same string here, and that is a coincidence of
        // this one action rather than a rule: an action names a row, a command names what running it
        // does, and the shape-derived contributors happen to be one row per command.
        actions.add(CodeAction.command(COPY_MESSAGE, "Copy problem message",
                CodeActionKind.SOURCE, COPY_MESSAGE));
        return actions;
    }

    /**
     * Runs one, against the editor it was offered in.
     *
     * <p>Dispatched here rather than through {@code CommandRegistry} because these are <b>about a
     * problem</b> and the registry resolves against the focused element, which during a hover is not
     * necessarily the editor. An id that is not one of ours returns false, so a future contributor can
     * route through the registry without this having to know.</p>
     */
    static boolean run(TextEditor editor, String commandId) {
        if (COPY_MESSAGE.equals(commandId)) {
            List<Diagnostic> problems = editor.diagnosticsAt(editor.getCaret());
            if (problems.isEmpty()) return false;
            StringBuilder text = new StringBuilder();
            for (Diagnostic problem : problems) {
                if (text.length() > 0) text.append('\n');
                text.append(problem.message());
            }
            CgPlatform.input().setClipboard(text.toString());
            return true;
        }
        return false;
    }
}
