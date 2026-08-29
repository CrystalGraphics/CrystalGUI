package com.crystalgui.ui.elements.editor;

import com.crystalgui.text.lang.CompletionItem;
import com.crystalgui.text.lang.CompletionProvider;
import com.crystalgui.ui.UIWindow;
import com.crystalgraphics.platform.input.CgKeyCodes;

import org.jetbrains.annotations.Nullable;

/**
 * <b>Completion</b> — the live session, the list, and the four keys it owns.
 *
 * <p>The session itself is {@link CompletionSession} and the list is {@link CompletionPopup}; what lives
 * here is the part that has to be the editor's — when to open, when to close, where to point the box, and
 * which keystrokes a live list is allowed to take out of the widget's hands.</p>
 *
 * <h3>The keys are deliberately few</h3>
 *
 * <p>Down, Up, Escape and the accept keys, and nothing else. Every key taken here is a key that stops doing
 * its normal job while a popup is open, and the popup is open more often than the user is thinking about
 * it — the same reasoning that keeps a search box from taking Left, Right, Home and End. The accept set is
 * read from {@link CompletionPopup#ACCEPT_KEYS}, so the hint strip at the list's foot cannot promise a key
 * this does not take.</p>
 */
final class EditorSuggest {

    private final TextEditor editor;

    EditorSuggest(TextEditor editor) {
        this.editor = editor;
    }

    @Nullable
    private CompletionSession completion;

    @Nullable
    private CompletionPopup popup;

    /** The live session, or null. Exposed so a test can assert on the model without going through pixels. */
    @Nullable
    CompletionSession session() {
        return completion;
    }

    /** The popup, built on first use. Null until a session has opened in an attached window. */
    @Nullable
    CompletionPopup popup() {
        return popup;
    }

    /**
     * Opens a completion session at the caret — Ctrl+Space, or a trigger character.
     *
     * <h3>Grammar-level suppression first (§18.1)</h3>
     *
     * <p>No session inside a comment or a string. It is one tokenizer query and it is the cheapest
     * wrong-popup filter there is: without it, typing a {@code .} in a javadoc sentence or a file path opens
     * a member list over prose. Asked of the <em>grammar</em> rather than the engine because the engine is
     * 300ms behind and this has to answer on the keystroke.</p>
     *
     * @return false when nothing opened — no engine, or the caret is somewhere completion has no business
     */
    boolean open(CompletionProvider.TriggerKind trigger, @Nullable String triggerCharacter) {
        if (TRACE) trace("open trigger=" + trigger + " char=" + triggerCharacter + " caret=" + editor.getCaret());
        if (editor.languageServices() == null) {
            if (TRACE) trace("  refused: no language services");
            return false;
        }
        if (editor.isInCommentOrString(editor.getCaret())) {
            if (TRACE) trace("  refused: the caret is in a comment or a string");
            return false;
        }

        close();
        CompletionSession opened = CompletionSession.open(editor.buffer(),
                editor.languageServices().completion(), editor.getCaret(), trigger, triggerCharacter);
        if (opened == null) {
            if (TRACE) trace("  refused: the provider produced no session");
            return false;
        }
        completion = opened;
        opened.caretMoved(editor.getCaret());
        opened.onClosed.connect(() -> {
            if (completion != opened) return;
            completion = null;
            // AND THE POPUP GOES WITH IT. A session closes ITSELF -- a refilter that leaves no rows ends
            // one -- and forgetting the field is not the same as taking the widget off screen: the popup
            // stayed, empty but for its hint strip, over the line being typed. Escape then did nothing,
            // which reads as a second bug and is this one: `handleKey` returns early once the session is
            // gone, so the key that exists to dismiss it could no longer reach it. Only the explicit
            // `close()` path hid the popup, and that is the path this case never takes.
            if (popup != null) popup.detach();
        });

        if (TRACE) trace("  opened rows=" + opened.visibleRows().size() + " closed=" + opened.isClosed());
        UIWindow window = editor.getAttachedWindow();
        if (TRACE && window == null) trace("  no window, so no popup is shown");
        if (window != null) {
            if (popup == null) {
                popup = new CompletionPopup();
                popup.onRowClicked.connect(index -> {
                    if (completion == null) return;
                    completion.setSelectedIndex(index);
                    accept(false);
                    // FOCUS BACK, on the mouse-DOWN this arrived from. emitMouseDown blurs before it
                    // dispatches, so without this the editor is left unfocused after a click-accept and the
                    // next keystroke goes nowhere -- the caret is still drawn, which makes it look like the
                    // editor simply stopped responding.
                    UIWindow attached = editor.getAttachedWindow();
                    if (attached != null) attached.getInputHandler().requestPointerFocus(editor);
                });
            }
            updateAnchor();
            popup.attach(window, opened);
        }
        return true;
    }

    void close() {
        if (completion != null) completion.close();
        if (popup != null) popup.detach();
    }

