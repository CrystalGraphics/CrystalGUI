package com.crystalgui.ui.elements.editor;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Change;
import com.crystalgui.text.Selection;
import com.crystalgui.text.SelectionModel;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.ui.text.HighlightRegistry;
import com.crystalgui.ui.text.TextRange;
import com.crystalgui.text.TextPoint;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A multi-line plain-text editor over a {@link TextBuffer}.
 *
 * <p>P6.1.6's widget half. The document, undo and coordinate conversion all live in
 * {@code com.crystalgui.text} and are testable with no window at all; this class is the view — carets,
 * selection, navigation, and rendering only the lines that are on screen.</p>
 *
 * <h3>Deliberately not an extension of {@code TextField}</h3>
 * <p>That widget's caret and selection are single-line <em>by construction</em>: its caret is an index
 * into one string and its horizontal scroll is a single offset. Generalising it would mean rewriting
 * every method on a widget that is still in use everywhere. A shared {@code EditableText} seam underneath
 * both is worth considering now that there are two — but after they both exist, not by growing one into
 * the other.</p>
 *
 * <h3>Why it virtualises its own lines instead of extending {@code ListView}</h3>
 * <p>{@code ListView} windows over an {@code ObservableList}. An editor's rows are <em>derived</em> from a
 * rope, and mirroring them into a list would be a second copy of the document that can drift from the
 * first — the precise failure the buffer exists to prevent. The windowing loop below is the same shape as
 * {@code ListView.updateWindow} and is knowingly duplicated; the seam worth extracting is
 * "window over N fixed-height rows", and it becomes worth extracting when 6.1.7's gutter needs a third
 * copy of it.</p>
 *
 * <h3>Soft wrap is deliberately absent, not stubbed</h3>
 * <p>Wrapping makes a line occupy a variable number of visual rows, so the window can no longer be
 * derived by dividing a scroll offset by a row height — it needs the variable-height virtualisation that
 * 6.1.3 explicitly deferred. There is therefore <b>no {@code setSoftWrap}</b>: a toggle that silently did
 * nothing would be worse than an absent one, and this engine has already paid for that lesson once with
 * highlight properties that resolved and never painted.</p>
 */
public class TextEditor extends ScrollerView implements UndoScope {

    public static final String LINE_CLASS = "__line__";
    public static final String CARET_CLASS = "__caret__";
    public static final String SELECTION_CLASS = "__selection__";
    public static final String GUTTER_CLASS = "__gutter__";
    public static final String LINE_NUMBER_CLASS = "__line-number__";
    public static final String CURRENT_LINE_CLASS = "__current-line__";

    /** Rows kept realised beyond the viewport, so a scroll does not expose an unpainted band. */
    private static final int OVERSCAN = 3;

    /** Emitted after any edit, with the whole document. */
    public final Signal.Value<String> onChanged = new Signal.Value<>();

    /** Emitted when the caret or selection moves. */
    public final Signal.Action onSelectionChanged = new Signal.Action();

    /** Emitted when the set of realised lines changes — i.e. on a scroll that moves the window. */
    public final Signal.Action onWindowChanged = new Signal.Action();

    private final TextBuffer buffer;

    private final Map<Integer, UIElement> realisedLines = new HashMap<>();
    private final Deque<UIElement> linePool = new ArrayDeque<>();
    private final List<UIElement> selectionBands = new ArrayList<>();

    /** One element per caret, pooled — a multi-caret edit that shrinks back to one must not churn them. */
    private final List<UIElement> caretElements = new ArrayList<>();

    /**
     * The gutter, and the line numbers inside it.
     *
     * <p><b>Scroll-exempt, with its numbers positioned by hand.</b> The gutter must hold still
     * horizontally while scrolling vertically with the text, and a scroll offset in this engine is a pose
     * translate applied to every non-exempt child — it cannot apply on one axis only. So the gutter opts
     * out of both and subtracts {@code scrollTop} itself. Letting it scroll normally would slide the
     * numbers sideways the moment a line is wider than the viewport.</p>
     */
    private final UIElement gutter = new UIElement();
    private final List<UIElement> lineNumbers = new ArrayList<>();
    private boolean gutterVisible = true;
    private float gutterWidth;

    /** A band behind the primary caret's row. An ordinary child, so it scrolls with the text. */
    private final UIElement currentLine = new UIElement();

    /**
     * The language, or {@link SyntaxTokenizer#NONE}.
     *
     * <p>The editor knows nothing about any language: it asks for named ranges over the rows it is about
     * to draw and publishes them under those names. What a {@code "keyword"} looks like is a stylesheet's
     * business, through {@code ::highlight(keyword)}.</p>
     */
    private SyntaxTokenizer tokenizer = SyntaxTokenizer.NONE;
    private int highlightedFrom = -1;
    private int highlightedTo = -1;
    private boolean highlightsDirty = true;

    /** Search hits, in document offsets. Published under {@code ::highlight(search)}. */
    private final List<TextRange> searchMatches = new ArrayList<>();
    private int currentMatch = -1;
    private String lastQuery = "";
    private boolean lastQueryCaseSensitive;

    /** The two bracket positions when the caret is on a bracket, or {@code null}. */
    private int[] bracketPair;

    /** One indent level, in spaces. */
    private int indentWidth = 4;

    private int firstRealised = -1;
    private int lastRealised = -1;

    /**
     * Every caret in the document, sorted and non-overlapping.
     *
     * <p>A list rather than a pair of offsets from the outset. Retrofitting it would mean rewriting every
     * movement method here, since each of them reads and writes the caret directly — and the layer below
     * is already built for it: several non-overlapping changes in one {@link ChangeSet} is precisely what
     * a multi-caret edit is, and {@code ChangeSet.of} refuses overlaps, which is the same invariant
     * {@link SelectionModel} maintains.</p>
     */
    private final SelectionModel selections = new SelectionModel();

    /**
     * The column vertical movement aims for, or -1 to take it from the caret.
     *
     * <p>Without it, moving down through a short line and back up lands somewhere other than where it
     * started — the caret is dragged inward by the short line and has forgotten where it came from. Every
     * editor keeps this, and it is reset by any horizontal movement or edit.</p>
     */
    private int preferredColumn = -1;

    private boolean selecting;

    /**
     * Blink period in seconds — one full off-and-on cycle. Chromium's is 1.06s; the exact number is less
     * important than being slow enough not to distract and fast enough to read as a caret.
     */
    private float blinkPeriodSeconds = 1.06f;
    private float blinkClock;
    private boolean caretShown = true;

    // Measurement cache, keyed by the text it measured — see prefixWidths.
    private final Map<Integer, float[]> measuredRows = new HashMap<>();
    private String measuredFontKey = "";

    public TextEditor() {
        this("");
    }

    public TextEditor(String text) {
        this.buffer = new TextBuffer(text);
        // NOT markAsInternal() -- that marks THIS element as somebody's internal part, hiding the whole
        // widget from traversal, focus and the description codec. The idiom applies to a widget's pieces
        // (the lines, the caret, the selection bands below), never to the widget itself. ListView made
        // exactly this mistake once already.
        setFocusPolicy(FocusPolicy.CLICK);


        currentLine.addClass(CURRENT_LINE_CLASS);
        currentLine.setHitTest(false);
        currentLine.markAsInternal();
        addInternalChild(currentLine);

        gutter.addClass(GUTTER_CLASS);
        gutter.setHitTest(false);
        gutter.markAsInternal();
        gutter.setScrollExempt(true);
        addInternalChild(gutter);

        buffer.onChanged.connect(change -> {
            // The tokenizer hears about the edit BEFORE the next query, so an incremental one can update
            // what it holds. Applying the edit is cheap and must be synchronous; the expensive reparse is
            // the implementation's business. See SyntaxTokenizer#edited.
            tokenizer.edited(buffer.document(), change);
            highlightsDirty = true;
            measuredRows.clear();
            invalidateWindow();
            onChanged.emit(buffer.toString());
        });

        installInput();
    }

    // ── Document ────────────────────────────────────────────────────────────────────────────────

    /**
     * This editor's history — its buffer's.
     *
     * <p>Implementing {@link UndoScope} is what puts the document on the map for {@code edit.undo}: a
     * menu item, the command palette and a remapped keystroke all reach the same stack this editor's own
     * Ctrl+Z does, rather than the keyboard path being the only way in. The editor still handles Ctrl+Z
     * itself and consumes the key, so the two never both fire.</p>
     */
    @Override
    public UndoStack undoStack() {
        return buffer.history();
    }

