package com.crystalgui.widget.display;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.trace.FrameStats;
import com.crystalgui.core.trace.FrameProfile;
import com.crystalgui.text.TextRange;
import com.crystalgui.ui.dom.Attribute;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.event.MouseEvent;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

/**
 * The frame rate, and what is behind it, drawn over whatever is being measured — a debug HUD.
 *
 * <pre>{@code
 * FrameStatsOverlay hud = FrameStatsOverlay.attach(document);   // top-right, above everything
 * hud.toggle();                                                 // bound to a key by the host
 * hud.stats().setBudgetMs(1000f / 144f);                        // this host's refresh rate
 * }</pre>
 *
 * <p>It reads {@link FrameStats}, which takes its frame boundary from
 * {@link FrameProfile} — so the same overlay works in a harness scene, in the
 * editor and on a Minecraft screen, and measures the whole frame rather than its own subtree. The phase
 * breakdown follows the readout rather than a property: {@code FrameProfile} times its phases whenever
 * something is collecting, and {@code -Dcrystalgui.frameprofile=true} adds the LOGGING on top.</p>
 *
 * <h3>It holds the collector only while it is in the tree</h3>
 *
 * <p>{@code connected()} takes a {@link FrameStats#hold()} and {@code disconnected()} releases it, so a
 * host that closes the HUD stops paying for it — and two overlays on two documents cannot switch each
 * other off, because holds are counted.</p>
 *
 * <h3>Why rows of {@link UIText} rather than painted text</h3>
 *
 * <p>A readout that paints its own glyphs re-shapes them every frame, which is measurable in the thing it
 * exists to measure. Text nodes re-shape when their string changes, and the strings are rebuilt at
 * {@link #REFRESH_SECONDS} — ten times a second, which is as fast as a number this noisy can be read
 * anyway.</p>
 */
public class FrameStatsOverlay extends UIElement {

    /** This widget's kind. Registered so a theme can reach it; it ships its own look in the UA sheet. */
    public static final Name NAME = Name.of("framestats");

    /** One line of the readout. */
    public static final String ROW_CLASS = "__row__";

    /** The headline row, which a theme draws larger. */
    public static final String HEAD_CLASS = "__head__";

    /** The frame-time bars. Its own class so a sheet can style the one row made of characters. */
    public static final String SPARK_CLASS = "__spark__";

    /**
     * What a hidden readout, or a row the readout has stopped needing, wears.
     *
     * <p>A class rather than an inline {@code display: none} because the engine may not write into the
     * cascade — see {@code EngineBoundaryTest}. The sheet says what hidden means.</p>
     */
    public static final String HIDDEN_CLASS = "__hidden__";

    /**
     * The two highlight names the bars are coloured through.
     *
     * <p>{@code ::highlight(name)} rather than a node per bar: forty-six characters would be forty-six
     * Taffy nodes rebuilt ten times a second, which is the cost the Custom Highlight API exists to
     * avoid — and it is being paid inside the thing that measures cost.</p>
     *
     * <p>There is no name for a healthy bar. Good is the row's own colour, so the common case registers
     * nothing at all and a clean run costs one highlight lookup that finds nothing.</p>
     */
    public static final String WARN_HIGHLIGHT = "framestats-warn";
    public static final String BAD_HIGHLIGHT = "framestats-bad";

    /**
     * What a row's numbers are worth — inside the budget, past it, or past twice it.
     *
     * <p>A class rather than a colour, because green/amber/red is a theme's business and the threshold is
     * {@link FrameStats.Health}'s. The three are mutually exclusive and the row carries exactly one.</p>
     */
    public static final String GOOD_CLASS = "__good__";
    public static final String WARN_CLASS = "__warn__";
    public static final String BAD_CLASS = "__bad__";

    /** The class a verdict wears, or null for a row that is stating rather than judging. */
    @Nullable
    private static String classFor(FrameStats.Health health) {
        return switch (health) {
            case NONE -> null;
            case GOOD -> GOOD_CLASS;
            case WARN -> WARN_CLASS;
            case BAD -> BAD_CLASS;
        };
    }

