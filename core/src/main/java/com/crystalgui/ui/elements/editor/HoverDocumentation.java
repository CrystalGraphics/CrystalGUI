package com.crystalgui.ui.elements.editor;

import com.crystalgui.text.WordOperations;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.text.wrap.ProjectedLines;

/**
 * The hover trigger for the Quick Documentation popup — <b>when</b> it opens and closes, and nothing
 * about what is in it.
 *
 * <h3>A collaborator beside the editor, not a view part</h3>
 *
 * <p>It draws nothing, so it is not an {@link EditorViewPart}; it is the shape {@code CompletionSession}
 * already has — a piece of the editor living in its package and reaching it through package-private
 * accessors. It came out of {@code TextEditor} because a rest timer, a grace timer, a stickiness rule and
 * a word-under-pointer query are a self-contained mechanism, and the widget was already the largest class
 * in the package before it arrived.</p>
 *
 * <h3>Every rule here exists because its absence was visible</h3>
 *
 * <ul>
 *   <li><b>A delay</b>, or crossing a line of code strobes popups.</li>
 *   <li><b>The delay does not restart within one word</b>, or the box appears only if the pointer is
 *       perfectly still — which reads as the feature working intermittently.</li>
 *   <li><b>A grace before hiding</b>, because the box opens <em>below</em> the token: reaching for it
 *       leaves the token immediately, and the editor's {@code Leave} and the popup's {@code Enter} are
 *       two dispatches whose order within a frame is not something to depend on.</li>
 *   <li><b>Nothing hides on a move at all.</b> Every route to the popup crosses the next line of code,
 *       and that line has words on it — so "the pointer is over a different word now" closed the box
 *       about a third of the way down its own top border. The old box is replaced when a new lookup
 *       actually fires, which is the one moment it is genuinely wrong.</li>
 * </ul>
 */
final class HoverDocumentation {

    private final TextEditor editor;

    HoverDocumentation(TextEditor editor) {
        this.editor = editor;
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0f;
    }

        /**
     * How long the pointer must rest on a token before its documentation appears.
     *
     * <p>Long enough that crossing a line of code does not strobe popups, short enough that resting on a
     * name feels like asking. VS Code's {@code editor.hover.delay} defaults to 300ms and IntelliJ's is in
     * the same range; this sits between them.</p>
     */
    private static final float DELAY_SECONDS = 0.4f;

    /**
     * How long the popup survives the pointer leaving the word.
     *
     * <p><b>This grace is what makes the popup reachable at all</b>, and it is not a nicety. The box opens
     * below the token, so moving the pointer towards it leaves the token immediately — and the editor's
     * {@code Leave} and the popup's {@code Enter} are two separate dispatches whose order within a frame is
     * not something to depend on. Hiding on {@code Leave} means the popup vanishes the instant you reach
     * for it, every time; a grace makes the two orderings indistinguishable. VS Code's
     * {@code editor.hover.sticky} is the same idea with a different name.</p>
     */
    private static final float GRACE_SECONDS = 0.25f;

    private boolean enabled = true;
    private int wordUnderPointer = -1;
    private int shownFor = -1;
    private float rest;
    private float grace;

