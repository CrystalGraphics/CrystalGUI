package com.crystalgui.ui.elements.editor;

import com.crystalgui.core.signal.ConnectionGroup;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.lang.CodeAction;
import com.crystalgui.text.lang.CodeActionProvider;
import com.crystalgui.text.lang.DeclarationSite;
import com.crystalgui.text.lang.SymbolInfo;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UIWindow;
import com.crystalgui.ui.elements.Menu;
import com.crystalgui.ui.elements.MenuItem;
import com.crystalgui.ui.elements.chrome.MenuBuilder;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * <b>What the engine can say about a position</b> — resolve, documentation, code actions,
 * go-to-definition.
 *
 * <p>Four features, one class, because they share the machinery that makes an asynchronous answer safe to
 * act on: the request serials below, and the two independent discards every callback applies. Split into
 * four they would each hold a copy of that, and the copies would drift on exactly the rule that must not.</p>
 *
 * <h3>A serial per DESTINATION, not per feature and not one for everything</h3>
 *
 * <p>The serial exists to drop an answer for a request the user has replaced — so two requests supersede
 * each other exactly when they would write to the same place. {@code Ctrl+Q} and hover share a lane because
 * they fill one popup and the later one genuinely replaces the earlier; go-to-definition has its own
 * because it moves the caret instead, and nothing about asking for documentation means you stopped wanting
 * the jump.</p>
 *
 * <p>This was <b>one</b> shared serial until hover arrived, on the reasoning that the user's last action
 * should win. Hover is what makes that wrong: it is ambient rather than an action, so a single lane let a
 * stray mouse movement one pixel after {@code Ctrl+B} silently eat the jump — and only sometimes, depending
 * on which resolve finished first.</p>
 *
 * <h3>Reports whether it ASKED, never whether anything arrived</h3>
 *
 * <p>{@link com.crystalgui.text.lang.Resolver} states as its contract that a superseded request's callback
 * may simply never fire, so anything keyed on an answer coming back would hang open on the one path
 * designed to produce silence.</p>
 */
final class EditorLanguageFeatures {

    private final TextEditor editor;

    /** When the popup opens and closes. @see HoverDocumentation */
    private final HoverDocumentation hover;

    EditorLanguageFeatures(TextEditor editor) {
        this.editor = editor;
        // IN THE CONSTRUCTOR, not as a field initializer -- those run before the body, so `editor` would
        // still be null and the hover would be built against nothing.
        this.hover = new HoverDocumentation(editor);
    }

    /** Documentation — {@code Ctrl+Q} and hover, which write to the same popup. */
    private static final int LANE_DOC = 0;
    /** Go-to-definition, which writes to the caret. */
    private static final int LANE_DEFINITION = 1;
    /** Code actions get a lane of their own, so a hover's request cannot cancel the palette's. */
    private static final int LANE_ACTIONS = 2;

    /**
     * The gutter bulb's own poll — and it must not share {@link #LANE_ACTIONS}.
     *
     * <p>A lane keeps only its newest request: the callback compares its serial against the lane's and
     * drops itself if anything asked later. The bulb asks whenever the caret moves, so on one lane it
     * would cancel the request Alt+Enter had in flight and the menu would simply never open — worst on a
     * slow answer, which is the case the whole asynchronous path exists for.</p>
     */
    static final int LANE_BULB = 3;

    private final int[] resolveSerials = new int[4];

    @Nullable
    private DocumentationPopup docPopup;

    private final ConnectionGroup popupActions = new ConnectionGroup();