    /**
     * How often the strings are rebuilt.
     *
     * <p>A number that changes every frame cannot be read, and re-shaping five lines per frame would put
     * the readout into its own measurements.</p>
     */
    public static final float REFRESH_SECONDS = 0.1f;

    private final FrameStats stats;

    private final List<UIText> rows = new ArrayList<>();

    private FrameStats.Detail detail = FrameStats.Detail.SUMMARY;

    private float sinceRefresh;
    private boolean ticking;
    private boolean holding;
    private boolean showing = true;

    public FrameStatsOverlay() {
        this(FrameStats.get());
    }

    /** For a test or a bench with a collector of its own. */
    public FrameStatsOverlay(FrameStats stats) {
        super(NAME);
        this.stats = stats;
        refusePublicChildren();
        // A READOUT IS NOT A CONTROL. It sits over the corner of whatever it measures, and a HUD that
        // ate the clicks under it would break the thing being profiled. TRANSPARENT rather than
        // `hit-test: false`, which is subtree-wide: the plate is never the answer to a click, and its rows
        // are not either -- except the sparkline, once something can open the frame a bar stands for.
        set(Attribute.HIT_TRANSPARENT, true);
    }

    /**
     * Puts one over {@code document}, above every window and dialog, and starts collecting.
     *
     * <p>The top layer, because a readout that a maximised window covers is a readout nobody can see —
     * and it takes no input, so nothing it covers becomes unclickable.</p>
     */
    public static FrameStatsOverlay attach(UIDocument document) {
        FrameStatsOverlay overlay = new FrameStatsOverlay();
        document.append(overlay);
        document.promote(overlay);
        return overlay;
    }

    /**
     * The readout already over {@code document}, or null — the non-attaching read.
     *
     * <p>A document's own children, because {@link #attach} appends there and promotion records top-layer
     * membership on the node rather than moving it.</p>
     */
    @Nullable
    public static FrameStatsOverlay of(UIDocument document) {
        for (UIElement child : document.children()) {
            if (child instanceof FrameStatsOverlay overlay) return overlay;
        }
        return null;
    }

    /**
     * Shows the readout over {@code document}, or hides the one already there — what a host binds a key
     * to, and the whole of what a Minecraft screen or an editor needs to offer it.
     *
     * <p>Hidden rather than removed on the way back, which is what stops the collector: a hidden readout
     * releases its {@link FrameStats#hold()} and costs one boolean read a frame, so there is nothing to
     * be gained by tearing the node out and everything to be lost — the next press would rebuild it and
     * start the window over.</p>
     */
    public static FrameStatsOverlay toggleOn(UIDocument document) {
        FrameStatsOverlay existing = of(document);
        return existing == null ? attach(document) : existing.toggle();
    }

    /**
     * Expands the readout over {@code document} into a row per phase, showing it first if it was hidden.
     *
     * <p>Never a hidden expansion: the key that asks for the breakdown is pressed by somebody who wants
     * to see it, and a press that silently changed the shape of an invisible panel would read as the key
     * doing nothing.</p>
     */
    public static FrameStatsOverlay expandOn(UIDocument document) {
        FrameStatsOverlay hud = of(document);
        if (hud == null) hud = attach(document);
        return hud.setShowing(true).toggleDetail();
    }

    public FrameStats stats() {
        return stats;
    }

    /**
     * Opens a frame somewhere that can show it. The readout cannot name a viewer — it is a widget, and
     * the viewer is an application above it — so the application puts itself here.
     *
     * <pre>{@code
     * FrameStatsOverlay.onOpenFrame((document, frameIndex) -> FrameProfiler.openAt(document, frameIndex));
     * }</pre>
     */
    @FunctionalInterface
    public interface FrameOpener {
        void open(UIDocument document, long frameIndex);
    }

    @Nullable
    private static volatile FrameOpener opener;

    /** Makes the sparkline clickable: a press on a bar opens that frame. Null takes it back. */
    public static void onOpenFrame(@Nullable FrameOpener value) {
        opener = value;
    }

    public boolean isShowing() {
        return showing;
    }

