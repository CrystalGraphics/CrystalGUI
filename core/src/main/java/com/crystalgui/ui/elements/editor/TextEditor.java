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
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.text.ChangeSet;
import com.crystalgui.text.Change;
import com.crystalgui.text.Selection;
import com.crystalgui.text.SelectionModel;
import com.crystalgui.text.TextBuffer;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.ui.text.HighlightRegistry;
import com.crystalgui.ui.text.TextRange;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.WordClassifier;
import com.crystalgui.text.WordOperations;
import com.crystalgui.text.cursor.CursorColumns;
import com.crystalgui.text.cursor.LineOperations;
import com.crystalgui.text.cursor.MouseSelection;
import com.crystalgui.text.cursor.MoveOperations;
import com.crystalgui.text.cursor.TypeOperations;
import com.crystalgui.text.wrap.LineBreaksComputer;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.text.view.IndentLevels;
import com.crystalgui.text.view.RenderWhitespace;
import com.crystalgui.text.view.WhitespaceMarkers;
import com.crystalgui.text.fold.FoldingModel;
import com.crystalgui.text.fold.FoldingRangeProvider;
import com.crystalgui.text.fold.FoldingRegions;
import com.crystalgui.text.fold.IndentRangeProvider;
import com.crystalgui.text.wrap.ProjectedLines;
import com.crystalgui.text.wrap.ShapedLineBreaks;
import com.crystalgui.text.wrap.WrapIndent;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.TaffyPosition;
import lombok.Getter;

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
 * <h3>Soft wrap</h3>
 * <p>{@link #setSoftWrap} is real, and the window is no longer derived by dividing a scroll offset by a
 * row height: {@link ProjectedLines} maps model rows onto view lines and is consulted unconditionally, so
 * there is one code path whether wrapping is on or off. See that field's note for why there is no
 * unwrapped fast path.</p>
 *
 * <p>This heading used to say soft wrap was "deliberately absent, not stubbed", on the reasoning that a
 * toggle which silently did nothing is worse than an absent one. That reasoning still holds; the feature
 * simply landed, and the paragraph outlived it — which is the same failure it was warning about, one
 * level up.</p>
 */
public class TextEditor extends ScrollerView implements UndoScope {

    public static final String LINE_CLASS = "__line__";
    public static final String CARET_CLASS = "__caret__";
    public static final String SELECTION_CLASS = "__selection__";
    public static final String GUTTER_CLASS = "__gutter__";
    public static final String INDENT_GUIDE_CLASS = "__indent-guide__";
    /** Added to the one guide belonging to the block the caret is in — see {@code layOutIndentGuides}. */
    public static final String ACTIVE_GUIDE_CLASS = "__active__";
    public static final String WHITESPACE_CLASS = "__whitespace__";
    public static final String RULER_CLASS = "__ruler__";
    public static final String GUTTER_EDGE_CLASS = "__gutter-edge__";
    public static final String TEXT_VIEWPORT_CLASS = "__text-viewport__";
    public static final String ZOOM_INDICATOR_CLASS = "__zoom-indicator__";
    public static final String ZOOM_LABEL_CLASS = "__zoom-label__";
    public static final String ZOOM_RESET_CLASS = "__zoom-reset__";
    /** Present while the zoom indicator is holding; its removal is what the fade transitions on. */
    public static final String SHOWN_CLASS = "__shown__";
    public static final String LINE_NUMBER_CLASS = "__line-number__";
    public static final String CURRENT_LINE_CLASS = "__current-line__";
    /** The clickable fold arrow in the gutter, on the first row of a foldable region. */
    public static final String FOLD_CLASS = "__fold__";
    /** The strip the arrows live in — the one part of the gutter region that answers the mouse. */
    public static final String FOLD_COLUMN_CLASS = "__fold-column__";
    /** Added to a fold arrow whose region is closed, so the sheet can point it sideways. */
    public static final String FOLD_COLLAPSED_CLASS = "__collapsed__";
    /** The marker drawn after a collapsed region's header, standing in for the rows it hides. */
    public static final String FOLD_PLACEHOLDER_CLASS = "__fold-placeholder__";

    // The fold arrows used to be "-"/"+" here — the bundled fonts have no triangle glyph at all
    // (MinecraftRegular.otf covers none of U+25BE, U+25B8, U+22EF or even U+2026, and a missing
    // glyph draws a blank advance rather than falling back, so a triangle rendered as an invisible
    // but still-clickable control). Real IntelliJ-style triangles now come from
    // `overlay: shape("chevron-down"/"chevron-right")` in default.css's texteditor .__fold__ rules,
    // toggled by FOLD_COLLAPSED_CLASS exactly as before — no glyph constants needed any more.

    /** {@code "..."} for the same reason — U+22EF and U+2026 are both absent from the bundled fonts. */
    static final String FOLD_PLACEHOLDER_TEXT = "...";

    /** Rows kept realised beyond the viewport, so a scroll does not expose an unpainted band. */
    private static final int OVERSCAN = 3;

    /** Emitted after any edit, with the whole document. */
    public final Signal.Value<String> onChanged = new Signal.Value<>();

    /** Emitted when the caret or selection moves. */
    public final Signal.Action onSelectionChanged = new Signal.Action();

    /** Emitted when the set of realised lines changes — i.e. on a scroll that moves the window. */
    public final Signal.Action onWindowChanged = new Signal.Action();

    private final TextBuffer buffer;

    /**
     * Model rows projected onto visual rows.
     *
     * <p><b>Always present, even with soft wrap off</b>, in which case every projection is the trivial
     * one and view line <i>n</i> is row <i>n</i>. There is deliberately no second code path: a
     * wrapped/unwrapped branch through the painting, hit testing, caret and gutter code would be six
     * places for the two to drift apart, and the unwrapped half is the one that is exercised constantly
     * and so would stay right while the other rotted. VS Code's view model is unconditional for the same
     * reason.</p>
     */
    private final ProjectedLines projections = new ProjectedLines(LineBreaksComputer.none());

    private boolean softWrap = false;
    private WrapIndent wrapIndent = WrapIndent.SAME;

    /** The wrap width the current projection was built against, so a resize can be detected. */
    private float projectedWrapWidth = -1f;

    /** Rows before the edit in flight, so its line-count delta names the rows to reproject. */
    private int previousLineCount = 1;

    /** Whether {@link EditorCommands} has been installed for this editor — see {@code updateWindow}. */
    private boolean commandsInstalled;

    /** Whether the live projection was built with real measurement, or is the no-font fallback. */
    private boolean projectedWithMeasurement = true;

    /** The digits' width, measured whenever {@code gutterWidth} is — the two must never disagree. */
    private float gutterNumbersWidth;

    /** The sheet's three gutter metrics, re-read once a frame — see {@code refreshGutterMetrics}. */
    private float cachedPadLeft;
    private float cachedFoldWidth;
    private float cachedCodeLeftPad;

    /** Clips everything drawn in document coordinates — see {@link #textViewport()}. */
    private UIElement textViewport;

    /** Widest line realised since the last edit, font change or reprojection. */
    private float widestSeen;

    /** The shaped width of one digit, and the font it was measured in. */
    private float digitWidth = -1f;
    private String digitWidthFontKey;

    /** The size the sheet gave this editor, captured on the first zoom so reset has a target. */
    private float baseFontSize = -1f;

    // ── §G view decorations ─────────────────────────────────────────────────────────────────────

    private boolean indentGuidesVisible;
    private RenderWhitespace renderWhitespace = RenderWhitespace.NONE;
    private int[] rulers = new int[0];
    private boolean scrollBeyondLastLine = true;
    private boolean offSideLanguage;

    private final Map<Integer, UIElement> realisedLines = new HashMap<>();
    private final Deque<UIElement> linePool = new ArrayDeque<>();
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
    @Getter
    private boolean gutterVisible = true;
    /**
     * -- GETTER --
     * The gutter's laid-out width — where the code starts, relative to the editor's padding edge. 
     */
    @Getter
    private float gutterWidth;

    /** A band behind the primary caret's row. An ordinary child, so it scrolls with the text. */
    private final UIElement currentLine = new UIElement();

    /**
     * The current-line band's other half, inside the gutter.
     *
     * <p><b>Two elements rather than one wide one</b>, because the gutter and the code area are separately
     * stacked: the gutter paints an opaque background above the text so a long line scrolled sideways
     * passes behind the numbers, which means a single band drawn behind everything is simply covered in
     * the gutter region, and one drawn in front of everything hides the numbers. A band inside the gutter
     * sits in the gutter's own stacking context — beneath its numbers, above its background — which is
     * the only place it can be both visible and behind the digits.</p>
     */
    private final UIElement currentLineGutter = new UIElement();

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

    /**
     * What the editor needs to know about the language in order to EDIT it — comment tokens, bracket
     * pairs, indent triggers. Separate from the tokenizer, which only knows how to colour it: a
     * tree-sitter grammar has no query that says {@code //} starts a comment.
     */
    private Language language = Language.PLAIN;

    /** Read-only refuses every mutation while leaving navigation, selection and copying alone. */
    private boolean readOnly;

    private boolean autoCloseBrackets = true;

    /**
     * What counts as a word — VS Code's separator set, where {@code _} is a word character.
     *
     * <p>Per-editor rather than global because languages disagree: {@code $} is part of an identifier in
     * one and punctuation in the next, which is why VS Code makes it a language-scoped option.</p>
     */
    private WordClassifier wordClassifier = WordClassifier.DEFAULT;

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
     * The column each caret aims for while moving vertically — one per caret, not one shared.
     *
     * <p>VS Code calls this {@code leftoverVisibleColumns} and keeps it <b>per cursor</b>. A single shared
     * value is wrong the moment there are two carets in different columns: whichever moved last imposes
     * its column on the others, and a rectangular block of carets collapses into a ragged one after a
     * single Down. Reset by any horizontal movement or edit, which is what makes "down, down, up" return
     * to where it started while "down, right, up" does not.</p>
     *
     * <p>Indices line up with {@code selections.all()}. When a move merges carets the lengths stop
     * matching, and the goals are dropped rather than guessed at — a wrong goal is worse than none.</p>
     */
    private int[] goalColumns = new int[0];

    private boolean selecting;

    /**
     * What a drag is extending by — ported from VS Code's mouse handler, which keeps the click count for
     * the whole drag rather than only for the press that started it.
     *
     * <p>A drag started by a double-click extends <b>by word</b>, and one started by a triple-click
     * extends <b>by line</b>. Dropping back to character granularity the moment the pointer moves is the
     * usual mistake, and it makes double-click-drag — the standard way to select a phrase — behave as if
     * the double-click never happened.</p>
     */
    private int dragGranularity = 1;

    /** The anchor a drag extends from, in document offsets: {@code {start, end}} of the initial unit. */
    private int[] dragAnchor;

    /** Last pointer position in this element's space, for autoscroll while dragging. */
    private float pointerX, pointerY;

    /**
     * Per-row measurement, keyed by row and dropped wholesale on any edit.
     *
     * <p>Holds the <b>displayed</b> text — tabs expanded to their stops — alongside the two maps between
     * document columns and display indices. See {@link RowMetrics}.</p>
     */
    private final Map<Integer, RowMetrics> measuredRows = new HashMap<>();
    private String measuredFontKey = "";

    /** Spaces per tab stop. Separate from {@link #indentWidth}, exactly as VS Code separates the two. */
    private int tabSize = 4;

    /**
     * Which blocks are foldable and which are closed.
     *
     * <p><b>View state, not document state</b>, by the same boundary the engine draws for undo: it is how
     * you are looking at the file, not what the file says. So it never reaches {@code UndoStack} and Ctrl+Z
     * will not unfold — which is what VS Code and IntelliJ both do, and the same rule that keeps scroll
     * position and selection out of the history.</p>
     */
    // ── View parts ──────────────────────────────────────────────────────────────────────────────
    //
    // VS Code's decomposition, ported: each piece of the view owns its own elements and places them in
    // its own pass. See EditorViewPart. They are rendered unconditionally for now, exactly as the
    // layOut* methods they replaced were -- the shouldRender protocol is defined but not yet wired.

    private final RulersPart rulersPart = new RulersPart(this);
    private final WhitespacePart whitespacePart = new WhitespacePart(this);
    private final IndentGuidesPart indentGuidesPart = new IndentGuidesPart(this);
    private final GutterEdgePart gutterEdgePart = new GutterEdgePart(this);
    private final FoldingDecorationsPart foldingDecorationsPart = new FoldingDecorationsPart(this);
    private final ZoomIndicatorPart zoomIndicatorPart = new ZoomIndicatorPart(this);
    private final ViewCursorsPart viewCursorsPart = new ViewCursorsPart(this);
    private final SelectionsPart selectionsPart = new SelectionsPart(this);
    private final CurrentLinePart currentLinePart = new CurrentLinePart(this, currentLine, currentLineGutter);

    private final LineNumbersPart lineNumbersPart = new LineNumbersPart(this, gutter);
    /** Every part, in paint order, so the frame drives one list rather than a dozen named calls. */
    private final java.util.List<EditorViewPart> viewParts = java.util.List.of(
            gutterEdgePart, indentGuidesPart, whitespacePart, rulersPart, foldingDecorationsPart,
            zoomIndicatorPart, lineNumbersPart, currentLinePart, selectionsPart, viewCursorsPart);


    private final FoldingModel folding = new FoldingModel();

    /**
     * Where foldable regions come from. Indentation by default — see {@link IndentRangeProvider} for why
     * that is Monaco's default too, and why brackets are not.
     */
    private FoldingRangeProvider foldingProvider = IndentRangeProvider.plain();

    private boolean foldingEnabled = true;

    /** Set by anything that could change the region set; drained once per frame by {@code refreshFolding}. */
    private boolean foldingDirty = true;

    /**
     * The arrows' own container, sitting over the gutter's fold column.
     *
     * <p><b>Not a child of the gutter, and it cannot be one.</b> {@code gutter.setHitTest(false)} applies
     * to the whole SUBTREE — the engine's spelling of {@code pointer-events: none} — so an arrow parented
     * there is laid out, painted and permanently unclickable. That is exactly how the arrows shipped: the
     * handles appeared and did nothing, and no test saw it because a test that dispatches to the element
     * directly never asks whether a real pointer could have reached it.</p>
     *
     * <p>So the arrows get a container the gutter does not own: scroll-exempt like the gutter, drawn over
     * the same strip, and hit-testable. It also means the fold column swallows clicks that would otherwise
     * place a caret in the margin, which is what IntelliJ does with that strip anyway.</p>
     */
    private final UIElement foldColumn = new UIElement();

    /** The arrows' strip. Owned here so its attachment order among siblings is unchanged. */
    UIElement foldColumn() {
        return foldColumn;
    }

    /** One row's expanded display text and column maps, plus the measured x of each display index. */
    record RowMetrics(CursorColumns.Line line, float[] widths) {
    }

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
        textViewport().addInternalChild(currentLine);
        currentLineGutter.addClass(CURRENT_LINE_CLASS);
        currentLineGutter.setHitTest(false);
        currentLineGutter.markAsInternal();
        gutter.addInternalChild(currentLineGutter);

        gutter.addClass(GUTTER_CLASS);
        gutter.setHitTest(false);
        gutter.markAsInternal();
        gutter.setScrollExempt(true);
        foldColumn.addClass(FOLD_COLUMN_CLASS);
        foldColumn.setScrollExempt(true);
        foldColumn.markAsInternal();
        addInternalChild(foldColumn);
        addInternalChild(gutter);

        buffer.onChanged.connect(change -> {
            // The tokenizer hears about the edit BEFORE the next query, so an incremental one can update
            // what it holds. Applying the edit is cheap and must be synchronous; the expensive reparse is
            // the implementation's business. See SyntaxTokenizer#edited.
            tokenizer.edited(buffer.document(), change);
            highlightsDirty = true;
            // BEFORE reprojectAfterEdit, which is what advances previousLineCount -- this needs the count
            // as it was in order to tell a same-row edit from one that shifted every row below it.
            invalidateMeasuredRows(change);
            // NOT invalidateWindow() unless the line COUNT changed.
            //
            // Recycling every line on every keystroke clears each one's highlights -- recycleLine has to,
            // since a pooled line reused for another row would keep offsets into a string that no longer
            // exists. The ranges are republished during updateWindow, which runs AFTER calculateStyle in
            // the frame, so the cascade only sees them next frame: for one frame every line rendered with
            // no highlight style at all, which is the whole editor's colour flickering on every keystroke.
            //
            // Rebind in place, ALWAYS -- including when a line was added or removed.
            //
            // The realised map is keyed by ROW INDEX, not by identity, so re-reading each row's text gives
            // every element the right content however much the rows shifted. Element identity does not
            // need to follow the text. What the window may need is to grow or shrink, and updateWindow
            // already recomputes that range every frame.
            //
            // Rebuilding on a line-count change was the remaining half of the flicker: pressing Enter
            // recycled every line, and recycling clears highlights that are only republished after the
            // frame's style pass.
            forgetWidestLine();
            reprojectAfterEdit(change);
            foldingDirty = true;
            rebindRealisedLines();
            // A document that shrank can leave a selection pointing past its end, and the caret then
            // indexes a row that is not there. Clamped HERE rather than at the keystroke that caused it,
            // because undo is no longer the only way in: edit.undo from a menu or the palette, a
            // programmatic setText, and a server-pushed change all arrive through this one signal. It was
            // a hand-written line in the Ctrl+Z handler, which is precisely why moving that binding to the
            // keymap would otherwise have taken the clamp with it.
            selections.clampTo(buffer.length());
            onChanged.emit(buffer.toString());
        });

        previousLineCount = buffer.lineCount();
        projections.rebuild(buffer.document());

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
        viewCursorsPart.restartBlink();
        // These two EAGERLY, ahead of the frame's own pass. A caret that only moved on the next
        // updateWindow would lag every keystroke by a frame, which is the one place in this widget where
        // that is visible. The rest of the parts have nothing to say about a selection change and wait.
        selectionsPart.render(firstRealised, lastRealised);
        viewCursorsPart.render(firstRealised, lastRealised);
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

    /** Applies a set of per-caret changes as one edit, then carries the carets through it. */
    private void applyEdit(List<Change> changes) {
        if (readOnly) return;
        changes.removeIf(Change::isEmpty);
        if (changes.isEmpty()) return;
        ChangeSet edit = ChangeSet.of(buffer.length(), changes);
        buffer.edit(edit);
        selections.mapThrough(edit).collapseEachToHead();
        clearGoalColumns();
        viewCursorsPart.restartBlink();
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
                typeCharacter(typed);
                event.stopPropagation();
            }
        }, false, false);

        events.getGroup(MouseEvent.Down.class).attachListener((el, event) -> {
            if (!isEnabled()) return;
            int offset = offsetAt(event.getPosition().x(), event.getPosition().y());
            // Click count drives GRANULARITY, and the granularity is remembered for the whole drag --
            // see dragGranularity. 1 = character, 2 = word, 3 = line, as in VS Code's mouse handler.
            int clicks = Math.min(3, Math.max(1, event.getDetail()));
            boolean extend = CgModifiers.hasShift(CgPlatform.input().getCurrentModifiers());
            boolean addCaret = CgModifiers.hasAlt(CgPlatform.input().getCurrentModifiers());

            if (addCaret && clicks == 1) {
                // Alt+Click adds a caret, as in VS Code. Ctrl is left alone because Ctrl+Click is
                // "go to definition" everywhere it appears.
                addCaret(offset);
                dragGranularity = 1;
                dragAnchor = new int[] { offset, offset };
            } else {
                dragGranularity = clicks;
                dragAnchor = unitAt(offset, clicks);
                if (extend) {
                    setSelection(getAnchor(), offset);
                } else {
                    setSelection(dragAnchor[0], dragAnchor[1]);
                }
            }

            selecting = true;
            // THE PRESS SEEDS THE AUTOSCROLL POSITION. A drag can begin and the button come up with no
            // Move in between, and the autoscroll reads pointerY every tick -- so without this it would
            // steer by wherever the pointer was left after some earlier gesture, which may be off-screen
            // and therefore instantly "outside". This replaced a `pointerInside` flag that was set true
            // on the first Move and never set false again: it read as a guard and, after one mouse
            // movement anywhere over the editor, was permanently true.
            rememberPointer(event.getPosition().x(), event.getPosition().y());
            var window = getAttachedWindow();
            if (window != null) window.getInputHandler().setPointerCapture(this);
            requestFocusHere();
            event.stopPropagation();
        }, false, false);

        events.getGroup(MouseEvent.Move.class).attachListener((el, event) -> {
            rememberPointer(event.getPosition().x(), event.getPosition().y());
            if (!selecting) return;
            extendDragTo(offsetAt(event.getPosition().x(), event.getPosition().y()));
        }, false, false);

        events.getGroup(MouseEvent.Up.class).attachListener((el, event) -> {
            selecting = false;
            dragGranularity = 1;
            dragAnchor = null;
        }, false, false);
    }

    /** Records the pointer in this element's own space, for the autoscroll to steer by. */
    private void rememberPointer(float screenX, float screenY) {
        var local = screenToLocal(screenX, screenY);
        pointerX = local.x() - getRuntimeCache().getX();
        pointerY = local.y() - getRuntimeCache().getY();
    }

    private void requestFocusHere() {
        var window = getAttachedWindow();
        if (window != null) window.getInputHandler().requestFocus(this);
    }

    /** @return true when the key was a command rather than a character */
    private boolean handleKey(int key, int modifiers) {
        boolean shift = CgModifiers.hasShift(modifiers);
        boolean ctrl = CgModifiers.hasCtrl(modifiers) || CgModifiers.hasSuper(modifiers);

        boolean alt = CgModifiers.hasAlt(modifiers);

        // THE NAMED ACTIONS ARE NOT HERE ANY MORE. Every modified chord -- Mod+D, Alt+Up, Mod+Shift+K,
        // Mod+Slash, Mod+C/X/V, F3 and the rest -- is an EditorCommands command bound on this element's
        // own keymap, so it is rebindable, reachable from a menu and listable in a palette. See §H.
        //
        // What remains below is the keys that are not actions: cursor movement, deletion, Enter, Tab and
        // typing. That line is the resolver's own, not one invented here -- it skips bare bindings while
        // an element is taking text input, because a bare key belongs to the thing being typed into.
        //
        // The resolver runs AFTER dispatch and only on an unconsumed event, which is exactly why these
        // cases had to be deleted rather than left as a fallback: a `return true` here consumes the key
        // and the binding could never fire, so remapping would silently do nothing.

        if (ctrl) {
            // Undo and redo are NOT here either. They were the last modified chord still hard-coded, on
            // the reasoning that a widget may pre-empt an application command -- but that is exactly the
            // shape this section deleted everywhere else, and it made `edit.undo` the one command in the
            // engine that could be remapped and still not move. They are now bound on this editor's own
            // keymap to edit.undo/edit.redo, the ids UndoCommands already owns; inventing editor.undo
            // beside them would put two commands for one concept in every menu.
            if (key == CgKeyCodes.KEY_HOME) {
                moveCaretTo(0, shift);
                return true;
            }
            if (key == CgKeyCodes.KEY_END) {
                moveCaretTo(buffer.length(), shift);
                return true;
            }
        }

        // THE NATIVE KEYS MUST YIELD TO A MODIFIED CHORD. The resolver runs only on an UNCONSUMED event,
        // so a `case KEY_UP: ... return true` below would eat Alt+Up before `editor.moveLineUp` could ever
        // see it -- and remapping would silently do nothing. Alt is never part of native movement, so an
        // Alt-held arrow is always somebody's binding; Ctrl+Enter likewise, while Ctrl+Arrow and
        // Ctrl+Home/End genuinely ARE native movement and stay here.
        if (alt) return false;
        if (ctrl && key == CgKeyCodes.KEY_RETURN) return false;

        switch (key) {
            case CgKeyCodes.KEY_LEFT:
                moveHorizontally(-1, shift, ctrl);
                return true;
            case CgKeyCodes.KEY_RIGHT:
                moveHorizontally(1, shift, ctrl);
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
                // Wrapped, Home goes to the start of the VIEW line first -- the same rule smart home
                // already follows within a row: the nearest useful stop, then the further one.
                moveEach(head -> softWrap && !atViewLineStart(head)
                        ? MoveOperations.viewLineStart(buffer.document(), projections, head)
                        : MoveOperations.smartHome(buffer.document(), head), shift);
                return true;
            case CgKeyCodes.KEY_END:
                moveEach(head -> softWrap
                        ? MoveOperations.viewLineEnd(buffer.document(), projections, head)
                        : MoveOperations.lineEnd(buffer.document(), head), shift);
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
                        ctrl ? previousWordBoundary(head)
                             : TypeOperations.backspaceFrom(buffer.document(), head, indentWidth),
                        head });
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

    // ── Movement ────────────────────────────────────────────────────────────────────────────────

    /** Absolute move — collapses to one caret, which is what Ctrl+Home/End mean. */
    private void moveCaretTo(int offset, boolean extend) {
        clearGoalColumns();
        setSelection(extend ? getAnchor() : offset, offset);
        ensureCaretVisible();
    }

    private void moveHorizontally(int direction, boolean extend, boolean byWord) {
        clearGoalColumns();
        selections.setAll(MoveOperations.horizontal(buffer.document(), selections.all(),
                direction, extend, byWord, wordClassifier), selections.primaryIndex());
        afterSelectionChange();
        ensureCaretVisible();
    }

    /**
     * Moves <b>every</b> caret by a function of its own head.
     *
     * <p>Every horizontal and line-relative movement goes through here, so "does this work with several
     * carets?" stops being a question that has to be asked once per key.</p>
     */
    private void moveEach(java.util.function.IntUnaryOperator move, boolean extend) {
        clearGoalColumns();
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

    /** Deletes every non-empty selection — public because {@code editor.cut} is a command. */
    public void deleteSelections() {
        List<Change> changes = new ArrayList<>(selections.count());
        for (Selection selection : selections.all()) {
            if (!selection.isEmpty()) changes.add(Change.delete(selection.start(), selection.end()));
        }
        applyEdit(changes);
    }

    /**
     * Moves the caret by whole rows, remembering the column it started from.
     *
     * <p>{@link #goalColumns} is what makes down-then-up return to where it began rather than being
     * dragged inward by the shortest line passed through.</p>
     */
    private void moveVertically(int rows, boolean extend) {
        // Wrapped, Up/Down move by VISUAL row -- otherwise a long paragraph is one keypress tall.
        var result = softWrap
                ? MoveOperations.verticalInView(buffer.document(), projections, selections.all(),
                        goalColumns, rows, extend)
                : MoveOperations.vertical(buffer.document(), selections.all(), goalColumns, rows, extend);
        selections.setAll(result.selections(), selections.primaryIndex());
        // Only keep the goals if nothing merged; otherwise the indices no longer line up.
        goalColumns = selections.count() == result.goalColumns().length ? result.goalColumns() : new int[0];
        afterSelectionChange();
        ensureCaretVisible();
    }

    private void clearGoalColumns() {
        if (goalColumns.length != 0) goalColumns = new int[0];
    }

    /** Whether a caret already sits at the start of its own view line — what makes Home a toggle. */
    private boolean atViewLineStart(int head) {
        return head == MoveOperations.viewLineStart(buffer.document(), projections, head);
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
    float viewportHeight() {
        return Math.max(0f, getClientHeight() - horizontalBarThickness());
    }

    /** The horizontal bar's thickness when it is showing, otherwise zero. */
    float horizontalBarThickness() {
        if (getMaxScrollLeft() <= 0f) return 0f;
        return Math.max(0f, horizontalScroller().getRuntimeCache().getHeight());
    }

    /** The vertical bar's thickness when it is showing, otherwise zero. */
    float verticalBarThickness() {
        if (getMaxScrollTop() <= 0f) return 0f;
        return Math.max(0f, verticalScroller().getRuntimeCache().getWidth());
    }

    /**
     * Word-right — the END of the next word, per {@link WordOperations}.
     *
     * <p>These used to classify with {@code Character.isLetterOrDigit}, which makes {@code _} a separator
     * and so splits {@code foo_bar} into two words, and they materialised the whole document with
     * {@code toString()} on every keypress. Both are fixed by the port.</p>
     */
    private int nextWordBoundary(int from) {
        return WordOperations.nextWordEnd(buffer.document(), from, wordClassifier);
    }

    /** Word-left — the START of the previous word. Deliberately not the mirror of the above. */
    private int previousWordBoundary(int from) {
        return WordOperations.previousWordStart(buffer.document(), from, wordClassifier);
    }

    /** Double-click selection, using the ported {@link WordOperations#wordAt}. */
    private void selectWordAt(int offset) {
        int[] word = WordOperations.wordAt(buffer.document(), offset, wordClassifier);
        if (word == null) {
            setCaret(offset);
            return;
        }
        setSelection(word[0], word[1]);
    }

    // ── Geometry ────────────────────────────────────────────────────────────────────────────────

    /** Seconds per full blink cycle; {@code 0} keeps the caret solid. */
    public TextEditor setCaretBlinkSeconds(float seconds) {
        viewCursorsPart.setBlinkSeconds(seconds);
        viewCursorsPart.restartBlink();
        return this;
    }

    /**
     * The height of one row, in pixels.
     *
     * <p><b>{@code line-height} is a unitless multiplier of the font size</b>, exactly as in CSS — not a
     * pixel height. Reading it as pixels compiles, runs, and produces rows a few pixels tall with the
     * text drawn over the top of itself; the property's own accessor documents the multiplier and it is
     * still the obvious thing to get wrong, so the conversion lives here in one place.</p>
     */
    public float lineHeight() {
        var general = getStyle().getGeneralGroup();
        float multiplier = general.lineHeight();
        if (multiplier <= 0f) multiplier = 1.2f;
        return Math.max(1f, general.fontSize() * multiplier);
    }

    /**
     * The width of the widest realised line, in editor coordinates.
     *
     * <p>Overridden for the same reason {@link #getScrollHeight()} is, and newly <b>necessary</b> rather
     * than merely tidy: {@code UIElement.getScrollWidth} walks direct children and skips scroll-exempt
     * ones, and the text now lives inside a scroll-exempt viewport. Without this the editor reports zero
     * content width, the horizontal bar never appears, and a long line simply cannot be scrolled to.</p>
     *
     * <p><b>The widest line SEEN, not the widest currently on screen.</b> A virtualised editor cannot know
     * the widest line in the document without measuring every one of them, which for a large file means
     * shaping every one of them. So this remembers the widest it has realised so far — and remembering is
     * what makes the bar stable: measuring only the realised lines meant scrolling to the end of a file,
     * where the last rows are a brace and a blank, collapsed the content width and the horizontal bar
     * disappeared underneath the pointer.</p>
     *
     * <p>The memory is reset whenever it could be wrong — an edit, a font change, a reprojection — rather
     * than being allowed to ratchet up forever. Deleting the one long line in a file must give the width
     * back, and a high-water mark that never falls would not.</p>
     *
     * <p><b>A pure accessor. The measuring is {@link #measureWidestRealisedLine()}'s, once a frame.</b>
     * This used to do the scan itself, and it is asked far more often than it looks: {@code getMaxScrollLeft}
     * reads it, {@code horizontalBarThickness} reads that, {@code viewportHeight} reads that, and
     * {@code getScrollHeight} reads {@code viewportHeight} — so a dozen callers that each look like a field
     * access fan back into the same loop. <b>Measured at 54 entries per settled frame</b> on a 500-line
     * document, each walking every realised line and doing two or three row-metric lookups: about 6,500
     * map probes a frame with nothing on screen changing. It is the same trap
     * {@link #refreshGutterMetrics} already documents — a cheap-looking read behind an accessor that the
     * layout code calls per line — and it is fixed the same way, by separating the once-a-frame
     * measurement from the O(1) query.</p>
     */
    @Override
    public float getScrollWidth() {
        return textViewport == null ? 0f : widestSeen;
    }

    /**
     * Grows the high-water mark to cover every realised line. Called once per {@code updateWindow}.
     *
     * <p>Stale by at most a frame, which the high-water mark already was — it only ever grows between
     * resets, so a query taken before this frame's scan sees the previous frame's answer for a line that
     * has not moved.</p>
     */
    private void measureWidestRealisedLine() {
        if (textViewport == null) return;
        int count = viewLineCount();
        float origin = textOriginX();
        for (Map.Entry<Integer, UIElement> entry : realisedLines.entrySet()) {
            int viewLine = entry.getKey();
            if (viewLine < 0 || viewLine >= count) continue;
            ProjectedLines.ModelPosition model = modelAt(viewLine);
            float end = xOfView(viewLine, projectionAt(viewLine).maxColumn(model.viewLineInRow()));
            widestSeen = Math.max(widestSeen, origin + end + 1f);
        }
    }

    /** Forgets the widest line, so a shorter document reports a smaller scroll width. */
    private void forgetWidestLine() {
        widestSeen = 0f;
    }

    @Override
    /**
     * The document's height, <b>plus the strip the horizontal scrollbar covers</b>.
     *
     * <p>Without the extra, the last line can never be scrolled clear of the bar: {@code getMaxScrollTop}
     * is {@code scrollHeight - getClientHeight()}, and {@code getClientHeight()} is the whole box, so the
     * scroll clamps exactly one bar-thickness short of where the caret needs it. Adding the strip to the
     * scrollable extent is the same thing every editor does by leaving trailing space below the last
     * line.</p>
     */
    public float getScrollHeight() {
        float content = viewLineCount() * lineHeight();
        // VS Code's ViewLayout._getTotalHeight, including the part that is easy to miss: the horizontal
        // bar's allowance is the ELSE branch. Scrolling past the end already leaves a viewport of empty
        // space below the last line, so adding the bar's thickness on top would be a second allowance for
        // the
        // same problem -- the last line would stop a bar's height short of where it should.
        if (scrollBeyondLastLine) {
            return content + Math.max(0f, viewportHeight() - lineHeight());
        }
        return content + horizontalBarThickness();
    }

    /** Document offset nearest a point in this element's own space. */
    public int offsetAt(float screenX, float screenY) {
        var local = screenToLocal(screenX, screenY);
        return offsetAtLocal(local.x() - getRuntimeCache().getX(), local.y() - getRuntimeCache().getY());
    }

    /**
     * The same, from coordinates already relative to this element's top-left.
     *
     * <p>Resolves through the <b>view line</b>, so a click on a wrapped continuation lands in the middle
     * of its row rather than at the row's start. The x search is still over the row's prefix widths —
     * rebased by the view line's origin — for the reason {@link #xOfView} gives.</p>
     */
    private int offsetAtLocal(float localX, float localY) {
        float relativeY = localY - textOriginY() + getScrollTop();
        int viewLine = Math.max(0, Math.min(viewLineCount() - 1, (int) (relativeY / lineHeight())));

        ProjectedLines.ModelPosition model = modelAt(viewLine);
        LineProjection projection = projectionAt(viewLine);
        int row = model.row();

        // The search runs only over this view line's own span, so a click past the end of a wrapped
        // segment clamps to that segment's end rather than jumping to a same-x position further down the
        // row -- which is what makes clicking in the blank area right of a wrap feel correct.
        int fromColumn = projection.viewLineStart(model.viewLineInRow());
        int toColumn = projection.viewLineEnd(model.viewLineInRow());

        RowMetrics metrics = rowMetrics(row);
        float[] widths = metrics.widths();
        float origin = widthOf(row, fromColumn);
        float relativeX = localX + getScrollLeft() - textOriginX() - carriedIndentPx(viewLine) + origin;

        int fromIndex = Math.max(0, Math.min(metrics.line().displayIndexOf(fromColumn), widths.length - 1));
        int toIndex = Math.max(fromIndex, Math.min(metrics.line().displayIndexOf(toColumn), widths.length - 1));

        int best = fromIndex;
        float bestDistance = Float.MAX_VALUE;
        for (int i = fromIndex; i <= toIndex; i++) {
            float distance = Math.abs(widths[i] - relativeX);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        // The nearest DISPLAY index, mapped back to a document column. Without the map a click past a
        // tab lands as many columns too far right as the tab was wide.
        int column = metrics.line().columnOf(best);
        return buffer.document().lineStartOffset(row) + column;
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
    float textOriginX() {
        return getTaffyLayout().padding().left + gutterWidth + codeLeftPad();
    }

    /**
     * The margin between the gutter's edge and the first character.
     *
     * <p>Two jobs, and the second is not obvious. It stops the first glyph of an unindented line sitting
     * on the gutter's border — and it gives the <b>level-0 indent guide somewhere to be</b>. A guide drawn
     * at the text origin is exactly where an unindented glyph starts, so the vertical run breaks around
     * that letter; drawn any further left it lands under the gutter, which has a higher z-index and paints
     * straight over it. The gap is the only place it can live.</p>
     */
    float codeLeftPad() {
        // Zero without a gutter: the margin exists to separate the code FROM the gutter, so with no
        // gutter there is nothing to separate from and the text belongs on the content-box origin.
        if (!gutterVisible) return 0f;
        // The gutter's margin-right. Margin is the honest property for it -- it is space OUTSIDE the
        // gutter's painted box, which is precisely why the gutter's edge can live in it without being
        // covered by the gutter's own background.
        return cachedCodeLeftPad;
    }

    /** Whether the line-number gutter is shown. */
    public TextEditor setGutterVisible(boolean visible) {
        if (this.gutterVisible == visible) return this;
        this.gutterVisible = visible;
        measuredRows.clear();
        invalidateWindow();
        return this;
    }

    /** The height available for text — the client box, less whatever the horizontal scrollbar covers. */
    public float getViewportHeight() {
        return viewportHeight();
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
    private void refreshHighlights(int firstViewLine, int lastViewLine) {
        if (lastViewLine < firstViewLine || realisedLines.isEmpty()) return;

        int from = viewLineStartOffset(Math.max(0, firstViewLine));
        int to = viewLineEndOffset(Math.max(0, Math.min(lastViewLine, viewLineCount() - 1)));
        if (!highlightsDirty && from == highlightedFrom && to == highlightedTo) return;
        highlightedFrom = from;
        highlightedTo = to;
        highlightsDirty = false;

        List<SyntaxToken> tokens = tokenizer == SyntaxTokenizer.NONE
                ? List.<SyntaxToken>of()
                : tokenizer.tokenize(buffer.document(), from, to);
        for (Map.Entry<Integer, UIElement> entry : realisedLines.entrySet()) {
            int viewLine = entry.getKey();
            if (viewLine < 0 || viewLine >= viewLineCount()) continue;
            // Ranges are offsets into the UIText this line owns, and that text is one VIEW line -- so a
            // wrapped row's second half must publish ranges relative to where IT starts. Using the row's
            // start would push every colour on a continuation line left by the width of everything above
            // it, which reads as the highlighter losing sync rather than as a coordinate bug.
            int lineStart = viewLineStartOffset(viewLine);
            int lineEnd = viewLineEndOffset(viewLine);

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

            // CLAMPED to what is actually painted. A collapsed header stops drawing its trailing bracket,
            // so a token covering it would publish a range past the end of the string. Correct to do
            // unconditionally -- a range beyond the text is never meaningful, folding or not.
            int painted = textOf(entry.getValue()).getText().length();
            for (List<TextRange> ranges : byName.values()) {
                for (int i = ranges.size() - 1; i >= 0; i--) {
                    TextRange range = ranges.get(i);
                    int start = Math.min(range.start(), painted);
                    int end = Math.min(range.end(), painted);
                    // DROPPED, not clamped to empty. TextRange refuses a zero-width range outright, so
                    // building one to filter it afterwards throws instead of filtering -- and the range
                    // that collapses is the bracket-match on the very brace the chip took over, i.e. the
                    // single most likely one to exist here.
                    if (end <= start) ranges.remove(i);
                    else if (start != range.start() || end != range.end()) ranges.set(i, TextRange.of(start, end));
                }
            }
            byName.values().removeIf(List::isEmpty);

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

    public TextEditor setLanguage(Language newLanguage) {
        this.language = newLanguage == null ? Language.PLAIN : newLanguage;
        return this;
    }

    public Language language() {
        return language;
    }

    /**
     * Read-only refuses edits but not navigation, selection or copying.
     *
     * <p>Enforced in {@link #applyEdit} — the single place every mutation funnels through — rather than at
     * each key. A per-key check is a list to keep in step with the key handler, and the failure when one
     * is missed is a read-only document that quietly changed.</p>
     */
    public TextEditor setReadOnly(boolean value) {
        this.readOnly = value;
        return this;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public TextEditor setAutoCloseBrackets(boolean value) {
        this.autoCloseBrackets = value;
        return this;
    }

    /** Overrides the word-separator set — see {@link WordClassifier#DEFAULT_SEPARATORS}. */
    public TextEditor setWordSeparators(String separators) {
        this.wordClassifier = new WordClassifier(separators);
        return this;
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
        applyEditKeepingSelection(new ArrayList<>(
                LineOperations.indent(buffer.document(), touchedRows(), indentWidth)));
    }

    /** Removes up to one indent level from every line touched by a selection. */
    private void outdentSelectedLines() {
        applyEditKeepingSelection(new ArrayList<>(
                LineOperations.outdent(buffer.document(), touchedRows(), indentWidth)));
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
        if (readOnly) return;
        changes.removeIf(Change::isEmpty);
        if (changes.isEmpty()) return;
        ChangeSet edit = ChangeSet.of(buffer.length(), changes);
        buffer.edit(edit);
        selections.mapThrough(edit);
        viewCursorsPart.restartBlink();
        ensureCaretVisible();
        onSelectionChanged.emit();
    }

    // ── Mouse selection ─────────────────────────────────────────────────────────────────────────

    private int[] unitAt(int offset, int clicks) {
        return MouseSelection.unitAt(buffer.document(), offset, clicks, wordClassifier);
    }

    private void extendDragTo(int offset) {
        if (dragAnchor == null) {
            setSelection(getAnchor(), offset);
            return;
        }
        Selection extended = MouseSelection.extend(
                buffer.document(), dragAnchor, offset, dragGranularity, wordClassifier);
        setSelection(extended.anchor(), extended.head());
    }

    /**
     * Scrolls while a selection drag is held outside the viewport.
     *
     * <p>Without it a drag simply stops at the edge, and selecting more than a screenful needs a scroll
     * with the other hand. The rate grows with how far outside the pointer is — a fixed step feels
     * unresponsive near the edge and uncontrollable far from it.</p>
     */
    private void autoScrollDuringDrag(float deltaSeconds) {
        if (!selecting) return;
        float viewport = viewportHeight();
        float outside = 0f;
        if (pointerY < 0f) outside = pointerY;
        else if (pointerY > viewport) outside = pointerY - viewport;
        if (outside == 0f) return;

        // Roughly a line per 10px outside per second, which is about what VS Code feels like.
        float step = Math.signum(outside) * Math.min(Math.abs(outside), 200f) * deltaSeconds * 1.5f;
        setScrollImmediate(getScrollLeft(), getScrollTop() + step);
        extendDragTo(offsetAtLocal(pointerX, Math.max(0f, Math.min(pointerY, viewport))));
    }

    // ── Typing aids ─────────────────────────────────────────────────────────────────────────────

    /**
     * One typed character, with the three behaviours that make brackets bearable.
     *
     * <ul>
     *   <li><b>Surround</b> — an opener typed over a selection wraps it instead of replacing it. Replacing
     *       is what a bare insert would do, and losing a selection to a stray bracket is unrecoverable
     *       without undo.</li>
     *   <li><b>Type-over</b> — a closer typed where that closer already sits steps past it. Without this,
     *       auto-closing is actively worse than not having it: you get {@code ()} and then {@code ())}
     *       because typing the close you expected to need inserts a second one.</li>
     *   <li><b>Auto-close</b> — an opener inserts its partner behind the caret.</li>
     * </ul>
     */
    private void typeCharacter(char typed) {
        if (readOnly) return;
        Character closer = language.closerFor(typed);

        if (selections.hasSelection() && closer != null) {
            applyEditKeepingSelection(new ArrayList<>(
                    TypeOperations.surround(selections.all(), typed, closer)));
            return;
        }
        if (autoCloseBrackets && language.isCloser(typed)
                && TypeOperations.nextCharIs(buffer.document(), selections.all(), typed)) {
            moveEach(head -> head + 1, false);
            return;
        }
        if (autoCloseBrackets && closer != null && TypeOperations.shouldAutoClose(
                buffer.document(), selections.all(), typed, language, wordClassifier)) {
            insertAtCaret(String.valueOf(typed) + closer);
            // Put the caret between the pair rather than after it.
            moveEach(head -> head - 1, false);
            return;
        }
        insertAtCaret(String.valueOf(typed));
    }






    // ── Multi-caret commands ────────────────────────────────────────────────────────────────────

    /**
     * Adds a caret at the next occurrence of the current selection — {@code Ctrl+D}.
     *
     * <p>With an empty caret it selects the word under it first, which is what makes the key usable from
     * a bare caret: press once to select the word, again for the next one. Wraps, so the last occurrence
     * leads back to the first rather than doing nothing.</p>
     */
    public boolean addCaretAtNextOccurrence() {
        Selection primary = selections.primary();
        if (primary.isEmpty()) {
            int[] word = WordOperations.wordAt(buffer.document(), primary.head(), wordClassifier);
            if (word == null) return false;
            setSelection(word[0], word[1]);
            return true;
        }

        String needle = buffer.document().slice(primary.start(), primary.end()).toString();
        if (needle.isEmpty()) return false;
        String haystack = buffer.toString();

        int from = selections.all().get(selections.count() - 1).end();
        int at = haystack.indexOf(needle, from);
        if (at < 0) at = haystack.indexOf(needle);
        if (at < 0) return false;
        for (Selection existing : selections.all()) {
            if (existing.start() == at) return false;   // already have this one
        }
        selections.add(new Selection(at, at + needle.length()));
        afterSelectionChange();
        ensureCaretVisible();
        return true;
    }

    /** A caret at every occurrence of the selection — {@code Ctrl+Shift+L}. */
    public int selectAllOccurrences() {
        Selection primary = selections.primary();
        if (primary.isEmpty() && !addCaretAtNextOccurrence()) return 0;
        primary = selections.primary();
        String needle = buffer.document().slice(primary.start(), primary.end()).toString();
        if (needle.isEmpty()) return 0;

        String haystack = buffer.toString();
        List<Selection> found = new ArrayList<>();
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found.add(new Selection(at, at + needle.length()));
            at = haystack.indexOf(needle, at + needle.length());
        }
        if (found.isEmpty()) return 0;
        selections.setAll(found, found.size() - 1);
        afterSelectionChange();
        ensureCaretVisible();
        return found.size();
    }

    /**
     * A caret on the line above or below every existing one — {@code Ctrl+Alt+Up/Down}.
     *
     * <p>Columns are kept, so a column of carets stays a column even down a ragged block; a line too short
     * to reach the column contributes a caret at its end rather than none, which is what keeps the set
     * rectangular enough to type into.</p>
     */
    public boolean addCaretOnAdjacentLine(int direction) {
        List<Selection> added = new ArrayList<>(selections.all());
        boolean any = false;
        for (Selection selection : selections.all()) {
            TextPoint point = buffer.offsetToPoint(selection.head());
            int row = point.row() + direction;
            if (row < 0 || row >= buffer.lineCount()) continue;
            added.add(Selection.caret(buffer.pointToOffset(new TextPoint(row, point.column()))));
            any = true;
        }
        if (!any) return false;
        selections.setAll(added, added.size() - 1);
        afterSelectionChange();
        ensureCaretVisible();
        return true;
    }

    // ── Line operations ─────────────────────────────────────────────────────────────────────────

    /** Selects the whole line (or extends to the next one when it is already selected) — {@code Ctrl+L}. */
    public void selectLine() {
        Selection primary = selections.primary();
        int firstRow = buffer.offsetToPoint(primary.start()).row();
        int lastRow = buffer.offsetToPoint(primary.end()).row();
        int start = buffer.document().lineStartOffset(firstRow);
        int end = Math.min(buffer.length(), buffer.document().lineEndOffset(lastRow) + 1);
        if (primary.start() == start && primary.end() == end && lastRow + 1 < buffer.lineCount()) {
            end = Math.min(buffer.length(), buffer.document().lineEndOffset(lastRow + 1) + 1);
        }
        setSelection(start, end);
    }

    /** Deletes every line any caret touches — {@code Ctrl+Shift+K}. */
    public void deleteLines() {
        applyEdit(new ArrayList<>(LineOperations.delete(buffer.document(), touchedRows())));
    }

    /** Copies every touched line above or below itself — {@code Shift+Alt+Up/Down}. */
    public void duplicateLines(int direction) {
        applyEditKeepingSelection(new ArrayList<>(
                LineOperations.duplicate(buffer.document(), touchedRows(), direction)));
    }

    /** Moves every touched line up or down one — {@code Alt+Up/Down}. */
    public void moveLines(int direction) {
        LineOperations.Move move = LineOperations.move(buffer.document(), touchedRows(), direction);
        if (move == null) return;

        List<Selection> moved = new ArrayList<>();
        for (Selection selection : selections.all()) {
            moved.add(new Selection(selection.anchor() + move.shift(), selection.head() + move.shift()));
        }
        applyEditKeepingSelection(new ArrayList<>(List.of(move.change())));
        selections.setAll(moved, selections.primaryIndex());
        afterSelectionChange();
        ensureCaretVisible();
    }

    /** Opens a line below (or above) each caret — {@code Ctrl+Enter}. */
    public void insertLine(int direction) {
        applyEdit(new ArrayList<>(
                LineOperations.insertLine(buffer.document(), touchedRows(), direction)));
    }

    /** Joins each touched line with the one after it. */
    public void joinLines() {
        applyEdit(new ArrayList<>(LineOperations.join(buffer.document(), touchedRows())));
    }

    // ── Comments ────────────────────────────────────────────────────────────────────────────────

    /** Toggles the line comment on every touched line — {@code Ctrl+/}. */
    public void toggleLineComment() {
        applyEditKeepingSelection(new ArrayList<>(
                LineOperations.toggleLineComment(buffer.document(), touchedRows(), language)));
    }

    /** Wraps or unwraps the selection in a block comment. */
    public void toggleBlockComment() {
        if (!language.hasBlockComment()) return;
        Selection primary = selections.primary();
        if (primary.isEmpty()) {
            toggleLineComment();
            return;
        }
        String open = language.blockCommentStart();
        String close = language.blockCommentEnd();
        String selected = buffer.document().slice(primary.start(), primary.end()).toString();
        String replacement = selected.startsWith(open) && selected.endsWith(close)
                ? selected.substring(open.length(), selected.length() - close.length())
                : open + selected + close;
        applyEditKeepingSelection(new ArrayList<>(List.of(
                new Change(primary.start(), primary.end(), replacement))));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────



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

    /** Searches for the word under the caret — {@code Ctrl+F3}. */
    public boolean findWordUnderCaret() {
        Selection primary = selections.primary();
        int start = primary.start();
        int end = primary.end();
        if (primary.isEmpty()) {
            int[] word = WordOperations.wordAt(buffer.document(), primary.head(), wordClassifier);
            if (word == null) return false;
            start = word[0];
            end = word[1];
        }
        if (end <= start) return false;
        find(buffer.document().slice(start, end).toString(), false);
        return findNext();
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
            // No else. `next` starts at 0 and stays there, which IS the wrap: running off the end of a
            // document whose last match is behind the caret returns to the first one.
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
     * The gutter's total width — <b>three</b> parts, following IntelliJ's.
     *
     * <pre>
     *   |&lt;- pad -&gt;|&lt;- digits, right-aligned -&gt;|&lt;----- fold column ----&gt;| code
     * </pre>
     *
     * <p>The fold column is the part that was missing, and it is not decoration. Without it the gutter
     * ends exactly where the text begins, so the first glyph of an unindented line sits <em>on</em> the
     * gutter's edge — a {@code p} with its descender crossing the border. Every editor leaves this gap;
     * IntelliJ and VS Code both fill it with fold arrows, which is also where one would go here.</p>
     *
     * <p>Derived from the font size rather than fixed, so the gutter stays proportionate when the editor
     * is zoomed — the numbers inside it scale, and a constant gap would look generous at 8px and cramped
     * at 24.</p>
     */
    private float measureGutter() {
        if (!gutterVisible) return 0f;
        return gutterNumberWidth() + gutterFoldWidth();
    }

    /**
     * The margin before the digits — the gutter's own {@code padding-left}, from the stylesheet.
     *
     * <p>A widest-number field is right-aligned, so without this the longest number in the file — the one
     * that fills the field exactly — sits flush against the gutter's left edge. Three digits touching the
     * border is what a two-digit file never shows and a hundred-line file always does.</p>
     *
     * <p><b>Read back from the cascade rather than computed here</b>, along with {@link #gutterFoldWidth}
     * and {@link #codeLeftPad}. All three were Java constants of the {@code max(6f, fontSize * 0.9f)}
     * kind, which is exactly what this project's own rule forbids: a pixel value in a widget belongs in
     * {@code default.css}. A theme can now change the gutter's proportions without touching Java, and the
     * three numbers sit next to each other in the sheet where they can be compared.</p>
     */
    float gutterPadLeft() {
        return cachedPadLeft;
    }

    /**
     * Re-reads the three metrics from the cascade. <b>Once per frame, never per use.</b>
     *
     * <p>{@link #textOriginX} calls {@link #codeLeftPad}, and {@code textOriginX} is called for every
     * line, every indent guide, every whitespace marker, every caret and every selection band. Reading
     * the cascade inside it turned a two-field addition into <b>78 style lookups per frame</b> on a
     * 32-line document with every decoration switched off, scaling with the visible line count and with
     * each feature turned on. Measured, after the harness went unresponsive.</p>
     *
     * <p>The values are pure CSS — no font, no layout — so one read per frame is always current.</p>
     */
    private void refreshGutterMetrics() {
        cachedPadLeft = gutterMetric(LayoutProperties.PADDING_LEFT);
        cachedFoldWidth = gutterMetric(LayoutProperties.PADDING_RIGHT);
        cachedCodeLeftPad = gutterMetric(LayoutProperties.MARGIN_RIGHT);
    }

    /**
     * One of the gutter's metrics, as the cascade computed it.
     *
     * <p>Read from the <b>computed style</b> rather than from the laid-out box, because
     * {@code getTaffyLayout()} is protected and the gutter is a plain {@code UIElement} — Java's protected
     * access does not reach another instance's. Reading the cascade is the better answer anyway: it is
     * available before the first layout pass, so the gutter is the right width on the frame it appears
     * rather than on the one after.</p>
     *
     * <p>Only absolute lengths are honoured. A percentage here would resolve against the gutter's own
     * width, which is computed <em>from</em> these three values — so it would be circular, and silently
     * returning something plausible is worse than ignoring it.</p>
     */
    private float gutterMetric(StyleProperty<LengthPercentageAuto> property) {
        LengthPercentageAuto value = gutter.getStyle().getLayoutGroup().getValueSave(property);
        if (value == null || value.getType() != LengthPercentageAuto.Type.LENGTH) return 0f;
        return Math.max(0f, value.getValue());
    }

    /**
     * The digits themselves, sized to the widest number the document can reach.
     *
     * <p><b>The shaped width of a "0" is cached.</b> {@code measureGutter} runs every frame, so this was
     * a text-shaping call per frame for a value that only moves when the font does. Keyed on the same
     * font key {@code rowMetrics} uses, so the two invalidate together.</p>
     */
    private float gutterDigitsWidth() {
        int digits = Math.max(2, String.valueOf(Math.max(1, buffer.lineCount())).length());
        var general = getStyle().getGeneralGroup();
        String fontKey = general.fontFamily() + "/" + general.fontSize();
        if (digitWidth < 0f || !fontKey.equals(digitWidthFontKey)) {
            digitWidth = CgTextLayout.of("0", resolveFamily()).build().totalWidth();
            digitWidthFontKey = fontKey;
        }
        return digits * digitWidth;
    }

    /** The numbers' own column: margin, digits, margin. */
    float gutterNumberWidth() {
        if (!gutterVisible) return 0f;
        return gutterPadLeft() + gutterDigitsWidth() + gutterPadLeft();
    }

    /**
     * The clear column between the numbers and the code — the gutter's {@code padding-right}.
     *
     * <p>Wide on purpose. It is what pushes the numbers towards the left edge of the gutter and away from
     * the text, which is the whole reason IntelliJ's gutter reads as a margin rather than as a column of
     * numbers jammed against the code. It is also where a fold arrow goes, when there is one.</p>
     */
    float gutterFoldWidth() {
        return gutterVisible ? cachedFoldWidth : 0f;
    }

    /** The vertical equivalent, for the same reason. */
    // ── Seams for the view parts ────────────────────────────────────────────────────────────────
    //
    // Package-private, and deliberately not public API. A view part is a piece of THIS widget rather
    // than a client of it, so these widen access without widening the contract -- see EditorViewPart
    // for why the parts sit beside the editor instead of behind a Monaco-style ViewContext.

    /** The model-row to view-line projection every part resolves geometry through. */
    ProjectedLines projections() {
        return projections;
    }

    /** The digits' measured width, cached alongside {@code gutterWidth} so the two never disagree. */
    float gutterNumbersWidth() {
        return gutterNumbersWidth;
    }

    /** The horizontal bar. {@code horizontalScroller()} is protected, so a sibling part cannot reach it. */
    UIElement horizontalScrollerElement() {
        return horizontalScroller();
    }

    /** The editor's own padding-left, which every absolutely-placed child is measured from. */
    float paddingLeft() {
        return getTaffyLayout().padding().left;
    }

    /** Whether blocks end by dedenting alone — Python, YAML. Drives the indent guides only. */
    boolean isOffSideLanguage() {
        return offSideLanguage;
    }

    /** The ruler columns, uncopied. {@link #getRulers()} clones, which a per-frame pass must not. */
    int[] rulerColumns() {
        return rulers;
    }


    /** The font key row measurements are currently keyed on — see {@code WhitespacePart}. */
    String measuredFontKey() {
        return measuredFontKey;
    }

    float textOriginY() {
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
    RowMetrics rowMetrics(int row) {
        var general = getStyle().getGeneralGroup();
        String fontKey = general.fontFamily() + "/" + general.fontSize();
        if (!fontKey.equals(measuredFontKey)) {
            measuredRows.clear();
            measuredFontKey = fontKey;
            textHeight = -1f;
        }
        return measuredRows.computeIfAbsent(row, r -> measureRow(buffer.line(r)));
    }

    /**
     * Drops the row measurements an edit invalidated — <b>one row where that is provably enough</b>.
     *
     * <p>{@link #measuredRows} is keyed by row index, so an edit that adds or removes a line renumbers
     * every row below it and the whole map has to go. An edit that leaves the count alone renumbers
     * nothing: only the row it landed on can have changed, and every other measurement is still exactly
     * right. That is the overwhelmingly common case — it is what ordinary typing is.</p>
     *
     * <p>It matters because a measurement is a <b>text shaping call</b>, not a lookup, and
     * {@code rebindRealisedLines} re-lays out every line on screen after each edit. Clearing the map
     * unconditionally therefore re-shaped the entire viewport on every keystroke: <b>measured at 4.0 ms
     * per keystroke</b> on a 500-line document, most of it re-deriving lines the edit never touched.</p>
     *
     * <p>Multi-caret edits take the wholesale path. They touch several disjoint rows and the bookkeeping
     * is not worth it, which is the same call {@link #reprojectAfterEdit} makes about the same edits.</p>
     */
    private void invalidateMeasuredRows(ChangeSet change) {
        List<Change> changes = change.changes();
        if (changes.size() != 1 || buffer.lineCount() != previousLineCount) {
            measuredRows.clear();
            return;
        }
        int at = Math.max(0, Math.min(change.mapPos(changes.get(0).from(), -1), buffer.length()));
        measuredRows.remove(buffer.document().offsetToPoint(at).row());
    }

    /** Expands tabs through {@link CursorColumns} and measures the result. */
    private RowMetrics measureRow(String line) {
        CursorColumns.Line expanded = CursorColumns.expand(line, tabSize);
        CgFontFamily family = FontFamilyCache.resolve(getStyle().getGeneralGroup().fontFamily(),
                Math.round(getStyle().getGeneralGroup().fontSize()));
        return new RowMetrics(expanded, caretOffsets(expanded.display(), family));
    }

    public TextEditor setTabSize(int size) {
        this.tabSize = Math.max(1, size);
        measuredRows.clear();
        reproject();
        invalidateWindow();
        return this;
    }

    public int getTabSize() {
        return tabSize;
    }

    // ── Soft wrap ───────────────────────────────────────────────────────────────────────────────

    /**
     * Wraps long lines to the viewport instead of scrolling horizontally.
     *
     * <p><b>A view setting, not an edit.</b> The document is untouched — no newline is inserted, nothing
     * is reflowed on disk, and {@link #getText()} returns the same string either way. That is the entire
     * distinction between soft and hard wrap, and it is the same document-versus-view boundary the undo
     * stack draws: toggling this is not undoable, because there is nothing to undo.</p>
     */
    public TextEditor setSoftWrap(boolean value) {
        if (softWrap == value) return this;
        this.softWrap = value;
        // Horizontal scrolling is meaningless once nothing overflows, and leaving a stale offset behind
        // would shift every line sideways with no bar left to bring them back.
        if (value) setScrollLeft(0f);
        reproject();
        invalidateWindow();
        return this;
    }

    public boolean isSoftWrap() {
        return softWrap;
    }

    /** How much of a row's indentation its continuation lines carry. Default {@link WrapIndent#SAME}. */
    public TextEditor setWrapIndent(WrapIndent indent) {
        this.wrapIndent = indent == null ? WrapIndent.NONE : indent;
        reproject();
        invalidateWindow();
        return this;
    }

    public WrapIndent getWrapIndent() {
        return wrapIndent;
    }

    // ── §G view decorations ─────────────────────────────────────────────────────────────────────

    /** Vertical lines marking each indent level. */
    public TextEditor setIndentGuidesVisible(boolean value) {
        this.indentGuidesVisible = value;
        invalidateWindow();
        return this;
    }

    public boolean isIndentGuidesVisible() {
        return indentGuidesVisible;
    }

    /**
     * Whether the language's blocks end by dedenting alone — Python, YAML.
     *
     * <p>Changes exactly one thing: what a blank line after a deeper block guides at. It belongs on the
     * editor rather than on {@code Language} only because {@code Language} describes how to <em>edit</em>
     * a language and this is how to <em>draw</em> it; move it there if a second drawing rule appears.</p>
     */
    public TextEditor setOffSideLanguage(boolean value) {
        this.offSideLanguage = value;
        invalidateWindow();
        return this;
    }

    /** Which whitespace to make visible. Default {@link RenderWhitespace#NONE}, as in VS Code. */
    public TextEditor setRenderWhitespace(RenderWhitespace mode) {
        this.renderWhitespace = mode == null ? RenderWhitespace.NONE : mode;
        invalidateWindow();
        return this;
    }

    public RenderWhitespace getRenderWhitespace() {
        return renderWhitespace;
    }

    /** Vertical rules at the given columns — VS Code's {@code editor.rulers}. */
    public TextEditor setRulers(int... columns) {
        this.rulers = columns == null ? new int[0] : columns.clone();
        invalidateWindow();
        return this;
    }

    public int[] getRulers() {
        return rulers.clone();
    }

    /**
     * Whether the document may be scrolled until the last line reaches the top — VS Code's
     * {@code scrollBeyondLastLine}, on by default there and here.
     *
     * <p>It exists so the last line of a file can be read and edited somewhere other than jammed against
     * the bottom edge, which is where every other line gets to be.</p>
     */
    public TextEditor setScrollBeyondLastLine(boolean value) {
        this.scrollBeyondLastLine = value;
        markTreeDirty();
        return this;
    }

    public boolean isScrollBeyondLastLine() {
        return scrollBeyondLastLine;
    }

    // ── Zoom ────────────────────────────────────────────────────────────────────────────────────

    /** Smallest and largest the editor will zoom to. Below 4 the glyphs are unreadable; above 96 one
     * screenful is a handful of words and every cached row measurement is being thrown away. */
    public static final float MIN_FONT_SIZE = 4f;
    public static final float MAX_FONT_SIZE = 96f;

    /**
     * Sets the editor's font size.
     *
     * <p>Written at {@code IMPORTANT} origin, and it has to be: {@code default.css} sets
     * {@code * { font-size: 10 }}, and a {@code *} rule at USER_AGENT beats an inline write at any
     * specificity — the same trap {@code syncLineFonts} documents for the lines themselves. Everything
     * measured from the font is invalidated here rather than left to notice on its own: row metrics, the
     * text height, the cached digit width, and the wrap projection, which is computed in pixels.</p>
     */
    public TextEditor setFontSize(float size) {
        float clamped = Math.max(MIN_FONT_SIZE, Math.min(MAX_FONT_SIZE, size));
        if (baseFontSize < 0f) baseFontSize = getStyle().getGeneralGroup().fontSize();
        // WHAT THE VIEW IS ANCHORED ON, captured before anything changes. scrollTop is in PIXELS, so
        // leaving it alone across a font change silently reinterprets it: 440px is line 44 at a
        // ten-pixel line and line 7 at sixty. Zooming in from line 44 landed the viewport on line 5.
        //
        // Anchored on a DOCUMENT OFFSET rather than on a view line or a pixel count, because the font
        // change also reprojects -- a different wrap width gives the same text a different number of
        // view lines, so a view line captured before the change does not mean the same thing after it.
        StableViewport anchor = captureStableViewport();
        StyleGroup.importantPipeline(getStyle().getGeneralGroup(), g -> g.fontSize(clamped));
        measuredRows.clear();
        measuredFontKey = null;
        textHeight = -1f;
        digitWidth = -1f;
        whitespacePart.forgetFont();
        reproject();
        restoreStableViewport(anchor);
        invalidateWindow();
        return this;
    }

    /**
     * What the viewport is anchored on across a font change — VS Code's {@code StableViewport}.
     *
     * <p>Ported from {@code ViewModel._captureStableViewport} / {@code StableViewport.recoverViewportStart}
     * ({@code src/vs/editor/common/viewModel/viewModelImpl.ts}, MIT). It captures the <b>model</b> position
     * of the viewport's first line and recovers it afterwards, which is the same reason this anchors on a
     * document offset: the change also reprojects, so a view line captured beforehand does not mean the
     * same thing after it.</p>
     *
     * @param offset the document offset at the top of the viewport, or {@code -1} for "do not restore"
     * @param delta  pixels the viewport was scrolled INTO that line, so a partial scroll is not snapped
     *               to a line boundary on every zoom step
     */
    private record StableViewport(int offset, float delta) {
        static final StableViewport NONE = new StableViewport(-1, 0f);
    }

    /**
     * What a FOLD is anchored on — the caret's line, not the top of the viewport.
     *
     * <p>The two differ exactly when rows change <em>above</em> the caret, which is what folding does. An
     * anchor on the top row holds the top row still and lets everything below slide up as blocks collapse:
     * fold-all with the caret halfway down a file leaves the first line where it was and drags the line you
     * were reading up to meet it. IntelliJ instead keeps <b>the line you are on</b> on the same screen row
     * and lets the top of the viewport move — its collapse-all changes which rows are above you while
     * the one under your cursor does not budge.</p>
     *
     * <p>Zooming keeps the top-row anchor, and that is not an inconsistency. A zoom changes how tall every
     * row is and removes none of them, so there is nothing above the caret to move and the top row is the
     * steadier reference. Folding changes how many rows exist above you, so the caret is.</p>
     *
     * <p>No {@code scrollTop <= 0} shortcut here either. {@link #captureStableViewport} skips that case
     * because a zoom at the top has nothing to preserve — but folding at the top genuinely can need to
     * scroll to keep the caret still once the rows above it are gone.</p>
     */
    private StableViewport captureFoldAnchor() {
        float height = lineHeight();
        if (!(height > 0f)) return StableViewport.NONE;
        int caret = selections.primary().head();
        int viewLine = viewLineOf(caret, LineProjection.Affinity.RIGHT);
        return new StableViewport(caret, getScrollTop() - viewLine * height);
    }

    private StableViewport captureStableViewport() {
        float height = lineHeight();
        // Nothing to preserve at the very top, and VS Code skips it there too -- restoring a zero is at
        // best a no-op and at worst fights a clamp.
        if (!(height > 0f) || getScrollTop() <= 0f) return StableViewport.NONE;
        int viewLine = Math.max(0, Math.min(viewLineCount() - 1, (int) (getScrollTop() / height)));
        return new StableViewport(viewLineStartOffset(viewLine), getScrollTop() - viewLine * height);
    }

    /**
     * Puts the captured line back at the top of the viewport.
     *
     * <p>Immediate rather than eased, as VS Code's is: the text has already changed size, so an animated
     * correction would show the wrong lines for the length of it — the same reason caret-follow scrolling
     * is immediate.</p>
     */
    private void restoreStableViewport(StableViewport anchor) {
        float height = lineHeight();
        if (anchor.offset() < 0 || !(height > 0f)) return;
        // The delta is re-added verbatim, which is what the original does. It is a pixel count taken at
        // the OLD line height, so a large zoom step lands slightly inside the line rather than exactly on
        // it -- accepted rather than "improved", because scaling it is an invention and the whole point of
        // porting this is that the behaviour is somebody else's, already lived with.
        setScrollImmediate(getScrollLeft(),
                viewLineOf(anchor.offset(), LineProjection.Affinity.RIGHT) * height + anchor.delta());
        clampScroll();
    }

    public float getFontSize() {
        return getStyle().getGeneralGroup().fontSize();
    }

    /**
     * Zooms by {@code steps} whole points, and shows the size — {@code Mod+=} and {@code Mod+-}.
     *
     * <p>A point at a time rather than a ratio. A multiplicative step is the obvious choice and is wrong
     * at this scale: 1.1x on a 10px editor is 11, then 12.1, and the rounding makes two presses do the
     * work of one or of three unpredictably. Whole points are what the indicator reports, so what it says
     * and what a press does agree.</p>
     */
    public TextEditor zoomBy(int steps) {
        setFontSize(Math.round(getFontSize()) + steps);
        zoomIndicatorPart.show(baseFontSize);
        return this;
    }

    /** Back to the size the sheet gave it — {@code Mod+0}. */
    public TextEditor resetZoom() {
        if (baseFontSize > 0f) setFontSize(baseFontSize);
        zoomIndicatorPart.show(baseFontSize);
        return this;
    }

    /** How long the size indicator holds before fading. The fade's own duration is CSS. */
    public TextEditor setZoomIndicatorSeconds(float seconds) {
        zoomIndicatorPart.setHoldSeconds(seconds);
        return this;
    }

    /**
     * The box every document-coordinate child lives in, and the reason text stops at the gutter.
     *
     * <p><b>A container, because nothing cheaper works.</b> The obvious fix is to widen the editor's own
     * {@code padding-left} so its content box starts where the code does — and it does nothing, because
     * {@code UIElement.drawSubtree} scissors to the <b>padding box</b>, deliberately ({@code overflow:
     * hidden} clips at the padding edge in real CSS too). Padding is inside that rect, so growing it moves
     * no clip at all. Scroll-exempt children are inside it as well: {@code popScissor} runs after they are
     * drawn. So the only way to clip the text and not the chrome is to give the text its own box.</p>
     *
     * <p>Scroll-exempt itself, which is what makes it a <em>window</em> rather than a moving frame — its
     * rect must hold still while the content behind it moves. The cost is that its children no longer get
     * the scroll translate for free and subtract the offsets by hand, exactly as the gutter's numbers
     * already did.</p>
     */
    UIElement textViewport() {
        if (textViewport == null) {
            textViewport = new UIElement();
            textViewport.addClass(TEXT_VIEWPORT_CLASS);
            // Not hit-tested itself: clicks belong to the editor, which converts them through
            // offsetAtLocal. A hit-testing box over the whole text would take every press.
            textViewport.setHitTest(false);
            textViewport.markAsInternal();
            textViewport.setScrollExempt(true);
            addInternalChild(textViewport);
        }
        return textViewport;
    }

    /** Width of the code area — the client box, less the gutter and whatever the vertical bar covers. */
    float textViewportWidth() {
        return Math.max(0f, getClientWidth() - textViewportLeft() - verticalBarThickness());
    }

    /**
     * Where the clip starts — the gutter's edge, NOT where the text starts.
     *
     * <p>The two differ by {@link #codeLeftPad}, and putting the clip at the text origin instead ate a
     * margin's worth of every line scrolled sideways: the glyphs vanished a few pixels before the border
     * rather than passing under it. IntelliJ's run right up to the gutter and disappear beneath it, which
     * is what a clip at the border does and what a clip at the text origin cannot.</p>
     *
     * <p>So the margin lives INSIDE the viewport: everything drawn in it is inset by {@code codeLeftPad}
     * from its left edge, which is where the unscrolled first glyph sits. Same screen position as before,
     * a margin's more room to scroll into.</p>
     */
    float textViewportLeft() {
        return textOriginX() - codeLeftPad();
    }

    private void layOutTextViewport() {
        final float left = textViewportLeft();
        final float width = textViewportWidth();
        final float height = viewportHeight();
        StyleGroup.defaultPipeline(textViewport().getStyle().getLayoutGroup(),
                l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                        .left(left).top(0f).width(width).height(height));
    }

    /** The document row showing at the top of the viewport — what a zoom keeps still. */
    public int rowAtTopOfViewport() {
        float height = lineHeight();
        if (!(height > 0f)) return 0;
        int viewLine = Math.max(0, Math.min(viewLineCount() - 1, (int) (getScrollTop() / height)));
        return modelAt(viewLine).row();
    }

    /** Visual rows — the same as the row count when wrap is off. */
    public int viewLineCount() {
        return Math.max(1, projections.viewLineCount());
    }

    /**
     * The width, in <b>pixels</b>, that lines wrap at.
     *
     * <p>Pixels rather than columns, and that is the whole correction. The first version divided this by
     * the advance of a space and handed the quotient to the column-based computer, which is exact only in
     * a monospaced font — the theme's IBM Plex Sans is proportional, a space is far narrower than an
     * average glyph, and the resulting budget was so generous that wrapped lines still ran off the right
     * edge and were clipped. It read as wrapping being broken; it was measuring that was.</p>
     */
    private float wrapWidthPx() {
        return getClientWidth() - gutterWidth - verticalBarThickness();
    }

    /** The advance of one space in the editor's measured font. */
    float spaceAdvance() {
        CgFontFamily family = resolveFamily();
        if (family == null) return 0f;
        float[] widths = caretOffsets(" ", family);
        return widths.length > 1 ? widths[1] : 0f;
    }

    /**
     * The measured x of every character column of a row, for {@link ShapedLineBreaks}.
     *
     * <p>Built from the same {@link RowMetrics} the caret and the selection bands are placed with, so the
     * width a break decision is made against is the width the text is actually drawn at. Measuring
     * wrapping separately would let the two disagree, and a break computed against a different metric than
     * the one that paints is exactly how a wrapped line ends up one glyph over the edge.</p>
     */
    private float[] columnOffsetsOf(String line) {
        RowMetrics metrics = measureRow(line);
        float[] widths = metrics.widths();
        float[] out = new float[line.length() + 1];
        for (int column = 0; column <= line.length(); column++) {
            int index = metrics.line().displayIndexOf(column);
            out[column] = widths[Math.max(0, Math.min(index, widths.length - 1))];
        }
        return out;
    }

    /**
     * Rebuilds every projection.
     *
     * <p>O(rows), and unavoidable on a width change — a new wrap width invalidates every row at once, so
     * there is no incremental answer. Per-edit reprojection goes through
     * {@link ProjectedLines#rowsChanged} instead, which is why typing does not pay this.</p>
     */
    private void reproject() {
        forgetWidestLine();
        float width = softWrap ? wrapWidthPx() : -1f;
        projectedWrapWidth = width;
        // No font yet means no measurement, and guessing one would wrap against a width that is about to
        // change. Falling back to "no wrapping" for a frame is self-correcting; a wrong projection is not.
        boolean measurable = softWrap && width > 0f && resolveFamily() != null;
        projectedWithMeasurement = measurable || !softWrap;
        projections.setComputer(
                measurable
                        ? new ShapedLineBreaks(width, tabSize, wrapIndent, this::columnOffsetsOf)
                        : LineBreaksComputer.none(),
                buffer.document());
    }

    /**
     * Reprojects only if the wrap width actually moved — called every frame, so it must be cheap.
     *
     * @return whether a reprojection ran, so the caller knows the lines on screen are now stale
     */
    private boolean reprojectIfWidthChanged() {
        if (!softWrap) return false;
        float wanted = wrapWidthPx();
        // The second clause is not redundant. A reproject that ran before the font resolved installed the
        // no-op computer and recorded the width anyway, so a width test alone would never fire again and
        // wrapping would stay off forever -- on the one path where the editor is built and shown in the
        // same frame, which is every harness scene.
        if (Math.abs(wanted - projectedWrapWidth) < 1f && projectedWithMeasurement) return false;
        reproject();
        return true;
    }

    /**
     * Reprojects the rows an edit touched, rather than the document.
     *
     * <p>Typing is one edit on one row, so reprojecting everything per keystroke would make it
     * O(document) — the cost soft wrap is most often accused of. The row range is derived from the
     * <em>line-count delta</em>: an edit that changed the count by {@code d} replaced one row with
     * {@code 1 + d} of them, which covers Enter, a paste, and a multi-line delete alike.</p>
     *
     * <p>Multi-caret edits touch several disjoint rows and are not worth the bookkeeping, so they take the
     * whole-document path. {@link ProjectedLines#rowsChanged} also reprojects wholesale whenever the row
     * arithmetic disagrees with the document, so a wrong answer here is slow rather than incorrect.</p>
     */
    private void reprojectAfterEdit(ChangeSet change) {
        int lineCount = buffer.lineCount();
        int delta = lineCount - previousLineCount;
        previousLineCount = lineCount;
        // NO `if (!softWrap) return`. The projection is the source of truth for the view line count in
        // BOTH states -- with wrap off it is simply the identity -- so skipping maintenance here leaves a
        // stale index and the window stops growing when a line is added. Caught by an existing test, and
        // it is the exact failure the "no second code path" note on the field warns about: the shortcut
        // looks free precisely because the unwrapped case is the one that appears to need nothing.

        List<Change> changes = change.changes();
        if (changes.size() != 1) {
            projections.rebuild(buffer.document());
            return;
        }
        int at = Math.max(0, Math.min(change.mapPos(changes.get(0).from(), -1), buffer.length()));
        int row = buffer.document().offsetToPoint(at).row();
        int removed = delta >= 0 ? 1 : 1 - delta;
        int added = delta >= 0 ? 1 + delta : 1;
        projections.rowsChanged(buffer.document(), row, removed, added);
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
    float textHeight() {
        if (textHeight < 0f) {
            var metrics = CgTextLayout.of("Xg", resolveFamily()).build().metrics();
            textHeight = Math.abs(metrics.getAscender()) + Math.abs(metrics.getDescender());
        }
        return Math.min(lineHeight(), Math.max(1f, textHeight));
    }

    private float textHeight = -1f;

    private void ensureCaretVisible() {
        float height = lineHeight();
        float top = viewLineOf(getCaret(), LineProjection.Affinity.LEFT) * height;
        // IMMEDIATE, not the smooth scroll the sheet asks for. `scroll-behavior: smooth` is right for a
        // wheel or a scrollIntoView, and wrong for following a caret: the caret has already moved, so an
        // eased scroll means it is off screen for the length of the animation and every keystroke chases
        // a viewport that is still catching up with the last one.
        float viewport = viewportHeight();
        if (top < getScrollTop()) setScrollImmediate(getScrollLeft(), top);
        else if (top + height > getScrollTop() + viewport) {
            setScrollImmediate(getScrollLeft(), top + height - viewport);
        }
        // NOT invalidateWindow(). Scrolling changes which rows are on screen, and updateWindow already
        // recomputes that range every frame -- realising what has come into view and recycling what has
        // left. Tearing the whole window down here recycled every line on every keystroke, which clears
        // their highlights and is the other half of the colour flicker.
        markTreeDirty();
    }

    // ── Virtualised rendering ───────────────────────────────────────────────────────────────────

    /** Re-reads the text of every realised line without recycling it, so highlights survive the edit. */
    private void rebindRealisedLines() {
        for (Map.Entry<Integer, UIElement> entry : realisedLines.entrySet()) {
            int viewLine = entry.getKey();
            if (viewLine < 0 || viewLine >= viewLineCount()) continue;
            // The FULL layout, not just the text. An edit or a reflow can turn a continuation line into a
            // first line or the reverse, which moves its carried indent and its width -- and after a
            // resize it moves every one of them.
            layOutLine(viewLine, entry.getValue());
        }
        markTreeDirty();
    }

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
        viewCursorsPart.advanceBlink(deltaSeconds);
        zoomIndicatorPart.tick(deltaSeconds);
        autoScrollDuringDrag(deltaSeconds);
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
        // The editor's own named actions, on the editor's own keymap. Installed here rather than left to
        // the host — unlike UndoCommands, which is an APPLICATION concern bound at the root and would be
        // a surprise if a window acquired it silently. These are the widget's own keys, and an editor
        // that does nothing on Mod+D is broken rather than neutral. Both halves are idempotent.
        if (!commandsInstalled) {
            EditorCommands.install(window, this);
            commandsInstalled = true;
        }

        float height = lineHeight();
        // Before ANY geometry is read: every position below goes through textOriginX, which needs these.
        refreshGutterMetrics();
        // Before anything is placed: the gutter's width moves textOriginX, so a change here has to be
        // known before rows, carets and bands are positioned or they land a gutter-width out for a frame.
        float wantedGutter = measureGutter();
        if (Math.abs(wantedGutter - gutterWidth) > 0.5f) {
            gutterWidth = wantedGutter;
            gutterNumbersWidth = gutterDigitsWidth();
            firstRealised = -1;
            lastRealised = -1;
        }
        // The gutter's width feeds the wrap width, so this must follow it -- otherwise the first frame
        // after the gutter grows a digit wraps against the old width and every line shifts next frame.
        //
        // Keyed on whether a reprojection HAPPENED, not on whether the view line count changed. A resize
        // moves every break while frequently leaving the count alone, and a count test then rebinds
        // nothing: the lines on screen keep the old projection's text and geometry, which is a
        // continuation showing the next row's content and every line overflowing the new width.
        if (reprojectIfWidthChanged()) {
            firstRealised = -1;
            lastRealised = -1;
            rebindRealisedLines();
            highlightsDirty = true;
        }

        // AFTER reprojection, BEFORE the view line count is read. Folding changes how many view lines
        // exist, so asking first would realise rows against a count that is about to move -- and a
        // reprojection resets visibility whenever the row count changed, so the hidden rows have to be
        // reapplied on the far side of it rather than the near side.
        if (refreshFolding()) {
            firstRealised = -1;
            lastRealised = -1;
            rebindRealisedLines();
            highlightsDirty = true;
            forgetWidestLine();
        }

        int count = viewLineCount();
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
            for (int viewLine = first; viewLine <= last; viewLine++) {
                if (!realisedLines.containsKey(viewLine)) {
                    realisedLines.put(viewLine, realiseLine(viewLine));
                }
            }
            firstRealised = first;
            lastRealised = last;
            highlightsDirty = true;
            onWindowChanged.emit();
        }
        // EVERY FRAME, not only when the realised set changes. The lines live in a scroll-exempt viewport
        // now, so they no longer get the scroll translate for free -- their `top` is baked in by
        // layOutLine and has to be re-derived from the current offset. Without this the gutter and the bar
        // scrolled while the text stood still.
        //
        // Cheap enough to do unconditionally: setText no-ops on an unchanged string and
        // replaceOrPutCandidate no-ops on an unchanged value, so a frame that did not scroll writes
        // nothing. It is also the rebinding path, not the recycling one -- recycling every frame is what
        // cleared highlights and made the colours flicker.
        rebindRealisedLines();
        // BEFORE anything reads the scroll extent, and exactly once. getScrollWidth is a pure accessor
        // over the mark this grows; see its note for why it must not do the scan itself.
        measureWidestRealisedLine();
        syncLineFonts();
        refreshHighlights(first, last);
        layOutTextViewport();
        // Every extracted part, in one pass. Monaco skips the ones whose shouldRender() is false;
        // this renders all of them, which is what the methods it replaced did.
        for (EditorViewPart part : viewParts) {
            part.render(first, last);
            part.onDidRender();
        }
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

    private UIElement realiseLine(int viewLine) {
        UIElement line = linePool.pollFirst();
        if (line == null) {
            line = new UIElement();
            line.addClass(LINE_CLASS);
            line.setHitTest(false);
            line.markAsInternal();
            line.addChild(new UIText(""));
        }
        layOutLine(viewLine, line);
        if (line.getParent() == null) textViewport().addInternalChild(line);
        return line;
    }

    /**
     * Puts one line element where its view line is, with that view line's text.
     *
     * <p><b>Shared by realisation and rebinding, and that is the point.</b> A reflow leaves every line
     * already on screen holding the previous projection's text and geometry, and the realise loop only
     * ever <em>adds</em> view lines it does not have — so a resize repainted stale content: a wrapped
     * row's continuation showed the <em>next</em> row's text, and everything overflowed the narrower box.
     * One routine means a line cannot be positioned two different ways depending on how it got here.</p>
     */
    private void layOutLine(int viewLine, UIElement line) {
        final float top = textOriginY() + viewLine * lineHeight() - getScrollTop();
        final float left = codeLeftPad() + carriedIndentPx(viewLine) - getScrollLeft();
        // A DEFINITE WIDTH IS REQUIRED. An absolutely-positioned box with no width resolves to zero, and
        // a zero-width line lays its text out as though it had no extent -- which shaved the first
        // character off every row on screen. Wide enough for the text, and at least the viewport, so a
        // selection band on a short line still reads as a band and horizontal scrolling has something to
        // scroll.
        //
        // THE VIEW LINE'S OWN EXTENT, not the row's. Sizing a wrapped continuation to the whole row makes
        // its box run off the viewport by everything above it -- invisible while the text fits inside,
        // and wrong for anything that measures the box: the horizontal scroll extent and the selection
        // band both read it.
        LineProjection projection = projectionAt(viewLine);
        ProjectedLines.ModelPosition model = modelAt(viewLine);
        final float width = Math.max(textViewportWidth(),
                xOfView(viewLine, projection.maxColumn(model.viewLineInRow())) + 1f);
        StyleGroup.defaultPipeline(line.getStyle().getLayoutGroup(),
                l -> l.positionType(dev.vfyjxf.taffy.style.TaffyPosition.ABSOLUTE)
                        .top(top).left(left).width(width).height(lineHeight()));
        // The DISPLAY text, with tabs expanded to their stops -- see RowMetrics. setText no-ops on an
        // unchanged string, so rebinding every visible line costs one re-shape per line that moved.
        ((UIText) line.getChildren().get(0)).setText(viewLineDisplayText(viewLine));
    }

    private void recycleLine(UIElement line) {
        // A pooled line reused for a different row would otherwise keep the old row's highlights, which
        // is worse than none: the ranges are offsets into a string that has been replaced.
        textOf(line).highlights().clear();
        textViewport().removeInternalChild(line);
        linePool.addLast(line);
    }

    // ===================================================================================================
    // Folding
    // ===================================================================================================

    /**
     * Recomputes regions when something invalidated them, then pushes the hidden rows into the projection.
     *
     * <p>Runs every frame and is cheap when nothing moved: the recompute is gated on {@code foldingDirty},
     * and {@link ProjectedLines#setHiddenAreas} reports whether it actually changed a row's visibility.</p>
     *
     * @return whether the set of visible rows changed, so the caller can drop what it has realised
     */
    private boolean refreshFolding() {
        if (!foldingEnabled) return false;
        if (foldingDirty) {
            foldingDirty = false;
            folding.update(buffer.document(), foldingProvider, tabSize);
        }
        return applyHiddenRows();
    }

    private boolean applyHiddenRows() {
        List<FoldingModel.RowRange> hidden = folding.hiddenRows();
        int[][] ranges = new int[hidden.size()][];
        for (int i = 0; i < hidden.size(); i++) {
            ranges[i] = new int[] { hidden.get(i).startRow(), hidden.get(i).endRow() };
        }
        return projections.setHiddenAreas(ranges);
    }

    /** The folding model, for tests and for anything wanting to drive folds directly. */
    public FoldingModel foldingModel() {
        return folding;
    }

    /** Swaps the region source — a syntax-aware provider layers over the indent one this way. */
    public TextEditor setFoldingProvider(FoldingRangeProvider provider) {
        this.foldingProvider = provider == null ? FoldingRangeProvider.none() : provider;
        this.foldingDirty = true;
        return this;
    }

    public TextEditor setFoldingEnabled(boolean enabled) {
        if (this.foldingEnabled == enabled) return this;
        this.foldingEnabled = enabled;
        if (!enabled) {
            folding.setCollapseStateForAll(false);
            applyHiddenRows();
        }
        this.foldingDirty = true;
        return this;
    }

    public boolean isFoldingEnabled() {
        return foldingEnabled;
    }

    /**
     * Ensures the regions are current before a command reads them.
     *
     * <p>A command can fire between an edit and the next frame, and the region set is only recomputed in
     * {@code refreshFolding}. Without this a fold command right after typing would act on the regions of
     * the document as it was, which is off by however many rows the edit added.</p>
     */
    private void ensureFoldingCurrent() {
        if (foldingDirty) {
            foldingDirty = false;
            folding.update(buffer.document(), foldingProvider, tabSize);
        }
    }

    /**
     * Moves every caret out of a row that is about to be hidden, onto its region's header.
     *
     * <p>Not cosmetic: a caret on a hidden row has no view line, so it cannot be drawn where it actually
     * is. {@code ProjectedLines.toViewPosition} walks it to the nearest visible row instead, and the caret
     * is then painted on a line it is not on — typing inserts somewhere other than where it appears. VS
     * Code does the same lift, which is why folding a block you are inside leaves the caret on the block's
     * first line.</p>
     *
     * <p><b>EVERY caret, which this did not used to do.</b> It read {@code selections.primary()} inside the
     * loop and returned after the first fix, so a secondary caret inside a folded block stayed there. With
     * one caret that is indistinguishable from correct, and every folding test had one — the plural in the
     * name and in this javadoc was the only evidence of the intent.</p>
     */
    private void liftCaretsOutOfHiddenRows() {
        List<FoldingModel.RowRange> hidden = folding.hiddenRows();
        if (hidden.isEmpty()) return;
        boolean[] moved = { false };
        selections.transform(selection -> {
            int row = buffer.document().offsetToPoint(selection.head()).row();
            for (FoldingModel.RowRange range : hidden) {
                if (!range.contains(row)) continue;
                moved[0] = true;
                // The region's HEADER. hiddenRows() starts at startLineNumber + 1 -- the first row stays
                // visible because it carries the fold arrow -- so startRow - 1 is that header, and is
                // never negative.
                return Selection.caret(buffer.document().lineStartOffset(range.startRow() - 1));
            }
            return selection;
        });
        // Several carets in one folded block all land on its header; setAll normalises them into one.
        if (moved[0]) afterSelectionChange();
    }

    /**
     * Finishes a fold change, keeping the viewport where it was.
     *
     * <p><b>The anchor is captured before the change, by the caller.</b> Folding removes rows above the
     * viewport as readily as below it, and {@code scrollTop} is a pixel count — so collapsing everything
     * while scrolled into a file silently pulls the whole document up past the top of the view, and
     * fold-all near the end leaves the editor apparently empty. IntelliJ keeps the line you are on exactly
     * where it is, which is the same guarantee zooming already makes here and the same
     * {@code StableViewport} that makes it.</p>
     */
    private void afterFoldChange(StableViewport anchor) {
        liftCaretsOutOfHiddenRows();
        if (applyHiddenRows()) {
            firstRealised = -1;
            lastRealised = -1;
            forgetWidestLine();
        }
        // AFTER the hidden rows are applied, or the anchor is resolved against the projection the fold
        // just invalidated.
        restoreStableViewport(anchor);
        invalidateStyleMatch();
    }

    /** Folds or unfolds the innermost region at the caret, stepping outwards when already in that state. */
    public void fold() {
        StableViewport anchor = captureFoldAnchor();
        ensureFoldingCurrent();
        folding.setCollapseStateUp(true, caretRow());
        afterFoldChange(anchor);
    }

    public void unfold() {
        StableViewport anchor = captureFoldAnchor();
        ensureFoldingCurrent();
        folding.setCollapseStateUp(false, caretRow());
        afterFoldChange(anchor);
    }

    /** Folds or unfolds the region at the caret and everything inside it. */
    public void foldRecursively() {
        StableViewport anchor = captureFoldAnchor();
        ensureFoldingCurrent();
        FoldingRegions.Region region = folding.getRegionAtLine(caretRow());
        if (region != null && !region.isCollapsed()) folding.toggleCollapseState(Integer.MAX_VALUE, caretRow());
        afterFoldChange(anchor);
    }

    public void foldAll() {
        StableViewport anchor = captureFoldAnchor();
        ensureFoldingCurrent();
        folding.collapseAllKeepingDocumentVisible(buffer.lineCount());
        afterFoldChange(anchor);
    }

    public void unfoldAll() {
        StableViewport anchor = captureFoldAnchor();
        ensureFoldingCurrent();
        folding.setCollapseStateForAll(false);
        afterFoldChange(anchor);
    }

    /** Folds every region at exactly {@code level}, leaving the block the caret is in open. */
    public void foldLevel(int level) {
        StableViewport anchor = captureFoldAnchor();
        ensureFoldingCurrent();
        folding.setCollapseStateAtLevel(level, true, caretRow());
        afterFoldChange(anchor);
    }

    /** Toggles the region whose first row is {@code row} — what clicking a gutter arrow does. */
    public void toggleFoldAt(int row) {
        ensureFoldingCurrent();
        FoldingRegions.Region region = folding.getRegionStartingAt(row);
        if (region == null) return;
        StableViewport anchor = captureFoldAnchor();
        region.setCollapsed(!region.isCollapsed());
        afterFoldChange(anchor);
    }

    int caretRow() {
        return buffer.document().offsetToPoint(selections.primary().head()).row();
    }

    /**
     * What a collapsed region's chip reads.
     *
     * <p>{@code "...}"} rather than plain {@code "..."} whenever the region's last row is the one that
     * closes it — so the header {@code void f() {} plus the chip renders as {@code void f() {...}}, which
     * is IntelliJ's collapsed form and the whole point of swallowing the closing row. The closer is taken
     * from the DOCUMENT rather than assumed, so {@code });} comes back intact instead of being guessed at
     * as a bare brace.</p>
     */
    String placeholderTextFor(FoldingRegions.Region region) {
        int endRow = region.endLineNumber();
        if (endRow <= region.startLineNumber() || endRow >= buffer.lineCount()) {
            return FOLD_PLACEHOLDER_TEXT;
        }
        String closing = buffer.document().line(endRow).trim();
        if (closing.isEmpty()) return FOLD_PLACEHOLDER_TEXT;
        char first = closing.charAt(0);
        if (first != '}' && first != ')' && first != ']') return FOLD_PLACEHOLDER_TEXT;
        return FOLD_PLACEHOLDER_TEXT + closing;
    }

    /**
     * Retires one pooled element.
     *
     * <p><b>Clears its text as well as collapsing its box</b>, for the reason {@code hideFrom} gives: zero
     * size hides a fill and nothing else, and a {@code UIText} inside keeps painting. The line numbers
     * looked immune because the gutter clips to its own bounds — but a retired number is still <em>inside</em>
     * those bounds, so the clip never applied to it. Invisible until scroll-past-end made it possible to
     * leave a long tail of retired numbers behind, which then drew on top of one another.</p>
     */
    private void hide(UIElement element) {
        StyleGroup.defaultPipeline(element.getStyle().getLayoutGroup(), l -> l.width(0f).height(0f));
        for (UIElement child : element.getChildren()) {
            if (child instanceof UIText label) label.setText("");
        }
    }

    // ── §G view decorations: layout ─────────────────────────────────────────────────────────────

    /** X of a document column, via its display index — see {@link RowMetrics}. */
    float widthOf(int row, int column) {
        RowMetrics metrics = rowMetrics(row);
        int index = metrics.line().displayIndexOf(column);
        float[] widths = metrics.widths();
        return widths[Math.max(0, Math.min(index, widths.length - 1))];
    }

    // ── View space ──────────────────────────────────────────────────────────────────────────────
    //
    // Everything below converts between the document (rows, offsets) and what is on screen (view lines,
    // x). With soft wrap off every one of these is an identity and costs a lookup; there is deliberately
    // no fast path around them, because a second path is what lets the two disagree.

    ProjectedLines.ModelPosition modelAt(int viewLine) {
        return projections.modelAt(viewLine);
    }

    LineProjection projectionAt(int viewLine) {
        return projections.projectionOf(modelAt(viewLine).row());
    }

    /** The document offset at which a view line's text begins. */
    int viewLineStartOffset(int viewLine) {
        ProjectedLines.ModelPosition model = modelAt(viewLine);
        return buffer.document().lineStartOffset(model.row())
                + projectionAt(viewLine).viewLineStart(model.viewLineInRow());
    }

    /** The document offset at which a view line's text ends, excluding any newline. */
    int viewLineEndOffset(int viewLine) {
        ProjectedLines.ModelPosition model = modelAt(viewLine);
        return buffer.document().lineStartOffset(model.row())
                + projectionAt(viewLine).viewLineEnd(model.viewLineInRow());
    }

    /** Which view line a document offset paints on. */
    int viewLineOf(int offset, LineProjection.Affinity affinity) {
        return projections.toViewPosition(buffer.document(), offset, affinity).viewLine();
    }

    /**
     * The pixel indent a continuation line carries.
     *
     * <p>Measured as the width of the row's own leading whitespace rather than multiplied out from a
     * column count, so it lines up with the text above it in whatever font actually resolved. Columns are
     * the right unit for <em>deciding</em> the break — they are what the ported computer works in — and
     * the wrong unit for drawing it.</p>
     */
    private float carriedIndentPx(int viewLine) {
        ProjectedLines.ModelPosition model = modelAt(viewLine);
        if (model.viewLineInRow() == 0) return 0f;
        int columns = projectionAt(viewLine).wrappedIndent();
        if (columns <= 0) return 0f;
        String row = buffer.line(model.row());
        int blank = 0;
        while (blank < row.length() && (row.charAt(blank) == ' ' || row.charAt(blank) == '\t')) blank++;
        return widthOf(model.row(), blank);
    }

    /**
     * The x of a column on a view line, relative to {@code textOriginX()}.
     *
     * <p><b>Measured against the whole row, then rebased</b> — never against the view line's own text.
     * A tab's stop depends on where it sits in the <em>row</em>, so measuring a continuation line on its
     * own puts every tab after a wrap at the wrong stop. VS Code carries a parallel
     * {@code breakOffsetsVisibleColumn} array to solve this; rebasing the row's prefix widths needs no
     * extra state, and state that exists only to mirror another array is state that goes stale.</p>
     */
    float xOfView(int viewLine, int column) {
        ProjectedLines.ModelPosition model = modelAt(viewLine);
        LineProjection projection = projectionAt(viewLine);
        int inRow = projection.toModelOffset(model.viewLineInRow(), column);
        float origin = widthOf(model.row(), projection.viewLineStart(model.viewLineInRow()));
        return widthOf(model.row(), inRow) - origin + carriedIndentPx(viewLine);
    }

    /**
     * The display column a collapsed header stops painting at, or {@code -1}.
     *
     * <p>A row that starts a collapsed region and ends in a bracket hands that bracket to the chip, which
     * renders {@code {...}} as one control. <b>The row must then stop drawing it.</b> Leaving it and covering
     * it with the chip's background was the first attempt and it showed: rounded corners let the brace's
     * corners through, and the chip's own left padding put its brace a few pixels right of the real one, so
     * the two disagreed more the further you zoomed in.</p>
     *
     * <p>Cutting a SUFFIX is what makes this safe. Every x in the row is measured from a prefix, so removing
     * trailing characters moves nothing that is still drawn — carets, selection bands and click targeting on
     * that row are all unaffected. A caret at the row's end lands where the brace was, which is exactly where
     * the chip now begins.</p>
     */
    private int collapsedHeaderCut(int row) {
        if (!foldingEnabled) return -1;
        FoldingRegions.Region region = folding.getRegionStartingAt(row);
        if (region == null || !region.isCollapsed()) return -1;
        int opener = FoldingDecorationsPart.trailingOpenerIndex(buffer.document().line(row));
        return opener < 0 ? -1 : rowMetrics(row).line().displayIndexOf(opener);
    }

    /**
     * The text painted on a view line — a slice of the row's <b>already tab-expanded</b> display string.
     *
     * <p>Slicing the expanded string rather than expanding the slice is what keeps tab stops right after a
     * wrap, for the same reason {@link #xOfView} rebases rather than remeasures.</p>
     */
    private String viewLineDisplayText(int viewLine) {
        ProjectedLines.ModelPosition model = modelAt(viewLine);
        LineProjection projection = projectionAt(viewLine);
        if (projection.isUnwrapped()) {
            String display = rowMetrics(model.row()).line().display();
            int cut = collapsedHeaderCut(model.row());
            return cut >= 0 && cut <= display.length() ? display.substring(0, cut) : display;
        }

        CursorColumns.Line line = rowMetrics(model.row()).line();
        String display = line.display();
        int from = line.displayIndexOf(projection.viewLineStart(model.viewLineInRow()));
        int to = line.displayIndexOf(projection.viewLineEnd(model.viewLineInRow()));
        from = Math.max(0, Math.min(from, display.length()));
        to = Math.max(from, Math.min(to, display.length()));
        // The cut lands on the row's LAST view line, which is the only one that can carry the opener.
        int cut = collapsedHeaderCut(model.row());
        if (cut >= from && cut < to) to = cut;
        return display.substring(from, to);
    }

    @Override
    public boolean acceptsPublicChildren() {
        return false;
    }
}