    public TextBuffer buffer() {
        return buffer;
    }

    public String getText() {
        return buffer.toString();
    }

    public TextEditor setText(String text) {
        buffer.replace(0, buffer.length(), text == null ? "" : text);
        buffer.breakUndoCoalescing();
        setSelection(0, 0);
        return this;
    }

    // ── Caret and selection ─────────────────────────────────────────────────────────────────────

    /** The primary caret — the one that scrolls into view and that single-caret API reports. */
    public int getCaret() {
        return selections.primary().head();
    }

    public int getAnchor() {
        return selections.primary().anchor();
    }

    public int getSelectionStart() {
        return selections.primary().start();
    }

    public int getSelectionEnd() {
        return selections.primary().end();
    }

    /** True when <b>any</b> caret has a range, not merely the primary one. */
    public boolean hasSelection() {
        return selections.hasSelection();
    }

    /** Every caret, sorted and non-overlapping. */
    public SelectionModel selections() {
        return selections;
    }

    public int caretCount() {
        return selections.count();
    }

    /**
     * The selected text.
     *
     * <p>With several carets the ranges are joined by newlines, which is what every editor puts on the
     * clipboard — and what makes a multi-caret copy paste back into a multi-caret paste sensibly.</p>
     */
    public String getSelectedText() {
        StringBuilder out = new StringBuilder();
        for (Selection selection : selections.all()) {
            if (selection.isEmpty()) continue;
            if (out.length() > 0) out.append('\n');
            out.append(buffer.document().slice(selection.start(), selection.end()));
        }
        return out.toString();
    }

    /** Adds another caret, leaving the existing ones in place. */
    public TextEditor addCaret(int offset) {
        selections.add(Selection.caret(clampOffset(offset)));
        afterSelectionChange();
        return this;
    }

    /** Drops every caret but the primary — what Escape does. */
    public TextEditor collapseCarets() {
        if (!selections.isMultiple()) return this;
        selections.collapseToPrimary();
        afterSelectionChange();
        return this;
    }

    private int clampOffset(int offset) {
        return Math.max(0, Math.min(offset, buffer.length()));
    }

    /** Moves the caret, collapsing the selection to it. */
    public TextEditor setCaret(int offset) {
        return setSelection(offset, offset);
    }

    /** Collapses to a single selection. Every plain click and most API calls do this. */
    public TextEditor setSelection(int anchorOffset, int caretOffset) {
        Selection wanted = new Selection(clampOffset(anchorOffset), clampOffset(caretOffset));
        if (!selections.isMultiple() && selections.primary().equals(wanted)) return this;
        selections.set(wanted);
        afterSelectionChange();
        return this;
    }

    public TextPoint caretPoint() {
        return buffer.offsetToPoint(getCaret());
    }

