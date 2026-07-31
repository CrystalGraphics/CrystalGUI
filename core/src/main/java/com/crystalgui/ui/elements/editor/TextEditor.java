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
import com.crystalgui.text.TextBuffer;
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
    private final UIElement caretElement = new UIElement();

    private int firstRealised = -1;
    private int lastRealised = -1;

    /** Caret and selection anchor, both UTF-16 offsets. Equal means an empty selection. */
    private int caret;
    private int anchor;

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

        caretElement.addClass(CARET_CLASS);
        caretElement.setHitTest(false);
        caretElement.markAsInternal();
        addInternalChild(caretElement);

        buffer.onChanged.connect(change -> {
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

    public int getCaret() {
        return caret;
    }

    public int getAnchor() {
        return anchor;
    }

    public int getSelectionStart() {
        return Math.min(anchor, caret);
    }

    public int getSelectionEnd() {
        return Math.max(anchor, caret);
    }

    public boolean hasSelection() {
        return anchor != caret;
    }

    public String getSelectedText() {
        return hasSelection()
                ? buffer.document().slice(getSelectionStart(), getSelectionEnd()).toString()
                : "";
    }

    /** Moves the caret, collapsing the selection to it. */
    public TextEditor setCaret(int offset) {
        return setSelection(offset, offset);
    }

    public TextEditor setSelection(int anchorOffset, int caretOffset) {
        int length = buffer.length();
        int newAnchor = Math.max(0, Math.min(anchorOffset, length));
        int newCaret = Math.max(0, Math.min(caretOffset, length));
        if (newAnchor == anchor && newCaret == caret) return this;
        this.anchor = newAnchor;
        this.caret = newCaret;
        // A deliberate caret move ends the current undo step: the next keystroke is a new thought, and
        // the buffer has no way to know the caret moved.
        buffer.breakUndoCoalescing();
        // NOT invalidateWindow(). The text did not change, so every realised line is still correct --
        // recycling them all and rebuilding on each arrow key is pure waste, and it left the realised set
        // momentarily empty, which is why the gallery's status line read "0 lines realised" immediately
        // after a click. Only the caret and the bands need to move.
        restartCaretBlink();
        layOutCaretAndSelection(firstRealised, lastRealised);
        markTreeDirty();
        onSelectionChanged.emit();
        return this;
    }

    public TextPoint caretPoint() {
        return buffer.offsetToPoint(caret);
    }

    // ── Editing ─────────────────────────────────────────────────────────────────────────────────

    /** Replaces the selection (or inserts at the caret) and puts the caret after the inserted text. */
    public void insertAtCaret(String text) {
        int from = getSelectionStart();
        int to = getSelectionEnd();
        buffer.replace(from, to, text);
        int next = from + text.length();
        this.anchor = next;
        this.caret = next;
        preferredColumn = -1;
        restartCaretBlink();
        ensureCaretVisible();
        onSelectionChanged.emit();
    }

    private void deleteSelectionOr(int from, int to) {
        if (hasSelection()) {
            from = getSelectionStart();
            to = getSelectionEnd();
        }
        from = Math.max(0, from);
        to = Math.min(buffer.length(), to);
        if (from >= to) return;
        buffer.delete(from, to);
        this.anchor = from;
        this.caret = from;
        preferredColumn = -1;
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
                setSelection(extend ? anchor : offset, offset);
                selecting = true;
                var window = getAttachedWindow();
                if (window != null) window.getInputHandler().setPointerCapture(this);
            }
            requestFocusHere();
            event.stopPropagation();
        }, false, false);

        events.getGroup(MouseEvent.Move.class).attachListener((el, event) -> {
            if (!selecting) return;
            setSelection(anchor, offsetAt(event.getPosition().x(), event.getPosition().y()));
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
                    if (key == CgKeyCodes.KEY_X) deleteSelectionOr(caret, caret);
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
                moveCaretTo(ctrl ? previousWordBoundary(caret) : Math.max(0, caret - 1), shift);
                return true;
            case CgKeyCodes.KEY_RIGHT:
                moveCaretTo(ctrl ? nextWordBoundary(caret) : Math.min(buffer.length(), caret + 1), shift);
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
                moveCaretTo(smartHomeOffset(), shift);
                return true;
            case CgKeyCodes.KEY_END:
                moveCaretTo(buffer.document().lineEndOffset(caretPoint().row()), shift);
                return true;
            case CgKeyCodes.KEY_BACK:
                // Ctrl+Backspace deletes the word before the caret, which is the same boundary
                // Ctrl+Left moves to -- so the two agree by construction rather than by two rules that
                // have to be kept in step.
                deleteSelectionOr(ctrl ? previousWordBoundary(caret) : Math.max(0, caret - 1), caret);
                return true;
            case CgKeyCodes.KEY_DELETE:
                deleteSelectionOr(caret, ctrl ? nextWordBoundary(caret) : Math.min(buffer.length(), caret + 1));
                return true;
            case CgKeyCodes.KEY_RETURN:
                insertAtCaret("\n");
                return true;
            case CgKeyCodes.KEY_TAB:
                insertAtCaret("    ");
                return true;
            default:
                return false;
        }
    }

    private void clampSelectionToDocument() {
        int length = buffer.length();
        this.anchor = Math.min(anchor, length);
        this.caret = Math.min(caret, length);
        ensureCaretVisible();
        onSelectionChanged.emit();
    }

    // ── Movement ────────────────────────────────────────────────────────────────────────────────

    private void moveCaretTo(int offset, boolean extend) {
        preferredColumn = -1;
        setSelection(extend ? anchor : offset, offset);
        ensureCaretVisible();
    }

    /**
     * Moves the caret by whole rows, remembering the column it started from.
     *
     * <p>{@link #preferredColumn} is what makes down-then-up return to where it began rather than being
     * dragged inward by the shortest line passed through.</p>
     */
    private void moveVertically(int rows, boolean extend) {
        TextPoint point = caretPoint();
        int column = preferredColumn >= 0 ? preferredColumn : point.column();
        int row = Math.max(0, Math.min(buffer.lineCount() - 1, point.row() + rows));
        int offset = buffer.pointToOffset(new TextPoint(row, column));

        setSelection(extend ? anchor : offset, offset);
        // Set AFTER the move: setSelection clears nothing, but moveCaretTo does, and keeping the
        // assignment here means only the horizontal paths reset it.
        preferredColumn = column;
        ensureCaretVisible();
    }

    private int visibleRowCount() {
        return Math.max(1, (int) (getClientHeight() / Math.max(1f, lineHeight())) - 1);
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
    private int smartHomeOffset() {
        int row = caretPoint().row();
        int lineStart = buffer.document().lineStartOffset(row);
        String text = buffer.line(row);
        int indent = 0;
        while (indent < text.length() && Character.isWhitespace(text.charAt(indent))) indent++;
        // A whitespace-only line has no "first non-blank"; treat its end as column 0 rather than sending
        // the caret past everything.
        if (indent >= text.length()) return lineStart;
        return caret == lineStart + indent ? lineStart : lineStart + indent;
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
        StyleGroup.importantPipeline(caretElement.getStyle().getGeneralGroup(), g -> g.opacity(opacity));
    }

    /** Makes the caret solid again and restarts the cycle. Called from every edit and every caret move. */
    private void restartCaretBlink() {
        blinkClock = 0f;
        if (caretShown) return;
        caretShown = true;
        StyleGroup.importantPipeline(caretElement.getStyle().getGeneralGroup(), g -> g.opacity(1f));
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

    @Override
    public float getScrollHeight() {
        return buffer.lineCount() * lineHeight();
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
        return getTaffyLayout().padding().left;
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
        TextPoint point = buffer.offsetToPoint(caret);
        float height = lineHeight();
        float top = point.row() * height;
        if (top < getScrollTop()) setScrollTop(top);
        else if (top + height > getScrollTop() + getClientHeight()) {
            setScrollTop(top + height - getClientHeight());
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
        int first = Math.max(0, (int) (getScrollTop() / height) - OVERSCAN);
        float viewport = getClientHeight();
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
            onWindowChanged.emit();
        }
        syncLineFonts();
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
        float height = lineHeight();
        TextPoint point = buffer.offsetToPoint(caret);
        float caretX = textOriginX() + widthOf(point.row(), point.column());
        final float ink = textHeight();
        final float caretTop = textOriginY() + point.row() * height + (height - ink) / 2f;
        // THE CARET'S RIGHT EDGE SITS ON THE BOUNDARY -- it does not start there, and it does not
        // straddle it.
        //
        // A character boundary in a bitmap font is where the NEXT glyph's ink begins: the advance is ink
        // plus trailing space, and there is no left side bearing. So the whole of the clear gap lies to
        // the LEFT of the boundary, and it is one logical pixel wide.
        //
        // Drawing rightwards from the boundary covers the next glyph's first ink column. Centring on it
        // is worse, not better: at uiScale 2 a 1px caret is two physical pixels and a Minecraft 'i' is
        // barely wider than that, so a centred caret buries the whole letter -- which is exactly what
        // the first attempt did. Right-aligning to the boundary puts the caret in the gap and cannot
        // overlap the glyph that follows it at any scale.
        final float caretWidth = Math.max(1f, getStyle().getGeneralGroup().caretWidth());
        final float caretLeft = caretX - caretWidth;
        StyleGroup.importantPipeline(caretElement.getStyle().getLayoutGroup(),
                l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                        .left(caretLeft).top(caretTop).width(caretWidth).height(ink));

        int used = 0;
        if (hasSelection()) {
            TextPoint start = buffer.offsetToPoint(getSelectionStart());
            TextPoint end = buffer.offsetToPoint(getSelectionEnd());
            for (int row = Math.max(firstRow, start.row()); row <= Math.min(lastRow, end.row()); row++) {
                int lineLength = buffer.line(row).length();
                int from = row == start.row() ? start.column() : 0;
                int to = row == end.row() ? end.column() : lineLength;
                float left = textOriginX() + widthOf(row, from);
                // A selected line break shows as a sliver past the end of the text, which is how every
                // editor signals "the newline is in the selection too".
                float right = textOriginX() + widthOf(row, to) + (row < end.row() ? height * 0.4f : 0f);

                UIElement band = bandAt(used++);
                final float bandInk = textHeight();
                final float top = textOriginY() + row * height + (height - bandInk) / 2f;
                final float width = Math.max(1f, right - left);
                StyleGroup.defaultPipeline(band.getStyle().getLayoutGroup(),
                        l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                                .left(left).top(top).width(width).height(bandInk));
            }
        }
        for (int i = used; i < selectionBands.size(); i++) {
            StyleGroup.defaultPipeline(selectionBands.get(i).getStyle().getLayoutGroup(),
                    l -> l.width(0f).height(0f));
        }
    }

    private float widthOf(int row, int column) {
        float[] widths = prefixWidths(row);
        return widths[Math.max(0, Math.min(widths.length - 1, column))];
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
