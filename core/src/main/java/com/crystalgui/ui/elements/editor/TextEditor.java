package com.crystalgui.ui.elements.editor;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgModifiers;
import com.crystalgui.core.async.FrameProfile;
import com.crystalgui.core.command.CommandRegistry;
import com.crystalgui.core.data.DataKey;
import com.crystalgui.core.search.SearchMatcher;
import com.crystalgui.core.search.SearchQuery;
import com.crystalgui.core.signal.Signal;
import com.crystalgui.core.undo.UndoScope;
import com.crystalgui.core.undo.UndoStack;
import com.crystalgui.render.text.FontFamilyCache;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.StyleProperty;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.text.*;
import com.crystalgui.text.cursor.*;
import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSet;
import com.crystalgui.text.diagnostic.DiagnosticTag;
import com.crystalgui.text.fold.FoldingModel;
import com.crystalgui.text.fold.FoldingRangeProvider;
import com.crystalgui.text.fold.FoldingRegions;
import com.crystalgui.text.lang.*;
import com.crystalgui.text.search.SearchResults;
import com.crystalgui.text.search.TextSearch;
import com.crystalgui.text.syntax.Language;
import com.crystalgui.text.syntax.SyntaxToken;
import com.crystalgui.text.syntax.SyntaxTokenizer;
import com.crystalgui.text.view.RenderWhitespace;
import com.crystalgui.text.wrap.*;
import com.crystalgui.ui.ClipboardActions;
import com.crystalgui.ui.UIElement;
import com.crystalgui.ui.UiDataKeys;
import com.crystalgui.text.WordOperations;
import com.crystalgui.ui.UITransform;
import com.crystalgui.ui.elements.ScrollerView;
import com.crystalgui.ui.elements.UIText;
import com.crystalgui.ui.event.KeyboardEvent;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.ui.text.HighlightRegistry;
import com.crystalgui.ui.text.SyntaxHighlighting;
import com.crystalgui.ui.text.TextRange;
import dev.vfyjxf.taffy.style.TaffyDisplay;
import dev.vfyjxf.taffy.style.LengthPercentageAuto;
import dev.vfyjxf.taffy.style.TaffyPosition;
import lombok.Getter;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

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

    /**
     * The editor's named actions — registered once for the class, by the engine.
     *
     * <p>This used to run from {@code updateWindow()} behind a {@code commandsInstalled} flag, which is
     * to say from the <b>layout path</b>: an editor's commands did not exist until it had been laid out
     * at least once, so a palette opened before that was missing every one of them, and an editor built
     * and never shown had none at all.</p>
     */
    @Override
    protected void registerCommands(CommandRegistry registry) {
        EditorCommands.register();
    }

    /**
     * The editor's chords, on the editor.
     *
     * <p>Per instance and element-scoped, because that is what makes them the editor's: {@code Mod+D}
     * adds a caret here and duplicates a node in a graph, and neither knows the other exists. Only
     * genuinely application-wide chords belong on the command itself.</p>
     */
    @Override
    protected void bindKeys() {
        EditorCommands.bindDefaults(keymap());
    }

    public static final String LINE_CLASS = "__line__";

    /**
     * Opt in to the {@code ::highlight()} vocabulary of §10.1 — the forty capture-name rules every
     * colour scheme defines.
     *
     * <p>A <b>capability</b>, not a part of this widget: anything drawing code coloured like code adds
     * this class and gets the same rules. Public because the documentation popup is the second consumer
     * and will not be the last.</p>
     */
    /** @see UIText#SYNTAX_CLASS — the definition; kept here because every scheme and half the editor
     * already names it through this class. */
    public static final String SYNTAX_CLASS = UIText.SYNTAX_CLASS;
    public static final String CARET_CLASS = "__caret__";
    public static final String SELECTION_CLASS = "__selection__";

    /** Difference decorations. The band spans the viewport; the mark sits on the characters it marks. */
    public static final String DIFF_BAND_CLASS = "__diff-band__";
    public static final String DIFF_MARK_CLASS = "__diff-mark__";
    public static final String DIFF_ADDED_CLASS = "__diff-added__";
    public static final String DIFF_REMOVED_CLASS = "__diff-removed__";
    public static final String DIFF_CHANGED_CLASS = "__diff-changed__";
    /** A change with no rows on this side: a rule at the boundary rather than a band over a line. */
    public static final String DIFF_THIN_CLASS = "__diff-thin__";
    /** The gutter control that pushes one side of a difference onto the other. */
    public static final String DIFF_CHEVRON_CLASS = "__diff-chevron__";
    /** Present while the gutter is mirrored to the right-hand edge. @see #setGutterOnRight */
    public static final String GUTTER_RIGHT_CLASS = "__gutter-right__";
    public static final String GUTTER_CLASS = "__gutter__";
    public static final String INDENT_GUIDE_CLASS = "__indent-guide__";
    /** Added to the one guide belonging to the block the caret is in — see {@code layOutIndentGuides}. */
    public static final String ACTIVE_GUIDE_CLASS = "__active__";
    public static final String WHITESPACE_CLASS = "__whitespace__";
    public static final String RULER_CLASS = "__ruler__";
    public static final String GUTTER_EDGE_CLASS = "__gutter-edge__";

    /**
     * A container that carries the scroll offset for everything inside it — see {@link #linesLayer()}.
     *
     * <p>One class for all three because they differ only in which axes their transform uses, and that
     * is decided in Java where the offsets are. A sheet has nothing to say about any of them.</p>
     */
    public static final String SCROLL_LAYER_CLASS = "__scroll-layer__";
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

    /** @see #linesLayer() */
    private UIElement linesLayer;

    /** @see #gutterLayer() */
    private UIElement gutterLayer;

    /** @see #foldLayer() */
    private UIElement foldLayer;

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
    private boolean gutterOnRight;
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
     * <p><b>Two elements rather than one wide one</b>, because the gutter and the code area are separate
     * boxes: {@link #textViewport()} is positioned at exactly the gutter's right border and clips with
     * {@code overflow: hidden}, so a band inside it <em>cannot reach</em> the gutter however it is
     * stacked. Drawn in front of everything instead, it hides the numbers. A band inside the gutter sits
     * in the gutter's own stacking context — beneath its numbers, above its background — which is the
     * only place it can be both visible and behind the digits.</p>
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

    /**
     * The viewport moved, so {@link #refreshHighlights} must run — but every row it still shows keeps the
     * bands it has. Distinct from {@link #highlightsDirty}, which means the bands themselves are wrong.
     */
    private boolean highlightWindowMoved = true;

    /**
     * Says the published {@code ::highlight()} ranges no longer describe the document.
     *
     * <p>For the subsystems that own a range set of their own — find, occurrences — since the flag itself
     * is the editor's and the pass that reads it is too.</p>
     */
    void markHighlightsDirty() {
        highlightsDirty = true;
    }

    /**
     * Syntax tokens per MODEL row, with offsets relative to that row's start.
     *
     * <p>Asking the tokenizer is the single most expensive thing this class does per frame — measured at
     * <b>3.3ms</b> for one viewport-sized query on a 5,000-line file, which is most of a 60fps frame and
     * was being paid on every keystroke <em>and every scroll step</em>. Interning the capture names moved
     * it by under 2%, so the cost is tree-sitter's own query execution: the only way to not pay it is to
     * not ask.</p>
     *
     * <p><b>Keyed by model row, not by view line</b>, which is what makes it survive folding, wrapping and
     * a window resize with no invalidation at all — those change which view line a row is drawn on and
     * change nothing about the row's own tokens. It is also the key {@code measuredRows} already uses, so
     * the two invalidate on the same rule for the same reason.</p>
     *
     * <p><b>Offsets are row-relative</b>, so an edit on one row does not shift every cached entry below
     * it. Absolute offsets would have to be re-based on every keystroke, which is the work this exists to
     * avoid, one indirection further down.</p>
     *
     * <p>A row present with an empty list means "queried, genuinely has no tokens" — distinct from absent,
     * which means "never asked". Conflating them re-queries blank lines forever.</p>
     */
    private final Map<Integer, List<SyntaxToken>> rowSyntax = new HashMap<>();

    /**
     * Rows whose text has changed since their tokens were computed — see {@link #settleSyntaxIfIdle}.
     *
     * <p>They keep showing those tokens, mapped forward. This is a list of what to re-ask about once
     * typing stops, <b>not</b> a list of what to blank.</p>
     */
    private final Set<Integer> staleRows = new HashSet<>();

    /** When the document was last edited. Only ever read while {@link #editing} — see the note there. */
    private long lastEditNanos;

    /** An edit has landed and typing has not settled since. */
    private boolean editing;

    /**
     * The engine behind this document, or null — and null is the ordinary case.
     *
     * <p>Held rather than owned: the lifecycle belongs to the <em>document</em>, so the same file in two
     * split panes is two editors sharing one of these. That is why {@link #setLanguageServices} does not
     * close what it replaces — see its note.</p>
     */
    @Nullable
    private LanguageServices languageServices;

    /** The engine's diagnostics subscription, dropped when the services are replaced or disposed. */
    @Nullable
    private com.crystalgui.core.signal.Connection languageDiagnostics;

    /** The two bracket positions when the caret is on a bracket, or {@code null}. */
    private int[] bracketPair;

    /** One indent level, in spaces. */
    private int indentWidth = 4;

    /** Whether one level is that many spaces, or a tab. @see #setInsertSpaces */
    private boolean insertSpaces = true;

    /** Whether the gutter counts from the caret rather than from the top. @see #setRelativeLineNumbers */
    private boolean relativeLineNumbers;

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

    /** Whether this drag belongs to a caret Alt+click added. @see #extendDragTo */
    private boolean draggingAddedCaret;

    /** Where a box selection was started, or -1. @see #applyColumnSelection */
    private int columnAnchor = -1;

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
    private final SquigglesPart squigglesPart = new SquigglesPart(this);
    private final ErrorStripePart errorStripePart = new ErrorStripePart(this);
    private final InspectionWidgetPart inspectionWidgetPart = new InspectionWidgetPart(this);
    private final QuickFixBulbPart quickFixBulbPart = new QuickFixBulbPart(this);
    private final DiffBandsPart diffBandsPart = new DiffBandsPart(this);
    private final DiffChevronPart diffChevronPart = new DiffChevronPart(this);
    /** Every part, in paint order, so the frame drives one list rather than a dozen named calls. */
    private final java.util.List<EditorViewPart> viewParts = java.util.List.of(
            // BEFORE the current-line band and the selection, so a diff band is the backmost of the
            // three. All three can be true of one row at once, and the one that answers "where am I"
            // must not be buried under the one that answers "what changed".
            diffBandsPart, gutterEdgePart, indentGuidesPart, whitespacePart, rulersPart,
            foldingDecorationsPart,
            zoomIndicatorPart, lineNumbersPart, currentLinePart, selectionsPart, squigglesPart, errorStripePart, inspectionWidgetPart,
            quickFixBulbPart, viewCursorsPart, diffChevronPart);

    // ── Difference decorations ─────────────────────────────────────────────────────────────────

    private DiffDecorations diffDecorations = DiffDecorations.NONE;

    /**
     * Marks this editor up as one side of a comparison.
     *
     * <p>Data, with no back-reference to the other text: an editor showing an ordinary file simply never
     * receives any, and behaves exactly as it always did. @see DiffDecorations</p>
     */
    public TextEditor setDiffDecorations(DiffDecorations decorations) {
        this.diffDecorations = decorations == null ? DiffDecorations.NONE : decorations;
        invalidateWindow();
        return this;
    }

    DiffDecorations diffDecorations() {
        return diffDecorations;
    }

    private java.util.function.IntConsumer diffRevertHandler;

    /**
     * Offers a {@code »} control beside each difference, which calls back with the difference's index.
     *
     * <p>The editor does not know what reverting <em>means</em> — it holds one text and cannot see the
     * other. It reports which difference was pressed and the caller, which has both sides, performs the
     * edit. Absent by default, so an editor showing an ordinary file grows no controls.</p>
     */
    public TextEditor setDiffRevertHandler(java.util.function.IntConsumer handler) {
        this.diffRevertHandler = handler;
        invalidateWindow();
        return this;
    }

    java.util.function.IntConsumer diffRevertHandler() {
        return diffRevertHandler;
    }

    // ── Diagnostics ────────────────────────────────────────────────────────────────────────────
    //
    // The subsystem is EditorDiagnostics. The SET is the buffer's -- a diagnostic describes a document as
    // an undo stack does -- and what moved out is everything that has to happen around it: the version
    // gate, the tracked ranges, and the navigation.

    private final EditorDiagnostics problems = new EditorDiagnostics(this);

    /** Filing, tracking and navigating problems. */
    EditorDiagnostics problems() {
        return problems;
    }

    /**
     * The problems reported about this document — <b>the buffer's</b>, not this widget's.
     *
     * <p>It used to be a field here, under a javadoc calling that a known compromise: a diagnostic
     * describes a document exactly as an undo stack does, so two views onto one file would have had two
     * sets, publishing two competing slices into one Problems panel. It now lives beside
     * {@code TextBuffer.decorations()}, which is where the squiggles' own tracked ranges already were —
     * the list and the marks it produces had different owners and different lifetimes.</p>
     */
    public DiagnosticSet diagnostics() {
        return buffer.diagnostics();
    }

    /** The decoration lane every diagnostic squiggle is tracked in. @see EditorDiagnostics */
    public static final String DIAGNOSTIC_LANE = "diagnostic";

    /**
     * The problems covering {@code offset} <b>right now</b>, nearest-first.
     *
     * @see EditorDiagnostics#at(int)
     */
    public List<Diagnostic> diagnosticsAt(int offset) {
        return problems.at(offset);
    }

    /**
     * Where {@code problem} is <b>now</b>, or null when nothing is tracking it.
     *
     * @see EditorDiagnostics#trackedRangeFor(Diagnostic)
     */
    @Nullable
    public TrackedRange trackedRangeFor(@Nullable Diagnostic problem) {
        return problems.trackedRangeFor(problem);
    }

    /**
     * A row/column against the live document, clamping a column past its row's end.
     *
     * <p>Clamping rather than refusing, because {@code Diagnostic.onRow} deliberately produces a column past
     * the end to mean "the whole row", and because a compiler occasionally reports one character past the
     * last. Both are the same clamp and neither is worth losing a mark over.</p>
     *
     * <p><b>The document's own conversion, not a second copy of it.</b> This was a hand-written clamp that
     * looked equivalent and was not: {@code rowStart + Integer.MAX_VALUE} <em>overflows</em>, so
     * {@code Math.min(rowEnd, …)} answered a negative offset and the {@code Math.max(from, …)} at the call
     * site pulled it back to the row's start — every whole-row diagnostic drew a one-character squiggle in
     * the leading whitespace instead of underlining its line. {@code SquigglesTest} passed throughout,
     * because a collapsed band is widened to one character to be visible and its width still looked
     * plausible. @see Rope#pointToOffset, which had the same defect and is now the one definition</p>
     */
    int offsetOfPoint(TextPoint point) {
        return buffer.pointToOffset(point);
    }

    /** Moves the caret to the next problem after it, wrapping. False when there are none. */
    public boolean goToNextProblem() {
        return problems.goToNext(caretPoint());
    }

    /** The mirror of {@link #goToNextProblem}, wrapping to the last. */
    public boolean goToPreviousProblem() {
        return problems.goToPrevious(caretPoint());
    }

    /** Puts the caret on a diagnostic, revealing it first. @see EditorDiagnostics#goTo */
    private boolean goToProblem(@Nullable Diagnostic target) {
        return problems.goTo(target);
    }

    /**
     * The rows currently hidden by collapsed folds.
     *
     * <p>Public because "can this row be shown at all?" is a question anything navigating to a row has to
     * be able to ask — {@link #goToProblem} does, and an error stripe deciding where to draw a mark for a
     * folded-away problem will too. Deriving it from the folding model each time rather than caching it:
     * the set changes on every fold, and a stale answer here places a caret somewhere unpaintable.</p>
     */
    public java.util.List<FoldingModel.RowRange> hiddenRowRanges() {
        return folds.hiddenRowRanges();
    }

    /** Opens every collapsed region hiding {@code row}. A no-op when the row is already visible, so this
     * does not disturb the fold state of a file whose problems are all in the open. */
    void revealRow(int row) {
        folds.revealRow(row);
    }

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

    /** The gutter box itself, for the view parts and for measuring where it landed. */
    UIElement gutterElement() {
        return gutter;
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
            long changed = FrameProfile.enter("buffer.onChanged");
            // The tokenizer hears about the edit BEFORE the next query, so an incremental one can update
            // what it holds. Applying the edit is cheap and must be synchronous; the expensive reparse is
            // the implementation's business. See SyntaxTokenizer#edited.
            long timed = FrameProfile.begin();
            tokenizer.edited(buffer.document(), change);
            FrameProfile.step(timed, "tokenizer.edited");
            highlightsDirty = true;
            FrameProfile.leave(changed, "buffer.onChanged head");
            // BEFORE reprojectAfterEdit, which is what advances previousLineCount -- this needs the count
            // as it was in order to tell a same-row edit from one that shifted every row below it.
            long piece = FrameProfile.begin();
            invalidateMeasuredRows(change);
            FrameProfile.step(piece, "ed:invalidateMeasuredRows");
            // Same rule, same reason, and it must read previousLineCount while it still says what it said
            // before this edit -- so it belongs beside the call above rather than anywhere later.
            //
            // MAPPED, not dropped -- see settleSyntaxIfIdle for why those are different things.
            piece = FrameProfile.begin();
            mapRowSyntaxThroughEdit(change);
            FrameProfile.step(piece, "ed:mapRowSyntaxThroughEdit");
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
            long reprojected = FrameProfile.begin();
            reprojectAfterEdit(change);
            FrameProfile.step(reprojected, "reprojectAfterEdit");
            piece = FrameProfile.begin();
            folds.markDirty();
            FrameProfile.step(piece, "ed:folds.markDirty");
            long rebound = FrameProfile.begin();
            rebindRealisedLines();
            FrameProfile.step(rebound, "rebindRealisedLines");
            // A document that shrank can leave a selection pointing past its end, and the caret then
            // indexes a row that is not there. Clamped HERE rather than at the keystroke that caused it,
            // because undo is no longer the only way in: edit.undo from a menu or the palette, a
            // programmatic setText, and a server-pushed change all arrive through this one signal. It was
            // a hand-written line in the Ctrl+Z handler, which is precisely why moving that binding to the
            // keymap would otherwise have taken the clamp with it.
            selections.clampTo(buffer.length());
            // THE MATCHES FOLLOW THE DOCUMENT. Every edit arrives here -- typing, a paste, a replace, and
            // undo and redo -- and offsets found against the old text describe the new one wrongly: the
            // count went stale and the highlights sat over whatever had moved into their place. Re-running
            // from this one signal is what makes undo correct without the undo path knowing about search.
            piece = FrameProfile.begin();
            find.refreshAfterEdit();
            FrameProfile.step(piece, "ed:find.refreshAfterEdit");
            // A HOVER BOX DESCRIBES AN OFFSET, and typing moves it. Dismissed rather than re-resolved,
            // because what is on screen is now about a position that has shifted underneath it and the
            // pointer has not asked about wherever the text ended up. A Ctrl+Q popup is left alone: it was
            // asked for deliberately, and hideHoverDocumentation is what tells the two apart.
            langFeatures.hover().hide();
            // THE WHOLE DOCUMENT AS A STRING, on every edit -- flattening a 108KB rope to hand to
            // listeners that may not want it. Named so the log says whether anybody is paying for it.
            // FLATTENED ONLY IF SOMEBODY IS LISTENING. `buffer.toString()` materialises the whole rope --
            // 73KB for an ordinary class, 108KB for Minecraft.class -- and it was built unconditionally,
            // as the argument to an emit with, measured, ZERO connections on every path that opens a file.
            // A signal with no listeners costs nothing; the argument handed to it does not.
            piece = FrameProfile.begin();
            if (onChanged.connectionCount() > 0) onChanged.emit(buffer.toString());
            FrameProfile.step(piece, "ed:onChanged.emit -> " + onChanged.connectionCount() + " listeners");
        });

        // AN UNDO THAT MOVES THE TEXT BACK AND NOT THE CARET has moved the text out from under your
        // hands. Every edit records where the carets were, and undo and redo hand them back here --
        // after `onChanged` above, so this is the last word rather than something the clamp overwrites.
        // Reported from the harness as "undo/redo don't put the caret back".
        buffer.onSelectionsRestored.connect(restored -> {
            if (restored.isEmpty()) return;
            selections.setAll(restored, 0);
            selections.clampTo(buffer.length());
            afterSelectionChange();
            ensureCaretVisible();
        });

        // EVERY producer's problems get tracked ranges, not only the engine's. See retrackDiagnostics.
        diagnostics().onChanged.connect(problems::retrack);

        // The one place the caret settles. A session must end on a plain arrow-key move, which changes no
        // text and would therefore never reach a buffer listener.
        onSelectionChanged.connect(suggest::caretMoved);

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
        // NORMALISED FIRST, and through the buffer's own loader — which is what makes this "load a file"
        // rather than "paste a string".
        //
        // This did `buffer.replace(0, length, text)` raw, so a CRLF file kept its carriage returns in the
        // document. `TextBuffer`'s constructor normalises and `load` normalises; only this path did not,
        // and it is the one every opened file arrives through.
        //
        // What that looked like is worth writing down, because nothing about it says "line endings". A
        // `\r` left on the end of a row reaches the shaper, which treats it as a PARAGRAPH BREAK exactly
        // as it should — so every single-line row shaped as two paragraphs and reported double height.
        // The row box stayed one line tall, `.__line__` centres its text, and centring a 26.4-tall child
        // in a 14-tall box lifts it by 6.2px. The result was that every line of text in the file sat
        // half a row above its own line number, while the numbers — which never carry a `\r` — were
        // exactly right. It was reported as "the gutter drifts, but only in JavaScript", and the only
        // reason it looked like a language was that the file which happened to be CRLF was a `.js` one.
        //
        // `load` also detects the ending and remembers it, so saving the file writes back what it came
        // with instead of silently converting it — which is the other half of why this is the right call
        // rather than a `replace` with a `normalise` in front of it.
        String next = LineEnding.normalise(text == null ? "" : text);
        // UNCHANGED TEXT IS NOT AN EDIT, and this engine suppresses equal writes everywhere else for the
        // same reason -- Property.set, ObservableList.set and replaceOrPutCandidate all no-op on an equal
        // value, and each of them documents a feedback loop that stops settling without it.
        //
        // Here the cost is visible rather than merely wasted. A generated document is re-pushed by whatever
        // produces it, often every frame and usually identical; replacing the buffer resets the caret,
        // breaks undo coalescing, re-projects, and forgets the widest line measured so far. That last one
        // is what shows: the horizontal scrollbar is decided from the widest line, so it vanished and came
        // back as the measurement was thrown away and rebuilt, and the text under it blinked with it. The
        // shader graph's emitted-source panel is fed exactly this way.
        //
        // Compared BEFORE touching the buffer, so nothing above has already happened by the time the
        // comparison says there was nothing to do.
        // COMPARED AFTER NORMALISING, which the raw version could not do: the incoming text is what the
        // file holds and `getText()` is what the buffer holds, so on a CRLF file they never matched and
        // every re-read replaced the whole document -- resetting the caret and throwing away the widest
        // measured line, which is the flicker this early-out exists to prevent.
        //
        // AND THE ENDING IS PART OF "unchanged". Normalising makes a CRLF document and an LF one the
        // same TEXT, which is the point -- but they are not the same FILE, so returning early would keep
        // the ending the buffer already had and a save would write back the wrong one. A test caught it
        // immediately, which is the argument for having written the save half of this down at all.
        if (buffer.length() == next.length() && next.contentEquals(getText())
                && buffer.lineEnding() == LineEnding.detect(text == null ? "" : text)) {
            return this;
        }
        // `load`, not `replace`: it normalises AND remembers the ending, so a save writes back what the
        // file came with. It breaks undo coalescing itself.
        long timed = FrameProfile.begin();
        buffer.load(text == null ? "" : text);
        FrameProfile.step(timed, "buffer.load (rope build, " + buffer.lineCount() + " lines)");
        timed = FrameProfile.begin();
        setSelection(0, 0);
        FrameProfile.step(timed, "setSelection");
        return this;
    }

    /**
     * {@link #setText(String)} for text a worker has already normalised and roped.
     *
     * <p>Same early-out, against the prepared text rather than a freshly normalised copy — which is also
     * one fewer 100KB pass on the frame. @see TextBuffer#prepare</p>
     */
    public TextEditor setText(TextBuffer.Prepared prepared) {
        if (prepared == null) return setText("");
        String next = prepared.normalised();
        if (buffer.length() == next.length() && next.contentEquals(getText())
                && buffer.lineEnding() == prepared.ending()) {
            return this;
        }
        long timed = FrameProfile.begin();
        buffer.load(prepared);
        FrameProfile.step(timed, "buffer.load (PREPARED, " + buffer.lineCount() + " lines)");
        timed = FrameProfile.begin();
        setSelection(0, 0);
        FrameProfile.step(timed, "setSelection");
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

    /**
     * Scrolls until the caret is on screen.
     *
     * <p><b>Deliberately not part of {@link #setCaret}</b>, and this is the distinction every navigation
     * caller has to know about: moving the caret and <em>revealing</em> it are separate acts. Typing
     * reveals because the user is looking at the caret; a programmatic move often must not, or every
     * background edit would yank the viewport around. So the editor's own key handlers call this and the
     * setters do not.</p>
     *
     * <p>Public because navigation lives outside this widget — opening a file at a line, jumping to a
     * definition, clicking a problem. Without it those all set a caret the viewport is nowhere near,
     * which reads as nothing having happened at all.</p>
     */
    public TextEditor revealCaret() {
        ensureCaretVisible();
        return this;
    }

    /**
     * Scrolls until the caret's line sits in the <b>middle</b> of the viewport — what a jump does.
     *
     * <p><b>A different question from {@link #revealCaret()}, not a nicer version of it.</b> Following a
     * caret wants the <em>least</em> scrolling that works, because the reader's eye is already on it and
     * moving the text under them is the cost; arriving somewhere new wants the most context, because they
     * have no idea where they are yet. Minimal scrolling puts the destination hard against the top or
     * bottom edge with the surrounding code entirely on one side, which is the worst possible framing for
     * a line you were sent to look at.</p>
     *
     * <p>Both references split it the same way: Monaco has {@code revealLine} beside
     * {@code revealLineInCenter}, IntelliJ has {@code ScrollType.MAKE_VISIBLE} beside
     * {@code ScrollType.CENTER}. Typing must never centre — the viewport would lurch on the keystroke
     * that crosses the halfway line and again on every one after it.</p>
     *
     * <p>Clamped at both ends by {@code setScrollImmediate}, so a target near the start or end of the
     * file simply comes as close to the middle as the document allows rather than scrolling into blank
     * space above line one.</p>
     */
    public TextEditor revealCaretCentred() {
        float height = lineHeight();
        float top = viewLineOf(getCaret(), LineProjection.Affinity.LEFT) * height;
        // CENTRED IN THE BAND THE TEXT OCCUPIES, not in the scrollport -- the top padding is where the
        // find bar sits, so centring through it puts the destination above the middle by half the bar.
        // Same correction ensureCaretVisible carries, and it reduces to the old expression whenever the
        // padding is zero.
        float origin = textOriginY();
        // IMMEDIATE, for the reason ensureCaretVisible gives: an eased scroll would leave the destination
        // off screen for the length of the animation, and a jump is precisely when you are looking for it.
        setScrollImmediate(getScrollLeft(), top - (viewportHeight() - origin - height) / 2f);
        markTreeDirty();
        return this;
    }

    /**
     * Whether the caret's line is inside the band the text is meant to occupy.
     *
     * <p>The membership half of {@link #ensureCaretVisible()}, shared so the two cannot drift — that
     * method still decides <em>which</em> edge to scroll to, which is a different and simpler question.
     * The asymmetry is explained there: the far edge accounts for the top padding because the line has to
     * fit above the bottom of the box, and the near edge does not because scrolling a line to {@code top}
     * already places it at the first row of text rather than under the chrome above it.</p>
     */
    boolean caretIsInView() {
        float height = lineHeight();
        float top = viewLineOf(getCaret(), LineProjection.Affinity.LEFT) * height;
        return top >= getScrollTop()
                && top + height + textOriginY() <= getScrollTop() + viewportHeight();
    }

    /** The shared tail of every selection change: end the undo run, re-place the carets, repaint. */
    void afterSelectionChange() {
        buffer.breakUndoCoalescing();
        updateBracketMatch();
        updateOccurrences();
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
    /**
     * Pastes, treating <b>whole-line clipboard content as whole lines</b> — what every IDE does.
     *
     * <h3>A cut line is a LINE, not the characters that were on it</h3>
     *
     * <p>Cut with no selection puts the line and its newline on the clipboard. Inserting that raw at the
     * caret does two visible wrongs at once, and they were reported as one: the text welds onto the end of
     * whatever line you are on, and the trailing newline then pushes the caret down to an empty line
     * below. Neither is what was cut -- a line was, so a line is what comes back.</p>
     *
     * <p>Recognised by the trailing newline rather than by remembering the cut. VS Code keeps a flag for
     * this, which it can because its clipboard is its own; ours is the SYSTEM clipboard, so a flag would
     * be wrong the moment the text came from anywhere else. The newline is the honest signal, and it is
     * the one thing a copied line always has and a copied fragment never does.</p>
     *
     * <p>Inserted at the START of the caret's line, so the paste lands on a line of its own however far
     * along the current one the caret happens to be -- a line paste ignores the column, which is the whole
     * point of it. The caret then goes to the END of what was pasted, so the next thing typed continues
     * the line that just arrived rather than sitting on a blank one after it.</p>
     */
    public void pasteAtCaret(String text) {
        if (text == null || text.isEmpty()) return;
        if (hasSelection() || !text.endsWith("\n")) {
            insertAtCaret(text);
            return;
        }
        int row = buffer.offsetToPoint(getCaret()).row();
        int lineStart = buffer.document().lineStartOffset(row);
        applyEdit(new ArrayList<>(List.of(new Change(lineStart, lineStart, text))));
        // The end of the last line pasted: everything inserted, less the newline that closes it.
        setCaret(Math.min(buffer.length(), lineStart + text.length() - 1));
    }

    public void insertAtCaret(String text) {
        List<Change> changes = new ArrayList<>(selections.count());
        for (Selection selection : selections.all()) {
            changes.add(new Change(selection.start(), selection.end(), text));
        }
        applyEdit(changes);
    }

    /**
     * Applies a rewrite somebody else computed — a whole-document transformation, as minimal edits.
     *
     * <h3>Why this rather than {@link #setText}</h3>
     *
     * <p>{@code setText} is <b>loading a file</b>: it replaces the rope wholesale, which is not an
     * undoable edit, resets the caret to the start, and throws away the widest line measured so far along
     * with every tracked range in the document. That is right for opening a file and wrong for
     * transforming the one on screen — a rewrite Ctrl+Z cannot take back is a rewrite nobody should press.
     * Edits map the carets, the diagnostics, the folds and the search marks through {@code ChangeSet} and
     * record one undo step, which is what makes a transformation ordinary rather than frightening.</p>
     *
     * <p><b>Offsets are into the text as it is now.</b> A caller that computed them off the frame thread
     * has to say so by comparing {@link TextBuffer#version()} against what it snapshotted, exactly as that
     * method's own contract requires — this cannot check for it, because a stale edit is arithmetically
     * indistinguishable from a fresh one and would apply cleanly onto the wrong text.</p>
     *
     * <p>Coalescing is broken first, so the rewrite is its own undo step rather than being folded into
     * the keystroke that preceded it. A rewrite of several changes is refused by the merge predicate
     * anyway — it only ever continues a run of <em>single</em> insertions or deletions — so this matters
     * for the one-change case, where a rewrite that happens to insert exactly where typing stopped is
     * indistinguishable from more typing. Undoing it would then take back the word as well.</p>
     *
     * @return whether anything was applied — false for a read-only editor and for an empty rewrite, both
     *         of which are ordinary outcomes rather than failures
     */
    public boolean applyChanges(List<Change> changes) {
        if (readOnly || changes == null || changes.isEmpty()) return false;
        List<Change> applied = new ArrayList<>(changes);
        applied.removeIf(Change::isEmpty);
        if (applied.isEmpty()) return false;
        buffer.breakUndoCoalescing();
        applyEdit(applied);
        return true;
    }

    /** Applies a set of per-caret changes as one edit, then carries the carets through it. */
    private void applyEdit(List<Change> changes) {
        if (readOnly) return;
        changes.removeIf(Change::isEmpty);
        if (changes.isEmpty()) return;
        ChangeSet edit = ChangeSet.of(buffer.length(), changes);
        buffer.edit(edit, selections.all());
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
            // THE POPUP GETS THE KEYS FIRST, and only the four it owns. Arrows, Enter, Tab and Escape mean
            // something different while a list is open, and the editor's own handler would consume them
            // before any listener downstream could -- so the interception has to be here rather than on the
            // popup, which never holds focus and therefore never receives a key at all.
            if (suggest.handleKey(event.getKeyCode(), event.getModifiers())) {
                event.stopPropagation();
                return;
            }
            if (handleKey(event.getKeyCode(), event.getModifiers())) {
                event.stopPropagation();
                return;
            }
            // Anything the control keys did not claim is a typed character. Accelerator combos are left
            // alone so an unhandled one still reaches the keymap rather than being typed into the
            // document.
            //
            // ALT COUNTS, and its absence here was a real bug: Alt+Shift+S typed a capital S into the
            // file and called stopPropagation(), so the keymap never ran and the binding looked dead.
            // Ctrl+letter escapes that by accident rather than by this check -- a control character is
            // ISOControl and fails the test below -- which is why Ctrl+N worked while Alt+Shift+S did
            // not, and why the difference looked like the binding rather than the editor.
            //
            // KNOWN GAP: AltGr is Ctrl+Alt, and on European layouts it genuinely produces characters that
            // SHOULD be typed. They are refused here, as they already were before Alt was added -- the
            // Ctrl branch caught them. Fixing it means letting Ctrl+Alt through, which would make every
            // Ctrl+Alt binding type instead of firing, so it wants its own change and its own test.
            int mods = event.getModifiers();
            if (CgModifiers.hasCtrl(mods) || CgModifiers.hasSuper(mods) || CgModifiers.hasAlt(mods)) {
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
            int mods = CgPlatform.input().getCurrentModifiers();

            // CTRL+CLICK IS GO-TO-DEFINITION, which the Alt branch below has named as the reason it leaves
            // Ctrl alone since multi-caret went in. It moves the caret FIRST and resolves from there: the
            // resolver is asked about an offset, and asking about the word under the pointer while the
            // caret is still wherever it was would resolve the wrong name -- and would do it silently,
            // since both are real names and both produce a plausible jump.
            if ((CgModifiers.hasCtrl(mods) || CgModifiers.hasSuper(mods)) && !extend && clicks == 1) {
                setCaret(offset);
                goToDefinition();
                event.stopPropagation();
                return;
            }

            if (addCaret && extend && clicks == 1) {
                // ALT+SHIFT+DRAG IS A BOX, which is VS Code's gesture for it and IntelliJ's too. Checked
                // before the plain Alt branch below, because Alt is in both and the one with more
                // modifiers has to win -- the other order makes this unreachable.
                columnAnchor = offset;
                draggingAddedCaret = false;
                dragGranularity = 1;
                dragAnchor = new int[] { offset, offset };
                applyColumnSelection(offset);
            } else if (addCaret && clicks == 1) {
                // Alt+Click adds a caret, as in VS Code. Ctrl is left alone because Ctrl+Click is
                // "go to definition" everywhere it appears.
                addCaret(offset);
                dragGranularity = 1;
                dragAnchor = new int[] { offset, offset };
                draggingAddedCaret = true;
                columnAnchor = -1;
            } else {
                columnAnchor = -1;
                draggingAddedCaret = false;
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
            langFeatures.hover().pointerMoved();
            if (!selecting) return;
            extendDragTo(offsetAt(event.getPosition().x(), event.getPosition().y()));
        }, false, false);

        // THE POINTER LEFT THE TEXT, which is not the same as it having left the popup -- the box sits
        // below the token, so reaching for it fires this immediately. The grace in the ticker is what
        // makes that survivable; hiding here would make the popup unreachable.
        onMouseLeave.attachListener((el, event) -> langFeatures.hover().cancel(), false, false);

        events.getGroup(MouseEvent.Up.class).attachListener((el, event) -> {
            selecting = false;
            columnAnchor = -1;
            dragGranularity = 1;
            dragAnchor = null;
            draggingAddedCaret = false;
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
        // AND Ctrl+TAB, which is never native editing. A tab character is inserted by a BARE Tab and
        // Shift+Tab outdents; Ctrl+Tab has no meaning in a document at all, so eating it could only ever
        // deny it to somebody else -- and it did. It is the desktop's window switcher, so with an editor
        // focused the chord silently indented the current line instead, which reads as the switcher being
        // broken rather than as the editor being greedy. Exactly the shape TextField's Alt bug had: the
        // key was consumed AND acted on, and the keymap only ever sees what is left over.
        if (ctrl && key == CgKeyCodes.KEY_TAB) return false;

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
                             : TypeOperations.backspaceFrom(
                                     buffer.document(), head, indentWidth, language),
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
                else insertTabAtCarets();
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
    private void moveEach(IntUnaryOperator move, boolean extend) {
        clearGoalColumns();
        selections.transform(selection -> {
            int head = Math.max(0, Math.min(move.applyAsInt(selection.head()), buffer.length()));
            return extend ? selection.withHead(head) : Selection.caret(head);
        });
        afterSelectionChange();
        ensureCaretVisible();
    }

    /** Deletes a per-caret range given as {@code {from, to}}. */
    private void deleteEach(IntFunction<int[]> range) {
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

    /**
     * Whether each bar is showing, <b>decided once per pass</b> by {@link #measureScrollbars()}.
     *
     * <h3>Why these are latched rather than derived</h3>
     *
     * <p>Both thicknesses used to ask "does the content overflow?" live. That is mutually recursive by
     * construction — a bar's presence changes the viewport, and the viewport decides the bar — and while
     * the query itself terminates, the <em>answer flips between layout passes</em>: adding the bar shrinks
     * the viewport, which removes the need for the bar, which grows it back.</p>
     *
     * <p>With a <b>definite</b> height the values converge and {@code replaceOrPutCandidate} no-ops, so it
     * settles and nobody notices. With a <b>parent-derived</b> height — {@code height: 100%}, or
     * {@code height: 0; flex-grow: 1} — nothing pins the viewport, the two answers never agree, and
     * {@code calculateLayout}'s {@code while (isLayoutDirty())} never exits. The window hangs on the first
     * layout of that page, with a stack that is pure Taffy and names nothing in this file.</p>
     *
     * <p>Measured: a fixed size or {@code height: 300px} was fine at ~4 ms a frame; {@code height: 100%}
     * and {@code height: 0; flex-grow: 1} both hung outright.</p>
     */
    private boolean horizontalBarShown;
    private boolean verticalBarShown;

    /**
     * The browser's algorithm: assume no bars, measure, add what overflows, re-measure <b>once</b>.
     *
     * <p>Bounded on purpose. Each bar steals from the other axis, so one correction is all that can be
     * needed — and iterating to a fixed point is exactly the oscillation this exists to stop. CSS
     * overflow resolution stops here for the same reason.</p>
     *
     * <p>Reads the previous pass's {@code widestSeen}, which is the same bargain {@link #getScrollWidth()}
     * already documents: a high-water mark that only grows between resets, stale by at most a frame.</p>
     */
    private void measureScrollbars() {
        float clientWidth = getClientWidth();
        float clientHeight = getClientHeight();
        float contentWidth = getScrollWidth();
        float barWidth = Math.max(0f, verticalScroller().getRuntimeCache().getWidth());
        float barHeight = Math.max(0f, horizontalScroller().getRuntimeCache().getHeight());

        // Pass one: no bars at all.
        boolean vertical = contentHeightWithin(clientHeight) > clientHeight;
        boolean horizontal = contentWidth > clientWidth;

        // Pass two: whichever bar appeared takes room from the other axis, which can bring the other in.
        // Only one of these can fire -- if both were already true there is nothing to correct.
        if (vertical && !horizontal) horizontal = contentWidth > clientWidth - barWidth;
        else if (horizontal && !vertical) {
            vertical = contentHeightWithin(clientHeight - barHeight) > clientHeight;
        }

        horizontalBarShown = horizontal;
        verticalBarShown = vertical;
    }

    /**
     * How tall the content would be inside a viewport of {@code viewport}.
     *
     * <p>Takes the viewport as an argument rather than calling {@link #viewportHeight()}, which is the
     * whole point: that method reads the latch, and this runs while the latch is being decided.</p>
     */
    private float contentHeightWithin(float viewport) {
        float content = viewLineCount() * lineHeight();
        // Scrolling past the end already leaves a viewport of empty space below the last line, so the
        // horizontal bar's allowance is the ELSE branch -- adding it here too would be a second allowance
        // for the same problem. Same split getScrollHeight() makes.
        return scrollBeyondLastLine ? content + Math.max(0f, viewport - lineHeight()) : content;
    }

    /** The horizontal bar's thickness when it is showing, otherwise zero. */
    float horizontalBarThickness() {
        if (!horizontalBarShown) return 0f;
        return Math.max(0f, horizontalScroller().getRuntimeCache().getHeight());
    }

    /** The vertical bar's thickness when it is showing, otherwise zero. */
    float verticalBarThickness() {
        if (!verticalBarShown) return 0f;
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
        // `!(x > 0)` RATHER THAN `x <= 0`, and that is the whole fix rather than a stylistic preference.
        // NaN fails every comparison, so `multiplier <= 0f` is FALSE for NaN and the default never
        // applied -- and `Math.max(1f, NaN)` is NaN too, so the floor below did not catch it either. Two
        // guards that both read as protective, neither of which stops the one value that matters.
        //
        // A NaN line height then poisons everything downstream of it: getScrollHeight multiplies by it,
        // getMaxScrollTop subtracts, setScrollImmediate clamps with Math.max/min and stores NaN, and every
        // view part computes a row's top as `origin + line * lineHeight - scrollTop`. One NaN stacks every
        // row in the document at the same y, with nothing having thrown.
        if (!(multiplier > 0f)) multiplier = 1.2f;
        float size = general.fontSize();
        if (!(size > 0f)) return 1f;
        float height = size * multiplier;
        return height > 1f ? height : 1f;
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
    void forgetWidestLine() {
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
    int offsetAtLocal(float localX, float localY) {
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
        // Mirrored: no gutter to skip on this side, but the vertical bar is here instead.
        float leading = gutterOnRight ? verticalBarThickness() : gutterWidth;
        return getTaffyLayout().padding().left + leading + codeLeftPad();
    }

    /**
     * Puts the line-number gutter on the <b>right</b> edge instead of the left.
     *
     * <p>For the left pane of a side-by-side diff, and for nothing else. Both references mirror it there
     * for the same reason: the two panes' line numbers then meet in the middle, so a reader comparing
     * line 1631 against line 1631 looks at one place rather than sweeping across the whole width. It is a
     * property of the <em>comparison</em>, not of the editor, which is why it is off by default and why
     * an editor showing an ordinary file is untouched by its existence.</p>
     *
     * <p><b>Only the geometry mirrors, not the text.</b> The code still reads left to right and still
     * starts at the left edge; what moves is the gutter and the width the text is given.</p>
     */
    public TextEditor setGutterOnRight(boolean value) {
        if (gutterOnRight == value) return this;
        gutterOnRight = value;
        if (value) {
            addClass(GUTTER_RIGHT_CLASS);
        } else {
            removeClass(GUTTER_RIGHT_CLASS);
            // UNDO THE PLACEMENT, or the flag is one-way. layOutMirroredGutter writes at IMPORTANT and
            // then returns early once the flag is off, so the box would stay pinned to the far edge with
            // nothing left to explain why. Handing the properties back to `auto` returns the gutter to
            // the sheet, which is where an unmirrored one has always got its geometry.
            StyleGroup.importantPipeline(gutter.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.RELATIVE)
                            .leftAuto().topAuto().widthAuto().heightAuto());
            StyleGroup.importantPipeline(verticalScroller().getStyle().getLayoutGroup(),
                    l -> l.leftAuto().right(0f));
        }
        invalidateWindow();
        return this;
    }

    public boolean isGutterOnRight() {
        return gutterOnRight;
    }

    /** Where the gutter's own box begins, in this element's coordinates. */
    float gutterLeft() {
        // MIRRORED, THE BAR MOVES TOO -- see layOutMirroredGutter -- so the gutter reaches the edge
        // rather than stopping short of a bar that is no longer there.
        return gutterOnRight
                ? Math.max(0f, getClientWidth() - gutterWidth)
                : getTaffyLayout().padding().left;
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
    /**
     * Where an indent level comes from when Enter is pressed, or null for the syntactic rule.
     *
     * <p>Set beside the tokenizer, because the only implementation is one: a provider needs the parse
     * tree, and the tokenizer is what owns one per document. @see IndentationProvider</p>
     */
    @Nullable
    private IndentationProvider indentation;

    /** @see #indentation */
    public TextEditor setIndentationProvider(@Nullable IndentationProvider provider) {
        this.indentation = provider;
        return this;
    }

    public TextEditor setTokenizer(SyntaxTokenizer newTokenizer) {
        if (this.tokenizer == newTokenizer) return this;
        // Detach the old one's listener before dropping it, or a tokenizer that is still finishing work
        // keeps marking THIS editor dirty about a document it no longer shows.
        this.tokenizer.setInvalidationListener(null);
        this.tokenizer = newTokenizer == null ? SyntaxTokenizer.NONE : newTokenizer;
        // A backend that parses in the background has no other way to say "ask me again": the document
        // did not change when its work landed, so nothing else would ever prompt a re-query and the
        // highlighting would sit one edit behind until something unrelated repainted.
        this.tokenizer.setInvalidationListener((fromOffset, toOffset) -> {
            invalidateRowSyntax(fromOffset, toOffset);
            highlightsDirty = true;
        });
        rowSyntax.clear();
        highlightsDirty = true;
        highlightedFrom = -1;
        highlightedTo = -1;
        return this;
    }

    public SyntaxTokenizer tokenizer() {
        return tokenizer;
    }

    /**
     * Attaches an engine, or detaches one with null.
     *
     * <h3>This editor does not own what it is given</h3>
     *
     * <p>The old services are <b>not closed</b> here, only unsubscribed. Services belong to the document
     * ({@link LanguageServices}), and the same file open in two panes is two editors holding one set —
     * so closing on replacement would release a compiler still in use by the other view. The owner is
     * whoever created it, which today is {@code TextFileDocument}.</p>
     *
     * <p>The subscription is the same one {@link #setTokenizer} makes and for the same reason: a compile
     * lands without the document changing, so nothing else would ever prompt a re-query and the semantic
     * colours would sit one compile behind until an unrelated repaint.</p>
     */
    public TextEditor setLanguageServices(@Nullable LanguageServices services) {
        if (this.languageServices == services) return this;
        if (this.languageServices != null) {
            this.languageServices.semanticTokens().setInvalidationListener(null);
        }
        if (languageDiagnostics != null) {
            languageDiagnostics.disconnect();
            languageDiagnostics = null;
        }
        this.languageServices = services;
        if (services != null) {
            services.semanticTokens().setInvalidationListener((fromOffset, toOffset) -> {
                invalidateRowSyntax(fromOffset, toOffset);
                highlightsDirty = true;
                // A NEW ANALYSIS LANDED. This is the only signal the editor gets that the engine knows more
                // than it did, and a completion list opened against the previous one may have been unable
                // to resolve its receiver at all. Asking again here is what stops an empty popup sitting on
                // screen until the next keystroke.
                suggest.retrigger();
            });
            // THE ENGINE ANNOUNCES, THIS DOCUMENT'S SET OWNS. Filed under the engine's own id so a
            // second producer -- the shader compiler on a .glsl, a future linter -- cannot erase it,
            // which is the whole reason DiagnosticSet is keyed by owner. From here the Problems panel,
            // the inspection widget and the status bar all light up through paths that already work.
            languageDiagnostics = services.onDiagnostics(
                    announced -> problems.install(services.id(), announced));
        }
        rowSyntax.clear();
        highlightsDirty = true;
        highlightedFrom = -1;
        highlightedTo = -1;
        return this;
    }

    /** The engine behind this document, or null. @see LanguageServices */
    @Nullable
    public LanguageServices languageServices() {
        return languageServices;
    }

    // ── Go-to-definition ────────────────────────────────────────────────────────────────────────

    /**
     * Where {@link #goToDefinition} sends a jump it cannot make itself — a declaration in <em>another</em>
     * file.
     *
     * <p><b>The editor deliberately cannot open documents.</b> Same line {@code ProblemsPanel} draws and
     * states for the same reason: opening a file is a workspace-level act, and a widget that did it itself
     * would have to reach a client, a tab strip and a dock through the application to get there.</p>
     *
     * <p>A <b>same-file</b> jump is deliberately <em>not</em> announced, and that asymmetry is the design
     * rather than an omission. Nothing is opened, no tab changes, and the entire act is moving this
     * widget's own caret — so routing it out and back would hand the shell a movement it cannot perform
     * better, and would make jumping to a local variable depend on a workbench that a harness scene and a
     * bare editor test do not have. What goes out is what genuinely cannot be done from in here.</p>
     *
     * <p>Both halves still converge on {@link #revealAt}, so they cannot drift on what "arriving" means.</p>
     */
    public final Signal.Value<DeclarationSite> onDefinitionChosen = new Signal.Value<>();

    /**
     * Put the caret at {@code at} and frame it — the one movement every navigation ends with.
     *
     * <p>Shared rather than repeated because {@link #setCaret} deliberately does not scroll, so every
     * caller has to remember a second call; the ones that forgot it are indistinguishable from nothing
     * having happened. Centred rather than merely visible, because a line you were <em>sent</em> to with
     * all of its context on one side is the worst possible framing for it — both references centre for
     * navigation and only scroll minimally for typing.</p>
     */
    public void revealAt(TextPoint at) {
        setCaret(buffer.pointToOffset(at));
        // NOT YET, IF THERE IS NOTHING TO CENTRE IN. @see #pendingReveal
        if (canCentre()) {
            pendingReveal = false;
            revealCaretCentred();
            return;
        }
        pendingReveal = true;
    }

    /**
     * A jump that arrived before the editor had a viewport, waiting for one.
     *
     * <h3>Why centring silently degrades into "put it at the top"</h3>
     *
     * <p>{@link #revealCaretCentred} scrolls to {@code caretY - viewportHeight / 2}. Every caller that
     * jumps into a file <b>just opened</b> — Ctrl+B into another file, Go to File with a line, a Problems
     * row — runs the moment the read lands, which is before that tab has been through a layout pass. The
     * height is then zero, the halving is zero, and the destination lands hard against the <em>top</em>
     * edge with the whole of its context below it.</p>
     *
     * <p>That is not a broken jump but a correct one framed as badly as possible, and it reads as the
     * centring never having been implemented — the caret really is on the right line. The same arithmetic
     * is right the moment a height exists, so this waits for one rather than reimplementing it.</p>
     *
     * <p>Drained in {@link #tickFrame}, which always runs, rather than from {@code onLayoutChanged}: a
     * newly attached editor can settle its layout in the same pass that created it, and a reveal issued
     * from inside layout would be scrolling a box whose scroll extents are still being computed.</p>
     */
    private boolean pendingReveal;

    /** Whether there is enough measured viewport for centring to mean anything. */
    private boolean canCentre() {
        return viewportHeight() > lineHeight();
    }

    // ── Language features ────────────────────────────────────────────────────────────────────
    //
    // The subsystem is EditorLanguageFeatures: resolve, documentation, code actions and go-to-definition,
    // which share the request-serial machinery that makes an asynchronous answer safe to act on. They are
    // one class for that reason and not four -- four copies of the two discards would drift on exactly the
    // rule that must not.

    private final EditorLanguageFeatures langFeatures = new EditorLanguageFeatures(this);

    /** Resolve, documentation, code actions, go-to-definition. */
    EditorLanguageFeatures langFeatures() {
        return langFeatures;
    }

    /**
     * Asks every contributor what can be done about the problems at {@code offset}.
     *
     * @see EditorLanguageFeatures#requestCodeActions(int, java.util.function.Consumer)
     */
    public boolean requestCodeActions(int offset, java.util.function.Consumer<List<CodeAction>> answer) {
        return langFeatures.requestCodeActions(offset, answer);
    }

    /** Applies one action — the only path. @see EditorLanguageFeatures#applyCodeAction */
    public boolean applyCodeAction(@Nullable CodeAction action) {
        return langFeatures.applyCodeAction(action);
    }

    /** {@code Show on Mouse Move} — IntelliJ's own name for this, and on by default as it is there. */
    public TextEditor setHoverDocumentationEnabled(boolean enabled) {
        langFeatures.setHoverEnabled(enabled);
        return this;
    }

    public boolean isHoverDocumentationEnabled() {
        return langFeatures.isHoverEnabled();
    }

    /** Test seam — @see HoverDocumentation#pointerForTest */
    public void hoverPointerForTest(int offset) {
        langFeatures.hover().pointerForTest(offset);
    }

    /** Test seam — @see HoverDocumentation#hoverAnchorAtForTest */
    public int hoverWordStartAtForTest(float localX, float localY) {
        return langFeatures.hover().hoverAnchorAtForTest(localX, localY);
    }

    boolean isSelecting() {
        return selecting;
    }

    float pointerX() {
        return pointerX;
    }

    float pointerY() {
        return pointerY;
    }

    WordClassifier wordClassifier() {
        return wordClassifier;
    }

    /** The live Quick Documentation popup, or null. Exposed so a test can read it without pixels. */
    @Nullable
    public DocumentationPopup documentationPopup() {
        return langFeatures.documentationPopup();
    }

    /**
     * Resolve at the caret and show the Quick Documentation popup — {@code Ctrl+Q}.
     *
     * @see EditorLanguageFeatures#showQuickDocumentation()
     */
    public boolean showQuickDocumentation() {
        return langFeatures.showQuickDocumentation();
    }

    /** The full action list at an offset — Alt+Enter. @see EditorLanguageFeatures#showCodeActionsAt */
    public boolean showCodeActionsAt(int offset) {
        return langFeatures.showCodeActionsAt(offset);
    }

    /** The overflow menu, and the lightbulb row inside it. */
    public static final String CODE_ACTIONS_CLASS = "__code-actions__";
    public static final String PREFERRED_ACTION_CLASS = "__preferred-action__";

    /** Moves the caret to {@code problem} and centres it — what clicking a stripe mark does. */
    public boolean goToDiagnostic(@Nullable Diagnostic problem) {
        return goToProblem(problem);
    }

    /** Closes the documentation popup if it is open. */
    public void closeQuickDocumentation() {
        langFeatures.closeQuickDocumentation();
    }

    /**
     * Resolve the name at the caret and go to where it is declared — {@code Ctrl+B}, and Ctrl+Click.
     *
     * @return whether a request was issued at all
     * @see EditorLanguageFeatures#goToDefinition()
     */
    public boolean goToDefinition() {
        return langFeatures.goToDefinition();
    }

    // ── Completion ──────────────────────────────────────────────────────────────────────────────
    //
    // The subsystem is EditorSuggest. anchorInWindow and isInCommentOrString stay here: the first is
    // shared with the documentation popup and the second with bracket matching, and both are seams where
    // being asked twice is how two answers appear.

    private final EditorSuggest suggest = new EditorSuggest(this);

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0f;
    }

    /** The live session, or null. Exposed so a test can assert on the model without going through pixels. */
    @Nullable
    public CompletionSession completionSession() {
        return suggest.session();
    }

    /**
     * Opens a completion session at the caret — Ctrl+Space, or a trigger character.
     *
     * @return false when nothing opened — no engine, or the caret is somewhere completion has no business
     * @see EditorSuggest#open
     */
    public boolean openCompletion(CompletionProvider.TriggerKind trigger, @Nullable String triggerCharacter) {
        return suggest.open(trigger, triggerCharacter);
    }

    public void closeCompletion() {
        suggest.close();
    }

    /** The popup, built on first use. Null until a session has opened in an attached window. */
    @Nullable
    public CompletionPopup completionPopup() {
        return suggest.popup();
    }

    /** Asks for completions here, whether or not a list is already open. @see EditorSuggest#trigger */
    public void triggerSuggest() {
        suggest.trigger();
    }

    /**
     * Where {@code offset} sits in the window's coordinate space, as {@code {x, y, lineHeight}}.
     *
     * <p>Shared by both popups because it is the seam where two coordinate spaces meet, and a popup placed
     * from the wrong one looks deliberately positioned while being wrong by exactly {@code uiScale}. Two
     * copies of this would be two chances to reach for the transform chain, which is in surface pixels and
     * is only populated once the element has painted.</p>
     *
     * <h3>Summed from the LAYOUT chain, not from the transform chain</h3>
     *
     * <p>The obvious implementation is {@code localToWorld}, and it is wrong here twice over. It is in
     * <b>surface</b> pixels — the root transform is baked into it — while the popup's {@code left}/{@code
     * top} are ordinary style values in logical pixels that the paint scales <em>again</em>, so the anchor
     * comes out multiplied by {@code uiScale}. And it is populated <b>during {@code drawSubtree}</b>, so
     * anything asking before this element has painted reads an identity matrix and gets the window's corner.
     * Both faults produce a popup that is neatly placed somewhere wrong, which is the hardest kind to
     * notice: it looks like a placement policy rather than a bad number.</p>
     *
     * <p>Summing {@code getLayoutX()}/{@code getLayoutY()} to the root gives the position in exactly the
     * space {@code left}/{@code top} are interpreted in, with no scale in it and no dependency on having
     * painted. The cost is that it ignores {@code transform:} — which is correct rather than a limitation,
     * because a transformed editor's popup should follow its layout box, not its visual one.</p>
     */
    @Nullable
    float[] anchorInWindow(int at) {
        int anchorOffset = Math.max(0, Math.min(at, buffer.length()));
        int viewLine = viewLineOf(anchorOffset, LineProjection.Affinity.RIGHT);
        ProjectedLines.ModelPosition model = modelAt(viewLine);
        int rowStart = buffer.document().lineStartOffset(model.row());
        LineProjection.ViewPosition view = projectionAt(viewLine)
                .toViewPosition(anchorOffset - rowStart, LineProjection.Affinity.RIGHT);

        // THE SCROLL OFFSET CAN BE NaN, and this is the seam where that stops being someone else's problem.
        //
        // A NaN scroll poisons everything downstream silently: it propagates through the subtraction, then
        // through the min/max in the placement, and lands the popup at the window's corner looking
        // deliberately placed. Treating a non-finite offset as zero is right rather than merely defensive —
        // "scrolled by an unknown amount" and "not scrolled" are the same picture for a document that has
        // not been scrolled, and the alternative is a popup nobody can find.
        float localX = textOriginX() + xOfView(viewLine, view.column()) - finiteOrZero(getScrollLeft());
        float localY = screenTopOfViewLine(viewLine);
        if (!Float.isFinite(localX) || !Float.isFinite(localY)) return null;
        return new float[] { getWindowX() + localX, getWindowY() + localY, lineHeight() };
    }

    /**
     * Whether {@code offset} is inside a comment or a string, per the grammar.
     *
     * <p>Read from the tokenizer's capture names, which is the vocabulary §10.1 already fixes — so this
     * needs no per-language table and a new grammar gets the suppression for free. A language with no
     * tokenizer answers false, which is the right default: it means the popup opens, and an unwanted popup
     * is recoverable in a way that a popup that refuses to open is not.</p>
     */
    boolean isInCommentOrString(int offset) {
        int from = Math.max(0, offset - 1);
        for (SyntaxToken token : tokenizer.tokenize(buffer.document(), from, offset + 1)) {
            if (token.start() > offset || token.end() < offset) continue;
            String capture = token.name();
            if (capture == null) continue;
            if (capture.equals("comment") || capture.startsWith("comment.")
                    || capture.equals("string") || capture.startsWith("string.")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Releases what this editor's own document work holds — the tokenizer's natives and the engine.
     *
     * <p><b>Not called from the widget's own teardown</b>, deliberately. A widget can be removed from the
     * tree and re-added, and a dock rebuild does exactly that on every split and drag; closing the parse
     * tree there would free natives for a document that is still open and rebuild them on the next frame.
     * The document is what ends, so the document calls this — {@code TextFileDocument.dispose()}.</p>
     *
     * <p>Idempotent, because the paths that reach it overlap: a file deleted while its tab is open can
     * plausibly arrive from both ends.</p>
     */
    public void disposeLanguage() {
        long timed = FrameProfile.begin();
        tokenizer.setInvalidationListener(null);
        tokenizer.close();
        tokenizer = SyntaxTokenizer.NONE;
        FrameProfile.step(timed, "close.tokenizer.close (frees the native trees)");
        if (languageDiagnostics != null) {
            languageDiagnostics.disconnect();
            languageDiagnostics = null;
        }
        if (languageServices != null) {
            timed = FrameProfile.begin();
            languageServices.semanticTokens().setInvalidationListener(null);
            languageServices.close();
            languageServices = null;
            FrameProfile.step(timed, "close.languageServices.close");
        }
        timed = FrameProfile.begin();
        rowSyntax.clear();
        FrameProfile.step(timed, "close.rowSyntax.clear");
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
        if (!highlightsDirty && !highlightWindowMoved && from == highlightedFrom && to == highlightedTo) {
            return;
        }
        highlightWindowMoved = false;
        // WHY WE ARE HERE, captured before the flag is cleared.
        //
        // The early-out above is all-or-nothing on the visible OFFSET RANGE, and a scroll changes that
        // range every single frame -- so scrolling rebuilt every realised row's bands, every frame, from
        // scratch. Measured on a 2,020-line class: `ed:highlightBands x34` at 3.3ms typical and 14ms
        // worst, inside an `ed:updateWindow` of which it was 57-90%, with 61 of 107 frames over budget
        // for the length of the scroll.
        //
        // But scrolling does not change what any row's bands ARE. It changes which rows are on screen,
        // and moving the viewport by one line leaves 33 of 34 rows showing exactly the view line they
        // were already showing. So a pure range change rebuilds only what genuinely moved, and anything
        // that changes the CONTENT of a row's bands -- a reparse, a new selection, a search, a fold, an
        // edit -- goes on setting `highlightsDirty` and rebuilds the lot, exactly as before.
        boolean rebuildEveryRow = highlightsDirty;
        highlightedFrom = from;
        highlightedTo = to;
        highlightsDirty = false;

        long syntaxTimed = FrameProfile.begin();
        ensureRowSyntax(firstViewLine, lastViewLine);
        FrameProfile.end(syntaxTimed, "ed:ensureRowSyntax");
        long bandsTimed = FrameProfile.begin();
        int rebuilt = 0;
        for (Map.Entry<Integer, UIElement> entry : realisedLines.entrySet()) {
            int viewLine = entry.getKey();
            if (viewLine < 0 || viewLine >= viewLineCount()) continue;
            // ALREADY SHOWING THIS VIEW LINE, and nothing about its content changed. @see #bandsShownFor
            if (!rebuildEveryRow && Integer.valueOf(viewLine).equals(bandsShownFor.get(entry.getValue()))) {
                continue;
            }
            rebuilt++;
            // Ranges are offsets into the UIText this line owns, and that text is one VIEW line -- so a
            // wrapped row's second half must publish ranges relative to where IT starts. Using the row's
            // start would push every colour on a continuation line left by the width of everything above
            // it, which reads as the highlighter losing sync rather than as a coordinate bug.
            int lineStart = viewLineStartOffset(viewLine);
            int lineEnd = viewLineEndOffset(viewLine);
            int modelRow = modelAt(viewLine).row();
            int rowStart = buffer.document().lineStartOffset(modelRow);

            Map<String, List<TextRange>> byName = new LinkedHashMap<>();
            addDocumentRanges(byName, "occurrence", occurrences, lineStart, lineEnd);
            addDocumentRanges(byName, "selection-occurrence", selectionOccurrences, lineStart, lineEnd);
            // AFTER the occurrences, so a search hit wins the character where the two overlap. A search
            // is something you asked for; occurrences are something the caret happened to be standing in.
            addDocumentRanges(byName, "search", find.matches(), lineStart, lineEnd);
            // A SECOND NAME rather than a second mechanism: `::highlight()` already carries
            // `text-decoration-line`, so an excluded span is struck through by the sheet.
            addDocumentRanges(byName, "search-excluded", find.excludedRanges(), lineStart, lineEnd);
            if (bracketPair != null) {
                addDocumentRanges(byName, "bracket", List.of(
                        TextRange.of(bracketPair[0], bracketPair[0] + 1),
                        TextRange.of(bracketPair[1], bracketPair[1] + 1)), lineStart, lineEnd);
            }
            // From the cache, and rebased twice: the entries are relative to their MODEL row, while a
            // range published here must be relative to the VIEW line -- which for a wrapped row is some
            // way into it. Doing only the first would push every colour on a continuation line left by
            // the width of everything above it.
            List<SyntaxToken> rowTokens = rowSyntax.get(modelRow);
            if (rowTokens != null && !rowTokens.isEmpty()) {
                for (SyntaxToken token : rowTokens) {
                    int start = Math.max(rowStart + token.start(), lineStart);
                    int end = Math.min(rowStart + token.end(), lineEnd);
                    if (end <= start) continue;
                    TextRange range = TextRange.of(start - lineStart, end - lineStart);
                    // THE GENERAL FORM GOES IN FIRST, and the order is the whole of its meaning.
                    //
                    // A dotted capture is published under both names so a theme that has not styled
                    // `function.call` still colours it as `function`. That is a FALLBACK -- but these end
                    // up in one insertion-ordered map, and whichever name is written last wins the
                    // character. Publishing the specific name first therefore inverted it: every
                    // specialisation was overwritten by its own stem, so `function.call` resolved
                    // correctly and then took `function`'s colour anyway, and the distinction the query
                    // was adjusted to make disappeared before it reached the screen.
                    String general = token.generalName();
                    if (general != null) SyntaxHighlighting.addRange(byName, general, range);
                    SyntaxHighlighting.addRange(byName, token.name(), range);
                }
            }

            // AFTER THE SYNTAX TOKENS, and that order is the whole of the effect. A character belongs to
            // one highlight and the last name written wins it, so publishing here REPLACES the token's
            // colour rather than tinting it -- which is IntelliJ's look for dead code (flat grey) rather
            // than VS Code's (opacity, which keeps the hue). The alternative would be blending colours in
            // the paint path, and the sheet can express one of these and not the other.
            addTagRanges(byName, lineStart, lineEnd);

            // CLAMPED to what is actually painted. A collapsed header stops drawing its trailing bracket,
            // so a token covering it would publish a range past the end of the string. Correct to do
            // unconditionally -- a range beyond the text is never meaningful, folding or not.
            int painted = textOf(entry.getValue()).getText().length();
            // SOURCE OFFSETS BECOME DISPLAY OFFSETS HERE, and every producer above needs it: search
            // matches, the bracket pair, `::highlight()` ranges and the syntax tokens are all expressed
            // in document offsets, while the string they are about to be applied to is
            // viewLineDisplayText -- which has TABS EXPANDED.
            //
            // One tab therefore pushed everything after it left by (tabSize - 1) columns. Invisible in a
            // space-indented codebase, which is why it survived: it took console output, where
            // printStackTrace indents every frame with a real tab, for it to show -- as links underlining
            // three characters to the left of the text they point at.
            //
            // Done ONCE, at the end, rather than per producer: they all share the fault, and a remap
            // inside each one is four places for the next producer to forget.
            // EXPANDED FROM THE LIVE ROW, never from `rowMetrics`. That is a computeIfAbsent cache, so a
            // stale entry is returned silently -- and `displayIndexOf` CLAMPS its argument to the table's
            // own length, so a table measured from a shorter row truncates every range on the real one at
            // a different point. The Run console's per-script filter showed exactly that: `RunTest` where
            // `RunTest.java:61` belonged, `Threa` for `Thread.java:1583`, and the last frame untouched
            // because its stale counterpart happened to be long enough.
            //
            // A paint pass must not depend on a cache it cannot check. Skipped entirely for a row with no
            // tab, which is nearly all of them: display equals source there, so the mapping is the
            // identity and there is nothing to expand.
            String source = buffer.line(modelRow);
            CursorColumns.Line expanded =
                    source.indexOf('\t') < 0 ? null : CursorColumns.expand(source, tabSize);
            int viewSourceStart = lineStart - rowStart;
            int displayFrom = expanded == null ? viewSourceStart : expanded.displayIndexOf(viewSourceStart);
            for (List<TextRange> ranges : byName.values()) {
                for (int i = ranges.size() - 1; i >= 0; i--) {
                    TextRange range = ranges.get(i);
                    int start = expanded == null ? range.start()
                            : expanded.displayIndexOf(viewSourceStart + range.start()) - displayFrom;
                    int end = expanded == null ? range.end()
                            : expanded.displayIndexOf(viewSourceStart + range.end()) - displayFrom;
                    start = Math.min(Math.max(0, start), painted);
                    end = Math.min(Math.max(0, end), painted);
                    // DROPPED, not clamped to empty. TextRange refuses a zero-width range outright, so
                    // building one to filter it afterwards throws instead of filtering -- and the range
                    // that collapses is the bracket-match on the very brace the chip took over, i.e. the
                    // single most likely one to exist here.
                    if (end <= start) ranges.remove(i);
                    else if (start != range.start() || end != range.end()) ranges.set(i, TextRange.of(start, end));
                }
            }
            byName.values().removeIf(List::isEmpty);

            // CLEARED AND REBUILT, so the ORDER is this line's and not the previous occupant's.
            //
            // Order is not cosmetic here: a character covered by two names takes the colour of whichever
            // was registered LAST, and tree-sitter's convention is that a later pattern refines an earlier
            // one. The Java grammar leans on it — `(identifier) @variable` is its first pattern and matches
            // everything, with @constant, @type and @function.method arriving later to say what a given
            // identifier actually is.
            //
            // Removing the absent names and re-setting the rest looked equivalent and was not: setting an
            // existing key in a LinkedHashMap keeps its ORIGINAL position, so a name kept whatever slot it
            // had been given by whichever line this pooled element rendered before. `variable` outranked
            // `constant` or not depending on scroll history, which is as close to random as makes no
            // difference — and it presented as constants simply never being purple.
            HighlightRegistry highlights = textOf(entry.getValue()).highlights();
            highlights.clear();
            for (Map.Entry<String, List<TextRange>> named : byName.entrySet()) {
                highlights.set(named.getKey(), named.getValue());
            }
            bandsShownFor.put(entry.getValue(), viewLine);
        }
        FrameProfile.end(bandsTimed, "ed:highlightBands " + rebuilt + "/" + realisedLines.size());
    }

    /**
     * Files one range under one highlight name, dropping it if that name already covers the text.
     *
     * <p><b>A highlight refuses overlapping ranges</b>, and nesting is ordinary rather than exceptional:
     * a grammar captures a string literal and then captures the escape sequence <em>inside</em> it. The
     * two are different names and coexist happily — until the dotted fallback republishes
     * {@code string.escape}'s range under {@code string}, which is already covering those characters. The
     * result is a hard failure from {@code HighlightRegistry.set}, and it only became reachable when a
     * real grammar replaced the word-list lexer, because a lexer never nests.</p>
     *
     * <p>Dropping is the correct resolution rather than a workaround: the outer range and the inner one
     * carry <em>the same name</em>, so they resolve to the same colour, and the fallback exists only to
     * cover what a specific name did not. Anything it duplicates is by definition already handled.</p>
     */
    /** The highlight name {@link DiagnosticTag#UNNECESSARY} is styled through. */
    static final String UNNECESSARY_HIGHLIGHT = "unnecessary";

    /**
     * Publishes the ranges that change how text is <b>drawn</b> rather than marked — unused code faded,
     * deprecated code struck through.
     *
     * <h3>Why this is not the squiggle</h3>
     *
     * <p>{@link DiagnosticTag}'s own note makes the case: "unused import" is not a lesser warning, it is a
     * different kind of statement, and underlining it says "something is wrong here" about code whose only
     * problem is that nobody reads it. Every reference implementation fades instead — which is what lets
     * them report six kinds of unused thing without the file looking broken.</p>
     *
     * <p>It reuses the highlight mechanism the syntax colours already arrive through, so the whole feature
     * is two names and two rules in the sheet. The colour stays in CSS, which is what lets a scheme decide
     * how faded "faded" is; nothing here knows what either tag looks like.</p>
     *
     * <p><b>Offsets come from the tracked lane, never from the diagnostic.</b> The same rule
     * {@code SquigglesPart} is built on and for the same reason: a row/column converted against the live
     * buffer is right only at the instant the analysis landed, so 300ms of typing later the fade would sit
     * over whatever moved into those offsets. A range whose text was deleted draws nothing at all.</p>
     *
     * <p><b>{@link DiagnosticTag#DEPRECATED} is deliberately not published here.</b> The
     * {@code deprecated} highlight already exists and is fed by the <em>semantic token</em> path, which
     * is the better producer of it in two ways: it marks the reference itself rather than whatever range
     * a diagnostic happened to cover, and it works whether or not the deprecation warning is switched on.
     * Publishing it a second time would put two producers on one name for no gain. The tag is still
     * carried on the diagnostic — the Problems panel styles its own row from it.</p>
     */
    private void addTagRanges(Map<String, List<TextRange>> byName, int lineStart, int lineEnd) {
        for (TrackedRange tracked : buffer.decorations().inLane(DIAGNOSTIC_LANE)) {
            Diagnostic diagnostic = tracked.payload(Diagnostic.class);
            if (diagnostic == null || tracked.collapsedByEdit()) continue;
            if (!diagnostic.hasTag(DiagnosticTag.UNNECESSARY)) continue;

            int start = Math.max(tracked.from(), lineStart);
            int end = Math.min(tracked.to(), lineEnd);
            if (end <= start) continue;
            SyntaxHighlighting.addRange(byName, UNNECESSARY_HIGHLIGHT, TextRange.of(start - lineStart, end - lineStart));
        }
    }

    /**
     * How long a pause counts as having stopped typing.
     *
     * <p>IntelliJ's {@code DaemonCodeAnalyzerSettings.getAutoReparseDelay()}, whose default is the same
     * 300ms.</p>
     */
    private static final long TYPING_SETTLE_NANOS = 300_000_000L;

    /**
     * <b>Nothing is re-highlighted while you are typing.</b>
     *
     * <h3>Two reasons a row's colours can be wrong, and they are not the same</h3>
     *
     * <p>The text <b>moved</b> — the offsets are stale and the colours are still right. The text
     * <b>changed meaning</b> — the colours are stale. This used to answer both by dropping the row and
     * re-querying, and conflating them is what made typing churn: a row that only needed shifting was
     * thrown away and rebuilt from whatever the parser currently believed.</p>
     *
     * <p>So an edit now <b>maps</b> tokens forward ({@link #mapRowSyntaxThroughEdit}) and records the row
     * as stale, and a new answer is only <b>adopted</b> once typing has settled.</p>
     *
     * <h3>Why waiting is the point rather than an optimisation</h3>
     *
     * <p>{@code TreeSitterTokenizer} answers from a stale tree while a reparse is in flight, on the
     * grounds that it is "structurally behind but positionally correct". That is true of <em>positions</em>
     * and false of <em>colours</em>. When the reparse lands it is a <b>correct parse of incomplete
     * text</b>: tree-sitter recovers from the half-written token, and the recovery re-classifies
     * everything after it. Adopting that repainted the whole rest of the file a beat after each keystroke,
     * and again a few keystrokes later with a different recovery — reported as "typing invalidates all the
     * following lines for a second or two".</p>
     *
     * <p>IntelliJ never shows this, and not because its parser is better: the pass that would is
     * <em>cancelled</em> by every keystroke and restarted on the delay above. Its lexer tier can afford to
     * run eagerly because a lexer is <b>local</b> — a stray character cannot change how the next line
     * lexes. A parse is not local. The engine already recorded this from the other end, where one
     * unparseable {@code import} line decoloured every line below it.</p>
     *
     * <h3>The word being typed goes plain by construction</h3>
     *
     * <p>{@link #mapRowSyntaxThroughEdit} drops any token <em>touching</em> the edit point and shifts the
     * rest, so the identifier under the caret loses its colour the moment it is edited while the rest of
     * the row keeps its own — which is exactly what IntelliJ draws, with no separate machinery for it. A
     * previous attempt suppressed the word explicitly and needed a snapshot, a burst range and a shift to
     * do it; all three were the mapping, written out longhand for one row.</p>
     *
     * <p>Cost, measured on a 2,000-line Java file with the production scheduler installed: the grammar
     * tier is <b>424µs a keystroke</b>, of which 68µs is the per-row query this skips. The other 356µs is
     * {@code edited()} keeping the tree in sync and cannot be skipped without desynchronising it.</p>
     */
    private void settleSyntaxIfIdle() {
        if (!editing) return;
        // Guarded on `editing` rather than on a "long ago" sentinel: nanoTime has an arbitrary origin and
        // may be negative, so a sentinel subtraction can overflow into a SMALL elapsed time.
        if (System.nanoTime() - lastEditNanos < TYPING_SETTLE_NANOS) return;
        editing = false;
        if (!staleRows.isEmpty()) highlightsDirty = true;
    }

    /**
     * Moves a row's tokens through an edit instead of discarding them.
     *
     * <p>Row-relative, so only the edited row's own tokens move — and a token <b>touching</b> the edit
     * is dropped rather than moved, because it is the one being written. That is what draws the
     * identifier under the caret in the plain foreground: typing at the end of {@code value} extends that
     * token rather than following it, so keeping it would colour a name that no longer exists.</p>
     *
     * <h3>An edit is a RANGE, and treating it as a point crashed the editor</h3>
     *
     * <p>This took only {@code column} and the net {@code delta}, and asked whether each token was before
     * or after that one offset. For an insertion the two are the same thing — nothing is consumed, so the
     * point IS the range. For a REPLACEMENT they are not, and typing a character while text is selected is
     * a replacement: {@code delta} is {@code 1 - selectionLength} and hugely negative, while every token
     * that lived <em>inside</em> the selection still answers {@code start() > column} and gets shifted by
     * it. {@code SyntaxToken} then refuses the result — {@code bad token range -2..1}, out of a keystroke,
     * on the frame thread, taking the game down with it.</p>
     *
     * <p>So the extent is passed rather than folded into the delta, and anything overlapping the consumed
     * range goes with it. A kept token is then provably in range: it starts past {@code column + replaced},
     * so its new start is at least {@code column + inserted + 1}. With {@code replaced == 0} this is
     * exactly the old behaviour, which is why plain typing was never affected and why the bug needed a
     * selection to show at all.</p>
     */
    private void shiftRowSyntax(int row, int column, int replaced, int inserted) {
        List<SyntaxToken> tokens = rowSyntax.get(row);
        if (tokens == null || tokens.isEmpty()) return;
        int consumedTo = column + replaced;
        int delta = inserted - replaced;
        List<SyntaxToken> moved = new ArrayList<>(tokens.size());
        for (SyntaxToken token : tokens) {
            if (token.end() < column) moved.add(token);
            else if (token.start() > consumedTo) {
                moved.add(new SyntaxToken(token.start() + delta, token.end() + delta, token.name()));
            }
            // Touching or inside the edited range -- it is being written, so it has no colour until the
            // text settles.
        }
        rowSyntax.put(row, moved);
    }

    /**
     * Renumbers the cache when an edit changed the line COUNT.
     *
     * <p>The map is keyed by row index, so inserting or removing a line makes every row below describe
     * someone else's text. Dropping them all is the easy answer and it is what made pressing Enter
     * repaint the viewport; moving the keys keeps every untouched row's colours through it.</p>
     */
    private void renumberRowSyntax(int atRow, int delta) {
        Map<Integer, List<SyntaxToken>> moved = new HashMap<>();
        for (Map.Entry<Integer, List<SyntaxToken>> entry : rowSyntax.entrySet()) {
            int row = entry.getKey();
            if (row < atRow) moved.put(row, entry.getValue());
            else if (row > atRow && row + delta > atRow) moved.put(row + delta, entry.getValue());
            // The edited row itself is dropped: it was split, or something was joined onto it.
        }
        rowSyntax.clear();
        rowSyntax.putAll(moved);

        Set<Integer> stale = new HashSet<>();
        for (int row : staleRows) {
            if (row < atRow) stale.add(row);
            else if (row > atRow && row + delta > atRow) stale.add(row + delta);
        }
        staleRows.clear();
        staleRows.addAll(stale);
    }

    /**
     * Fills {@link #rowSyntax} for any realised row that has no entry — and asks the tokenizer nothing
     * when they all do, which is the entire point.
     *
     * <p>Scrolling back over rows already seen, a repaint, a fold, a resize and a selection change all
     * land here with a full cache and cost one map lookup per row. Typing invalidates one row (or the
     * rows below it, when the line count moved) and so queries a row-sized range rather than a
     * viewport-sized one.</p>
     *
     * <p>The query covers the whole span from the first to the last uncached row rather than issuing one
     * per row: a tree-sitter query has a fixed setup cost that dwarfs a few extra rows of range, so n
     * small queries are slower than one slightly larger one. Rows in the span that were already cached
     * are re-filled from the same result, which is free and keeps the code honest about what the span
     * covers.</p>
     */
    private void ensureRowSyntax(int firstViewLine, int lastViewLine) {
        // ADOPTED HERE AND NOWHERE ELSE. Everything upstream only ever RECORDS that a row's answer has
        // changed; this is the one place a new one replaces what is on screen, and it runs only once
        // typing has settled. See settleSyntaxIfIdle.
        //
        // AND THE RECOVERY GUARD APPLIES HERE TOO. It was written on the other way into this cache -- the
        // else-branch of invalidateRowSyntax, which is the path an announcement takes when the user is NOT
        // typing -- and settling dropped every stale row unconditionally. That is the path a keystroke
        // actually takes: the analysis debounce and this settle are both 300ms, so an ordinary pause at
        // the end of a word lands the parse while `editing` is still true. So the guard was installed on
        // one of two doors and the traffic came through the other. @see #keepsColoursThroughRecovery
        if (!editing && !staleRows.isEmpty()) {
            for (int row : staleRows) {
                if (!keepsColoursThroughRecovery(row)) rowSyntax.remove(row);
            }
            staleRows.clear();
        }
        SemanticTokenProvider semantic = languageServices == null
                ? SemanticTokenProvider.NONE : languageServices.semanticTokens();
        if (tokenizer == SyntaxTokenizer.NONE && semantic == SemanticTokenProvider.NONE) return;

        int firstMissing = Integer.MAX_VALUE;
        int lastMissing = -1;
        for (int viewLine = firstViewLine; viewLine <= lastViewLine; viewLine++) {
            if (viewLine < 0 || viewLine >= viewLineCount()) continue;
            int row = modelAt(viewLine).row();
            if (rowSyntax.containsKey(row)) continue;
            firstMissing = Math.min(firstMissing, row);
            lastMissing = Math.max(lastMissing, row);
        }
        if (lastMissing < 0) return;

        int lineCount = buffer.lineCount();
        firstMissing = Math.max(0, Math.min(firstMissing, lineCount - 1));
        lastMissing = Math.max(0, Math.min(lastMissing, lineCount - 1));

        int spanStart = buffer.document().lineStartOffset(firstMissing);
        int spanEnd = clampToDocument(buffer.document().lineStartOffset(lastMissing)
                + buffer.line(lastMissing).length());

        // Seed every row THIS PASS IS FILLING as "queried, nothing found" first. A row with no captures
        // at all -- a blank line, a line of punctuation the grammar does not name -- would otherwise stay
        // absent and be re-queried on every single refresh, which is the cache failing exactly where it
        // looks like it is working.
        //
        // A row already in the cache is NOT one of them, and the distinction only started to matter when
        // rows began surviving an invalidation on purpose (see keepsColoursThroughRecovery). The span runs
        // from the first missing row to the last, so it sweeps past rows that are present; seeding those
        // too threw away exactly the colours that had just been kept, and it did it one frame later, which
        // made it look as though the keeping had never happened.
        Set<Integer> filling = new HashSet<>();
        for (int row = firstMissing; row <= lastMissing; row++) {
            if (rowSyntax.containsKey(row)) continue;
            rowSyntax.put(row, new ArrayList<>());
            filling.add(row);
        }
        if (filling.isEmpty()) return;

        long grammarTimed = FrameProfile.begin();
        List<SyntaxToken> grammarTokens = tokenizer.tokenize(buffer.document(), spanStart, spanEnd);
        FrameProfile.end(grammarTimed, "ed:tokenize");
        FrameProfile.step(grammarTimed, "ed:tokenize rows " + firstMissing + ".." + lastMissing
                + " (" + (lastMissing - firstMissing + 1) + " of " + lineCount + "), span "
                + (spanEnd - spanStart) + " chars -> " + grammarTokens.size() + " tokens");
        for (SyntaxToken token : grammarTokens) {
            distributeToRows(token, firstMissing, lastMissing, filling);
        }

        // SEMANTIC TOKENS LAND SECOND AND WIN. Both sources speak the same capture vocabulary and describe
        // the same spans; the difference is that the grammar guessed from shape and the engine knows. So a
        // parameter the grammar called `variable` becomes `variable.parameter`, and that is the entire
        // value of having an engine colour anything.
        //
        // Merged into the SAME per-row bucket rather than kept as a second layer, because the paint path
        // takes one list per row and the overlap rule has to be decided somewhere -- a second list would
        // push that decision into refreshHighlights, where two ranges under different names overlapping is
        // exactly the shape that crashed HighlightRegistry once already.
        // TWO PASSES, AND THE SPLIT IS THE RULE. The precedence is "the engine's answer beats the
        // grammar's", which is a statement about SOURCES -- so every grammar token overlapping any
        // semantic one is cleared first, and only then are the semantic tokens added. Doing it token by
        // token would make each semantic token displace the previous one, and they are deliberately
        // allowed to overlap each other: `count` being a field and `count` being deprecated are two
        // true things about one range, drawn as a colour and a strike-through by two different rules.
        long semanticTimed = FrameProfile.begin();
        List<SyntaxToken> semanticTokens = semantic.tokensIn(spanStart, spanEnd);
        FrameProfile.end(semanticTimed, "ed:semanticTokens");
        FrameProfile.step(semanticTimed, "ed:semanticTokens span " + (spanEnd - spanStart)
                + " chars -> " + semanticTokens.size() + " tokens");
        for (SyntaxToken token : semanticTokens) {
            clearGrammarUnder(token, firstMissing, lastMissing, filling);
        }
        for (SyntaxToken token : semanticTokens) {
            distributeToRows(token, firstMissing, lastMissing, filling);
        }
    }

    /**
     * Drops the grammar's cached tokens wherever this semantic token covers them.
     *
     * <p>Called for every semantic token <em>before</em> any is added, which is what lets semantic
     * tokens overlap each other. Leaving the grammar's instead would put two ranges under unrelated
     * names over one span and make which colour paints depend on the order the paint path happened to
     * walk the list in — the same class of bug as the capture-precedence one, and just as invisible,
     * since both names are legitimate and both resolve to a real colour.</p>
     */
    private void clearGrammarUnder(SyntaxToken token, int firstRow, int lastRow, Set<Integer> filling) {
        int startRow = buffer.document().offsetToPoint(clampToDocument(token.start())).row();
        int endRow = buffer.document().offsetToPoint(
                clampToDocument(Math.max(token.start(), token.end() - 1))).row();
        for (int row = Math.max(startRow, firstRow); row <= Math.min(endRow, lastRow); row++) {
            if (!filling.contains(row)) continue;
            List<SyntaxToken> bucket = rowSyntax.get(row);
            if (bucket == null) continue;
            int rowStart = buffer.document().lineStartOffset(row);
            int rowEnd = rowStart + buffer.line(row).length();
            final int from = Math.max(token.start(), rowStart) - rowStart;
            final int to = Math.min(token.end(), rowEnd) - rowStart;
            if (to <= from) continue;
            bucket.removeIf(existing -> replacedBySemantic(existing, from, to));
        }
    }

    /**
     * Whether a grammar token is displaced by a semantic one over {@code [from, to)}.
     *
     * <p>ONE NAMED RULE rather than the two halves spelled out at the call site, so a test asks the
     * same question the merge does. Written out inline, a test could only restate it — and a restated
     * rule agrees with itself forever while saying nothing about the editor.</p>
     */
    static boolean replacedBySemantic(SyntaxToken existing, int from, int to) {
        return overlaps(existing, from, to) && !contains(existing, from, to);
    }

    /** Whether {@code existing} shares any character with {@code [from, to)}. */
    private static boolean overlaps(SyntaxToken existing, int from, int to) {
        return from < existing.end() && existing.start() < to;
    }

    /**
     * Whether {@code existing} strictly CONTAINS {@code [from, to)} — and so is not a competing answer.
     *
     * <h3>Why a container survives a semantic token</h3>
     *
     * <p>"The engine's answer beats the grammar's" is a statement about two sources describing <b>the
     * same thing</b>: the grammar called {@code count} a {@code variable} from its shape, the engine
     * knows it is a {@code variable.parameter}. Both answer "what is this identifier", so the better
     * answer replaces the worse one.</p>
     *
     * <p>A token that contains the semantic one answers a different question — "what is this identifier
     * <em>inside</em>" — and both are true at once. {@code DocComments} emits a coarse
     * {@code comment.doc} over the WHOLE comment before the pieces within it, so once doc-tag references
     * began resolving, a single {@code {@link List}} cleared that container for its entire row: the
     * prose either side lost the comment's colour and its italic, while a line whose reference happened
     * not to resolve kept both. It was reported as the highlighting being inconsistent from one line to
     * the next, which is precisely how it looked.</p>
     *
     * <p>Keeping it is safe because the semantic tokens are added <b>after</b> and the last name written
     * wins the character — so the container styles the prose and the engine still styles the name it
     * resolved. STRICT, because a token with exactly the semantic one\u2019s bounds is describing the same
     * characters and nothing else, which makes it a competing answer rather than a context.</p>
     */
    private static boolean contains(SyntaxToken existing, int from, int to) {
        return existing.start() <= from && existing.end() >= to
                && (existing.start() < from || existing.end() > to);
    }

    /**
     * Files one document token under every row it covers, clipped and rebased to each.
     *
     * <p>A token is not a row: a block comment or a text block spans many, and the grammar reports it as
     * one. Storing it only under the row it starts on leaves every row after the first uncoloured, which
     * reads as the comment ending early rather than as a cache bug.</p>
     */
    private void distributeToRows(SyntaxToken token, int firstRow, int lastRow, Set<Integer> filling) {
        int startRow = buffer.document().offsetToPoint(clampToDocument(token.start())).row();
        int endRow = buffer.document().offsetToPoint(clampToDocument(Math.max(token.start(), token.end() - 1))).row();
        for (int row = Math.max(startRow, firstRow); row <= Math.min(endRow, lastRow); row++) {
            if (!filling.contains(row)) continue;
            List<SyntaxToken> bucket = rowSyntax.get(row);
            if (bucket == null) continue;
            int rowStart = buffer.document().lineStartOffset(row);
            int rowEnd = rowStart + buffer.line(row).length();
            int start = Math.max(token.start(), rowStart) - rowStart;
            int end = Math.min(token.end(), rowEnd) - rowStart;
            if (end > start) bucket.add(new SyntaxToken(start, end, token.name()));
        }
    }

    /**
     * Drops cached tokens for the rows an edit touched — and for everything below it when the edit
     * changed the line COUNT.
     *
     * <p>The same rule {@link #invalidateMeasuredRows} follows, for the same reason: the map is keyed by
     * row index, so inserting or removing a line renumbers every row below and their cached tokens now
     * describe someone else's text. Removing that guard breaks nothing that any existing test would
     * notice, and shows up as colour from one line appearing on another after a newline is typed.</p>
     */
    private void mapRowSyntaxThroughEdit(ChangeSet change) {
        editing = true;
        lastEditNanos = System.nanoTime();

        List<Change> changes = change.changes();
        // More than one change is a multi-cursor edit or a replace-all: cheap to reason about only by
        // starting over, and rare enough that a full re-tokenise on settle is the right trade.
        if (changes.size() != 1) {
            rowSyntax.clear();
            staleRows.clear();
            return;
        }
        Change edit = changes.get(0);
        int start = clampToDocument(change.mapPos(edit.from(), -1));
        int firstRow = buffer.document().offsetToPoint(start).row();
        int lineDelta = buffer.lineCount() - previousLineCount;
        if (lineDelta != 0) {
            renumberRowSyntax(firstRow, lineDelta);
            staleRows.add(firstRow);
            return;
        }
        // LOCAL TO ONE ROW, or there is nothing worth carrying forward. Mapping exists for typing; a
        // wholesale `buffer.load()` replaces every character while frequently leaving the line COUNT
        // alone, so the checks above let it through and every row kept the previous document's colours.
        int rowStart = buffer.document().lineStartOffset(firstRow);
        int rowLength = buffer.line(firstRow).length();
        int replaced = edit.to() - edit.from();
        int inserted = edit.insert().length();
        if (start + inserted > rowStart + rowLength || replaced > rowLength) {
            rowSyntax.clear();
            staleRows.clear();
            return;
        }
        shiftRowSyntax(firstRow, start - rowStart, replaced, inserted);
        staleRows.add(firstRow);
    }

    /**
     * Whether a row should keep the colours it has rather than take the ones a recovered parse offers.
     *
     * <p><b>Only a row the user has not touched, and only inside the recovery.</b> The line being written
     * takes whatever the parser says about it — that is the line whose colours are genuinely in question,
     * and it is the one that goes plain while it is unfinished. What it may not do is drag its neighbours
     * with it: an unfinished statement makes the parser recover, and the recovery re-classifies the rows
     * it swallows, so the line below the one you are writing changed colour and changed back when you
     * added the semicolon.</p>
     *
     * <p>Scoped through {@link SyntaxTokenizer#recoveredAround} rather than "does this file parse",
     * because the second is false for almost every file that is being edited and would hold the colours of
     * the whole document whenever anything anywhere was unfinished.</p>
     *
     * <p>A row with nothing cached has nothing to keep, so it is always queried — otherwise scrolling into
     * a region near an unfinished statement would show blank rows.</p>
     */
    private boolean keepsColoursThroughRecovery(int row) {
        List<SyntaxToken> existing = rowSyntax.get(row);
        if (existing == null || existing.isEmpty()) return false;
        int rowStart = buffer.document().lineStartOffset(row);
        return tokenizer.recoveredAround(rowStart, rowStart + buffer.line(row).length());
    }

    /**
     * Records that a backend has new answers for a range — what it reports when a background parse lands.
     *
     * <p><b>Recorded rather than applied.</b> A parse that finishes while the caret is mid-word is a
     * correct parse of incomplete text, and adopting it repaints every line the parser recovered
     * differently. The rows are marked and re-queried on settle. See {@link #settleSyntaxIfIdle}.</p>
     */
    private void invalidateRowSyntax(int fromOffset, int toOffset) {
        highlightsDirty = true;
        if (toOffset >= SyntaxTokenizer.InvalidationListener.EVERYTHING
                || fromOffset <= 0 && toOffset >= buffer.length()) {
            // OVER WHAT IS CACHED, not over every row in the document: a row nobody has looked at has
            // nothing to invalidate, and walking a 20,000-line file to say so costs a lineStartOffset per
            // row on an announcement that arrives every few keystrokes.
            if (editing) staleRows.addAll(rowSyntax.keySet());
            else rowSyntax.keySet().removeIf(row -> !keepsColoursThroughRecovery(row));
            return;
        }
        int firstRow = buffer.document().offsetToPoint(clampToDocument(fromOffset)).row();
        int lastRow = buffer.document().offsetToPoint(clampToDocument(toOffset)).row();
        for (int row = firstRow; row <= lastRow; row++) {
            if (editing) staleRows.add(row);
            else if (!keepsColoursThroughRecovery(row)) rowSyntax.remove(row);
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

    /**
     * Whether one indent level is spaces or a tab.
     *
     * <p>Separate from {@link #setIndentWidth} because a tab-indented file still needs a width — it is
     * where the stops are, and Backspace and Tab both ask. VS Code's pair is {@code insertSpaces} and
     * {@code tabSize} for exactly this reason.</p>
     */
    public TextEditor setInsertSpaces(boolean spaces) {
        this.insertSpaces = spaces;
        return this;
    }

    public boolean isInsertSpaces() {
        return insertSpaces;
    }

    /** The pair, as the cursor operations want it. */
    private TypeOperations.IndentStyle indentStyle() {
        return new TypeOperations.IndentStyle(insertSpaces, indentWidth);
    }

    /**
     * Numbers the gutter by distance from the caret, keeping the caret's own row absolute.
     *
     * <p>Vim's {@code number relativenumber} pair and VS Code's {@code lineNumbers: "relative"}. Off by
     * default: it is for people who type motions by count, and for everyone else it replaces a number
     * they can read with arithmetic they cannot.</p>
     */
    public TextEditor setRelativeLineNumbers(boolean relative) {
        if (this.relativeLineNumbers == relative) return this;
        this.relativeLineNumbers = relative;
        // EVERY NUMBER CHANGES WHEN THE CARET MOVES, so the gutter has to be redrawn on selection
        // changes and not only when the window scrolls -- which `afterSelectionChange` already does by
        // marking the tree dirty. Here it is the switch itself that moved.
        markTreeDirty();
        return this;
    }

    public boolean isRelativeLineNumbers() {
        return relativeLineNumbers;
    }

    /**
     * What the caret is drawn as.
     *
     * <p>The three every reference offers, and the only three that mean anything: a bar between two
     * characters, a block over one, an underline beneath one. VS Code's {@code editor.cursorStyle} adds
     * "thin" variants of the last two, which are the same shapes at a different width — and the width is
     * already {@code caret-width} in the sheet, so they would be a second way to say one thing.</p>
     */
    public enum CaretStyle { LINE, BLOCK, UNDERLINE }

    /** The caret's shape. Defaults to {@link CaretStyle#LINE}, which is what a text editor looks like. */
    public TextEditor setCaretStyle(CaretStyle style) {
        this.caretStyle = style == null ? CaretStyle.LINE : style;
        markTreeDirty();
        return this;
    }

    public CaretStyle getCaretStyle() {
        return caretStyle;
    }

    private CaretStyle caretStyle = CaretStyle.LINE;

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
        List<Integer> carets = new ArrayList<>(selections.count());
        int shift = 0;
        for (Selection selection : selections.all()) {
            TypeOperations.Enter enter = TypeOperations.enterAt(
                    buffer.document(), selection.start(), indentStyle(), language, indentation);
            changes.add(new Change(selection.start(), selection.end(), enter.text()));
            // EACH CARET IS SHIFTED BY THE EDITS BEFORE IT. `enterAt` answers against the document as it
            // stands, and the changes are applied together, so the second caret's offset has to carry the
            // first change's growth.
            carets.add(enter.caret() + shift);
            shift += enter.text().length() - (selection.end() - selection.start());
        }
        applyEdit(changes);

        // AND THE CARET IS NOT ALWAYS AT THE END OF WHAT WAS INSERTED, which is why this cannot leave the
        // placement to `mapThrough`: pressing Enter between a brace pair writes TWO lines and belongs on
        // the first of them. Mapping an insertion puts it after both.
        List<Selection> placed = new ArrayList<>(carets.size());
        for (int caret : carets) placed.add(Selection.caret(clampToDocument(caret)));
        selections.setAll(placed, selections.primaryIndex());
        afterSelectionChange();
        ensureCaretVisible();
    }

    /**
     * Tab with no selection — <b>to the next stop</b>, computed per caret.
     *
     * <p>Inserting {@code indentWidth} spaces regardless of where the caret stood is what this replaces:
     * from column six with a width of four it produced column ten, which is not a stop, so a block indented
     * by Tab drifted one character further out per press.</p>
     */
    private void insertTabAtCarets() {
        List<Change> changes = new ArrayList<>(selections.count());
        for (Selection selection : selections.all()) {
            changes.add(new Change(selection.start(), selection.end(),
                    TypeOperations.tabAt(buffer.document(), selection.start(), indentStyle())));
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
        buffer.edit(edit, selections.all());
        selections.mapThrough(edit);
        // THE GOAL COLUMN IS STALE ONCE THE TEXT MOVES, exactly as it is in `applyEdit`, which clears it.
        // Without this, Tab-indenting a line and then pressing Up aimed at the column the caret had
        // BEFORE the indent -- so the caret drifted left by one indent, once, and then behaved.
        clearGoalColumns();
        viewCursorsPart.restartBlink();
        ensureCaretVisible();
        onSelectionChanged.emit();
    }

    // ── Mouse selection ─────────────────────────────────────────────────────────────────────────

    private int[] unitAt(int offset, int clicks) {
        return MouseSelection.unitAt(buffer.document(), offset, clicks, wordClassifier);
    }

    private void extendDragTo(int offset) {
        if (columnAnchor >= 0) {
            applyColumnSelection(offset);
            return;
        }
        if (dragAnchor == null) {
            setSelection(getAnchor(), offset);
            return;
        }
        Selection extended = MouseSelection.extend(
                buffer.document(), dragAnchor, offset, dragGranularity, wordClassifier);

        // AN ALT-DRAG EXTENDS THE CARET IT ADDED AND LEAVES THE OTHERS ALONE.
        //
        // `setSelection` is documented as collapsing to a single selection, which is right for an
        // ordinary drag and destroys the entire point of an Alt+click: the press added a caret, the very
        // first pointer Move afterwards replaced every caret with one, and it took a movement of a single
        // pixel. Reported from the harness as multi-caret being "kind of broken", which is what it looks
        // like from the outside -- the carets appear and then silently do not survive the mouse.
        //
        // The primary is the one `add` just made -- `normalise` carries the index through its sort and
        // its merges for exactly this kind of reason.
        if (draggingAddedCaret && selections.isMultiple()) {
            List<Selection> all = new ArrayList<>(selections.all());
            int at = Math.max(0, Math.min(selections.primaryIndex(), all.size() - 1));
            all.set(at, extended);
            selections.setAll(all, at);
            afterSelectionChange();
            ensureCaretVisible();
            return;
        }
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
        maybeTriggerCompletion(typed);
    }

    /**
     * Opens a session when a trigger character is typed — §18.1.
     *
     * <p>After the insertion, not before: the request carries the caret, and a provider asked to complete
     * {@code foo} where the {@code .} has not landed yet resolves the expression as if there were no member
     * access at all. Every reference implementation triggers on the character having been typed.</p>
     *
     * <p>Only when no session is live. Typing {@code .} inside an open list is a commit character's job, and
     * re-opening would discard the list mid-keystroke.</p>
     */
    private void maybeTriggerCompletion(char typed) {
        if (languageServices == null) return;

        // A TRIGGER CHARACTER RESTARTS THE SESSION; it never defers to a live one.
        //
        // This deferred, on the reasoning that reopening would discard a list mid-keystroke. Backwards: a
        // dot ENDS the word the open list was about. Typing `f`, `b` opens an autopopup session for a word,
        // and the `.` then found that session live and returned -- so the member list never opened at all.
        // What stayed on screen was the dying word session, whose prefix had just become `fb.` and matched
        // nothing, which is why it looked like an empty member list and why Ctrl+Space -- which has no such
        // guard -- worked on the very same text.
        if (language.isCompletionTrigger(typed)) {
            if (EditorSuggest.TRACE) EditorSuggest.trace("typed '" + typed + "' -> TRIGGER CHARACTER");
            closeCompletion();
            openCompletion(CompletionProvider.TriggerKind.CHARACTER, String.valueOf(typed));
            return;
        }

        // The autopopup half still defers: every character of a word would otherwise restart the session and
        // throw away the list it is narrowing.
        if (suggest.isLive()) {
            if (EditorSuggest.TRACE) EditorSuggest.trace("typed '" + typed + "' -> deferred, a list is already live");
            return;
        }
        // TYPING A NAME OPENS THE LIST TOO -- IntelliJ's autopopup, and without it the only way in was
        // Ctrl+Space, which is a thing you have to remember rather than a thing that helps.
        //
        // It self-limits rather than needing a threshold: the session filters down as you type and closes
        // itself the moment nothing matches, so declaring a brand-new name costs one popup that vanishes
        // on the first character that makes the name unique. A minimum prefix length would be a number
        // chosen to feel right, and would delay exactly the case it was meant to serve.
        if (Character.isJavaIdentifierStart(typed)) {
            openCompletion(CompletionProvider.TriggerKind.EXPLICIT, null);
        }
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

        // FROM THE ONE MOST RECENTLY ADDED, which is the primary -- `SelectionModel.add` makes it so, and
        // `normalise` carries the index through its sort.
        //
        // Taking the last selection BY POSITION worked for as long as the matches ran down the document
        // and died the moment the search wrapped: the newest caret was then at the top and the last-by-
        // position one still at the bottom, so every further press resumed from the end, found the match
        // it had already taken, and refused. Multi-caret simply stopped responding, which is what was
        // reported.
        int at = nextUnselectedOccurrence(needle, primary.end(), isWholeWordAt(primary));
        if (at < 0) return false;
        selections.add(new Selection(at, at + needle.length()));
        afterSelectionChange();
        ensureCaretVisible();
        return true;
    }

    /**
     * The next occurrence of {@code needle} at or after {@code from} that no caret already holds, wrapping
     * once — or {@code -1} when every occurrence is taken.
     *
     * <p>Skipping rather than refusing is the second half of the same bug: a match that is already
     * selected is a reason to keep looking, not a reason to stop. Refusing left the next unselected one
     * unreachable whenever an earlier match happened to lie in the way.</p>
     */
    /**
     * Replaces the selection with the box between the column anchor and {@code offset}.
     *
     * <p>The last entry is made primary, because {@link ColumnSelection#between} puts the head's row
     * there — so the blinking caret stays on the row the pointer is over rather than jumping to whichever
     * row happens to sort first.</p>
     */
    private void applyColumnSelection(int offset) {
        if (columnAnchor < 0) return;
        List<Selection> box = ColumnSelection.between(
                buffer.document(), clampToDocument(columnAnchor), clampToDocument(offset), getTabSize());
        if (box.isEmpty()) return;
        selections.setAll(box, box.size() - 1);
        afterSelectionChange();
        ensureCaretVisible();
    }

    private int nextUnselectedOccurrence(String needle, int from, boolean wholeWords) {
        // THROUGH `TextSearch`, which is the one definition of what a match is -- the hand-rolled
        // `indexOf` walk here had to grow its own whole-word test the moment Ctrl+D needed one, which is
        // the second copy of a rule the search already owned. It also copied the whole document to do it.
        List<TextRange> all = TextSearch.findAll(buffer.document(),
                SearchQuery.of(needle, new SearchQuery.Options(true, wholeWords, false)));
        for (TextRange match : all) {
            if (match.start() >= from && !alreadySelected(match.start(), needle.length())) {
                return match.start();
            }
        }
        for (TextRange match : all) {
            if (match.start() < from && !alreadySelected(match.start(), needle.length())) {
                return match.start();
            }
        }
        return -1;
    }

    /**
     * Whether a selection covers <b>exactly</b> a word — which is what decides Ctrl+D's matching rule.
     *
     * <p>VS Code's {@code addSelectionToNextFindMatch}: a selection that <em>started</em> as a word keeps
     * looking for that word, so {@code count} does not go on to select the {@code count} inside
     * {@code counter}. A selection somebody dragged out by hand is a request about those characters and
     * matches them anywhere — the gesture says which question is being asked, and the difference only
     * shows up on the second press.</p>
     */
    private boolean isWholeWordAt(Selection selection) {
        if (selection.isEmpty()) return false;
        int[] word = WordOperations.wordAt(buffer.document(), selection.start(), wordClassifier);
        return word != null && word[0] == selection.start() && word[1] == selection.end();
    }

    private boolean alreadySelected(int start, int length) {
        for (Selection existing : selections.all()) {
            if (existing.start() == start && existing.end() == start + length) return true;
        }
        return false;
    }

    /** A caret at every occurrence of the selection — {@code Ctrl+Shift+L}. */
    public int selectAllOccurrences() {
        Selection primary = selections.primary();
        if (primary.isEmpty() && !addCaretAtNextOccurrence()) return 0;
        primary = selections.primary();
        String needle = buffer.document().slice(primary.start(), primary.end()).toString();
        if (needle.isEmpty()) return 0;

        List<Selection> found = new ArrayList<>();
        for (TextRange match : TextSearch.findAll(buffer.document(),
                SearchQuery.of(needle, new SearchQuery.Options(true, false, false)))) {
            found.add(new Selection(match.start(), match.end()));
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

    /**
     * What Cut and Copy act on: the selection, or <b>the whole of every line a caret touches</b>.
     *
     * <h3>An empty selection is not nothing to cut</h3>
     *
     * <p>Both references do this and it is one of the most-used things in either: {@code Ctrl+X} on a line
     * you have not selected takes the line. Ours required a selection to even ENABLE the command, so the
     * chord did nothing at all and read as the editor ignoring it.</p>
     *
     * <p>The trailing newline is part of what is taken, which is what makes the pair whole: cut a line,
     * put the caret elsewhere, paste, and you get a LINE back rather than its text welded into the middle
     * of another one. It is also why this cannot be built from {@code getSelectedText} plus a range — the
     * newline belongs to the operation, not to the text.</p>
     */
    public String selectionOrTouchedLines() {
        if (hasSelection()) return getSelectedText();
        StringBuilder out = new StringBuilder();
        for (int row : touchedRows()) {
            out.append(buffer.document().line(row)).append('\n');
        }
        return out.toString();
    }

    /** Deletes every line any caret touches — {@code Ctrl+Shift+K}. */
    public void deleteLines() {
        applyEdit(new ArrayList<>(LineOperations.delete(buffer.document(), touchedRows())));
    }

    /** Copies every touched line above or below itself — {@code Shift+Alt+Up/Down}, {@code Mod+D}. */
    public void duplicateLines(int direction) {
        List<Integer> rows = touchedRows();
        applyEditKeepingSelection(new ArrayList<>(
                LineOperations.duplicate(buffer.document(), rows, direction)));
        if (direction > 0) moveSelectionsDownRows(rows.size());
    }

    /**
     * Puts every caret on the COPY after a downward duplicate.
     *
     * <h3>The point of duplicating a line is to edit the new one</h3>
     *
     * <p>Both references land the caret on the copy, and leaving it on the original means every use of
     * the chord is followed by pressing Down -- which is the whole gesture again, by hand. Upward
     * duplication needs nothing: the copy goes ABOVE, so the original keeps its row and the caret is
     * already on the text that moved down into it.</p>
     *
     * <p>The COLUMN is kept rather than forced to the end. That is the same rule, and it lands at the end
     * of the copy when the end is where you were -- which is the common case and the one that reads as
     * "the caret follows the duplicate".</p>
     *
     * <p>Done by row rather than by offset because a duplicate inserts whole lines: shifting by a
     * character count would need the copied text's length, and the row count is what the operation
     * already knows.</p>
     */
    private void moveSelectionsDownRows(int rows) {
        if (rows <= 0) return;
        selections.transform(selection -> new Selection(
                shiftedDownRows(selection.anchor(), rows),
                shiftedDownRows(selection.head(), rows)));
        clearGoalColumns();
        ensureCaretVisible();
        onSelectionChanged.emit();
    }

    /** The same column, {@code rows} lines further down — clamped to the document and to that line. */
    private int shiftedDownRows(int offset, int rows) {
        TextPoint at = buffer.offsetToPoint(offset);
        int row = Math.min(buffer.lineCount() - 1, at.row() + rows);
        int column = Math.min(at.column(), buffer.document().line(row).length());
        return buffer.pointToOffset(new TextPoint(row, column));
    }

    /** Moves every touched line up or down one — {@code Alt+Up/Down}. */
    public void moveLines(int direction) {
        LineOperations.Move move = LineOperations.move(buffer.document(), touchedRows(), direction);
        if (move == null) return;

        if (readOnly || move.change().isEmpty()) return;
        List<Selection> moved = new ArrayList<>();
        for (Selection selection : selections.all()) {
            moved.add(new Selection(selection.anchor() + move.shift(), selection.head() + move.shift()));
        }
        // APPLIED WITHOUT LETTING THE EDIT PLACE THE CARETS. Every touched row moves by the same known
        // amount, so this already has the answer -- going through `applyEditKeepingSelection` mapped the
        // carets through the change, emitted `onSelectionChanged`, and then had that answer overwritten
        // and emitted again. Two emissions per Alt+Up, one of them describing selections nobody kept.
        ChangeSet edit = ChangeSet.of(buffer.length(), List.of(move.change()));
        buffer.edit(edit, selections.all());
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

    // ── Bracket matching ────────────────────────────────────────────────────────────────────────


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

    /**
     * <b>Every other place the word under the caret appears</b>, published as {@code ::highlight(occurrence)}.
     *
     * <h3>What it is for, and why it is not a search</h3>
     *
     * <p>Both references do this and it is the most visible thing an editor without it is missing: put the
     * caret in a name and every use of that name is marked, so "where else is this" is answered by
     * standing still rather than by typing it into a find box. IntelliJ calls it identifier highlighting,
     * VS Code occurrence highlighting.</p>
     *
     * <h3>Three refusals, and each of them is what keeps it quiet</h3>
     *
     * <ul>
     *   <li><b>Only from a bare caret inside a word.</b> With a selection the user has already said what
     *       they are interested in, and marking something else competes with it; between two words there
     *       is no question being asked.</li>
     *   <li><b>Whole words, matching case.</b> Without both, putting the caret in {@code i} marks every
     *       letter i in the file — which is not a highlight, it is noise with a colour.</li>
     *   <li><b>Nothing at all when there is only one.</b> A word that appears once is marked as its own
     *       only occurrence otherwise: a box drawn round the thing you are already looking at, on every
     *       caret move, saying nothing.</li>
     * </ul>
     *
     * <p>Recomputed on selection change rather than per frame, which is the same beat the bracket match
     * runs on. It is a whole-document scan, so it is bounded: past {@link #OCCURRENCE_SCAN_LIMIT}
     * characters nothing is marked at all, on the same reasoning {@link #BRACKET_SCAN_LIMIT} records —
     * a scan long enough to be felt on a keystroke is worse than the feature is good.</p>
     */
    private void updateOccurrences() {
        occurrences.clear();
        selectionOccurrences.clear();
        if (buffer.length() > OCCURRENCE_SCAN_LIMIT || selections.isMultiple()) return;

        if (selections.hasSelection()) {
            selectionOccurrences.addAll(selectedTextOccurrences());
            return;
        }

        int[] word = WordOperations.wordAt(buffer.document(), getCaret(), wordClassifier);
        if (word == null || word[1] <= word[0]) return;
        String needle = buffer.document().slice(word[0], word[1]).toString();
        if (needle.isEmpty() || !wordClassifier.isWordPart(needle.charAt(0))) return;
        occurrences.addAll(matchesOf(needle, true));
    }

    /**
     * <b>Selection highlight</b> — the other places the selected text appears.
     *
     * <h3>A different name from the caret's word, because it is a different statement</h3>
     *
     * <p>VS Code keeps {@code wordHighlight} and {@code selectionHighlight} apart and so does this: one
     * marks where the symbol you are standing in also lives, the other marks where the characters you
     * <em>chose</em> also appear. The second is by definition not whole-word — selecting {@code ell} is a
     * request about those three letters — which is exactly why it must not wear the first one's colour.</p>
     *
     * <h3>One line, and not a trivial one</h3>
     *
     * <p>A multi-line selection is a block of code being moved, not a string being looked for, and marking
     * every place a whole paragraph recurs answers a question nobody asked. Whitespace-only and
     * single-character selections are refused for the reason the word case refuses {@code i}: the marks
     * would outnumber the text.</p>
     */
    private List<TextRange> selectedTextOccurrences() {
        Selection primary = selections.primary();
        String selected = buffer.document().slice(primary.start(), primary.end()).toString();
        if (selected.length() < MINIMUM_SELECTION_HIGHLIGHT || selected.trim().isEmpty()) return List.of();
        if (selected.indexOf('\n') >= 0) return List.of();
        return matchesOf(selected, false);
    }

    /** Every occurrence of {@code needle}, or nothing when it is its own only one. */
    private List<TextRange> matchesOf(String needle, boolean wholeWords) {
        List<TextRange> found = TextSearch.findAll(buffer.document(),
                SearchQuery.of(needle, new SearchQuery.Options(true, wholeWords, false)));
        // ONE IS NONE -- see the note on updateOccurrences.
        return found.size() < 2 ? List.of() : found;
    }

    /** Occurrences of the word under the caret. Published under {@code ::highlight(occurrence)}. */
    private final List<TextRange> occurrences = new ArrayList<>();

    /** The same for the SELECTED text, under {@code ::highlight(selection-occurrence)}. */
    private final List<TextRange> selectionOccurrences = new ArrayList<>();

    /** Below this a selection highlight marks more than it says. One character is every character. */
    private static final int MINIMUM_SELECTION_HIGHLIGHT = 2;

    /**
     * Past this many characters the occurrence scan is skipped outright.
     *
     * <p>It runs on every caret move and reads the whole document, so on a large file it would be felt as
     * the arrow keys becoming sticky — which is a worse thing to have than the highlight is a good one.</p>
     */
    private static final int OCCURRENCE_SCAN_LIMIT = 2_000_000;

    private int matchAt(int offset) {
        if (offset < 0 || offset >= buffer.length()) return -1;
        return partnerOf(buffer.document().charAt(offset)) != 0 ? offset : -1;
    }

    /**
     * The other half of the pair {@code c} belongs to, or {@code 0}.
     *
     * <p>From the {@link Language}, which already knows the pairs — the matcher used to carry its own
     * {@code "([{"} and {@code ")]}"} beside it, which is two definitions of "a bracket" and the one that
     * cannot be taught a language whose blocks are spelled differently.</p>
     *
     * <p><b>A self-closing pair is refused.</b> A quote's opener and closer are the same character, so
     * there is nothing to count depth on: a matcher that believed {@code isCloser} about it would scan for
     * the "partner" of a quote and land on whichever quote came next in the file.</p>
     */
    private char partnerOf(char c) {
        if (language.isSelfClosing(c)) return 0;
        Character closer = language.structuralCloserFor(c);
        if (closer != null) return closer;
        Character opener = language.structuralOpenerFor(c);
        return opener != null ? opener : 0;
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
        char partner = partnerOf(bracket);
        if (partner == 0) return -1;
        boolean forward = language.structuralCloserFor(bracket) != null;
        // A BRACKET INSIDE A STRING OR A COMMENT IS NOT PUNCTUATION, and the anchor's own state is what
        // decides which candidates count. Without this, `(` in `"("` counted the next real `)` in the
        // file and drew a pair spanning code it had nothing to do with -- and the highlight looked
        // authoritative while doing it. Both references skip token types that are not brackets.
        //
        // Asked only of candidates, never of every character: `isInCommentOrString` tokenizes a window,
        // and a scan bounded at BRACKET_SCAN_LIMIT holds a few dozen brackets rather than thousands.
        boolean anchorQuoted = isInCommentOrString(offset);
        int step = forward ? 1 : -1;
        int depth = 0;
        int limit = Math.min(BRACKET_SCAN_LIMIT, buffer.length());
        for (int i = 0, at = offset; i < limit; i++, at += step) {
            if (at < 0 || at >= buffer.length()) return -1;
            char c = buffer.document().charAt(at);
            if (c != bracket && c != partner) continue;
            if (isInCommentOrString(at) != anchorQuoted) continue;
            if (c == bracket) depth++;
            else if (--depth == 0) return at;
        }
        return -1;
    }

    private static final int BRACKET_SCAN_LIMIT = 16 * 1024;

    // ── Find and replace ────────────────────────────────────────────────────────────────────────
    //
    // The subsystem itself is EditorFind; what is left here is the public surface the commands, the bar
    // and the tests call. Delegating rather than re-exposing the object keeps `TextEditor.findNext()`
    // working for every existing caller -- the extraction is a code move, and the 226 widget tests are
    // the net under it.

    private final EditorFind find = new EditorFind(this);

    /** Find, replace and the bar. */
    EditorFind finder() {
        return find;
    }

    /**
     * Finds every occurrence and publishes them under {@code ::highlight(search)}.
     *
     * @return how many matches there are
     * @see EditorFind#find(SearchQuery)
     */
    public int find(String query, boolean caseSensitive) {
        return find.find(query, caseSensitive);
    }

    /**
     * Finds every match of {@code query}, honouring its Match Case / Words / Regex options.
     *
     * <p><b>Every</b> match, which is why this cannot go through {@link SearchMatcher#match} — that answers
     * "the best match in this candidate", which is the right question for a list row and the wrong one for
     * a document. The word-boundary rule is still shared, via {@link SearchMatcher#isWholeWordAt}: a second
     * definition of "a word" would be a second answer to the same toggle.</p>
     *
     * @return how many matches there are
     */
    public int find(@Nullable SearchQuery query) {
        return find.find(query);
    }

    /** The occurrences, the cursor and the exclusions. @see SearchResults */
    public SearchResults searchResults() {
        return find.results();
    }

    /** Excludes the selected match from Replace All, or puts it back — IntelliJ's <b>Exclude</b>. */
    public boolean toggleExcludeCurrentMatch() {
        return find.toggleExcludeCurrentMatch();
    }

    /** The find &amp; replace bar, built on first use and floated over the editor's top edge. */
    public SearchReplaceBar searchBar() {
        return find.bar();
    }

    /** Opens the find bar — Ctrl+F. */
    public void openFind() {
        searchBar().open();
    }

    /** Opens it with the replace row expanded — Ctrl+R. */
    public void openReplace() {
        searchBar().openReplace();
    }

    /** Whether a replacement should take the case of what it replaced. @see TextSearch#preserveCase */
    public TextEditor setPreserveCase(boolean value) {
        find.setPreserveCase(value);
        return this;
    }

    public boolean preserveCase() {
        return find.preserveCase();
    }

    /** Searches for the word under the caret — {@code Ctrl+F3}. */
    public boolean findWordUnderCaret() {
        return find.findWordUnderCaret();
    }

    public int matchCount() {
        return find.matchCount();
    }

    /** Which match is selected, 1-based for display, or 0 when none is. */
    public int currentMatchNumber() {
        return find.currentMatchNumber();
    }

    /** Selects the next match after the caret, wrapping. */
    public boolean findNext() {
        return find.findNext();
    }

    /** Selects the previous match before the caret, wrapping. */
    public boolean findPrevious() {
        return find.findPrevious();
    }

    /**
     * The offset of the <b>first line on screen</b> — where a fresh query starts looking.
     *
     * <p>Scrolling is view state and deliberately never moves the caret, so the two drift apart the moment
     * you read rather than edit. That is what made {@link #findNext()} the wrong operation to run on a
     * newly typed query: it anchors on the caret, which after a wheel-scroll is still wherever you last
     * clicked — usually the top of the file — so typing a query scrolled the document back there. Nothing
     * about it was intermittent except whether you had clicked first.</p>
     *
     * <p>It stays on the editor rather than moving to {@code EditorFind} with the rest: it names no find
     * state at all, and the next viewport-anchored feature would otherwise reach into the find subsystem
     * to ask a question about the <em>scroll</em>.</p>
     */
    public int firstVisibleOffset() {
        // Through rowAtTopOfViewport, which is the same question zoom already asks. Written out again here
        // it would be a second definition of "which row is at the top", and the two would answer
        // differently the first time either learned something about wrapping or folding.
        return buffer.document().lineStartOffset(rowAtTopOfViewport());
    }

    /**
     * Selects the first match at or after {@code offset}, wrapping — what a <b>fresh</b> query does.
     *
     * @see EditorFind#findFrom(int)
     */
    public boolean findFrom(int offset) {
        return find.findFrom(offset);
    }

    /** Replaces the selected match and finds the next. */
    public boolean replaceCurrent(String replacement) {
        return find.replaceCurrent(replacement);
    }

    /**
     * Replaces every match as <b>one</b> edit.
     *
     * @return how many were replaced
     * @see EditorFind#replaceAll(String)
     */
    public int replaceAll(String replacement) {
        return find.replaceAll(replacement);
    }

    /**
     * Clips document-relative ranges to one line and rebases them onto it.
     *
     * <p><b>Through {@link #addRange}, which is what the syntax path already does.</b> This added
     * directly, so five names reached {@code HighlightRegistry.set} with no overlap guard at all —
     * {@code occurrence}, {@code selection-occurrence}, {@code search}, {@code search-excluded} and
     * {@code bracket} — and a highlight refuses overlapping ranges with a hard failure, thrown from
     * {@code tickFrame} where nothing above it in the trace names the producer.</p>
     *
     * <p>Overlap here is ordinary rather than exotic: a search can match at two positions one character
     * apart, and an occurrence list computed against one revision and rebased onto another can hold two
     * spans of the same word that no longer sit where they did. Dropping is the same resolution and the
     * same reasoning {@code addRange} already documents — the ranges carry ONE name, so they resolve to
     * one colour, and painting the union or the first is indistinguishable to a reader.</p>
     */
    static void addDocumentRanges(Map<String, List<TextRange>> byName, String name,
                                          List<TextRange> ranges, int lineStart, int lineEnd) {
        for (TextRange range : ranges) {
            int start = Math.max(range.start(), lineStart);
            int end = Math.min(range.end(), lineEnd);
            if (end <= start) continue;
            SyntaxHighlighting.addRange(byName, name, TextRange.of(start - lineStart, end - lineStart));
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
        return gutterChevronWidth() + gutterNumberWidth() + gutterFoldWidth();
    }

    /**
     * Room inside the gutter for the revert chevrons, or zero when nothing is offering any.
     *
     * <p>Reserved rather than overlaid. A chevron drawn on top of the code is legible only where the line
     * happens to be short, and it covers the one thing the reader is comparing; reserving the column costs
     * a character's width on an editor that has chevrons and nothing at all on one that does not.</p>
     *
     * <p>Derived from the line height rather than being a constant, because the mark is an icon drawn
     * into a square box: tying it to the row keeps it proportionate at every zoom level, which a pixel
     * value would not. Three quarters of a row, so the column is narrow enough that the numbers stay close
     * to the code rather than being pushed away by a control that is only sometimes there.</p>
     */
    float gutterChevronWidth() {
        return diffRevertHandler == null ? 0f : lineHeight() * 0.75f;
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
        // THE GUTTER GETS THE EDITOR'S FONT SIZE, and that is what makes the three `em` metrics below
        // mean anything. font-size does NOT effectively inherit here -- ua/core.css opens with
        // `* { font-size: 10 }`, which is a candidate on every element, and inheritance only applies
        // where there is no candidate at any origin. So without this push the gutter's `em`s would
        // resolve against 10 at every zoom level while the digits beside them grew, which is precisely
        // the drift the old baseline-ratio hack existed to undo.
        pushEditorFontTo(gutter);
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
     *
     * <h3>The zoom scaling is gone, and the sheet does it now</h3>
     *
     * <p>This used to multiply by {@code fontSize / gutterMetricBaselineFontSize}, where the baseline was
     * the font size the FIRST call ever saw. It existed for a real defect — {@code lineHeight} and the
     * digits' shaped width both grow with zoom while a bare CSS length does not, so the gutter's
     * proportions visibly came apart from the code — and it was an {@code em} with no name, no way for a
     * sheet to opt out of, and a reference value that depended on when the editor happened to be created.
     * The three metrics are authored in {@code em} now (see {@code ua/editor.css}) and the cascade
     * resolves them per element, so this is a plain read again.</p>
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

    /**
     * Gives {@code element} the font the editor <b>measures</b> with — the one seam for it.
     *
     * <p>Four places wrote this pair out: the line renderer, the line numbers, the whitespace markers and
     * the fold decorations. It is cheap, because {@code replaceOrPutCandidate} no-ops on an unchanged
     * value — but a font disagreement between a decoration and the text it sits on is a <b>scale error
     * that grows across the row</b>, so four independent statements of "the editor's font" is four places
     * for one of them to be edited alone.</p>
     *
     * <p>At {@code IMPORTANT}, because the sheet's own rule for these classes would otherwise win and the
     * decoration would size itself independently of the text it is describing.</p>
     *
     * <h3>"It is cheap" was measured and is not true</h3>
     *
     * <p>Five callers reach this per element per frame — every realised line, every line number, every
     * whitespace marker, every fold glyph — so an editor viewport is ~100 calls a frame. Measured on the
     * frame that opens a class: <b>{@code ed:fonts} 13.3ms and {@code ln:pushFont} 4.5ms</b>, and on an
     * earlier build {@code ln:pushFont} alone reached 30ms. {@code replaceOrPutCandidate} does no-op on an
     * unchanged value, but reaching it costs a pipeline, two {@code StyleSlot}s and two candidate-list
     * walks — and on the one frame that matters the value has genuinely changed (the gutter pushes a new
     * size), so every write resolves, fires the {@code FONT_SIZE} listener and drops each label's shaped
     * paragraph.</p>
     *
     * <p>So the guard is here rather than in each caller: the value is the same for every element, so
     * "has this element already got the current font" is one membership test. The set is cleared when
     * the editor's own font moves, which is the only thing that can invalidate it, and it is weakly held
     * so a pooled line that goes away does not pin it.</p>
     */
    void pushEditorFontTo(UIElement element) {
        var general = getStyle().getGeneralGroup();
        float size = general.fontSize();
        List<String> family = general.fontFamily();
        if (size != pushedFontSize || !family.equals(pushedFontFamily)) {
            pushedFontSize = size;
            pushedFontFamily = family;
            fontUpToDate.clear();
        }
        // add() answers false when it was already there -- one hash lookup instead of two style writes.
        if (!fontUpToDate.add(element)) return;
        StyleGroup.importantPipeline(element.getStyle().getGeneralGroup(),
                g -> g.fontSize(size).fontFamily(family));
    }

    /** Elements already carrying {@link #pushedFontSize}/{@link #pushedFontFamily}. @see #pushEditorFontTo */
    private final Set<UIElement> fontUpToDate =
            Collections.newSetFromMap(new WeakHashMap<UIElement, Boolean>());

    private float pushedFontSize = Float.NaN;

    private List<String> pushedFontFamily;

    /**
     * Starts the horizontal scrollbar after the gutter rather than under it.
     *
     * <p>The gutter is pinned and does not scroll horizontally, so a bar running beneath it offers to
     * scroll something that will not move.</p>
     *
     * <p>Written at {@code IMPORTANT} origin because {@code ScrollerView} rewrites the bar's geometry
     * every frame from {@code refreshScrollers}; a lower-origin write would simply lose to it.</p>
     *
     * <p><b>The editor's own layout, and it used to live in {@code LineNumbersPart}.</b> A view part places
     * its own decorations; the scrollbar is neither its decoration nor its business, and finding this
     * inside the line-number renderer is exactly the surprise the review named. The editor already owns
     * {@code setTopChromeInset} for the vertical bar.</p>
     */
    void insetHorizontalBarPastGutter() {
        if (!gutterVisible) return;
        UIElement bar = horizontalScrollerElement();
        if (bar == null) return;
        final float left = paddingLeft() + gutterWidth();
        final float width = Math.max(0f, getClientWidth() - left - verticalBarThickness());
        StyleGroup.importantPipeline(bar.getStyle().getLayoutGroup(), l -> l.left(left).width(width));
    }

    float textOriginY() {
        return getTaffyLayout().padding().top;
    }

    /**
     * <b>Where a view line's top edge is drawn</b>, in this element's own space — the one statement of it.
     *
     * <h3>Eleven copies, and only one of them guarded the scroll offset</h3>
     *
     * <p>{@code textOriginY() + viewLine * lineHeight() - getScrollTop()} was written out in eight view
     * parts and three more places here. That is a formula every part has to agree on exactly, and the
     * disagreement was already there: {@code getScrollTop()} <b>can be NaN</b> — and NaN minus anything is
     * NaN, so every row lands at the same y and the whole editor draws as one stacked line. One call site
     * had a {@code finiteOrZero} around it. The other ten did not, and could not have been fixed without
     * finding them all.</p>
     *
     * <p>So the guard lives here, once, and the parts ask rather than compute. <b>The source is fixed</b> —
     * it was a NaN {@code line-height} multiplier getting past two guards that both looked protective, see
     * {@link #lineHeight()} — so this is defence in depth rather than the repair. It stays because the
     * formula having one home is worth it on its own, and because a scroll offset arriving non-finite from
     * somewhere new should degrade to zero rather than flatten the document.</p>
     */
    float topOfViewLine(int viewLine) {
        return textOriginY() + viewLine * lineHeight();
    }

    /**
     * <b>Where a view line's top edge is on SCREEN</b> — the same row, in the space that has been
     * scrolled.
     *
     * <p>The distinction is the whole of the scroll-layer design and it is not a detail: a decoration
     * inside {@link #linesLayer()} is positioned in <em>document</em> coordinates and moved by the
     * layer's transform, so it must use {@link #topOfViewLine}; anything measured against the editor's
     * own box — a popup anchor, a band that spans the viewport, a marker parented outside the layers —
     * lives in <em>viewport</em> coordinates and must use this. Getting it backwards is silent while
     * the document is scrolled to the top, which is exactly the state every test and every screenshot
     * starts in.</p>
     *
     * <p>{@code finiteOrZero} for the reason {@link #lineHeight()} records: a NaN offset minus anything
     * is NaN, and every row then lands at the same y with nothing having thrown.</p>
     */
    float screenTopOfViewLine(int viewLine) {
        return topOfViewLine(viewLine) - finiteOrZero(getScrollTop());
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
        // EVERY ROW THE CHANGE SPANS, not merely the one it starts on.
        //
        // A single change is not a single row. `setText` replaces the whole document as one change, and
        // a replacement with the same number of lines therefore reached here and dropped only row 0 --
        // leaving every row below holding a RowMetrics whose DISPLAY TEXT was the old document's. The
        // editor painted a new first line above a stale remainder, and only a later edit that happened to
        // alter the line count cleared it.
        Change edit = changes.get(0);
        int start = clampToDocument(change.mapPos(edit.from(), -1));
        int end = clampToDocument(start + edit.insert().length());
        int firstRow = buffer.document().offsetToPoint(start).row();
        int lastRow = buffer.document().offsetToPoint(end).row();
        for (int row = firstRow; row <= lastRow; row++) measuredRows.remove(row);
    }

    private int clampToDocument(int offset) {
        return Math.max(0, Math.min(offset, buffer.length()));
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
    record StableViewport(int offset, float delta) {
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
    StableViewport captureFoldAnchor() {
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
    void restoreStableViewport(StableViewport anchor) {
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

    /**
     * <b>Where everything that scrolls with the text lives.</b>
     *
     * <h3>Why a container at all</h3>
     *
     * <p>{@link UIElement#applyScrollOffset} says it plainly: <em>"Position only — no relayout. The
     * offset never reaches Taffy; it lives purely in the transform chain."</em> A scroll container moves
     * its children by one matrix, and that is why scrolling a list costs nothing. The text viewport
     * opts out of that — it is {@code setScrollExempt(true)} because it has to be a <em>window</em>
     * that holds still while content moves behind it — and its own javadoc names the price: "its
     * children no longer get the scroll translate for free and subtract the offsets by hand".</p>
     *
     * <p>Subtracting by hand means every realised row, every indent guide, every whitespace marker,
     * every selection band, squiggle and caret has its {@code left} and {@code top} rewritten into the
     * CASCADE on every frame the view moves — and cascade writes reach Taffy, so a layout pass follows.
     * Measured side by side in one window with no GL: a scrolled frame cost <b>1,628µs</b> for the
     * editor against <b>367µs</b> for an ordinary scroller, and the gap is work the scroller simply
     * does not do.</p>
     *
     * <p>So the exemption stays on the viewport, which is what needs it, and a layer inside it takes
     * the translate back. Children are positioned in <b>document coordinates</b> that do not change
     * when the view moves; the layer carries one {@link UITransform}, which is layout-free by
     * construction — Taffy never sees it. A scroll frame writes one matrix instead of several hundred
     * style values and runs no layout at all. This is Monaco's {@code linesContent}.</p>
     *
     * <h3>Three of them, because there are three coordinate spaces</h3>
     *
     * <p>Not every decoration follows both axes, and the two that do not are documented as such where
     * they are drawn. The current-line band "spans the viewport and does NOT move with horizontal
     * scroll", and a ruler marks a column at a fixed screen height. So:</p>
     *
     * <table>
     *   <tr><th>space</th><th>carried by</th><th>holds</th></tr>
     *   <tr><td>document x, document y</td><td>{@code linesLayer}</td>
     *       <td>lines, selections, indent guides, whitespace, squiggles, carets</td></tr>
     *   <tr><td>gutter x, document y</td><td>{@link #gutterLayer()}, {@link #foldLayer()}</td>
     *       <td>line numbers, fold arrows, the quick-fix bulb</td></tr>
     *   <tr><td>viewport x, viewport y</td><td>nothing — the editor's own box</td>
     *       <td>current-line bands, rulers, the collapsed-region marker</td></tr>
     * </table>
     *
     * <p>The third group is the one to be careful with: it uses {@link #screenTopOfViewLine} while
     * everything in a layer uses {@link #topOfViewLine}.</p>
     *
     * <h3>Stacking is preserved, and that is not automatic</h3>
     *
     * <p>z-index only orders siblings, so splitting one parent into layers could have reordered the
     * whole editor. It does not, because the three spaces do not interleave: the sheet puts
     * {@code __current-line__} at -2, {@code __selection__} and {@code __indent-guide__} at -1,
     * {@code __line__} and {@code __whitespace__} at 0, {@code __caret__} at 1 and {@code __ruler__} at
     * 4 — so everything moving into a layer occupies -1..1 with the viewport-space decorations strictly
     * below and strictly above. The layer sits at 0 between them and the inner order is untouched.</p>
     */
    UIElement linesLayer() {
        if (linesLayer == null) linesLayer = scrollLayer(textViewport());
        return linesLayer;
    }

    /** The gutter's scroll layer — its numbers follow the rows. @see #linesLayer() */
    UIElement gutterLayer() {
        if (gutterLayer == null) gutterLayer = scrollLayer(gutter);
        return gutterLayer;
    }

    /** The fold column's scroll layer — its arrows follow the rows. @see #linesLayer() */
    UIElement foldLayer() {
        if (foldLayer == null) foldLayer = scrollLayer(foldColumn());
        return foldLayer;
    }

    /**
     * One scroll layer, over a host that is scroll-exempt.
     *
     * <p><b>Hit-testing is left alone, and that is not an oversight.</b> {@code setHitTest(false)}
     * applies to the whole SUBTREE, like CSS {@code pointer-events: none} — so switching it off here to
     * mirror the viewport would make every fold arrow unclickable, and the fold column exists precisely
     * because {@code gutter.setHitTest(false)} already swallowed them once. A layer inherits whatever
     * its host decided: the text viewport is already untestable, the gutter already is, and the fold
     * column deliberately is not.</p>
     */
    private UIElement scrollLayer(UIElement host) {
        UIElement layer = new UIElement();
        layer.addClass(SCROLL_LAYER_CLASS);
        layer.markAsInternal();
        host.addInternalChild(layer);
        return layer;
    }

    /**
     * Moves the scroll layers, once a frame.
     *
     * <p>The whole cost of scrolling, in three writes. {@code replaceOrPutCandidate} no-ops on an
     * unchanged value and {@link UITransform} compares by value, so a frame that did not scroll writes
     * nothing at all — and a frame that did writes one matrix per layer rather than two style values
     * per decoration.</p>
     *
     * <p>Only what already exists: a layer is built on first use by whichever part needs it, and an
     * editor with no gutter never makes one.</p>
     */
    private void syncScrollLayers() {
        final float x = -finiteOrZero(getScrollLeft());
        final float y = -finiteOrZero(getScrollTop());
        if (linesLayer != null) linesLayer.setTransform(UITransform.translate(x, y));
        if (gutterLayer != null) gutterLayer.setTransform(UITransform.translate(0f, y));
        if (foldLayer != null) foldLayer.setTransform(UITransform.translate(0f, y));
    }

    /** Width of the code area — the client box, less the gutter and whatever the vertical bar covers. */
    float textViewportWidth() {
        // MIRRORED, the gutter is at the far end rather than behind the origin, so its width comes off
        // the far side instead of having already been skipped by textViewportLeft. Forgetting it lets the
        // text run underneath the numbers, which reads as the gutter being transparent.
        // Mirrored, the bar is already behind textViewportLeft and the gutter is at the far end;
        // unmirrored it is the other way round. Subtracting both in either case takes a bar's width
        // out twice and leaves a strip of dead space down one side.
        float gutterInset = gutterOnRight ? gutterWidth : 0f;
        float barInset = gutterOnRight ? 0f : verticalBarThickness();
        return Math.max(0f, getClientWidth() - textViewportLeft() - barInset - gutterInset);
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
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(left).top(0f).width(width).height(height));
        layOutMirroredGutter(height);
    }

    /**
     * Pins the gutter to the right-hand edge while {@link #setGutterOnRight} is on.
     *
     * <p><b>Here rather than in the sheet, and that was learned the hard way.</b> It was written as
     * {@code position: absolute; right: 0} in {@code editor.css} and it did nothing at all — the numbers
     * stayed where flow had put them while {@link #textOriginX} had already stopped reserving room for
     * them, so the gutter painted over the first several characters of every line and the chevron sat
     * alone at the far edge. Every other box in this widget is placed from its own measurements; the
     * gutter was the one getting away with being left where flow put it.</p>
     *
     * <p>Nothing is written while the gutter is on the left, so an ordinary editor keeps taking its
     * geometry from the sheet exactly as before.</p>
     */
    private void layOutMirroredGutter(float height) {
        if (!gutterOnRight) return;
        final float left = gutterLeft();
        final float width = gutterWidth;
        // IMPORTANT ORIGIN, and it has to be. A DEFAULT-origin write sits BELOW the user-agent sheet, and
        // ua/editor.css styles `.__gutter__` -- so the box was told left=562 and laid out at 0, silently.
        // Measured before it was believed: the arithmetic was correct the whole time and the cascade was
        // throwing it away. Same reason UIText pushes its measured height back at IMPORTANT.
        StyleGroup.importantPipeline(gutter.getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .left(left).top(0f).width(width).height(height));

        // THE VERTICAL BAR MIRRORS WITH IT, to the outer edge. Left on the right it would sit between
        // the two panes' gutters -- in the one place a side-by-side view has no room, and on the side
        // the reader is comparing across. The outer edge is where the other pane's bar already is, so
        // the pair reads as a frame around the comparison rather than as a divider through it.
        StyleGroup.importantPipeline(verticalScroller().getStyle().getLayoutGroup(),
                l -> l.positionType(TaffyPosition.ABSOLUTE).left(0f).rightAuto());
    }


    /**
     * Starts the vertical scrollbar below whatever chrome is floating at the editor's top edge.
     *
     * <p>The find bar already displaces the <em>text</em>, by writing this element's {@code padding-top} —
     * see {@code SearchReplaceBar.syncEditorInset}. The scrollbar does not follow, because it is pinned to
     * the padding box with {@code top: 0} and padding is exactly what it is pinned <em>inside</em>. So the
     * bar kept covering the top of the bar you would drag to reach the first line — the one place the
     * scrollbar is most likely to be grabbed while a search is open.</p>
     *
     * <p>IMPORTANT origin, and package-private rather than public, for the same two reasons
     * {@code ScrollerView.reserveCorner} gives when it writes the opposite edges: whether the strip exists
     * is <b>runtime state</b> a stylesheet cannot know, and only the bar itself knows how tall it is this
     * frame. The two writes do not collide — that one owns {@code bottom} and {@code right}, this owns
     * {@code top}.</p>
     */
    void setTopChromeInset(float inset) {
        StyleGroup.importantPipeline(verticalScroller().getStyle().getLayoutGroup(), l -> l.top(inset));
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
        var general = getStyle().getGeneralGroup();
        String fontKey = general.fontFamily() + "/" + general.fontSize();
        // CACHED, for the reason `gutterDigitsWidth` records for the digit: two view parts ask this
        // EVERY FRAME -- the indent guides and the rulers, once each -- and every ask shaped a space. A
        // value that only moves when the font does, re-derived sixty times a second. Keyed on the same
        // font key `rowMetrics` uses, so the caches invalidate together.
        if (spaceWidth < 0f || !fontKey.equals(spaceWidthFontKey)) {
            CgFontFamily family = resolveFamily();
            if (family == null) return 0f;
            float[] widths = caretOffsets(" ", family);
            spaceWidth = widths.length > 1 ? widths[1] : 0f;
            spaceWidthFontKey = fontKey;
        }
        return spaceWidth;
    }

    private float spaceWidth = -1f;
    private String spaceWidthFontKey = "";

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
            long whole = FrameProfile.begin();
            projections.rebuild(buffer.document());
            // THE WHOLE-DOCUMENT PATH, named apart from the incremental one. Which of the two ran is the
            // first question about any reprojection cost, and both used to report as one number.
            FrameProfile.step(whole, "ed:projections.rebuild (WHOLE DOC, " + lineCount + " lines, "
                    + changes.size() + " changes)");
            return;
        }
        Change edit = changes.get(0);
        int at = Math.max(0, Math.min(change.mapPos(edit.from(), -1), buffer.length()));
        int row = buffer.document().offsetToPoint(at).row();

        // THE ROWS COME FROM THE EDIT, not from the line-count delta.
        //
        // Deriving them from the delta alone assumes the change is LOCAL -- true of every keystroke, and
        // false of a single change that replaces the whole document. `TextBuffer.load` is exactly that:
        // one Change spanning everything. Filtering the Run console from 478 rows to 427 gave delta -51,
        // which the old arithmetic read as "52 rows at row 0 became 1 row" -- so every row from 52 down
        // kept its OLD projection, and `viewLineEndOffset` answered for text that was no longer there.
        //
        // That is silent: the rows paint correctly, because the text comes from elsewhere. What breaks is
        // anything CLIPPED to a view line's end -- refreshHighlights clamps every range to it, so the Run
        // console's stack-frame links came out truncated by however far each stale end happened to fall
        // short. `RunTest` for `RunTest.java:61`, `Threa` for `Thread.java:1583`, and one frame perfect
        // because its stale end overshot instead.
        //
        // Counted from the inserted text, which is exact in every case: one row for a plain keystroke,
        // two for a newline, and the whole new document for a replace. The removed count then follows,
        // since delta is by definition added minus removed.
        int added = 1;
        String insert = edit.insert();
        for (int i = 0; i < insert.length(); i++) {
            if (insert.charAt(i) == '\n') added++;
        }
        int removed = added - delta;
        if (removed < 1) {
            // Not a shape rowsChanged can express. Rebuilding is always correct, only slower.
            long whole = FrameProfile.begin();
            projections.rebuild(buffer.document());
            FrameProfile.step(whole, "ed:projections.rebuild (WHOLE DOC, unexpressible shape, "
                    + lineCount + " lines)");
            return;
        }
        long some = FrameProfile.begin();
        projections.rowsChanged(buffer.document(), row, removed, added);
        // A FULL LOAD LANDS HERE, not on the rebuild branch: TextBuffer.load is ONE change that replaces
        // everything, so `added` is the whole new document and this is a whole-document reprojection
        // wearing the incremental path's name. Worth reporting the row count, or a 2,208-row reprojection
        // reads as an ordinary keystroke.
        FrameProfile.step(some, "ed:projections.rowsChanged at " + row
                + " (-" + removed + " +" + added + " of " + lineCount + ")");
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

    void ensureCaretVisible() {
        float height = lineHeight();
        float top = viewLineOf(getCaret(), LineProjection.Affinity.LEFT) * height;
        // IMMEDIATE, not the smooth scroll the sheet asks for. `scroll-behavior: smooth` is right for a
        // wheel or a scrollIntoView, and wrong for following a caret: the caret has already moved, so an
        // eased scroll means it is off screen for the length of the animation and every keystroke chases
        // a viewport that is still catching up with the last one.
        float viewport = viewportHeight();
        // IN THE COORDINATES A LINE IS ACTUALLY DRAWN IN, which offsetAtLocal is the definition of: a line
        // at content offset `top` appears at `top + textOriginY() - scrollTop`. This compared `top`
        // against `scrollTop` directly, i.e. it assumed the text starts at the top of the scrollport --
        // true only while the top padding is zero.
        //
        // The find bar makes it 26px (it insets the editor by its own height), so every comparison here
        // was out by nearly two lines: stepping to a match computed `2814 > 2814`, concluded the caret
        // was already visible, and left it one row below the last one on screen. That is the whole of
        // "sometimes puts them one line before/after the visible lines".
        //
        // ASYMMETRIC, and deliberately. The far edge adds the origin because the line has to fit above
        // the bottom of the box. The near edge does not, because scrolling a line to `top` puts it at the
        // FIRST ROW OF TEXT rather than at the top of the scrollport -- the padding strip above it is
        // where the bar sits, and a line revealed into it is covered rather than shown. Both edges then
        // mean the same thing: inside the band the text is meant to occupy.
        float origin = textOriginY();
        if (!caretIsInView()) {
            if (top < getScrollTop()) setScrollImmediate(getScrollLeft(), top);
            else setScrollImmediate(getScrollLeft(), top + height + origin - viewport);
        }
        revealCaretHorizontally();
        // NOT invalidateWindow(). Scrolling changes which rows are on screen, and updateWindow already
        // recomputes that range every frame -- realising what has come into view and recycling what has
        // left. Tearing the whole window down here recycled every line on every keystroke, which clears
        // their highlights and is the other half of the colour flicker.
        markTreeDirty();
    }

    /**
     * Scrolls sideways so the caret is inside the text viewport — <b>the other axis</b>.
     *
     * <h3>Half a reveal is not a reveal</h3>
     *
     * <p>This scrolled vertically only, so pressing End on a line wider than the viewport put the caret
     * somewhere off to the right and left it there: the row was on screen, the caret was not, and the
     * next character typed appeared somewhere nobody was looking. Both references reveal on both axes.</p>
     *
     * <h3>A margin, not the edge</h3>
     *
     * <p>Revealed with a few characters of context either side, because a caret pinned exactly to the
     * right edge has nothing after it to read and jumps again on the very next keystroke. Monaco's
     * {@code cursorSurroundingLines} is the vertical form of the same idea.</p>
     *
     * <p>Soft wrap makes this a no-op by construction — there is nothing to scroll to — and the guard is
     * the horizontal scroll range itself rather than a flag, so the two cannot disagree.</p>
     */
    private void revealCaretHorizontally() {
        float maximum = getMaxScrollLeft();
        if (!Float.isFinite(maximum) || maximum <= 0f) return;

        ProjectedLines.ViewPosition view = projections().toViewPosition(
                buffer.document(), getCaret(), LineProjection.Affinity.LEFT);
        float caretX = xOfView(view.viewLine(), view.column());
        if (!Float.isFinite(caretX)) return;

        float margin = Math.max(1f, spaceAdvance()) * HORIZONTAL_REVEAL_MARGIN;
        float left = finiteOrZero(getScrollLeft());
        float width = textViewportWidth();
        if (width <= 0f) return;

        float wanted = left;
        if (caretX - margin < left) wanted = caretX - margin;
        else if (caretX + margin > left + width) wanted = caretX + margin - width;
        wanted = Math.max(0f, Math.min(wanted, maximum));
        if (wanted != left) setScrollImmediate(wanted, getScrollTop());
    }

    /** Characters of context kept either side of a caret revealed sideways. */
    private static final float HORIZONTAL_REVEAL_MARGIN = 4f;

    // ── Virtualised rendering ───────────────────────────────────────────────────────────────────

    /** Re-reads the text of every realised line without recycling it, so highlights survive the edit. */
    private void rebindRealisedLines() {
        // THE PER-LINE SPLIT, because this runs over every realised row on every frame and the two things
        // it does are unrelated: placing a row is style writes that no-op when nothing moved, while
        // re-reading its text is a rope read plus a UIText that may re-shape. A scroll changes both for
        // every row at once, which is why this is the shape of frame the wheel produces.
        long placed = 0L;
        int rows = 0;
        for (Map.Entry<Integer, UIElement> entry : realisedLines.entrySet()) {
            int viewLine = entry.getKey();
            if (viewLine < 0 || viewLine >= viewLineCount()) continue;
            rows++;
            // The FULL layout, not just the text. An edit or a reflow can turn a continuation line into a
            // first line or the reverse, which moves its carried indent and its width -- and after a
            // resize it moves every one of them.
            String before = textOf(entry.getValue()).getText();
            long t0 = FrameProfile.begin();
            layOutLine(viewLine, entry.getValue());
            if (t0 != 0L) placed += System.nanoTime() - t0;
            // A ROW WHOSE TEXT CHANGED HAS STALE HIGHLIGHTS, and nothing else says so.
            //
            // refreshHighlights below early-outs on `!highlightsDirty && from == highlightedFrom && to ==
            // highlightedTo` -- i.e. on the visible OFFSET RANGE being unchanged. That is not a proxy for
            // "the ranges are still valid": replace the document wholesale while the viewport is scrolled
            // and the range can be identical over completely different text, so every realised row keeps
            // the previous document's ranges and NEVER self-corrects, because nothing dirties them again.
            //
            // Reached from the Run console, where filtering re-derives the transcript under a reader who
            // has scrolled back to look at a stack trace: ten link ranges published, one still painted --
            // a character short, over the wrong word. It is reachable in an ordinary editor too, by
            // reloading a file from disk while scrolled away from the top.
            //
            // Compared rather than assumed, so the method keeps its contract that a frame which changed
            // nothing writes nothing: setText no-ops on an unchanged string, and so does this.
            if (!before.equals(textOf(entry.getValue()).getText())) highlightsDirty = true;
        }
        FrameProfile.report(placed, "ed:rebind.layOutLine x" + rows);
        // NO markTreeDirty() HERE, and its absence is the point.
        //
        // This method's own contract, three lines up in updateWindow, is that it is safe to call every
        // frame BECAUSE a frame that changed nothing writes nothing -- setText no-ops on an unchanged
        // string and replaceOrPutCandidate no-ops on an unchanged value. An unconditional dirty defeated
        // exactly that: this runs from onLayoutChanged, so every settled pass ended by declaring the tree
        // dirty again, and calculateLayout's while (isLayoutDirty()) had no reason to ever exit.
        //
        // Whatever genuinely moved has already dirtied the tree by writing a different value, which is the
        // mechanism the rest of the widget layer relies on. Adding a blanket dirty on top is not
        // belt-and-braces; it is the belt sewn to the floor.
    }

    /**
     * Forces the next pass to rebuild every visible highlight range.
     *
     * <p>For a caller that <b>replaced the document wholesale</b> — the Run console re-derives its whole
     * transcript when a per-script filter changes — where the buffer's own change signal is not enough on
     * its own. {@link #refreshHighlights} early-outs when the visible OFFSET RANGE is unchanged, and a
     * replace under a scrolled viewport can produce an identical range over completely different text.</p>
     *
     * <p>Clearing the remembered range as well as setting the flag is the point: the flag alone is
     * consumed by whichever pass runs first, and the range comparison then holds the stale answer.</p>
     */
    public void invalidateHighlights() {
        highlightsDirty = true;
        highlightedFrom = -1;
        highlightedTo = -1;
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
        // THE JUMP THAT CAME IN TOO EARLY, now that there is a viewport to centre in. @see #pendingReveal
        if (pendingReveal && canCentre()) {
            pendingReveal = false;
            revealCaretCentred();
        }
        long profiled = FrameProfile.begin();
        updateWindow();
        FrameProfile.end(profiled, "ed:updateWindow");
        // THE REST OF THE TICK, named. A frame measured `tick:TextEditor 35,010us` with `ed:updateWindow`
        // absent from the same line -- so 35ms was in one of the four calls below and nothing said which.
        profiled = FrameProfile.begin();
        viewCursorsPart.advanceBlink(deltaSeconds);
        FrameProfile.end(profiled, "ed:blink");
        profiled = FrameProfile.begin();
        zoomIndicatorPart.tick(deltaSeconds);
        FrameProfile.end(profiled, "ed:zoomIndicator");
        profiled = FrameProfile.begin();
        autoScrollDuringDrag(deltaSeconds);
        FrameProfile.end(profiled, "ed:autoScrollDuringDrag");
        // A REST TIMER, so it belongs on the heartbeat rather than on the move event: what it measures is
        // the pointer NOT moving, and the last move is the one event that will not be followed by another.
        profiled = FrameProfile.begin();
        langFeatures.hover().tick(deltaSeconds);
        FrameProfile.end(profiled, "ed:hoverTick");
        return true;
    }

    /** Realises exactly the rows on screen, plus {@link #OVERSCAN} either side. */
    public void updateWindow() {
        var window = getAttachedWindow();
        if (window == null) return;
        // FIRST, and before anything reads a viewport. Everything below -- the gutter width, the wrap
        // width, where each line is laid out -- is measured against a viewport whose size depends on which
        // bars are showing, so that question has to be answered once and held for the rest of the pass.
        // Answering it live is what made a parent-derived height loop forever.
        measureScrollbars();
        // ScrollerView registers its ticker only when a scroll begins, so an editor that has never been
        // scrolled would never tick — and the caret would never blink. Registration is a HashSet insert,
        // so repeating it is free and there is deliberately no unregister in the SPI.
        window.registerTicker(this);
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
        // THE TOP OF THE METHOD, and the last thing in it with no bucket. Folding is computed over the
        // WHOLE document rather than the viewport -- a fold can start above the screen and end below it,
        // which is stated on FoldingRegions itself -- so it is the one call in here whose cost scales
        // with the file rather than with what is visible. ed:updateWindow reports 33.6ms on the frame
        // that opens a 2,000-line class while everything named inside it sums to 8ms.
        long folded = FrameProfile.begin();
        boolean foldingChanged = folds.refreshFolding();
        FrameProfile.step(folded, "ed:refreshFolding (" + viewLineCount() + " view lines)");
        if (foldingChanged) {
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
            // THE REALISE LOOP, which nothing has ever timed. ed:updateWindow measured at 33.2ms on the
            // frame that opens a class while every named sub-item inside it summed to 9.8ms -- so 23ms
            // was landing here, in creating and placing the viewport's line elements and in whatever
            // onWindowChanged wakes up.
            long recycled = FrameProfile.begin();
            for (var iterator = realisedLines.entrySet().iterator(); iterator.hasNext(); ) {
                var entry = iterator.next();
                if (entry.getKey() < first || entry.getKey() > last) {
                    recycleLine(entry.getValue());
                    iterator.remove();
                }
            }
            FrameProfile.step(recycled, "ed:recycleLines");
            long realised = FrameProfile.begin();
            int created = 0;
            for (int viewLine = first; viewLine <= last; viewLine++) {
                if (!realisedLines.containsKey(viewLine)) {
                    realisedLines.put(viewLine, realiseLine(viewLine));
                    created++;
                }
            }
            FrameProfile.step(realised, "ed:realiseLines x" + created);
            firstRealised = first;
            lastRealised = last;
            // THE WINDOW MOVED, WHICH IS NOT THE SAME AS THE CONTENT CHANGING.
            //
            // This set `highlightsDirty`, and that flag means "every row's bands are wrong" -- so a
            // scroll, which moves the window on every frame, forced a full rebuild of every realised
            // row's bands on every frame. It is the reason the per-row skip in refreshHighlights could
            // never fire: the counter read 34/34 throughout.
            //
            // What the realise loop actually knows is that the VIEWPORT moved, so refreshHighlights must
            // run rather than early-out on an unchanged range. Which rows within it still hold good bands
            // is a per-row question, and `bandsShownFor` is what answers it.
            highlightWindowMoved = true;
            long announced = FrameProfile.begin();
            onWindowChanged.emit();
            FrameProfile.step(announced, "ed:onWindowChanged -> "
                    + onWindowChanged.connectionCount() + " listeners");
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
        long profiled = FrameProfile.begin();
        rebindRealisedLines();
        FrameProfile.end(profiled, "ed:rebind");
        // BEFORE anything reads the scroll extent, and exactly once. getScrollWidth is a pure accessor
        // over the mark this grows; see its note for why it must not do the scan itself.
        profiled = FrameProfile.begin();
        measureWidestRealisedLine();
        FrameProfile.end(profiled, "ed:measure");
        profiled = FrameProfile.begin();
        syncLineFonts();
        FrameProfile.end(profiled, "ed:fonts");
        profiled = FrameProfile.begin();
        refreshHighlights(first, last);
        FrameProfile.end(profiled, "ed:highlights");
        profiled = FrameProfile.begin();
        layOutTextViewport();
        FrameProfile.end(profiled, "ed:viewport");
        // Every extracted part, in one pass. Monaco gates each on a dirty flag; this does not, and that is
        // now stated where the flag used to be rather than implied by a field nobody set. See
        // EditorViewPart.
        for (EditorViewPart part : viewParts) {
            long partStart = FrameProfile.begin();
            part.render(first, last);
            FrameProfile.end(partStart, "part:" + part.getClass().getSimpleName());
        }
        // THE TAIL WAS NEVER TIMED, and ed:updateWindow reports 40ms while everything named inside it
        // sums to 22. Four calls sat past the parts loop with no bucket of their own, which is exactly
        // how a cost stays invisible while the number above it is read over and over.
        profiled = FrameProfile.begin();
        insetHorizontalBarPastGutter();
        FrameProfile.end(profiled, "ed:insetBar");
        // AFTER the parts have rendered, so a layer built on this frame is moved on this frame rather
        // than drawing once at the unscrolled origin. It is a transform, so nothing below it re-lays out.
        profiled = FrameProfile.begin();
        syncScrollLayers();
        FrameProfile.end(profiled, "ed:syncScrollLayers");
        // The pause that finishes a word, checked once a frame -- there is no other timer in the editor
        // and adding one for this would be a thread to keep in step with the frame it reports to.
        profiled = FrameProfile.begin();
        settleSyntaxIfIdle();
        FrameProfile.end(profiled, "ed:settleSyntax");
        // THE POPUP RE-ANCHORS HERE, once a frame, and not only when the caret moves.
        //
        // The anchor is derived from measured row widths, and those are computed in this very method --
        // so asking for it at the moment a session opens reads whatever the last frame happened to have
        // measured, which for text edited since is NOTHING. It came back NaN, the popup fell back to the
        // origin, and it drew neatly over the editor's top-left corner: plausible enough to look like a
        // placement policy rather than an unmeasured read. Re-anchoring per frame also keeps it correct
        // through a scroll, which no caret-driven update would have caught either.
        profiled = FrameProfile.begin();
        suggest.updateAnchor();
        FrameProfile.end(profiled, "ed:suggestAnchor");
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
        for (UIElement line : realisedLines.values()) {
            pushEditorFontTo(line.getChildren().get(0));
        }
    }

    private UIElement realiseLine(int viewLine) {
        UIElement line = linePool.pollFirst();
        if (line == null) {
            line = new UIElement();
            line.addClass(LINE_CLASS);
            line.setHitTest(false);
            line.markAsInternal();
            // SYNTAX_CLASS is what carries the forty ::highlight() rules. They used to be selected as
            // `texteditor text::highlight(...)`, which made the whole capture vocabulary the editor's
            // private property -- so the documentation popup, which draws a declaration the engine
            // tokenized in exactly the same vocabulary, would have needed a duplicate list. Two lists
            // drift, and the first divergence reads as a scheme bug rather than a selector one.
            line.addChild(new UIText("").addClass(SYNTAX_CLASS));
        }
        layOutLine(viewLine, line);
        if (line.getParent() == null) linesLayer().addInternalChild(line);
        // AND SHOWN, by the same route and for the same reason. @see #recycleLine
        StyleGroup.importantPipeline(line.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.FLEX));
        line.getStyle().taffyBridge.setDisplay(TaffyDisplay.FLEX);
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
        final float top = topOfViewLine(viewLine);
        // DOCUMENT COORDINATES. linesLayer() carries the horizontal offset for everything inside it.
        final float left = codeLeftPad() + carriedIndentPx(viewLine);
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
                l -> l.positionType(TaffyPosition.ABSOLUTE)
                        .top(top).left(left).width(width).height(lineHeight()));
        // The DISPLAY text, with tabs expanded to their stops -- see RowMetrics. setText no-ops on an
        // unchanged string, so rebinding every visible line costs one re-shape per line that moved.
        ((UIText) line.getChildren().get(0)).setText(viewLineDisplayText(viewLine));
    }

    /**
     * Which view line each realised element last published highlight bands for.
     *
     * <p>The memo behind {@link #refreshHighlights}'s per-row skip. Keyed by ELEMENT rather than by view
     * line, because a scroll is exactly the case where the same view line is shown by a different element
     * and the same element shows a different view line -- so the question worth asking is "is this element
     * still showing what it published for", and the view line alone cannot answer it.</p>
     *
     * <p>Identity-keyed and weak, so a line element that is dropped for good takes its entry with it. It
     * never needs invalidating by content: anything that changes what a row's bands ARE sets
     * {@code highlightsDirty}, and that rebuilds every row regardless of what is recorded here.</p>
     */
    private final Map<UIElement, Integer> bandsShownFor =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private void recycleLine(UIElement line) {
        // IT IS ABOUT TO SHOW SOMETHING ELSE. The highlights are cleared just below, so the record of what
        // they were must go with them or the next row to land on this element is skipped as up to date.
        bandsShownFor.remove(line);
        // A pooled line reused for a different row would otherwise keep the old row's highlights, which
        // is worse than none: the ranges are offsets into a string that has been replaced.
        textOf(line).highlights().clear();
        // HIDDEN, NOT DETACHED -- the pool/hide idiom `DecorationPool` already uses one layer up.
        //
        // This pooled the Java objects and threw away their tree membership, which is the expensive half:
        // `removeInternalChild` unregisters the whole subtree, destroying a Taffy node for the line and
        // one for its UIText, and the matching `addInternalChild` on the way back registers them again --
        // and a registration invalidates the style match, so the cascade re-runs over every line that
        // came back.
        //
        // It is paid on every SCROLL, and in bulk whenever a viewport's worth turns over at once. The
        // case that made it visible is a tab switch: an unselected pane is `display: none`, a hidden box
        // measures zero, and the windowing above reads zero as "one line is on screen" -- so switching
        // away recycles the whole viewport and switching back realises it again. Measured on the frame
        // after closing one of two open tabs, which is a switch to the survivor:
        // `UIWindow.registerElement x181`, `style:drainDirtyMatch 6,607us`, `layout 7,282us`.
        //
        // Hiding costs one IMPORTANT-origin display write, and `replaceOrPutCandidate` no-ops when the
        // value is unchanged. A hidden element takes no layout and paints nothing, so a pool of them is
        // bounded by the largest viewport this editor has ever shown -- which is what it would hold
        // detached anyway.
        // THROUGH THE CASCADE, at IMPORTANT origin -- NOT `taffyBridge.setDisplay`, which is immediate
        // and was tried first. A direct Taffy write leaves no candidate behind, so the next thing to
        // re-match this element resolves `display` to its INITIAL value and pops the pooled line back
        // into layout. Re-matching a line is ordinary: `invalidateStyleMatch` recurses, so any class
        // change on the editor reaches every line under it, pool included.
        //
        // Immediate in practice all the same. `display` is transitionable and its DISPLAY_ALLOW_DISCRETE
        // interpolator holds the visible end until the very end -- but only while a transition is
        // RUNNING, and nothing declares one on a line.
        StyleGroup.importantPipeline(line.getStyle().getLayoutGroup(),
                l -> l.display(TaffyDisplay.NONE));
        // AND STRAIGHT AT TAFFY TOO, which is not belt-and-braces -- the two do different halves. The
        // candidate is what SURVIVES: without it the next re-match resolves `display` to its initial
        // value and pops the pooled line back into layout, and re-matching a line is ordinary, since
        // `invalidateStyleMatch` recurses and any class change on the editor reaches the whole pool. The
        // direct write is what makes it take effect NOW: this runs from a ticker, after the frame's
        // cascade has already drained, so a candidate alone leaves the line laid out and painted for one
        // more frame -- a ghost row wherever the viewport just turned over.
        line.getStyle().taffyBridge.setDisplay(TaffyDisplay.NONE);
        linePool.addLast(line);
    }

    // ===================================================================================================
    // Folding
    // ===================================================================================================
    //
    // The subsystem is EditorFolding; these are the calls the commands, the gutter arrows and the tests
    // make. Every one of them was already one line over a private helper, so delegating changes nothing
    // about them except which file the helper lives in.

    private final EditorFolding folds = new EditorFolding(this);

    /** Folding — regions, hidden rows, and the eight commands. */
    EditorFolding folds() {
        return folds;
    }

    /** The folding model, for tests and for anything wanting to drive folds directly. */
    public FoldingModel foldingModel() {
        return folds.model();
    }

    /**
     * The first row of every collapsed region — the whole fold state, as something storable.
     *
     * @see EditorFolding#collapsedRows()
     */
    public int[] collapsedRows() {
        return folds.collapsedRows();
    }

    /**
     * Sets the fold state outright: every region starting on one of {@code startRows} is collapsed and
     * every other region is opened.
     *
     * @see EditorFolding#setCollapsedRows(int...)
     */
    public TextEditor setCollapsedRows(int... startRows) {
        folds.setCollapsedRows(startRows);
        return this;
    }

    /** Swaps the region source — a syntax-aware provider layers over the indent one this way. */
    public TextEditor setFoldingProvider(FoldingRangeProvider provider) {
        folds.setProvider(provider);
        return this;
    }

    public TextEditor setFoldingEnabled(boolean enabled) {
        folds.setEnabled(enabled);
        return this;
    }

    public boolean isFoldingEnabled() {
        return folds.isEnabled();
    }

    /** Folds or unfolds the innermost region at the caret, stepping outwards when already in that state. */
    public void fold() {
        folds.fold();
    }

    public void unfold() {
        folds.unfold();
    }

    /** Folds or unfolds the region at the caret and everything inside it. */
    public void foldRecursively() {
        folds.foldRecursively();
    }

    public void foldAll() {
        folds.foldAll();
    }

    public void unfoldAll() {
        folds.unfoldAll();
    }

    /** Folds every region at exactly {@code level}, leaving the block the caret is in open. */
    public void foldLevel(int level) {
        folds.foldLevel(level);
    }

    /** Toggles the region whose first row is {@code row} — what clicking a gutter arrow does. */
    public void toggleFoldAt(int row) {
        folds.toggleFoldAt(row);
    }

    int caretRow() {
        return buffer.document().offsetToPoint(selections.primary().head()).row();
    }

    /** What a collapsed region's chip reads. @see EditorFolding#placeholderTextFor */
    String placeholderTextFor(FoldingRegions.Region region) {
        return folds.placeholderTextFor(region);
    }

    /**
     * Drops every realised line, so the next pass rebuilds them.
     *
     * <p>For folding, which changes <em>which</em> rows exist rather than only where they are — what is
     * realised is keyed on a view-line window that no longer describes the same text.</p>
     */
    void dropRealisedLines() {
        firstRealised = -1;
        lastRealised = -1;
        forgetWidestLine();
    }

    /**
     * Re-runs selector matching over the editor's subtree.
     *
     * <p>{@code invalidateStyleMatch} is {@code protected} on {@code UIElement} — deliberately, so nothing
     * outside a widget can force its cascade — and the parts and subsystems are outside it in Java's terms
     * while being inside it in every other sense. This is the one forwarder rather than a widening.</p>
     */
    void invalidateStyles() {
        invalidateStyleMatch();
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
        if (!folds.isEnabled()) return -1;
        FoldingRegions.Region region = folds.model().getRegionStartingAt(row);
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

    /**
     * What Cut/Copy/Paste mean in a text editor. @see com.crystalgui.ui.ClipboardActions
     *
     * <p>Held rather than built per call: {@code getData} is asked while a menu is being built, once per
     * row, and a fresh object each time would make identity meaningless to anything caching one.</p>
     */
    private final ClipboardActions clipboardActions = new ClipboardActions() {
        @Override
        public boolean canCut() {
            return hasSelection() && !isReadOnly();
        }

        @Override
        public void cut() {
            CgPlatform.input().setClipboard(getSelectedText());
            deleteSelections();
        }

        @Override
        public boolean canCopy() {
            return hasSelection();
        }

        @Override
        public void copy() {
            CgPlatform.input().setClipboard(getSelectedText());
        }

        @Override
        public boolean canPaste() {
            // The SYSTEM clipboard, because that is the one an editor pastes from -- and it is why this
            // question belongs to the provider rather than to the command.
            String pending = CgPlatform.input().getClipboard();
            return !isReadOnly() && pending != null && !pending.isEmpty();
        }

        @Override
        public void paste() {
            String pending = CgPlatform.input().getClipboard();
            if (pending == null || pending.isEmpty()) return;
            // RE-INDENTED TO WHERE IT LANDS, so a method copied out of one class arrives at the new
            // one's depth. A shift and not a reformat -- see TypeOperations.reindentForPaste.
            insertAtCaret(TypeOperations.reindentForPaste(
                    buffer.document(), selections.primary().start(), pending, indentStyle()));
        }
    };

    /**
     * Routes {@link UiDataKeys#UNDO_STACK} through the same walk everything else uses.
     *
     * <p>Without this the key would answer null for this widget while {@code UndoScope.nearest} found a
     * stack — two mechanisms disagreeing about the same question, which is the thing {@code DataContext}
     * exists to stop.</p>
     */
    @Override
    public Object getData(DataKey<?> key) {
        if (key == UiDataKeys.CLIPBOARD) return clipboardActions;
        Object undo = undoScopeData(key);
        return undo != null ? undo : super.getData(key);
    }

}