    /** {@code Show on Mouse Move} — IntelliJ's own name for this, and on by default as it is there. */
    void setEnabled(boolean value) {
        this.enabled = value;
        if (!value) editor.closeQuickDocumentation();
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * The start of the word under a point, or -1 when the pointer is not over one.
     *
     * <p><b>The past-the-end check is the whole difficulty.</b> {@code offsetAtLocal} clamps to the
     * nearest position by design — that is what makes clicking in the blank area right of a line put the
     * caret at its end — so without a horizontal bound every pixel to the right of a short line reports
     * that line's last token. The result is a documentation popup for {@code foo} while the pointer sits
     * in empty space two hundred pixels away, which reads as the popup being stuck rather than as a
     * hit-testing question.</p>
     */
    private int wordStartAt(float localX, float localY) {
        if (editor.viewLineCount() <= 0 || editor.buffer().length() == 0) return -1;
        int offset = editor.offsetAtLocal(localX, localY);
        int viewLine = editor.viewLineOf(offset, LineProjection.Affinity.RIGHT);
        ProjectedLines.ModelPosition model = editor.modelAt(viewLine);
        LineProjection projection = editor.projectionAt(viewLine);
        float endX = editor.textOriginX() + editor.xOfView(viewLine, projection.viewLineEnd(model.viewLineInRow()))
                - finiteOrZero(editor.getScrollLeft());
        if (localX > endX) return -1;
        int[] word = WordOperations.wordAt(editor.buffer().document(), offset, editor.wordClassifier());
        if (word == null || word[1] <= word[0]) return -1;
        return word[0];
    }

    /**
     * Re-reads what the pointer is over. Called from the move listener rather than the ticker, because a
     * timer should measure how long the pointer has been <em>still</em> and only a move can restart that.
     */
    void pointerMoved() {
        if (suppressed()) {
            cancel();
            return;
        }
        overWord(wordStartAt(editor.pointerX(), editor.pointerY()));
    }

    /** No hover while a drag is editor.isSelecting(), while completion owns the caret, or when it is switched off. */
    private boolean suppressed() {
        return !enabled || editor.isSelecting() || editor.completionSession() != null;
    }

    private void overWord(int word) {
        // THE SAME WORD IS NOT A NEW HOVER. Resetting on every move event would mean the delay never
        // elapses while the pointer drifts a pixel at a time over a long identifier -- so the popup would
        // appear only if you held perfectly still, which reads as it working intermittently.
        if (word == wordUnderPointer) return;
        wordUnderPointer = word;
        rest = 0f;
        // NOTHING IS HIDDEN HERE, on any path. A move only ever RE-AIMS the timer.
        //
        // Hiding on "the pointer is over a different word now" is the obvious rule and it makes the popup
        // unreachable, because the box opens BELOW the token: every route to it crosses the next line of
        // code, and that line has words on it. So reaching for the popup read as "you asked about
        // something else" and closed it about a third of the way down the top border -- which is exactly
        // where the pointer stops being over the token's line and starts being over the line beneath.
        //
        // Hiding on "over no word" fails the same way through the gaps between tokens.
        //
        // So the box now survives the whole traversal and is replaced only when a NEW lookup actually
        // fires -- see tickHoverDocumentation, which hides immediately before asking. The pointer
        // crosses a line in a few frames and the delay is 400ms, so nothing fires in transit.
    }

    /**
     * Test seam: report the pointer as resting on the word containing {@code offset}, or nowhere for -1.
     *
     * <p>Takes an <b>offset</b> rather than a word start, so the "is this still the same word" rule is the
     * real one — handing it a word start directly would make two points inside one identifier look like
     * two different hovers and the test would pass against a broken timer. The geometry that normally
     * produces the offset is {@link #wordUnderPointerAt}, which is covered from local coordinates.</p>
     */
    void pointerForTest(int offset) {
        if (suppressed()) {
            cancel();
            return;
        }
        if (offset < 0) {
            overWord(-1);
            return;
        }
        int[] word = WordOperations.wordAt(editor.buffer().document(), offset, editor.wordClassifier());
        overWord(word == null || word[1] <= word[0] ? -1 : word[0]);
    }

    /**
     * The start of the word at a point in this element's own coordinates, or -1 for none — the real
     * geometry the hover path runs, exposed because "is the pointer over a token" is the half of it that
     * a timing test cannot reach and that silently answers yes for empty space.
     */
    int wordStartAtForTest(float localX, float localY) {
        return wordStartAt(localX, localY);
    }

    void cancel() {
        wordUnderPointer = -1;
        rest = 0f;
    }

    /**
     * Hides only what HOVER opened.
     *
     * <p>A popup opened with {@code Ctrl+Q} is a deliberate request and must not be dismissed by the
     * pointer wandering off a word it was never anchored to — {@code shownFor} is what tells the two
     * apart. Escape and a press outside still close either, through the popover stacks.</p>
     */
    /** Forgets what hover opened, without touching a Ctrl+Q popup. */
    void forget() {
        shownFor = -1;
        grace = 0f;
    }

    void hide() {
        if (shownFor < 0) return;
        shownFor = -1;
        grace = 0f;
        if (editor.documentationPopup() != null && editor.documentationPopup().isOpen()) editor.documentationPopup().hide();
    }

    void tick(float deltaSeconds) {
        if (!enabled) return;

        // STICKY: the pointer being inside the popup is the one state where nothing should be counted.
        if (shownFor >= 0 && editor.documentationPopup() != null && editor.documentationPopup().isPointerOver()) {
            grace = 0f;
            return;
        }

        if (wordUnderPointer >= 0 && shownFor != wordUnderPointer) {
            rest += deltaSeconds;
            if (rest >= DELAY_SECONDS) {
                rest = 0f;
                int word = wordUnderPointer;
                // CLEARED WHEN THE NEW LOOKUP FIRES, not when the pointer moved. This is the one moment
                // the old box is genuinely wrong -- it describes a word we have stopped asking about --
                // and doing it here rather than on the move is what lets the pointer travel to the popup
                // without destroying it on the way.
                //
                // Before the request, so a word that resolves to nothing leaves an empty popup rather
                // than the previous symbol's, which would be a confident answer to a question nobody
                // asked. @see Resolver, whose callback may never fire at all.
                hide();
                shownFor = word;
                grace = 0f;
                editor.showDocumentationAt(word);
            }
            return;
        }

        if (shownFor >= 0 && wordUnderPointer < 0) {
            grace += deltaSeconds;
            if (grace >= GRACE_SECONDS) hide();
        }
    }
}