    /** Hides or shows the readout. Hidden, it stops collecting as well as drawing. */
    public FrameStatsOverlay setShowing(boolean value) {
        if (showing == value) return this;
        showing = value;
        // A CLASS, NOT A CASCADE WRITE. `EngineBoundaryTest` forbids the engine writing style at
        // IMPORTANT origin, and it is right to: a widget that pushes its own geometry in is the habit
        // the three-tree rewrite exists to end. The sheet decides what hidden looks like.
        if (value) removeClass(HIDDEN_CLASS);
        else addClass(HIDDEN_CLASS);
        applyCollection();
        return this;
    }

    public FrameStatsOverlay toggle() {
        return setShowing(!showing);
    }

    public boolean isDetailed() {
        return detail == FrameStats.Detail.FULL;
    }

    /**
     * Expands the readout into a row per phase, or collapses it back.
     *
     * <p>Summary by default: the breakdown is what you want once the summary has told you there is
     * something to look at, and until then it is four rows of "everything is fine" over the content.</p>
     */
    public FrameStatsOverlay setDetailed(boolean value) {
        FrameStats.Detail wanted = value ? FrameStats.Detail.FULL : FrameStats.Detail.SUMMARY;
        if (detail == wanted) return this;
        detail = wanted;
        // AT ONCE, not at the next refresh: this is a keypress, and a tenth of a second between the key
        // and the panel changing shape reads as the key not having worked.
        write(stats.rows(detail));
        return this;
    }

    public FrameStatsOverlay toggleDetail() {
        return setDetailed(!isDetailed());
    }

    @Override
    protected void connected() {
        super.connected();
        applyCollection();
        UIDocument window = document();
        // `every` is a plain add and `disconnected` clears the flag, or a HUD hidden and reshown comes
        // back with the flag set and no hook behind it.
        if (ticking || window == null) return;
        ticking = true;
        window.animation().every(this, this::tickFrame);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        ticking = false;
        applyCollection();
    }

    /** Collecting exactly while this is visible and in a tree. @see FrameStats#hold */
    private void applyCollection() {
        boolean wanted = showing && isConnected();
        if (wanted == holding) return;
        holding = wanted;
        if (wanted) stats.hold();
        else stats.release();
    }

    private boolean tickFrame(float deltaSeconds) {
        ticks++;
        sinceRefresh += deltaSeconds;
        if (sinceRefresh < REFRESH_SECONDS) return true;
        sinceRefresh = 0f;
        write(stats.rows(detail));
        return true;
    }

    /**
     * Shows {@code lines}, adding and retiring rows as the readout's shape changes.
     *
     * <p>A row whose text is unchanged is left alone — {@link UIText#setText} re-shapes, and most of
     * these lines are the same from one refresh to the next.</p>
     */
    /**
     * The frame each sparkline bar stood for when it was DRAWN. The ring moves on between one refresh and
     * the next, so a press mapped against the ring as it is now opened a neighbour of the bar pressed.
     */
    private long[] barFrames = new long[0];
    /**
     * The mapping before the last rewrite, and the tick it happened on. A frame ticks animation BEFORE
     * it dispatches input, so a press dispatched on the tick that rewrote the bars was aimed at the bars
     * still on screen -- the previous ones.
     */
    private long[] shownBarFrames = new long[0];
    private long ticks;
    private long rewroteOnTick = -1L;

    private void write(List<FrameStats.Row> lines) {
        while (rows.size() < lines.size()) {
            UIText row = new UIText("");
            row.addClass(ROW_CLASS);
            if (rows.isEmpty()) row.addClass(HEAD_CLASS);
            // ONLY THE SPARKLINE TAKES A CLICK, and only once something can open a frame: the rest of the
            // readout stays transparent to the pointer, over whatever it is measuring.
            row.setHitTest(false);
            row.onMouseDown.attachListener((element, event) -> openBarUnder(row, event), false, true);
            rows.add(row);
            appendStructural(row);
        }
        for (int i = 0; i < rows.size(); i++) {
            UIText row = rows.get(i);
            FrameStats.Row wanted = i < lines.size() ? lines.get(i) : EMPTY_ROW;
            if (!row.getText().equals(wanted.text())) row.setText(wanted.text());
            String health = classFor(wanted.health());
            for (String other : List.of(GOOD_CLASS, WARN_CLASS, BAD_CLASS)) {
                if (!other.equals(health)) row.removeClass(other);
            }
            if (health != null && !row.hasClass(health)) row.addClass(health);
            paintBars(row, wanted.barHealth());
            // Same rule as the plate itself: a retired row wears the class and the sheet hides it.
            if (wanted.text().isEmpty()) row.addClass(HIDDEN_CLASS);
            else row.removeClass(HIDDEN_CLASS);
        }
    }