    // ── Resolve ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Resolve the name at {@code offset} and hand the answer over, or report that nothing was asked.
     *
     * <p>Both discards live here rather than at each call site because they are one line each and silent
     * to omit: neither produces an error, both produce a confident answer about a position the user has
     * left.</p>
     *
     * @return whether a request was issued — false means no engine, which is the ordinary case
     */
    private boolean resolveAt(int lane, int offset, Consumer<SymbolInfo> onResolved) {
        if (editor.languageServices() == null) return false;
        final int serial = ++resolveSerials[lane];
        editor.languageServices().resolver().resolveAt(offset, answer -> {
            // AGAINST THE VERSION THE ANSWER ARRIVED AT, not the one it was asked at -- isFresh is an
            // equality, so comparing with the ask-time stamp would accept every answer it ever got and
            // the gate would read as present while doing nothing.
            if (serial != resolveSerials[lane] || answer == null
                    || !answer.isFresh(editor.buffer().version())) {
                return;
            }
            SymbolInfo symbol = answer.value();
            if (symbol != null) onResolved.accept(symbol);
        });
        return true;
    }

    /**
     * Resolve the name at the caret and go to where it is declared — {@code Ctrl+B}, and Ctrl+Click.
     *
     * <h3>Three ways this legitimately does nothing, and none of them is a failure</h3>
     *
     * <p>No engine is the three-tier absence rule and the ordinary case for a language that will never have
     * one. A null {@link SymbolInfo#declaration()} is {@link DeclarationSite}'s own documented ordinary
     * case — a member of a compiled class with no source attached, which is most of the JDK. And the
     * callback may simply never fire; see the class note.</p>
     *
     * @return whether a request was issued at all
     */
    boolean goToDefinition() {
        return resolveAt(LANE_DEFINITION, editor.getCaret(), symbol -> {
            DeclarationSite site = symbol.declaration();
            if (site == null) return;
            if (site.isSameDocument()) {
                editor.revealAt(site.start());
            } else {
                editor.onDefinitionChosen.emit(site);
            }
        });
    }

    // ── Code actions ────────────────────────────────────────────────────────────────────────────

    /**
     * Asks every contributor what can be done about the problems at {@code offset}.
     *
     * <p>The engine's answers and the ones that need no engine are <b>merged here</b>, because this is the
     * only place that can see both — a provider answers for itself and never for the list. See
     * {@link CodeActionProvider} for why nothing is asked to enumerate the whole set.</p>
     */
    boolean requestCodeActions(int offset, Consumer<List<CodeAction>> answer) {
        return requestCodeActions(LANE_ACTIONS, offset, answer);
    }

    /** @see #LANE_BULB for why the gutter bulb asks on a lane of its own. */
    boolean requestCodeActions(int lane, int offset, Consumer<List<CodeAction>> answer) {
        List<Diagnostic> problems = editor.diagnosticsAt(offset);
        List<CodeAction> shapeDerived = DiagnosticActions.forProblems(problems);
        if (editor.languageServices() == null) {
            if (!shapeDerived.isEmpty()) answer.accept(shapeDerived);
            return false;
        }
        final int serial = ++resolveSerials[lane];
        CodeActionProvider.Request request =
                CodeActionProvider.Request.at(offset, problems, editor.buffer().version());
        editor.languageServices().codeActions().actionsAt(request, reply -> {
            if (serial != resolveSerials[lane] || reply == null) return;
            List<CodeAction> merged = new ArrayList<>();
            // ONLY THE ENGINE'S HALF IS GATED. Its actions carry offsets from a parse that may have been
            // superseded; the shape-derived ones carry no edit at all and cannot go stale.
            if (reply.isFresh(editor.buffer().version()) && reply.value() != null) {
                merged.addAll(reply.value());
            }
            merged.addAll(shapeDerived);
            merged.sort(CodeAction.ORDER);
            answer.accept(merged);
        });
        return true;
    }

