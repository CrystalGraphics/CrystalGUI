package com.crystalgui.widget.display;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgui.core.async.FrameStats;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.ui.dom.Name;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.widget.text.UIText;

import dev.vfyjxf.taffy.style.TaffyDisplay;

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
 * {@link com.crystalgui.core.async.FrameProfile} — so the same overlay works in a harness scene, in the
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
        // ate the clicks under it would break the thing being profiled.
        setHitTest(false);
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

    public FrameStats stats() {
        return stats;
    }

    public boolean isShowing() {
        return showing;
    }

    /** Hides or shows the readout. Hidden, it stops collecting as well as drawing. */
    public FrameStatsOverlay setShowing(boolean value) {
        if (showing == value) return this;
        showing = value;
        // IMPORTANT, so a sheet cannot leave a hidden HUD drawn -- the same channel Tab hides a pane on.
        StyleGroup.importantPipeline(getStyle().getLayoutGroup(),
                l -> l.display(value ? TaffyDisplay.FLEX : TaffyDisplay.NONE));
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
    private void write(List<FrameStats.Row> lines) {
        while (rows.size() < lines.size()) {
            UIText row = new UIText("");
            row.addClass(ROW_CLASS);
            if (rows.isEmpty()) row.addClass(HEAD_CLASS);
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
            StyleGroup.importantPipeline(row.getStyle().getLayoutGroup(),
                    l -> l.display(wanted.text().isEmpty() ? TaffyDisplay.NONE : TaffyDisplay.FLEX));
        }
    }

    /** A row the readout has stopped needing — hidden rather than removed, so the tree stops churning. */
    private static final FrameStats.Row EMPTY_ROW = new FrameStats.Row("", FrameStats.Health.NONE);

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
