package com.crystalgui.ui.elements.editor;

import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.property.layout.LayoutProperties;
import com.crystalgui.text.Rope;
import com.crystalgui.text.decoration.TrackedRange;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.diagnostic.DiagnosticSeverity;
import com.crystalgui.text.diagnostic.DiagnosticTag;
import com.crystalgui.text.wrap.LineProjection;
import com.crystalgui.ui.UIElement;
import dev.vfyjxf.taffy.style.TaffyPosition;

import java.util.ArrayList;
import java.util.List;

/**
 * The underline beneath every diagnostic — one band per visible <b>view line</b> it covers.
 *
 * <p>Structurally {@link SelectionsPart}, and deliberately so: a range decoration that survives soft wrap
 * has exactly one correct shape, which is to work in view space and emit a band per visual row. A single
 * band spanning a wrap would underline text that has no diagnostic on it.</p>
 *
 * <h3>Colour comes from the cascade, not from Java</h3>
 *
 * <p>Each band carries {@code __squiggle__} plus a per-severity class, and {@code default.css} decides what
 * red and amber mean. Same rule the whole widget layer follows, and the same one the node graph already
 * relies on for its per-type port palette — a new severity is a stylesheet edit, not a recompile.</p>
 *
 * <h3>{@link DiagnosticSeverity#HINT} draws nothing</h3>
 *
 * <p>Not an omission. A hint is a suggestion, and underlining it in the text makes a style note look like a
 * compile error — VS Code renders hints only as a lightbulb for the same reason. The band is skipped here
 * rather than made transparent in CSS so it costs no element at all.</p>
 *
 * <h3>Positions come from {@link TrackedRange}, not from the diagnostic</h3>
 *
 * <p>They used to be converted here, row/column against the live buffer, every frame. That is correct only
 * at the instant the analysis landed: 300ms of typing later, every mark below the caret pointed at text it
 * was never about. It did not look like a bug — the squiggles were merely under the wrong words, and the
 * next compile put them back — which is what made it read as the analyser lagging.</p>
 *
 * <p>Now the offsets are maintained by the document (§17.1) and read straight out. The part does no
 * conversion at all, which also means there is no second copy of the clamping rules to drift.</p>
 *
 * <p><b>A range that collapsed because its text was deleted draws nothing.</b> A zero-width diagnostic is
 * legitimate — "expected ';'" points between two characters and is widened to one so it can be seen — but a
 * range whose word was deleted would be widened into a mark over whatever moved into its place, which is a
 * squiggle on innocent text. {@link TrackedRange#collapsedByEdit()} is what tells the two apart.</p>
 */
final class SquigglesPart extends EditorViewPart {

    static final String SQUIGGLE_CLASS = "__squiggle__";
    static final String ERROR_CLASS = "__squiggle-error__";
    static final String WARNING_CLASS = "__squiggle-warning__";
    static final String INFORMATION_CLASS = "__squiggle-information__";

    private final List<UIElement> bands = new ArrayList<>();

    SquigglesPart(TextEditor editor) {
        super(editor);
    }

    @Override
    void render(int firstViewLine, int lastViewLine) {
        int used = 0;
        if (lastViewLine >= firstViewLine) {
            for (TrackedRange tracked
                    : editor.buffer().decorations().inLane(TextEditor.DIAGNOSTIC_LANE)) {
                Diagnostic diagnostic = tracked.payload(Diagnostic.class);
                if (diagnostic == null || diagnostic.severity() == DiagnosticSeverity.HINT) continue;
                // DEAD WEIGHT IS FADED, NOT UNDERLINED -- and never both. The fade IS this diagnostic's
                // rendering: it says "nothing reads this" in place of a mark, which is the whole reason
                // DiagnosticTag exists apart from severity. Drawing a band under it as well put a yellow
                // line under every unused import beneath text that was already grey -- two marks for one
                // fact, and the second one saying "something is wrong here" about code whose only fault is
                // that it is unread. IntelliJ greys an unused import and underlines nothing.
                if (diagnostic.hasTag(DiagnosticTag.UNNECESSARY)) continue;
                // See the class note: born-empty is a real mark, collapsed-by-edit is a mark whose text is
                // gone. Widening the second one paints over whatever took its place.
                if (tracked.collapsedByEdit()) continue;
                used = place(tracked, diagnostic, firstViewLine, lastViewLine, used);
            }
        }
        for (int i = used; i < bands.size(); i++) DecorationPool.hide(bands.get(i));
    }