    /**
     * Applies one action — the only path, and the only place the version is re-checked.
     *
     * <p><b>Re-checked here rather than trusted from the request</b>, because an action is shown in a
     * popup and applied when the user gets round to pressing the key. An edit is a set of offsets, and
     * offsets into a document that has since been typed in still resolve — they name different text. So a
     * stale action does not fail, it silently edits the wrong place, and the gate is the difference
     * between a quick fix and a corruption.</p>
     *
     * <p>Bracketed by {@code breakUndoCoalescing} on both sides so the fix is exactly one entry in the
     * history: without the leading break it merges into the typing run before it, and without the trailing
     * one the next keystroke merges into the fix. Either way Ctrl+Z takes back half a fix.</p>
     *
     * @return false when the action could not be applied, which a caller should treat as "ask again"
     */
    boolean applyCodeAction(@Nullable CodeAction action) {
        if (action == null || editor.isReadOnly()) return false;
        if (!action.isApplicableTo(editor.buffer().version())) return false;
        if (action.commandId() != null && !DiagnosticActions.run(editor, action)) return false;
        ChangeSet edit = action.edit();
        if (edit == null) return action.commandId() != null;
        editor.buffer().breakUndoCoalescing();
        editor.buffer().edit(edit, editor.selections().all());
        editor.buffer().breakUndoCoalescing();
        return true;
    }

    /**
     * The full action list at an offset — Alt+Enter, and the popup's "More actions…".
     *
     * <p>A {@code Menu} rather than a list of its own, because that is what it is: rows with titles, an
     * accelerator column and a keyboard walk, all of which {@code MenuBuilder} and {@code Menu} already
     * do. A second list widget here would be a second set of the six rules {@code MenuBuilder} records,
     * and they were each learned from a bug.</p>
     *
     * @return whether anything was offered
     */
    boolean showCodeActionsAt(int offset) {
        UIWindow window = editor.getAttachedWindow();
        if (window == null) return false;
        float[] anchor = editor.anchorInWindow(offset);
        if (anchor == null) return false;
        requestCodeActions(offset, available -> {
            if (available.isEmpty()) return;
            Menu menu = new Menu();
            menu.addClass(TextEditor.CODE_ACTIONS_CLASS);
            for (CodeAction action : available) {
                MenuItem row = new MenuItem(action.title());
                if (action.preferred()) row.addClass(TextEditor.PREFERRED_ACTION_CLASS);
                row.onPressed.connect(() -> {
                    applyCodeAction(action);
                    menu.hide();
                });
                menu.addItem(row);
            }
            // PRESENTED THROUGH MenuBuilder, which is what attaches it and what drops it again when the
            // root closes by any route -- light dismiss, Escape, or choosing a row. Attaching it here
            // instead leaves one display:none menu in the tree per press.
            List<Menu> live = new ArrayList<>(MenuBuilder.present(menu, editor, window));
            menu.onClosed.connect(() -> MenuBuilder.discard(live));
            menu.showAt(anchor[0], anchor[1] + anchor[2], null);
        });
        return true;
    }

    // ── Documentation ───────────────────────────────────────────────────────────────────────────

    /** {@code Show on Mouse Move} — IntelliJ's own name for this, and on by default as it is there. */
    void setHoverEnabled(boolean enabled) {
        hover.setEnabled(enabled);
    }

    boolean isHoverEnabled() {
        return hover.isEnabled();
    }

    HoverDocumentation hover() {
        return hover;
    }

    /** The live Quick Documentation popup, or null. Exposed so a test can read it without pixels. */
    @Nullable
    DocumentationPopup documentationPopup() {
        return docPopup;
    }

    /**
     * Resolve at the caret and show the Quick Documentation popup — {@code Ctrl+Q}.
     *
     * <p>Opens on the answer rather than on the keystroke: an empty box that fills in 300ms later is worse
     * than one that appears once there is something in it, and a resolve that produces nothing should
     * produce nothing on screen too.</p>
     */
    boolean showQuickDocumentation() {
        return showDocumentationAt(editor.getCaret());
    }