    /** The shared tail of every selection change: end the undo run, re-place the carets, repaint. */
    private void afterSelectionChange() {
        buffer.breakUndoCoalescing();
        updateBracketMatch();
        highlightsDirty = true;
        restartCaretBlink();
        layOutCaretAndSelection(firstRealised, lastRealised);
        markTreeDirty();
        onSelectionChanged.emit();
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    /**
     * Replaces every selection (or inserts at every caret) with {@code text}.
     *
     * <p><b>One {@link ChangeSet} for all of them, not one edit per caret.</b> Applying them one at a time
     * would invalidate the later offsets after the first, and would put each caret's edit on the undo
     * stack separately — so a single keystroke at five carets would take five undos to reverse. As one
     * change set it is one edit, one undo step, and every caret is carried through it by the same mapping
     * that carries an anchor.</p>
     */
    public void insertAtCaret(String text) {
        List<Change> changes = new ArrayList<>(selections.count());
        for (Selection selection : selections.all()) {
            changes.add(new Change(selection.start(), selection.end(), text));
        }
        applyEdit(changes);
    }

    /**
     * Deletes at every caret. {@code from}/{@code to} are used only for a <em>single</em> empty caret —
     * the backspace-and-delete case, where the range depends on which key was pressed.
     */
    private void deleteSelectionOr(int from, int to) {
        List<Change> changes = new ArrayList<>(selections.count());
        boolean anySelection = selections.hasSelection();
        for (Selection selection : selections.all()) {
            if (anySelection) {
                if (!selection.isEmpty()) changes.add(Change.delete(selection.start(), selection.end()));
            } else {
                // Every empty caret deletes the same way the pressed key says, relative to itself.
                int offset = selection.head();
                int delta = to - from;
                int start = from <= selections.primary().head() && to <= selections.primary().head()
                        ? offset - delta : offset;
                int end = start + delta;
                if (start >= 0 && end <= buffer.length() && end > start) {
                    changes.add(Change.delete(start, end));
                }
            }
        }
        applyEdit(changes);
    }

    /** Applies a set of per-caret changes as one edit, then carries the carets through it. */
    private void applyEdit(List<Change> changes) {
        changes.removeIf(Change::isEmpty);
        if (changes.isEmpty()) return;
        ChangeSet edit = ChangeSet.of(buffer.length(), changes);
        buffer.edit(edit);
        selections.mapThrough(edit).collapseEachToHead();
        preferredColumn = -1;
        restartCaretBlink();
        ensureCaretVisible();
        onSelectionChanged.emit();
    }


    // ── Input ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Text input is claimed wholesale.
     *
     * <p>Without this, {@code UIInputHandler}'s keyboard-activation bridge turns Space and Enter into a
     * synthetic click — which is what gives every other widget free keyboard activation, and is exactly
     * wrong for something you type into.</p>
     */
    @Override
    public boolean consumesTextInput() {
        return true;
    }

    private void installInput() {
        events.getGroup(KeyboardEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            if (handleKey(event.getKeyCode(), event.getModifiers())) {
                event.stopPropagation();
                return;
            }
            // Anything the control keys did not claim is a typed character. Ctrl-combos are left alone so
            // an unhandled accelerator still reaches the keymap rather than being typed into the document.
            if (CgModifiers.hasCtrl(event.getModifiers()) || CgModifiers.hasSuper(event.getModifiers())) {
                return;
            }
            char typed = event.getCharacter();
            if (typed != '\0' && !Character.isISOControl(typed)) {
                insertAtCaret(String.valueOf(typed));
                event.stopPropagation();
            }
        }, false, false);

        events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            int offset = offsetAt(event.getPosition().x(), event.getPosition().y());
            if (event.getDetail() >= 2) {
                selectWordAt(offset);
            } else {
                // A MouseEvent carries no modifier mask -- only KeyboardEvent does -- so shift-click has
                // to ask the platform for the live modifier state. Worth noting rather than working
                // around silently: this is the one place in the widget where a modifier is read outside
                // the event that caused it.
                boolean extend = CgModifiers.hasShift(CgPlatform.input().getCurrentModifiers());
                if (CgModifiers.hasAlt(CgPlatform.input().getCurrentModifiers())) {
                    // Alt+Click adds a caret, as in VS Code. Ctrl is not used for this because Ctrl+Click
                    // is already "go to definition" everywhere it appears, and 6.1.7 will want it.
                    addCaret(offset);
                } else {
                    setSelection(extend ? getAnchor() : offset, offset);
                }
                selecting = true;
                var window = getAttachedWindow();
                if (window != null) window.getInputHandler().setPointerCapture(this);
            }
            requestFocusHere();
            event.stopPropagation();
        }, false, false);

        events.getGroup(MouseEvent.Move.class).attachListener((el, event) -> {
            if (!selecting) return;
            setSelection(getAnchor(), offsetAt(event.getPosition().x(), event.getPosition().y()));
        }, false, false);

        events.getGroup(MouseEvent.Up.class).attachListener((el, event) -> selecting = false, false, false);
    }

    private void requestFocusHere() {
        var window = getAttachedWindow();
        if (window != null) window.getInputHandler().requestFocus(this);
    }

    /** @return true when the key was a command rather than a character */
    private boolean handleKey(int key, int modifiers) {
        boolean shift = CgModifiers.hasShift(modifiers);
        boolean ctrl = CgModifiers.hasCtrl(modifiers) || CgModifiers.hasSuper(modifiers);

        if (ctrl) {
            if (key == CgKeyCodes.KEY_Z) {
                if (shift) buffer.redo();
                else buffer.undo();
                clampSelectionToDocument();
                return true;
            }
            if (key == CgKeyCodes.KEY_Y) {
                buffer.redo();
                clampSelectionToDocument();
                return true;
            }
            if (key == CgKeyCodes.KEY_A) {
                setSelection(0, buffer.length());
                return true;
            }
            if (key == CgKeyCodes.KEY_C || key == CgKeyCodes.KEY_X) {
                if (hasSelection()) {
                    CgPlatform.input().setClipboard(getSelectedText());
                    if (key == CgKeyCodes.KEY_X) deleteSelections();
                }
                return true;
            }
            if (key == CgKeyCodes.KEY_V) {
                String pasted = CgPlatform.input().getClipboard();
                if (pasted != null && !pasted.isEmpty()) insertAtCaret(pasted);
                return true;
            }
            if (key == CgKeyCodes.KEY_HOME) {
                moveCaretTo(0, shift);
                return true;
            }
            if (key == CgKeyCodes.KEY_END) {
                moveCaretTo(buffer.length(), shift);
                return true;
            }
        }

        switch (key) {
            case CgKeyCodes.KEY_LEFT:
                moveEach(head -> ctrl ? previousWordBoundary(head) : Math.max(0, head - 1), shift);
                return true;
            case CgKeyCodes.KEY_RIGHT:
                moveEach(head -> ctrl ? nextWordBoundary(head) : Math.min(buffer.length(), head + 1), shift);
                return true;
            case CgKeyCodes.KEY_UP:
                moveVertically(-1, shift);
                return true;
            case CgKeyCodes.KEY_DOWN:
                moveVertically(1, shift);
                return true;
            case CgKeyCodes.KEY_PRIOR:
                moveVertically(-visibleRowCount(), shift);
                return true;
            case CgKeyCodes.KEY_NEXT:
                moveVertically(visibleRowCount(), shift);
                return true;
            case CgKeyCodes.KEY_HOME:
                moveEach(this::smartHomeOffset, shift);
                return true;
            case CgKeyCodes.KEY_END:
                moveEach(head -> buffer.document().lineEndOffset(buffer.offsetToPoint(head).row()), shift);
                return true;
            case CgKeyCodes.KEY_ESCAPE:
                // Only claims the key when there is something to collapse, so Escape still reaches a
                // dialog or a popover above the editor when there is only one caret.
                if (!selections.isMultiple()) return false;
                collapseCarets();
                return true;
            case CgKeyCodes.KEY_BACK:
                // Ctrl+Backspace deletes to the same boundary Ctrl+Left moves to, so the two agree by
                // construction rather than by two rules that have to be kept in step.
                deleteEach(head -> new int[] {
                        ctrl ? previousWordBoundary(head) : Math.max(0, head - 1), head });
                return true;
            case CgKeyCodes.KEY_DELETE:
                deleteEach(head -> new int[] {
                        head, ctrl ? nextWordBoundary(head) : Math.min(buffer.length(), head + 1) });
                return true;
            case CgKeyCodes.KEY_RETURN:
                insertNewlineWithIndent();
                return true;
            case CgKeyCodes.KEY_TAB:
                if (shift) outdentSelectedLines();
                else if (selections.hasSelection()) indentSelectedLines();
                else insertAtCaret(spaces(indentWidth));
                return true;
            default:
                return false;
        }
    }

    private void clampSelectionToDocument() {
        selections.clampTo(buffer.length());
        ensureCaretVisible();
        onSelectionChanged.emit();
    }

    // ── Movement ────────────────────────────────────────────────────────────────────────────────

    /** Absolute move — collapses to one caret, which is what Ctrl+Home/End mean. */
    private void moveCaretTo(int offset, boolean extend) {
        preferredColumn = -1;
        setSelection(extend ? getAnchor() : offset, offset);
        ensureCaretVisible();
    }

    /**
     * Moves <b>every</b> caret by a function of its own head.
     *
     * <p>Every horizontal and line-relative movement goes through here, so "does this work with several
     * carets?" stops being a question that has to be asked once per key.</p>
     */
    private void moveEach(java.util.function.IntUnaryOperator move, boolean extend) {
        preferredColumn = -1;
        selections.transform(selection -> {
            int head = Math.max(0, Math.min(move.applyAsInt(selection.head()), buffer.length()));
            return extend ? selection.withHead(head) : Selection.caret(head);
        });
        afterSelectionChange();
        ensureCaretVisible();
    }

    /** Deletes a per-caret range given as {@code {from, to}}. */
    private void deleteEach(java.util.function.IntFunction<int[]> range) {
        if (selections.hasSelection()) {
            deleteSelections();
            return;
        }
        List<Change> changes = new ArrayList<>(selections.count());
        for (Selection selection : selections.all()) {
            int[] span = range.apply(selection.head());
            int from = Math.max(0, Math.min(span[0], buffer.length()));
            int to = Math.max(from, Math.min(span[1], buffer.length()));
            if (to > from) changes.add(Change.delete(from, to));
        }
        applyEdit(changes);
    }

    /** Deletes every non-empty selection. */
    private void deleteSelections() {
        List<Change> changes = new ArrayList<>(selections.count());
        for (Selection selection : selections.all()) {
            if (!selection.isEmpty()) changes.add(Change.delete(selection.start(), selection.end()));
        }
        applyEdit(changes);
    }

    /**
     * Moves the caret by whole rows, remembering the column it started from.
     *
     * <p>{@link #preferredColumn} is what makes down-then-up return to where it began rather than being
     * dragged inward by the shortest line passed through.</p>
     */
    private void moveVertically(int rows, boolean extend) {
        // A shared preferred column only makes sense with one caret; with several, each keeps its own,
        // because they start in different columns and a single remembered value would drag them together.
        final int shared = selections.isMultiple() ? -1 : preferredColumn;
        final int[] usedColumn = { -1 };
        selections.transform(selection -> {
            TextPoint point = buffer.offsetToPoint(selection.head());
            int column = shared >= 0 ? shared : point.column();
            usedColumn[0] = column;
            int row = Math.max(0, Math.min(buffer.lineCount() - 1, point.row() + rows));
            int offset = buffer.pointToOffset(new TextPoint(row, column));
            return extend ? selection.withHead(offset) : Selection.caret(offset);
        });
        afterSelectionChange();
        // Set AFTER the move, so only the horizontal paths reset it.
        preferredColumn = selections.isMultiple() ? -1 : usedColumn[0];
        ensureCaretVisible();
    }

    private int visibleRowCount() {
        return Math.max(1, (int) (viewportHeight() / Math.max(1f, lineHeight())) - 1);
    }

    /**
     * The height actually available for text, with the horizontal scrollbar's own strip taken off.
     *
     * <p>{@code getClientHeight()} is the whole box: the bars are drawn <em>over</em> the content, the way
     * an overlay scrollbar works. For a list that is fine, because a row half-hidden behind a bar is still
     * obviously a row. For an editor it is not — the last line ends up sliced in half by the bar, and it
     * is the line you are usually typing on, since that is where the caret was scrolled to.</p>
     */
    private float viewportHeight() {
        return Math.max(0f, getClientHeight() - horizontalBarThickness());
    }

    /** The horizontal bar's thickness when it is showing, otherwise zero. */
    private float horizontalBarThickness() {
        if (getMaxScrollLeft() <= 0f) return 0f;
        return Math.max(0f, horizontalScroller().getRuntimeCache().getHeight());
    }

    /** The vertical bar's thickness when it is showing, otherwise zero. */
    private float verticalBarThickness() {
        if (getMaxScrollTop() <= 0f) return 0f;
        return Math.max(0f, verticalScroller().getRuntimeCache().getWidth());
    }

    /** Word boundaries in the CSS/browser sense: runs of letters-or-digits, everything else a break. */
    private int nextWordBoundary(int from) {
        String text = buffer.toString();
        int i = from;
        while (i < text.length() && !Character.isLetterOrDigit(text.charAt(i))) i++;
        while (i < text.length() && Character.isLetterOrDigit(text.charAt(i))) i++;
        return i;
    }

    private int previousWordBoundary(int from) {
        String text = buffer.toString();
        int i = from;
        while (i > 0 && !Character.isLetterOrDigit(text.charAt(i - 1))) i--;
        while (i > 0 && Character.isLetterOrDigit(text.charAt(i - 1))) i--;
        return i;
    }

    /**
     * Home goes to the first non-blank character, and to column 0 only if already there.
     *
     * <p>"Smart home", and every code editor has it: in indented code the useful position is the start of
     * the text, not the start of the indentation. Pressing it twice still gets you to column 0, so
     * nothing is taken away.</p>
     */
    private int smartHomeOffset(int head) {
        int row = buffer.offsetToPoint(head).row();
        int lineStart = buffer.document().lineStartOffset(row);
        String text = buffer.line(row);
        int indent = 0;
        while (indent < text.length() && Character.isWhitespace(text.charAt(indent))) indent++;
        // A whitespace-only line has no "first non-blank"; treat its start as the answer rather than
        // sending the caret past everything on it.
        if (indent >= text.length()) return lineStart;
        return head == lineStart + indent ? lineStart : lineStart + indent;
    }

    private void selectWordAt(int offset) {
        setSelection(previousWordBoundary(Math.min(offset + 1, buffer.length())), nextWordBoundary(offset));
    }

    // ── Geometry ────────────────────────────────────────────────────────────────────────────────

    /**
     * The height of one row, in pixels.
     *
     * <p><b>{@code line-height} is a unitless multiplier of the font size</b>, exactly as in CSS — not a
     * pixel height. Reading it as pixels compiles, runs, and produces rows a few pixels tall with the
     * text drawn over the top of itself; the property's own accessor documents the multiplier and it is
     * still the obvious thing to get wrong, so the conversion lives here in one place.</p>
     */
    /**
     * Advances the blink and shows or hides the caret.
     *
     * <p>Hidden outright when the editor is not focused: a caret in an unfocused editor claims a text
     * cursor that no keystroke would reach. And the phase is <b>reset by any edit or caret move</b>
     * ({@link #restartCaretBlink()}), so the caret is always solid at the instant you type — a caret that
     * happened to be in its off phase would otherwise vanish exactly when it is being looked for.</p>
     */
    private void advanceCaretBlink(float deltaSeconds) {
        boolean focused = isFocused();
        boolean shown;
        if (!focused) {
            shown = false;
        } else if (blinkPeriodSeconds <= 0f) {
            shown = true;
        } else {
            blinkClock = (blinkClock + deltaSeconds) % blinkPeriodSeconds;
            shown = blinkClock < blinkPeriodSeconds / 2f;
        }
        if (shown == caretShown) return;
        caretShown = shown;
        final float opacity = shown ? 1f : 0f;
        for (UIElement caret : caretElements) {
            StyleGroup.importantPipeline(caret.getStyle().getGeneralGroup(), g -> g.opacity(opacity));
        }
    }

    /** Makes the caret solid again and restarts the cycle. Called from every edit and every caret move. */
    private void restartCaretBlink() {
        blinkClock = 0f;
        if (caretShown) return;
        caretShown = true;
        for (UIElement caret : caretElements) {
            StyleGroup.importantPipeline(caret.getStyle().getGeneralGroup(), g -> g.opacity(1f));
        }
    }

    /** Seconds per full blink cycle; {@code 0} keeps the caret solid. */
    public TextEditor setCaretBlinkSeconds(float seconds) {
        this.blinkPeriodSeconds = Math.max(0f, seconds);
        restartCaretBlink();
        return this;
    }

    public float lineHeight() {
        var general = getStyle().getGeneralGroup();
        float multiplier = general.lineHeight();
        if (multiplier <= 0f) multiplier = 1.2f;
        return Math.max(1f, general.fontSize() * multiplier);
    }

    /**
     * The document's height, <b>plus the strip the horizontal scrollbar covers</b>.
     *
     * <p>Without the extra, the last line can never be scrolled clear of the bar: {@code getMaxScrollTop}
     * is {@code scrollHeight - getClientHeight()}, and {@code getClientHeight()} is the whole box, so the
     * scroll clamps exactly one bar-thickness short of where the caret needs it. Adding the strip to the
     * scrollable extent is the same thing every editor does by leaving trailing space below the last
     * line.</p>
     */
    @Override
    public float getScrollHeight() {
        return buffer.lineCount() * lineHeight() + horizontalBarThickness();
    }

    /** Document offset nearest a point in this element's own space. */
    public int offsetAt(float screenX, float screenY) {
        var local = screenToLocal(screenX, screenY);
        float relativeY = local.y() - getRuntimeCache().getY() - textOriginY() + getScrollTop();
        int row = Math.max(0, Math.min(buffer.lineCount() - 1, (int) (relativeY / lineHeight())));

        float relativeX = local.x() - getRuntimeCache().getX() + getScrollLeft() - textOriginX();
        float[] widths = prefixWidths(row);
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < widths.length; i++) {
            float distance = Math.abs(widths[i] - relativeX);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return buffer.document().lineStartOffset(row) + best;
    }

    /**
     * Where the text's left edge sits, in the coordinate space absolutely-positioned children use.
     *
     * <p><b>Padding only — deliberately not {@code border + padding}.</b> Taffy places an absolutely
     * positioned child relative to its containing block's <b>padding box</b>, so an inset of 0 already
     * starts after the border; adding the border again shifts everything right by exactly that much. The
     * scrollport, meanwhile, clips to the <em>content</em> box, so a line left at inset 0 begins inside
     * the padding and has its first characters scissored away.</p>
     *
     * <p>Both symptoms came from this one disagreement: with a 2px border and 10px padding the lines sat
     * at 2 and the caret at 14, while the visible text began at 12. The lines lost a character off the
     * front and the caret trailed the glyph it had just moved past.</p>
     */
    private float textOriginX() {
        return getTaffyLayout().padding().left + gutterWidth;
    }

    /** Whether the line-number gutter is shown. */
    public TextEditor setGutterVisible(boolean visible) {
        if (this.gutterVisible == visible) return this;
        this.gutterVisible = visible;
        measuredRows.clear();
        invalidateWindow();
        return this;
    }

    public boolean isGutterVisible() {
        return gutterVisible;
    }

    public float gutterWidth() {
        return gutterWidth;
    }

    /** Sets the language. Pass {@link SyntaxTokenizer#NONE} for plain text. */
    public TextEditor setTokenizer(SyntaxTokenizer newTokenizer) {
        if (this.tokenizer == newTokenizer) return this;
        this.tokenizer = newTokenizer == null ? SyntaxTokenizer.NONE : newTokenizer;
        highlightsDirty = true;
        highlightedFrom = -1;
        highlightedTo = -1;
        return this;
    }

    public SyntaxTokenizer tokenizer() {
        return tokenizer;
    }

    /**
     * Re-highlights the rows on screen.
     *
     * <p><b>Bounded to the realised rows.</b> The editor already knows which lines exist as elements, so
     * the query covers those and nothing else — highlighting cost is proportional to the viewport rather
     * than to the file, which is the same argument the virtualised list is built on and what Zed does by
     * capping a query at 16KB.</p>
     *
     * <p><b>A {@link HighlightRegistry} belongs to a {@code UIText}, not to a document</b>, and its ranges
     * are offsets into <em>that element's</em> string. So document-relative tokens are clipped to each
     * line and rebased onto it. That is also what makes a token spanning many lines — a block comment —
     * work: it is one token, distributed as one clipped range per line it crosses, rather than a range
     * that would read as running off the end of every line but the last.</p>
     *
     * <p>A dotted capture is <b>also</b> published under its general form, so {@code function.builtin}
     * still colours as {@code function} in a theme that has not named the specialisation. An unstyled
     * capture renders as plain text, which reads as the highlighter failing rather than the theme being
     * incomplete.</p>
     */
    private void refreshHighlights(int firstRow, int lastRow) {
        if (lastRow < firstRow || realisedLines.isEmpty()) return;

        int from = buffer.document().lineStartOffset(Math.max(0, firstRow));
        int to = buffer.document().lineEndOffset(Math.min(lastRow, buffer.lineCount() - 1));
        if (!highlightsDirty && from == highlightedFrom && to == highlightedTo) return;
        highlightedFrom = from;
        highlightedTo = to;
        highlightsDirty = false;

        List<SyntaxToken> tokens = tokenizer == SyntaxTokenizer.NONE
                ? List.<SyntaxToken>of()
                : tokenizer.tokenize(buffer.document(), from, to);
        for (Map.Entry<Integer, UIElement> entry : realisedLines.entrySet()) {
            int row = entry.getKey();
            if (row < 0 || row >= buffer.lineCount()) continue;
            int lineStart = buffer.document().lineStartOffset(row);
            int lineEnd = buffer.document().lineEndOffset(row);

            Map<String, List<TextRange>> byName = new LinkedHashMap<>();
            addDocumentRanges(byName, "search", searchMatches, lineStart, lineEnd);
            if (bracketPair != null) {
                addDocumentRanges(byName, "bracket", List.of(
                        TextRange.of(bracketPair[0], bracketPair[0] + 1),
                        TextRange.of(bracketPair[1], bracketPair[1] + 1)), lineStart, lineEnd);
            }
            for (SyntaxToken token : tokens) {
                int start = Math.max(token.start(), lineStart);
                int end = Math.min(token.end(), lineEnd);
                if (end <= start) continue;
                TextRange range = TextRange.of(start - lineStart, end - lineStart);
                byName.computeIfAbsent(token.name(), key -> new ArrayList<>()).add(range);
                String general = token.generalName();
                if (general != null) {
                    byName.computeIfAbsent(general, key -> new ArrayList<>()).add(range);
                }
            }

            HighlightRegistry highlights = textOf(entry.getValue()).highlights();
            for (String name : new ArrayList<>(highlights.names())) {
                if (!byName.containsKey(name)) highlights.remove(name);
            }
            for (Map.Entry<String, List<TextRange>> named : byName.entrySet()) {
                highlights.set(named.getKey(), named.getValue());
            }
        }
    }

    // ── Indentation ─────────────────────────────────────────────────────────────────────────────

    /** Spaces per indent level. */
    public TextEditor setIndentWidth(int width) {
        this.indentWidth = Math.max(1, width);
        return this;
    }

    public int getIndentWidth() {
        return indentWidth;
    }

    private static String spaces(int howMany) {
        StringBuilder out = new StringBuilder(howMany);
        for (int i = 0; i < howMany; i++) out.append(' ');
        return out.toString();
    }

    /**
     * Enter, carrying the current line's indentation onto the new one — and one level further after an
     * opening brace.
     *
     * <p>Computed <b>per caret</b>, because with several carets on differently indented lines a single
     * shared indent would be wrong for all but one of them. The rule is deliberately syntactic and dumb:
     * copy the leading whitespace, add a level if the line ends in an opener. A real indent engine needs
     * the tree, which is what step 6 of the plan puts behind the tokenizer seam.</p>
     */
    private void insertNewlineWithIndent() {
        List<Change> changes = new ArrayList<>(selections.count());
        for (Selection selection : selections.all()) {
            int row = buffer.offsetToPoint(selection.start()).row();
            String line = buffer.line(row);
            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
                indent++;
            }
            String carried = line.substring(0, Math.min(indent, Math.max(0,
                    selection.start() - buffer.document().lineStartOffset(row))));
            String trimmed = line.trim();
            boolean opens = trimmed.endsWith("{") || trimmed.endsWith("(") || trimmed.endsWith("[");
            String insert = "\n" + carried + (opens ? spaces(indentWidth) : "");
            changes.add(new Change(selection.start(), selection.end(), insert));
        }
        applyEdit(changes);
    }

    /** Adds one indent level to the start of every line touched by a selection. */
    private void indentSelectedLines() {
        List<Change> changes = new ArrayList<>();
        for (int row : touchedRows()) {
            changes.add(Change.insert(buffer.document().lineStartOffset(row), spaces(indentWidth)));
        }
        applyEditKeepingSelection(changes);
    }

    /** Removes up to one indent level from every line touched by a selection. */
    private void outdentSelectedLines() {
        List<Change> changes = new ArrayList<>();
        for (int row : touchedRows()) {
            int lineStart = buffer.document().lineStartOffset(row);
            String line = buffer.line(row);
            int remove = 0;
            while (remove < indentWidth && remove < line.length() && line.charAt(remove) == ' ') remove++;
            if (remove == 0 && !line.isEmpty() && line.charAt(0) == '\t') remove = 1;
            if (remove > 0) changes.add(Change.delete(lineStart, lineStart + remove));
        }
        applyEditKeepingSelection(changes);
    }

    /** Every row any selection touches, ascending and without repeats. */
    private List<Integer> touchedRows() {
        java.util.TreeSet<Integer> rows = new java.util.TreeSet<>();
        for (Selection selection : selections.all()) {
            int first = buffer.offsetToPoint(selection.start()).row();
            int last = buffer.offsetToPoint(selection.end()).row();
            for (int row = first; row <= last; row++) rows.add(row);
        }
        return new ArrayList<>(rows);
    }

    /**
     * Applies an edit and carries the selections through it <b>without</b> collapsing them.
     *
     * <p>Indenting a block must leave the block selected, or the obvious next action — pressing Tab again
     * — indents one line instead of the block. {@link #applyEdit} collapses on purpose, because typing
     * replaces a selection; these two want opposite things from the same machinery.</p>
     */
    private void applyEditKeepingSelection(List<Change> changes) {
        changes.removeIf(Change::isEmpty);
        if (changes.isEmpty()) return;
        ChangeSet edit = ChangeSet.of(buffer.length(), changes);
        buffer.edit(edit);
        selections.mapThrough(edit);
        restartCaretBlink();
        ensureCaretVisible();
        onSelectionChanged.emit();
    }

    // ── Bracket matching ────────────────────────────────────────────────────────────────────────

    private static final String OPENERS = "([{";
    private static final String CLOSERS = ")]}";

    /**
     * Finds the bracket at the caret and its partner, or clears the pair.
     *
     * <p>Looks at the character <em>before</em> the caret as well as the one after it, which is what makes
     * the highlight appear when you have just typed a closing brace — the common case, and the one where
     * it is most useful.</p>
     */
    private void updateBracketMatch() {
        bracketPair = null;
        if (selections.isMultiple() || selections.hasSelection()) return;
        int caret = getCaret();
        int found = matchAt(caret);
        if (found < 0 && caret > 0) found = matchAt(caret - 1);
        if (found >= 0) bracketPair = new int[] { found, matchingBracket(found) };
        if (bracketPair != null && bracketPair[1] < 0) bracketPair = null;
    }

    private int matchAt(int offset) {
        if (offset < 0 || offset >= buffer.length()) return -1;
        char c = buffer.document().charAt(offset);
        return OPENERS.indexOf(c) >= 0 || CLOSERS.indexOf(c) >= 0 ? offset : -1;
    }

    /**
     * The partner of the bracket at {@code offset}, or -1.
     *
     * <p>Bounded by {@link #BRACKET_SCAN_LIMIT}: an unmatched brace at the top of a large file would
     * otherwise make every caret move scan the entire document, and a search that finds nothing is
     * indistinguishable from one that was stopped early — except in how long it took.</p>
     */
    private int matchingBracket(int offset) {
        char bracket = buffer.document().charAt(offset);
        int openIndex = OPENERS.indexOf(bracket);
        boolean forward = openIndex >= 0;
        char partner = forward ? CLOSERS.charAt(openIndex) : OPENERS.charAt(CLOSERS.indexOf(bracket));
        int step = forward ? 1 : -1;
        int depth = 0;
        int limit = Math.min(BRACKET_SCAN_LIMIT, buffer.length());
        for (int i = 0, at = offset; i < limit; i++, at += step) {
            if (at < 0 || at >= buffer.length()) return -1;
            char c = buffer.document().charAt(at);
            if (c == bracket) depth++;
            else if (c == partner && --depth == 0) return at;
        }
        return -1;
    }

    private static final int BRACKET_SCAN_LIMIT = 16 * 1024;

    // ── Find and replace ────────────────────────────────────────────────────────────────────────

    /**
     * Finds every occurrence and publishes them under {@code ::highlight(search)}.
     *
     * <p>Whole-document rather than viewport-bounded, unlike syntax highlighting, and for a reason: the
     * match <em>count</em> is the answer the user wants, and "3 of 47" cannot be computed from what is on
     * screen. The ranges themselves are still only rendered for realised rows.</p>
     *
     * @return how many matches there are
     */
    public int find(String query, boolean caseSensitive) {
        searchMatches.clear();
        currentMatch = -1;
        lastQuery = query == null ? "" : query;
        lastQueryCaseSensitive = caseSensitive;
        if (lastQuery.isEmpty()) {
            highlightsDirty = true;
            return 0;
        }

        String haystack = buffer.toString();
        String needle = lastQuery;
        if (!caseSensitive) {
            haystack = haystack.toLowerCase(java.util.Locale.ROOT);
            needle = needle.toLowerCase(java.util.Locale.ROOT);
        }
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            searchMatches.add(TextRange.of(at, at + needle.length()));
            // Advance by one, not by the match length: overlapping matches of "aa" in "aaa" are two hits,
            // which is what every editor reports.
            at = haystack.indexOf(needle, at + 1);
        }
        highlightsDirty = true;
        return searchMatches.size();
    }

    public int matchCount() {
        return searchMatches.size();
    }

    /** Which match is selected, 1-based for display, or 0 when none is. */
    public int currentMatchNumber() {
        return currentMatch < 0 ? 0 : currentMatch + 1;
    }

    /** Selects the next match after the caret, wrapping. */
    public boolean findNext() {
        if (searchMatches.isEmpty()) return false;
        int caret = getCaret();
        int next = 0;
        for (int i = 0; i < searchMatches.size(); i++) {
            if (searchMatches.get(i).start() > caret) {
                next = i;
                break;
            }
            next = (i == searchMatches.size() - 1) ? 0 : next;
        }
        return selectMatch(next);
    }

    /** Selects the previous match before the caret, wrapping. */
    public boolean findPrevious() {
        if (searchMatches.isEmpty()) return false;
        int caret = getSelectionStart();
        int previous = searchMatches.size() - 1;
        for (int i = searchMatches.size() - 1; i >= 0; i--) {
            if (searchMatches.get(i).start() < caret) {
                previous = i;
                break;
            }
        }
        return selectMatch(previous);
    }

    private boolean selectMatch(int index) {
        if (index < 0 || index >= searchMatches.size()) return false;
        currentMatch = index;
        TextRange match = searchMatches.get(index);
        setSelection(match.start(), match.end());
        ensureCaretVisible();
        return true;
    }

    /** Replaces the selected match and finds the next. */
    public boolean replaceCurrent(String replacement) {
        if (currentMatch < 0 || currentMatch >= searchMatches.size()) return false;
        TextRange match = searchMatches.get(currentMatch);
        buffer.replace(match.start(), match.end(), replacement == null ? "" : replacement);
        buffer.breakUndoCoalescing();
        find(lastQuery, lastQueryCaseSensitive);
        return true;
    }

    /**
     * Replaces every match as <b>one</b> edit.
     *
     * <p>One {@link ChangeSet} rather than a loop of replacements: a loop would invalidate every later
     * offset after the first, and would put each replacement on the undo stack separately — so undoing a
     * replace-all would take one press per match.</p>
     *
     * @return how many were replaced
     */
    public int replaceAll(String replacement) {
        if (searchMatches.isEmpty()) return 0;
        String text = replacement == null ? "" : replacement;
        List<Change> changes = new ArrayList<>(searchMatches.size());
        for (TextRange match : searchMatches) {
            changes.add(new Change(match.start(), match.end(), text));
        }
        int replaced = changes.size();
        ChangeSet edit = ChangeSet.of(buffer.length(), changes);
        buffer.edit(edit);
        buffer.breakUndoCoalescing();
        selections.mapThrough(edit).collapseEachToHead();
        find(lastQuery, lastQueryCaseSensitive);
        return replaced;
    }

    /** Clips document-relative ranges to one line and rebases them onto it. */
    private static void addDocumentRanges(Map<String, List<TextRange>> byName, String name,
                                          List<TextRange> ranges, int lineStart, int lineEnd) {
        for (TextRange range : ranges) {
            int start = Math.max(range.start(), lineStart);
            int end = Math.min(range.end(), lineEnd);
            if (end <= start) continue;
            byName.computeIfAbsent(name, key -> new ArrayList<>())
                    .add(TextRange.of(start - lineStart, end - lineStart));
        }
    }

    private static UIText textOf(UIElement line) {
        return (UIText) line.getChildren().get(0);
    }

    /**
     * How wide the gutter needs to be for the largest line number it will show.
     *
     * <p>Sized from the <b>digit count of the last line</b> rather than from the widest number currently
     * on screen, so the text does not shift sideways as you scroll past line 99 into line 100 — which is
     * the kind of thing that reads as the editor being unstable rather than as a gutter resizing.</p>
     */
    private float measureGutter() {
        if (!gutterVisible) return 0f;
        int digits = Math.max(2, String.valueOf(Math.max(1, buffer.lineCount())).length());
        var general = getStyle().getGeneralGroup();
        float digitWidth = CgTextLayout.of("0", resolveFamily()).build().totalWidth();
        return digits * digitWidth + Math.max(4f, general.fontSize() * 0.75f);
    }

    /** The vertical equivalent, for the same reason. */
    private float textOriginY() {
        return getTaffyLayout().padding().top;
    }

    /**
     * Cumulative x of every caret position on a row.
     *
     * <p>Measured from substrings, the same way {@code TextField} does, and with the same known
     * divergence: a caret between two glyphs that kern or ligate is placed at the width of the prefix
     * rather than where it would sit in the fully-shaped line. Cached per row and dropped wholesale on
     * any edit, since a row's index is not stable across one.</p>
     */
    private float[] prefixWidths(int row) {
        var general = getStyle().getGeneralGroup();
        String fontKey = general.fontFamily() + "/" + general.fontSize();
        if (!fontKey.equals(measuredFontKey)) {
            measuredRows.clear();
            measuredFontKey = fontKey;
            textHeight = -1f;
        }
        return measuredRows.computeIfAbsent(row, r -> caretOffsets(buffer.line(r), resolveFamily()));
    }

    private CgFontFamily resolveFamily() {
        var general = getStyle().getGeneralGroup();
        return FontFamilyCache.resolve(general.fontFamily(), Math.round(general.fontSize()));
    }

    /**
     * The x of every caret position on a line, taken from the <b>same shaped run the renderer draws</b>.
     *
     * <p>The obvious implementation — {@code width(text.substring(0, i))} for each i, which is what
     * {@code TextField} does — re-shapes a fresh string per caret position, and shaping is not a
     * per-character mapping. Kerning between the last glyph of the prefix and the first glyph after it
     * simply does not exist in the prefix, so every caret lands a fraction off, and the error is
     * different at every position rather than a constant that could be nudged out. That is the
     * "not exactly before or after the character" this replaced.</p>
     *
     * <p>Shaping the line once and accumulating per-glyph advances gives the positions the renderer
     * actually used, so the caret sits exactly on a glyph boundary by construction.</p>
     *
     * <p><b>Clusters are UTF-8 byte offsets</b> — HarfBuzz's convention, and documented as such on
     * {@code CgShapedRun} — while every offset in this widget is a UTF-16 code unit. They coincide for
     * ASCII, which is exactly why a conversion is easy to skip and then wrong only for the text that
     * needed it most.</p>
     */
    private static float[] caretOffsets(String text, CgFontFamily family) {
        float[] xs = new float[text.length() + 1];
        java.util.Arrays.fill(xs, -1f);
        xs[0] = 0f;
        if (text.isEmpty()) return xs;

        int[] utf16ForByte = utf16IndexByUtf8Byte(text);
        float x = 0f;
        for (java.util.List<CgShapedRun> lineRuns : CgTextLayout.of(text, family).build().lines()) {
            for (CgShapedRun run : lineRuns) {
                float[] advances = run.advancesX();
                int[] clusters = run.clusterIds();
                for (int glyph = 0; glyph < advances.length; glyph++) {
                    int cluster = glyph < clusters.length ? clusters[glyph] : -1;
                    if (cluster >= 0 && cluster < utf16ForByte.length) {
                        int index = utf16ForByte[cluster];
                        // First glyph of a cluster wins: a ligature covers several source characters and
                        // the caret belongs at its leading edge, not partway through one glyph.
                        if (index >= 0 && index < xs.length && xs[index] < 0f) xs[index] = x;
                    }
                    x += advances[glyph];
                }
            }
        }
        xs[text.length()] = x;
        // Anything inside a multi-character cluster keeps the position of the cluster it belongs to.
        for (int i = 1; i < xs.length; i++) {
            if (xs[i] < 0f) xs[i] = xs[i - 1];
        }
        return xs;
    }

    /** For each UTF-8 byte offset of {@code text}, the UTF-16 index it starts at, or -1 mid-sequence. */
    private static int[] utf16IndexByUtf8Byte(String text) {
        int[] map = new int[utf8Length(text) + 1];
        java.util.Arrays.fill(map, -1);
        int bytes = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            map[bytes] = i;
            bytes += utf8LengthOf(codePoint);
            i += Character.charCount(codePoint);
        }
        map[map.length - 1] = text.length();
        return map;
    }

    private static int utf8Length(String text) {
        int total = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            total += utf8LengthOf(codePoint);
            i += Character.charCount(codePoint);
        }
        return total;
    }

    private static int utf8LengthOf(int codePoint) {
        if (codePoint < 0x80) return 1;
        if (codePoint < 0x800) return 2;
        if (codePoint < 0x10000) return 3;
        return 4;
    }

    /**
     * The ink height of a line of text, used to size and centre the caret and the selection bands.
     *
     * <p>Sizing them to the full row instead makes both overhang the text by the leading, which reads as
     * a caret that is not on the line it belongs to — and the taller the {@code line-height}, the worse
     * it looks. Taken from the font's own ascender and descender so it tracks the font rather than a
     * guessed fraction of the row.</p>
     */
    private float textHeight() {
        if (textHeight < 0f) {
            var metrics = CgTextLayout.of("Xg", resolveFamily()).build().metrics();
            textHeight = Math.abs(metrics.getAscender()) + Math.abs(metrics.getDescender());
        }
        return Math.min(lineHeight(), Math.max(1f, textHeight));
    }

    private float textHeight = -1f;

    private void ensureCaretVisible() {
        TextPoint point = buffer.offsetToPoint(getCaret());
        float height = lineHeight();
        float top = point.row() * height;
        // IMMEDIATE, not the smooth scroll the sheet asks for. `scroll-behavior: smooth` is right for a
        // wheel or a scrollIntoView, and wrong for following a caret: the caret has already moved, so an
        // eased scroll means it is off screen for the length of the animation and every keystroke chases
        // a viewport that is still catching up with the last one.
        float viewport = viewportHeight();
        if (top < getScrollTop()) setScrollImmediate(getScrollLeft(), top);
        else if (top + height > getScrollTop() + viewport) {
            setScrollImmediate(getScrollLeft(), top + height - viewport);
        }
        invalidateWindow();
    }

    // ── Virtualised rendering ───────────────────────────────────────────────────────────────────

    protected void invalidateWindow() {
        firstRealised = -1;
        lastRealised = -1;
        for (UIElement line : realisedLines.values()) recycleLine(line);
        realisedLines.clear();
        markTreeDirty();
    }

    @Override
    protected void onLayoutChanged() {
        super.onLayoutChanged();
        updateWindow();
    }

    /**
     * <b>Always keeps ticking</b>, unlike {@link ScrollerView#tickFrame}, which returns
     * {@code isAnimating()} and is therefore dropped the moment a smooth scroll settles.
     *
     * <p>Two things need a heartbeat rather than an event. The caret blinks, which is time-driven and has
     * nothing to fire it. And {@link #syncLineFonts()} has to notice a cascade change — swapping the
     * theme does not resize the editor, so no layout pass runs, and without a tick the lines kept the
     * font they were given under the previous sheet: the gallery's editor stayed in the Ore font after
     * switching to the default theme while every other widget changed.</p>
     *
     * <p>The work per tick is two early-outs when nothing moved: {@code updateWindow} returns immediately
     * on an unchanged range, and the font push no-ops on an unchanged value.</p>
     */
    @Override
    public boolean tickFrame(float deltaSeconds) {
        super.tickFrame(deltaSeconds);
        updateWindow();
        advanceCaretBlink(deltaSeconds);
        return true;
    }

    /** Realises exactly the rows on screen, plus {@link #OVERSCAN} either side. */
    public void updateWindow() {
        var window = getAttachedWindow();
        if (window == null) return;
        // ScrollerView registers its ticker only when a scroll begins, so an editor that has never been
        // scrolled would never tick — and the caret would never blink. Registration is a HashSet insert,
        // so repeating it is free and there is deliberately no unregister in the SPI.
        window.registerTicker(this);

        float height = lineHeight();
        int count = buffer.lineCount();
        // Before anything is placed: the gutter's width moves textOriginX, so a change here has to be
        // known before rows, carets and bands are positioned or they land a gutter-width out for a frame.
        float wantedGutter = measureGutter();
        if (Math.abs(wantedGutter - gutterWidth) > 0.5f) {
            gutterWidth = wantedGutter;
            firstRealised = -1;
            lastRealised = -1;
        }
        int first = Math.max(0, (int) (getScrollTop() / height) - OVERSCAN);
        float viewport = viewportHeight();
        int last = viewport <= 0f
                ? first
                : Math.min(count - 1, (int) ((getScrollTop() + viewport) / height) + OVERSCAN);

        if (first != firstRealised || last != lastRealised) {
            for (var iterator = realisedLines.entrySet().iterator(); iterator.hasNext(); ) {
                var entry = iterator.next();
                if (entry.getKey() < first || entry.getKey() > last) {
                    recycleLine(entry.getValue());
                    iterator.remove();
                }
            }
            for (int row = first; row <= last; row++) {
                if (!realisedLines.containsKey(row)) realisedLines.put(row, realiseLine(row));
            }
            firstRealised = first;
            lastRealised = last;
            highlightsDirty = true;
            onWindowChanged.emit();
        }
        syncLineFonts();
        refreshHighlights(first, last);
        layOutGutter(first, last);
        layOutCurrentLine();
        layOutCaretAndSelection(first, last);
    }

    /**
     * Forces every realised line to render in the font the editor measures with.
     *
     * <p><b>Caret positions are prefix widths, so a font disagreement is a SCALE error that grows with
     * the column</b> — the caret drifts further left of the glyph it belongs to the further along the
     * line it goes, and text is inserted where the caret really is rather than where it appears. The ore
     * theme drew lines at font-size 10 while the editor measured at 8, because {@code font-size} is
     * inheritable but a sheet targeting {@code text} sets a <em>specified</em> value, and a specified
     * value beats an inherited one.</p>
     *
     * <p>Pushed here rather than at realise time, and at {@code IMPORTANT} origin so no sheet can
     * reintroduce the disagreement. Timing is the reason: at realise time the editor's own computed style
     * may not be resolved yet, and an IMPORTANT write of the wrong value would then stick forever — which
     * is exactly what happened on the first attempt, pinning every line at the initial 16px. Re-pushing
     * each pass is cheap because {@code replaceOrPutCandidate} no-ops on an unchanged value, and it is
     * self-correcting once the cascade settles.</p>
     */
    private void syncLineFonts() {
        var general = getStyle().getGeneralGroup();
        final float size = general.fontSize();
        final var family = general.fontFamily();
        for (UIElement line : realisedLines.values()) {
            StyleGroup.importantPipeline(line.getChildren().get(0).getStyle().getGeneralGroup(),
                    g -> g.fontSize(size).fontFamily(family));
        }
    }

    private UIElement realiseLine(int row) {
        UIElement line = linePool.pollFirst();
        if (line == null) {
            line = new UIElement();
            line.addClass(LINE_CLASS);
            line.setHitTest(false);
            line.markAsInternal();
            line.addChild(new UIText(""));
        }
        final float top = textOriginY() + row * lineHeight();
        // A DEFINITE WIDTH IS REQUIRED. An absolutely-positioned box with no width resolves to zero, and
        // a zero-width line lays its text out as though it had no extent -- which shaved the first
        // character off every row on screen. Wide enough for the text, and at least the viewport, so a
        // selection band on a short line still reads as a band and horizontal scrolling has something to
        // scroll.
        float[] widths = prefixWidths(row);
        final float width = Math.max(getClientWidth(), widths[widths.length - 1] + 1f);
        StyleGroup.defaultPipeline(line.getStyle().getLayoutGroup(),
                l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                        .top(top).left(textOriginX()).width(width).height(lineHeight()));
        ((UIText) line.getChildren().get(0)).setText(buffer.line(row));
        if (line.getParent() == null) addInternalChild(line);
        return line;
    }

    private void recycleLine(UIElement line) {
        // A pooled line reused for a different row would otherwise keep the old row's highlights, which
        // is worse than none: the ranges are offsets into a string that has been replaced.
        textOf(line).highlights().clear();
        removeInternalChild(line);
        linePool.addLast(line);
    }

    /**
     * Positions the caret and the selection bands.
     *
     * <p>Both are ordinary internal children in document coordinates rather than something painted in
     * {@code paintSelf}. That is what makes them scroll for free — a scroll is a pose translate applied to
     * children at paint time, so anything painted by the view itself would have to subtract the scroll
     * offset by hand — and it is what lets a theme colour them, since the engine's rule is that no widget
     * writes a colour in Java.</p>
     */
    private void layOutCaretAndSelection(int firstRow, int lastRow) {
        if (lastRow < firstRow) return; // nothing realised yet; updateWindow will call again

        int caretsUsed = 0;
        int bandsUsed = 0;
        for (Selection selection : selections.all()) {
            caretsUsed = placeCaret(selection, caretsUsed);
            bandsUsed = placeBands(selection, firstRow, lastRow, bandsUsed);
        }
        // Anything left over from a larger set of carets is collapsed rather than removed: these are
        // pooled, and a multi-caret edit that shrinks back to one would otherwise churn elements every
        // keystroke.
        for (int i = caretsUsed; i < caretElements.size(); i++) hide(caretElements.get(i));
        for (int i = bandsUsed; i < selectionBands.size(); i++) hide(selectionBands.get(i));
    }

    /**
     * Places one caret.
     *
     * <p><b>The caret's right edge sits on the character boundary</b> — it does not start there, and it
     * does not straddle it. A boundary in a bitmap font is where the <em>next</em> glyph's ink begins: the
     * advance is ink plus trailing space and there is no left side bearing, so the whole of the clear gap
     * lies to the left of it. Drawing rightwards covers the next glyph's first ink column, and centring is
     * worse still — at uiScale 2 a 1px caret is two physical pixels and a Minecraft {@code i} is barely
     * wider than that, so a centred caret buries the letter.</p>
     */
    private int placeCaret(Selection selection, int index) {
        TextPoint point = buffer.offsetToPoint(selection.head());
        float height = lineHeight();
        final float ink = textHeight();
        final float caretWidth = Math.max(1f, getStyle().getGeneralGroup().caretWidth());
        final float left = textOriginX() + widthOf(point.row(), point.column()) - caretWidth;
        final float top = textOriginY() + point.row() * height + (height - ink) / 2f;

        UIElement caret = caretAt(index);
        StyleGroup.importantPipeline(caret.getStyle().getLayoutGroup(),
                l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                        .left(left).top(top).width(caretWidth).height(ink));
        return index + 1;
    }

    /** Places the highlight bands for one selection, one per visible row it covers. */
    private int placeBands(Selection selection, int firstRow, int lastRow, int index) {
        if (selection.isEmpty()) return index;
        float height = lineHeight();
        TextPoint start = buffer.offsetToPoint(selection.start());
        TextPoint end = buffer.offsetToPoint(selection.end());

        for (int row = Math.max(firstRow, start.row()); row <= Math.min(lastRow, end.row()); row++) {
            int lineLength = buffer.line(row).length();
            int from = row == start.row() ? start.column() : 0;
            int to = row == end.row() ? end.column() : lineLength;
            float left = textOriginX() + widthOf(row, from);
            // A selected line break shows as a sliver past the end of the text, which is how every editor
            // signals "the newline is in the selection too".
            float right = textOriginX() + widthOf(row, to) + (row < end.row() ? height * 0.4f : 0f);

            final float bandInk = textHeight();
            final float top = textOriginY() + row * height + (height - bandInk) / 2f;
            final float bandLeft = left;
            final float width = Math.max(1f, right - left);
            StyleGroup.defaultPipeline(bandAt(index++).getStyle().getLayoutGroup(),
                    l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                            .left(bandLeft).top(top).width(width).height(bandInk));
        }
        return index;
    }

    /** Places the gutter box and one number per visible row. */
    private void layOutGutter(int firstRow, int lastRow) {
        if (!gutterVisible) {
            hide(gutter);
            for (UIElement number : lineNumbers) hide(number);
            return;
        }
        final float width = gutterWidth;
        final float left = getTaffyLayout().padding().left;
        // Stops ABOVE the horizontal bar. The gutter sits at a higher z than the scrollbars so that a
        // long line scrolled sideways passes behind the numbers -- which also means a full-height gutter
        // paints over the bar's left end, showing as a dead square in the corner.
        final float viewport = viewportHeight();
        StyleGroup.defaultPipeline(gutter.getStyle().getLayoutGroup(),
                l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                        .left(left).top(0f).width(width).height(viewport));

        float height = lineHeight();
        int used = 0;
        for (int row = Math.max(0, firstRow); row <= Math.min(lastRow, buffer.lineCount() - 1); row++) {
            UIElement number = numberAt(used++);
            ((UIText) number.getChildren().get(0)).setText(String.valueOf(row + 1));
            StyleGroup.importantPipeline(number.getChildren().get(0).getStyle().getGeneralGroup(),
                    g -> g.fontSize(getStyle().getGeneralGroup().fontSize())
                            .fontFamily(getStyle().getGeneralGroup().fontFamily()));
            // Scroll-exempt, so the offset has to be subtracted by hand -- see the field's note.
            final float top = textOriginY() + row * height - getScrollTop();
            StyleGroup.defaultPipeline(number.getStyle().getLayoutGroup(),
                    l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                            .left(0f).top(top).width(width).height(height));
        }
        for (int i = used; i < lineNumbers.size(); i++) hide(lineNumbers.get(i));
        insetHorizontalBarPastGutter();
    }

    /**
     * Starts the horizontal scrollbar after the gutter rather than under it.
     *
     * <p>The gutter is pinned and does not scroll horizontally, so a bar running beneath it offers to
     * scroll something that will not move.</p>
     *
     * <p>Written at {@code IMPORTANT} origin because {@code ScrollerView} rewrites the bar's geometry
     * every frame from {@code refreshScrollers}; a lower-origin write would simply lose to it.</p>
     */
    private void insetHorizontalBarPastGutter() {
        UIElement bar = horizontalScroller();
        if (bar == null) return;
        final float left = getTaffyLayout().padding().left + gutterWidth;
        final float width = Math.max(0f, getClientWidth() - left - verticalBarThickness());
        StyleGroup.importantPipeline(bar.getStyle().getLayoutGroup(),
                l -> l.left(left).width(width));
    }

    /**
     * Places the current-line band behind the primary caret's row.
     *
     * <p>Hidden while there is a selection, which is what every editor does: two overlapping highlights
     * on the same row read as a rendering fault rather than as two pieces of information.</p>
     */
    private void layOutCurrentLine() {
        if (selections.hasSelection() || !isFocused()) {
            hide(currentLine);
            return;
        }
        float height = lineHeight();
        int row = buffer.offsetToPoint(getCaret()).row();
        final float top = textOriginY() + row * height;
        final float left = textOriginX();
        final float width = Math.max(1f, getClientWidth() - gutterWidth - verticalBarThickness());
        StyleGroup.defaultPipeline(currentLine.getStyle().getLayoutGroup(),
                l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                        .left(left).top(top).width(width).height(height));
    }

    private UIElement numberAt(int index) {
        while (lineNumbers.size() <= index) {
            UIElement number = new UIElement();
            number.addClass(LINE_NUMBER_CLASS);
            number.setHitTest(false);
            number.markAsInternal();
            number.addChild(new UIText(""));
            gutter.addInternalChild(number);
            lineNumbers.add(number);
        }
        return lineNumbers.get(index);
    }

    private void hide(UIElement element) {
        StyleGroup.defaultPipeline(element.getStyle().getLayoutGroup(), l -> l.width(0f).height(0f));
    }

    private float widthOf(int row, int column) {
        float[] widths = prefixWidths(row);
        return widths[Math.max(0, Math.min(widths.length - 1, column))];
    }

    private UIElement caretAt(int index) {
        while (caretElements.size() <= index) {
            UIElement caret = new UIElement();
            caret.addClass(CARET_CLASS);
            caret.setHitTest(false);
            caret.markAsInternal();
            addInternalChild(caret);
            caretElements.add(caret);
        }
        return caretElements.get(index);
    }

    private UIElement bandAt(int index) {
        while (selectionBands.size() <= index) {
            UIElement band = new UIElement();
            band.addClass(SELECTION_CLASS);
            band.setHitTest(false);
            band.markAsInternal();
            // Before the caret, so the caret is not painted underneath its own selection.
            insertInternalChildAt(band, 0);
            selectionBands.add(band);
        }
        return selectionBands.get(index);
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