    private int place(TrackedRange tracked, Diagnostic diagnostic,
                      int firstViewLine, int lastViewLine, int index) {
        Rope document = editor.buffer().document();
        int from = Math.min(tracked.from(), document.length());
        int to = Math.min(tracked.to(), document.length());
        // A zero-width diagnostic still has to be visible -- "expected ';'" points between two characters,
        // and a band of width 0 is a band nobody can see. One character's worth is the smallest honest mark.
        if (to <= from) to = Math.min(document.length(), from + 1);

        int startView = editor.viewLineOf(from, LineProjection.Affinity.RIGHT);
        int endView = editor.viewLineOf(to, LineProjection.Affinity.LEFT);
        float height = editor.lineHeight();
        float pad = editor.codeLeftPad();

        for (int viewLine = Math.max(firstViewLine, startView);
             viewLine <= Math.min(lastViewLine, endView); viewLine++) {
            if (viewLine < 0 || viewLine >= editor.viewLineCount()) continue;
            int lineStart = editor.viewLineStartOffset(viewLine);
            int lineEnd = editor.viewLineEndOffset(viewLine);
            int segmentFrom = Math.max(lineStart, from);
            int segmentTo = Math.min(lineEnd, to);
            if (segmentTo < segmentFrom) continue;

            int rowStart = document.lineStartOffset(editor.modelAt(viewLine).row());
            LineProjection.ViewPosition fromView = editor.projectionAt(viewLine)
                    .toViewPosition(segmentFrom - rowStart, LineProjection.Affinity.RIGHT);
            LineProjection.ViewPosition toView = editor.projectionAt(viewLine)
                    .toViewPosition(segmentTo - rowStart, LineProjection.Affinity.LEFT);

            float left = pad + editor.xOfView(viewLine, fromView.column());
            float right = pad + editor.xOfView(viewLine, toView.column());
            float width = Math.max(1f, right - left);

            UIElement band = bandAt(index++);
            applySeverity(band, diagnostic.severity());
            // READ FROM THE SHEET, then used for both the height and the top -- see below.
            float thickness = styleSize(band, LayoutProperties.HEIGHT, DEFAULT_THICKNESS);
            // Under the text rather than through it: the band sits at the bottom of the line box, so it
            // never overlaps a glyph and never fights the selection band drawn behind the same characters.
            float top = editor.topOfViewLine(viewLine) + height - thickness;
            StyleGroup.defaultPipeline(band.getStyle().getLayoutGroup(),
                    l -> l.positionType(TaffyPosition.ABSOLUTE)
                            .left(left).top(top).width(width).height(thickness));
        }
        return index;
    }

    /**
     * What the band is drawn at when the sheet has not been matched yet — <b>not</b> the styling.
     *
     * <p>{@code .__squiggle__ { height: 1px }} in {@code ua/editor.css} is the real value. This used to be
     * a Java constant under a note defending it: the band's <em>top</em> is computed from its height, so a
     * height the cascade could change independently would put the underline somewhere other than the
     * bottom of the line box. True, and true only of a value the part <b>writes</b> — reading it once and
     * using it for both is what makes the two incapable of disagreeing, whatever a theme says.</p>
     */
    private static final float DEFAULT_THICKNESS = 1f;

    /** Set AND cleared, all three, because bands are recycled — a band that underlined an error and is
     * reused for a warning would otherwise carry both classes and take whichever the cascade preferred. */
    private static void applySeverity(UIElement band, DiagnosticSeverity severity) {
        band.removeClass(ERROR_CLASS);
        band.removeClass(WARNING_CLASS);
        band.removeClass(INFORMATION_CLASS);
        switch (severity) {
            case ERROR -> band.addClass(ERROR_CLASS);
            case WARNING -> band.addClass(WARNING_CLASS);
            case INFORMATION -> band.addClass(INFORMATION_CLASS);
            default -> { }
        }
    }

    private UIElement bandAt(int index) {
        while (bands.size() <= index) {
            UIElement band = new UIElement();
            band.addClass(SQUIGGLE_CLASS);
            band.setHitTest(false);
            band.markAsInternal();
            // In the viewport, in document coordinates, like every other decoration -- see SelectionsPart
            // for what happens when one of these is parented to the editor instead.
            editor.linesLayer().addInternalChild(band);
            bands.add(band);
        }
        // BACK INTO LAYOUT. Retirement is `display: none` at IMPORTANT -- see DecorationPool.hide for why
        // a zero box alone cannot survive a sheet that sizes this class -- so taking a band out of the
        // pool has to undo it.
        return DecorationPool.show(bands.get(index));
    }
}