    /**
     * The popup for what is at {@code offset} — the symbol if one resolves, the problems if any, or both.
     *
     * <h3>The problems do not wait for the resolve, and must not</h3>
     *
     * <p>This used to open only from inside the resolve callback, which fires <b>only when a symbol came
     * back</b>. So the one case where a problem popup is worth most — a name that resolves to nothing —
     * was the one case that showed nothing at all: hovering an unresolved {@code lenght()} gave a red
     * squiggle, a lightbulb in the gutter, a working Alt+Enter, and no hover popup, because the resolve
     * that never succeeded was gating the band that had nothing to do with it.</p>
     *
     * <p>They are independent sources and are now treated as such. Diagnostics are tracked ranges in the
     * buffer, known synchronously; a symbol comes from an engine and arrives whenever it arrives. So the
     * problem-only box opens immediately if there is a problem, and the resolve upgrades it in place when
     * it lands — which is also the right order for the slow case, since the message is the part you were
     * hovering for.</p>
     *
     * @return whether anything was shown or asked for
     */
    boolean showDocumentationAt(int offset) {
        UIWindow window = editor.getAttachedWindow();
        if (window == null) return false;
        float[] anchor = editor.anchorInWindow(offset);
        if (anchor == null) return false;

        List<Diagnostic> problems = editor.diagnosticsAt(offset);
        if (!problems.isEmpty()) {
            ensureDocPopup();
            docPopup.showProblemsAt(window, problems, anchor[0], anchor[1], anchor[2]);
            fillProblemSection(offset);
        }

        boolean asked = resolveAt(LANE_DOC, offset, symbol -> {
            UIWindow live = editor.getAttachedWindow();
            if (live == null) return;
            ensureDocPopup();
            float[] at = editor.anchorInWindow(offset);
            if (at == null) return;
            docPopup.show(live, symbol, at[0], at[1], at[2]);
            fillProblemSection(offset);
        });
        return asked || !problems.isEmpty();
    }

    /**
     * Fills the popup's problem band, and connects what it offers to what applies it.
     *
     * <p>Two passes on purpose. The problems are known synchronously — they are tracked ranges in the
     * buffer — so the band appears with the popup; the actions come from an engine and grow in when they
     * arrive. A hover that waited for the compiler would feel broken on exactly the file slow enough for
     * anyone to notice.</p>
     *
     * <p>The signals are re-connected per show, and disconnected first: the popup outlives any one symbol,
     * so a listener added per hover and never removed would apply the fix for a problem three hovers ago.</p>
     */
    private void fillProblemSection(int offset) {
        if (docPopup == null) return;
        List<Diagnostic> problems = editor.diagnosticsAt(offset);
        docPopup.setProblem(problems, List.of());
        // NO LONGER GATED ON A PROBLEM. It was, and that made an INTENTION unreachable from here: there
        // is no diagnostic behind "Replace with lambda", so the request was never made and the popup for
        // a convertible anonymous class showed a signature and nothing to do -- while the gutter bulb two
        // inches away said there was something. The same rule that had to change for the bulb, in the one
        // other place it was written down.
        popupActions.disconnectAll();
        popupActions.add(docPopup.onActionChosen.connect(action -> {
            if (applyCodeAction(action)) closeQuickDocumentation();
        }));
        popupActions.add(docPopup.onMoreActions.connect(() -> {
            closeQuickDocumentation();
            showCodeActionsAt(offset);
        }));
        // THE FOOTER'S PENCIL. The popup closes first: go-to-definition moves the caret, and a
        // documentation box left open over the destination describes the symbol you have just left.
        popupActions.add(docPopup.onGoToDeclaration.connect(() -> {
            closeQuickDocumentation();
            goToDefinition();
        }));
        requestCodeActions(offset, available -> {
            if (docPopup != null && docPopup.isOpen()) {
                docPopup.setProblem(problems, worthTheBand(problems, available));
            }
        });
    }