    /**
     * Colours {@code row}'s characters by what each bar is worth, or clears that if it is not the bars.
     *
     * <p>Set AFTER the text, always: a range is a pair of offsets into the string that is there now, and
     * a row that just shrank would be carrying ranges off the end of it.</p>
     */
    private void paintBars(UIText row, @Nullable List<FrameStats.Health> bars) {
        if (bars == null) {
            // `remove` only fires a change when there was something to remove, so an ordinary row pays
            // nothing for being asked every refresh.
            if (row.hasClass(SPARK_CLASS)) row.removeClass(SPARK_CLASS);
            row.setHitTest(false);
            row.highlights().remove(WARN_HIGHLIGHT).remove(BAD_HIGHLIGHT);
            return;
        }
        if (!row.hasClass(SPARK_CLASS)) row.addClass(SPARK_CLASS);
        row.setHitTest(opener != null);
        int columns = bars.size();
        long[] frames = new long[columns];
        for (int column = 0; column < columns; column++) frames[column] = stats.frameIndexAt(column, columns);
        if (!Arrays.equals(frames, barFrames)) {
            shownBarFrames = barFrames;
            barFrames = frames;
            rewroteOnTick = ticks;
        }
        row.highlights().set(WARN_HIGHLIGHT, runsOf(bars, FrameStats.Health.WARN));
        row.highlights().set(BAD_HIGHLIGHT, runsOf(bars, FrameStats.Health.BAD));
    }

    /**
     * The stretches of {@code bars} that are {@code wanted}, as ranges over the row's characters.
     *
     * <p>RUNS RATHER THAN A RANGE PER BAR, for two reasons and both are hard: a highlight's own ranges
     * may not overlap or touch ambiguously, and every range boundary is a shaping-run boundary — so
     * forty-six single-character ranges would re-shape the row as forty-six runs. A steady scene has one
     * run or none.</p>
     */
    private static List<TextRange> runsOf(List<FrameStats.Health> bars, FrameStats.Health wanted) {
        List<TextRange> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < bars.size(); i++) {
            boolean here = bars.get(i) == wanted;
            if (here && start < 0) start = i;
            else if (!here && start >= 0) {
                out.add(TextRange.of(start, i));
                start = -1;
            }
        }
        if (start >= 0) out.add(TextRange.of(start, bars.size()));
        return out;
    }

    /** The bar under the press, opened through {@link #onOpenFrame}. */
    private void openBarUnder(UIText row, MouseEvent.Down event) {
        FrameOpener open = opener;
        UIDocument window = document();
        if (open == null || window == null || !row.hasClass(SPARK_CLASS)) return;
        long[] frames = rewroteOnTick == ticks ? shownBarFrames : barFrames;
        int column = row.offsetAtScreen(event.getPosition().x(), event.getPosition().y());
        if (frames.length == 0 || column < 0) return;
        long index = frames[Math.min(frames.length - 1, column)];
        if (index < 0L) return;
        event.stopPropagation();
        open.open(window, index);
    }

    /** A row the readout has stopped needing — hidden rather than removed, so the tree stops churning. */
    private static final FrameStats.Row EMPTY_ROW = new FrameStats.Row("", FrameStats.Health.NONE);

    /** The frame the sparkline's {@code column} was drawn for, or -1 — what a press on it opens. */
    public long barFrame(int column) {
        long[] frames = barFrames;
        return column >= 0 && column < frames.length ? frames[column] : -1L;
    }

    /** The row elements, for a test asserting on what the readout says. */
    public List<UIText> rows() {
        return List.copyOf(rows);
    }

    /** What the readout is showing, as text — the same lines, for a test or a log. */
    public List<String> text() {
        List<String> out = new ArrayList<>(rows.size());
        for (UIText row : rows) out.add(row.getText());
        return out;
    }
}