    /**
     * Asks for completions here, whether or not a list is already open.
     *
     * <p>Re-asking is how you get a full list after a trigger character gave you a narrow one, which is
     * why this is not a toggle. Bound to {@code Mod+Space} through the keymap like every other named
     * action — it used to be matched inside {@link #handleKey}, which made it the one chord in the widget
     * that could not be rebound or listed.</p>
     */
    void trigger() {
        open(CompletionProvider.TriggerKind.EXPLICIT, null);
    }

    /**
     * Traces every step of opening a list, behind {@code -Dcrystalgui.completion.trace=true}.
     *
     * <p>The four ways a member list ends up empty are indistinguishable on screen AND in a test, which is
     * what made one report cost three wrong diagnoses. This prints the chain: which branch the keystroke
     * took, what the session anchored on, what the provider answered, and what survived the filter.</p>
     *
     * <p>Property-gated rather than removed after use: a completion runs on every keystroke, so the cost
     * of leaving it in is one boolean read, and the cost of taking it out is writing it again next time.</p>
     */
    static final boolean TRACE = Boolean.getBoolean("crystalgui.completion.trace");

    /**
     * <b>Guard at the CALL SITE, always</b> — {@code if (TRACE) trace(...)}.
     *
     * <p>The check here is a backstop, not the mechanism. Java evaluates arguments before the call, so a
     * bare {@code trace("caret=" + caret)} concatenates on every keystroke whether or not anything is
     * listening — and one of these asks for {@code visibleRows()}, which BUILDS A LIST. Completion runs on
     * the typing hot path, which is the one place a disabled diagnostic must cost nothing at all.</p>
     */
    static void trace(String message) {
        if (TRACE) System.err.println("[completion] " + message);
    }

    /** The keys a live list owns, and no others. @see EditorSuggest */
    boolean handleKey(int key, int modifiers) {
        if (completion == null || completion.isClosed()) {
            if (key == CgKeyCodes.KEY_ESCAPE) {
                if (TRACE) trace("escape ignored: session=" + (completion == null ? "null" : "closed"));
            }
            return false;
        }

        if (key == CgKeyCodes.KEY_DOWN) {
            completion.moveSelection(1);
            return true;
        }
        if (key == CgKeyCodes.KEY_UP) {
            completion.moveSelection(-1);
            return true;
        }
        // FROM THE POPUP'S OWN TABLE, so the strip at its foot cannot promise a key this does not take.
        for (CompletionPopup.AcceptKey accept : CompletionPopup.ACCEPT_KEYS) {
            if (key == accept.keyCode()) return accept(accept.replaces());
        }
        if (key == CgKeyCodes.KEY_ESCAPE) {
            close();
            return true;
        }
        return false;
    }

    /** Applies the selected item and puts the caret after what was inserted. */
    private boolean accept(boolean replace) {
        if (completion == null) return false;
        CompletionItem item = completion.selectedItem();
        if (item == null) {
            close();
            return false;
        }
        int caretAfter = completion.caretAfterAccept(item, editor.getCaret());
        // The accept is ONE ChangeSet, so this is one undo step -- the name and the import it brought go
        // together on Ctrl+Z. See CompletionSession.accept.
        completion.accept(replace);
        editor.setCaret(Math.max(0, Math.min(caretAfter, editor.buffer().length())));
        close();
        return true;
    }

    /** Whether a session is open and has not closed itself. */
    boolean isLive() {
        return completion != null && !completion.isClosed();
    }

    /**
     * Asks the engine again for a live session — what a fresh analysis landing means for an open list.
     *
     * <p>A list opened against the previous analysis may have been unable to resolve its receiver at all,
     * so it is sitting there empty. Without this it stays empty until the next keystroke.</p>
     */
    void retrigger() {
        if (isLive()) completion.retrigger();
    }

    /**
     * Keeps a live session in step with the caret, and re-anchors the popup.
     *
     * <p>Called from the one place the caret settles rather than subscribed to the buffer: a session must
     * end on a plain arrow-key move, which changes no text and would therefore never reach a buffer
     * listener.</p>
     */
    void caretMoved() {
        if (completion == null || completion.isClosed()) return;
        completion.caretMoved(editor.getCaret());
        updateAnchor();
    }

    /**
     * Points the popup at the <b>word being completed</b>, in window coordinates.
     *
     * <p>The word, not the caret — anchored to the caret the list steps right one character per keystroke,
     * which reads as the popup running away from the word it is completing.</p>
     *
     * <p>The conversion itself is {@code TextEditor.anchorInWindow}, which is shared with the documentation
     * popup and carries the long note about why it sums the <b>layout</b> chain rather than reaching for
     * {@code localToWorld}. Two copies of that would be two chances to reach for the transform chain, which
     * is in surface pixels and is only populated once the element has painted.</p>
     */
    void updateAnchor() {
        if (popup == null || completion == null) return;
        float[] anchor = editor.anchorInWindow(completion.replacementStart());
        if (anchor == null) return;
        popup.setAnchor(anchor[0], anchor[1], anchor[2]);
    }
}