    /**
     * The actions worth a band in a HOVER — the rest belong to the bulb and to Alt+Enter.
     *
     * <h3>The band exists to say something</h3>
     *
     * <p>{@code FixContext.intention} already states the rule from the other end: a quick fix leaves its
     * description null because <em>the compiler has already said the useful thing</em>, and an intention
     * carries one because otherwise "the band draws as a blank grey strip, which reads as a message that
     * failed to load rather than as a message that does not exist". So an action with <b>neither</b> a
     * diagnostic behind it nor a line about itself has nothing to put there.</p>
     *
     * <p>The JavaScript catalog was written without that rule — its {@code refactor(id, title, edit)}
     * helper takes no description where Java's {@code intention(id, title, description, edit)} requires
     * one — and three of its entries apply to very nearly every line: "Change 'var' to 'let'", "Change
     * 'var' to 'const'" and "Surround with try/catch". A hover anywhere in a script therefore grew an
     * action bar with no message above it, which is the shape of a popup that failed rather than one with
     * nothing to add. IntelliJ keeps exactly that class of intention behind the bulb.</p>
     *
     * <p><b>Not gated on the diagnostic alone</b>, which is what this used to be and why it changed: there
     * is no diagnostic behind "Replace with lambda", so gating hid an inspection the gutter bulb two
     * inches away was advertising. A described action still shows; only the silent ones move.</p>
     */
    static List<CodeAction> worthTheBand(List<Diagnostic> problems, List<CodeAction> available) {
        if (!problems.isEmpty() || available.isEmpty()) return available;
        List<CodeAction> described = new ArrayList<>(available.size());
        for (CodeAction action : available) {
            if (action.description() != null && !action.description().isEmpty()) described.add(action);
        }
        return described;
    }

    /**
     * The problem popup for one diagnostic, anchored at a point in the window — what a stripe mark shows.
     *
     * <p>Anchored where the pointer is rather than at the problem's text, because the text is by
     * definition somewhere else: the whole value of the stripe is that it marks problems off screen.</p>
     */
    void showProblemPopupAt(Diagnostic problem, UIElement anchor) {
        UIWindow window = editor.getAttachedWindow();
        if (window == null || problem == null || anchor == null) return;
        ensureDocPopup();
        List<Diagnostic> problems = List.of(problem);
        docPopup.showProblems(window, problems, anchor);

        popupActions.disconnectAll();
        TrackedRange tracked = editor.trackedRangeFor(problem);
        int offset = tracked != null && !tracked.isRemoved()
                ? Math.min(tracked.from(), editor.buffer().length())
                : editor.offsetOfPoint(problem.start());
        popupActions.add(docPopup.onActionChosen.connect(action -> {
            if (applyCodeAction(action)) closeQuickDocumentation();
        }));
        popupActions.add(docPopup.onMoreActions.connect(() -> {
            closeQuickDocumentation();
            showCodeActionsAt(offset);
        }));
        requestCodeActions(offset, available -> {
            if (docPopup == null || !docPopup.isOpen()) return;
            docPopup.setProblem(problems, available);
            // RE-PLACED, because the actions row changes the box's width and the anchor is its RIGHT
            // edge. Without this the message alone is positioned and the fix row then grows off to the
            // left of where it was measured.
            docPopup.reposition();
        });
    }

    /**
     * The popup, built on first use and told what language its code samples are in.
     *
     * <p>Three call sites created it identically and none of them said that second part, which is the
     * reason to have one: a doc comment's {@code <pre>} samples are in the language of the file that
     * carries the comment, so the answer is the editor's own — and setting it here rather than at
     * construction means it cannot go stale against an editor whose language was set after the popup
     * first appeared.</p>
     */
    private DocumentationPopup ensureDocPopup() {
        if (docPopup == null) docPopup = new DocumentationPopup();
        docPopup.setCodeLanguage(editor.language());
        return docPopup;
    }

    /** Closes the documentation popup if it is open. */
    void closeQuickDocumentation() {
        hover.forget();
        if (docPopup != null && docPopup.isOpen()) docPopup.hide();
    }
}
