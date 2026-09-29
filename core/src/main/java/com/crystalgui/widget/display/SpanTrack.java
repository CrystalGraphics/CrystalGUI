package com.crystalgui.widget.display;

import com.crystalgraphics.api.font.CgFontFamily;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.property.StylePropertyRegistry;
import com.crystalgui.ui.box.Box;
import com.crystalgui.ui.dom.Name;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Spans stacked by depth — a flame chart's rows, drawn as one leaf.
 *
 * <pre>{@code
 * SpanTrack track = new SpanTrack(axis);
 * track.add(new SpanTrack.Span("paint:tree", 0, start, end, "render/CgUiPaintContext.java:838"));
 * track.onSelected(span -> showDetail(span));
 * }</pre>
 *
 * <h3>A span keeps its colour, and a subsystem keeps its family</h3>
 *
 * <p>Hue comes from a zone's CATEGORY — the part of its name before the first {@code :} or {@code .}
 * — and a small lightness step from the rest of the name. So every {@code paint:*} zone is one family
 * and every {@code style:*} another, which is what makes a chart readable at a glance: the eye groups by
 * colour before it reads a single label. Twelve hues hashed from whole names put {@code paint:tree} and
 * {@code paint:layer} in unrelated colours and made that grouping impossible. Chrome's performance panel
 * colours by category for the same reason.</p>
 *
 * <p>Both halves are hashes, so a zone is the same colour in every frame and every run — scrubbing
 * recolours nothing, and two runs compared side by side agree about which bar is which.</p>
 *
 * <p>Selection is an outline and a lift, never a colour change — recolouring the selected span would
 * defeat the paragraph above at exactly the moment somebody is looking hardest.</p>
 */
public class SpanTrack extends TimelineTrack {

    public static final Name NAME = Name.of("spantrack");

    /** The class a sheet sizes the rows with. */
    public static final String ROW_CLASS = "__span-row__";

    /**
     * The name a caller gives a stretch of time nothing measured.
     *
     * <p>Drawn flat and unlabelled rather than left blank: blank reads as "nothing here" when it means
     * "nothing instrumented here", and those are opposite answers.</p>
     */
    public static final String GAP_NAME = "—";

    /**
     * One drawn interval.
     *
     * @param name   what it is called, and what its colour is hashed from
     * @param depth  its row; zero is the outermost
     * @param source {@code File.java:line}, or null — shown when one span is selected
     */
    public record Span(String name, int depth, long startNanos, long endNanos, @Nullable String source) {

        public long durationNanos() {
            return Math.max(0L, endNanos - startNanos);
        }

        public double millis() {
            return durationNanos() / 1_000_000d;
        }
    }

    private final List<Span> spans = new ArrayList<>();
    private int maxDepth;

    private float rowHeight = 18f;

    @Nullable
    private Span selected;
    @Nullable
    private Span hovered;

    private final List<Consumer<Span>> selectionListeners = new ArrayList<>(2);
    private final List<Consumer<Span>> hoverListeners = new ArrayList<>(2);

    public SpanTrack(TimelineAxis axis) {
        this(NAME, axis);
    }

    protected SpanTrack(Name name, TimelineAxis axis) {
        super(name, axis);
    }

    public SpanTrack setSpans(List<Span> values) {
        spans.clear();
        maxDepth = 0;
        if (values != null) {
            spans.addAll(values);
            for (Span span : spans) maxDepth = Math.max(maxDepth, span.depth());
        }
        selected = null;
        hovered = null;
        // A different number of rows is a different height, so this is a layout change and not a repaint.
        markTreeDirty();
        repaint();
        return this;
    }

    public List<Span> spans() {
        return List.copyOf(spans);
    }

    public SpanTrack setRowHeight(float value) {
        rowHeight = Math.max(4f, value);
        markTreeDirty();
        return this;
    }

    @Nullable
    public Span selected() {
        return selected;
    }

    public void onSelected(Consumer<Span> listener) {
        if (listener != null) selectionListeners.add(listener);
    }

    /**
     * Hears the span under the pointer as it changes, and {@code null} when the pointer leaves or is
     * over nothing — what a readout naming the hovered zone listens to.
     */
    public void onHover(Consumer<Span> listener) {
        if (listener != null) hoverListeners.add(listener);
    }

    /** Draws the selection ring on {@code span} without announcing it — for a view restoring state. */
    public void showSelected(@Nullable Span span) {
        if (Objects.equals(selected, span)) return;
        selected = span;
        repaint();
    }

    /**
     * Faint vertical lines at these times — where one frame ends and the next begins, when a track
     * shows several. Without them a range reads as one long frame, and a zone cannot be told apart
     * from its neighbour in the next frame.
     */
    public SpanTrack setBoundaries(@Nullable long[] nanos) {
        boundaries = nanos == null ? new long[0] : nanos;
        repaint();
        return this;
    }

    private long[] boundaries = new long[0];

    @Override
    protected float trackHeight() {
        return (maxDepth + 1) * rowHeight;
    }

    // ── Picking ─────────────────────────────────────────────────────────────────────────────

    @Override
    protected void onPicked(float x, float y, long nanos) {
        Span hit = spanAt(x, y);
        if (hit == null) return;
        selected = hit;
        repaint();
        for (Consumer<Span> listener : selectionListeners) listener.accept(hit);
    }

    @Override
    protected void onHovered(float x, float y, long nanos) {
        Span was = hovered;
        hovered = Float.isNaN(x) ? null : spanAt(x, y);
        if (hovered != was) {
            repaint();
            for (Consumer<Span> listener : hoverListeners) listener.accept(hovered);
        }
    }

    @Nullable
    private Span spanAt(float x, float y) {
        if (Float.isNaN(x)) return null;
        int row = Float.isNaN(y) ? -1 : (int) (y / rowHeight);
        long at = axis().timeAt(x);
        Span best = null;
        for (Span span : spans) {
            if (GAP_NAME.equals(span.name())) continue;     // absence is not selectable
            if (row >= 0 && span.depth() != row) continue;
            if (at < span.startNanos() || at >= span.endNanos()) continue;
            // THE DEEPEST ONE WINS when the row is unknown: an outer zone covers every inner one, so
            // picking the first match would always answer the root and never the thing clicked.
            if (best == null || span.depth() > best.depth()) best = span;
        }
        return best;
    }

    // ── Painting ────────────────────────────────────────────────────────────────────────────

    @Override
    protected void paintTrack(CgUiPaintContext ctx, Box box) {
        if (spans.isEmpty()) return;
        TimelineAxis axis = axis();
        CgFontFamily font = labelFont();
        int textColor = computedStyle().get(StylePropertyRegistry.COLOR);
        int ring = computedStyle().get(StylePropertyRegistry.BORDER_COLOR);
        float trackWidth = box.width();
        Arrays.fill(lastSliverPixel, Integer.MIN_VALUE);

        for (Span span : spans) {
            float x0 = axis.xOf(span.startNanos());
            float x1 = axis.xOf(span.endNanos());
            if (x1 < 0f || x0 > trackWidth) continue;   // wholly off screen: no draw, no clamp
            float left = Math.max(0f, x0);
            float right = Math.min(trackWidth, x1);
            // A SPAN NARROWER THAN A PIXEL STILL DRAWS. Dropping it would make a chart of a thousand
            // short zones look empty, which is the opposite of what it is being asked.
            float width = Math.max(MIN_SPAN_WIDTH, right - left);
            float top = span.depth() * rowHeight + ROW_GAP * 0.5f;
            float height = rowHeight - ROW_GAP;

            if (GAP_NAME.equals(span.name())) {
                // A GAP TAKES THE TEXT COLOUR at a whisper, not a palette hue: it is absence, and a
                // colour of its own would put it in the legend beside real zones.
                ctx.rect().at(left, top).size(width, height).radius(RADIUS, RADIUS)
                        .fillColor((textColor & 0x00FFFFFF) | 0x10000000).submit();
                continue;
            }

            boolean isSelected = span.equals(selected);
            boolean isHovered = span.equals(hovered);
            int fill = colorOf(span.name());
            if (isHovered && !isSelected) fill = lighten(fill, 0.14f);

            // A HAIRLINE GAP BETWEEN NEIGHBOURS, taken from the right edge: two siblings that touch
            // would otherwise read as one bar, and a flame chart is mostly touching siblings.
            float drawn = width > 3f ? width - 1f : width;
            if (drawn < SLIVER && !isSelected && !isHovered) {
                // A SLIVER on a pixel its row has already painted adds nothing a reader can see, and a
                // frame of glyph-level zones is thousands of them. One per pixel column per row, and
                // plain: a corner narrower than its own radius is not drawn anyway, and a rounded rect
                // is a draw call of its own where a plain one joins the batch.
                int pixel = (int) left;
                int depth = span.depth();
                if (depth >= lastSliverPixel.length) lastSliverPixel = growPixels(lastSliverPixel, depth);
                if (lastSliverPixel[depth] == pixel) continue;
                lastSliverPixel[depth] = pixel;
                ctx.rect().at(left, top).size(drawn, height).fillColor(fill).submit();
                continue;
            }
            if (isSelected) {
                ctx.rect().at(left, top).size(drawn, height).radius(RADIUS, RADIUS)
                        .border(1.5f, ring).fillColor(fill).submit();
            } else {
                ctx.rect().at(left, top).size(drawn, height).radius(RADIUS, RADIUS)
                        .fillColor(fill).submit();
            }

            if (drawn >= MIN_LABEL_SPAN) {
                String duration = formatMillis(span.millis());
                String text = span.name();
                // THE DURATION WHEN IT FITS, because the bar's width only says how long RELATIVE to
                // its neighbours, and "how long" is the first question anybody asks of a zone.
                float advance = getStyle().getGeneralGroup().fontSize() * 0.6f;
                if ((text.length() + duration.length() + 2) * advance <= drawn - LABEL_PAD * 2f) {
                    text = text + "  " + duration;
                }
                label(ctx, font, text, left + LABEL_PAD, top, height, drawn - LABEL_PAD * 2f, textColor);
            }
        }

        // FRAME BOUNDARIES OVER THE SPANS, not under them: in a range most of the chart is spans, and a
        // line drawn first was hidden exactly where a boundary falls between two frames' zones.
        for (long at : boundaries) {
            float x = axis.xOf(at);
            if (x < 0f || x > trackWidth) continue;
            ctx.rect().at(x - 0.5f, 0f).size(1f, box.height())
                    .fillColor((textColor & 0x00FFFFFF) | 0x66000000).submit();
        }
    }

    private static String formatMillis(double millis) {
        if (millis >= 10d) return String.format("%.1fms", millis);
        if (millis >= 1d) return String.format("%.2fms", millis);
        return String.format("%.0f\u00b5s", millis * 1000d);
    }

    /** So a zone one pixel wide is still a mark rather than nothing. */
    private static final float MIN_SPAN_WIDTH = 1f;

    /** Space between rows, so depth reads as a stack of bars rather than one solid block. */
    private static final float ROW_GAP = 2f;

    private static final float RADIUS = 2.5f;

    /** Narrower than two corners, a span is drawn plain and merged per pixel. @see #paintTrack */
    private static final float SLIVER = 2f * RADIUS;

    /** Per depth, the pixel column the last sliver drawn on that row covered. Reused across paints. */
    private int[] lastSliverPixel = new int[16];

    private static int[] growPixels(int[] held, int depth) {
        int[] grown = Arrays.copyOf(held, Math.max(depth + 1, held.length * 2));
        Arrays.fill(grown, held.length, grown.length, Integer.MIN_VALUE);
        return grown;
    }

    private static final float LABEL_PAD = 4f;

    /** Below this a bar carries no label; it is a shape, and the table below names it. */
    private static final float MIN_LABEL_SPAN = 26f;

    /**
     * A stable colour for {@code name}: the category's hue, stepped slightly by the rest of the name.
     *
     * @see SpanTrack the class note on why the category, not the whole name, picks the hue
     */
    public static int colorOf(String name) {
        if (name == null || name.isEmpty()) return FAMILIES[0];
        String category = categoryOf(name);
        int family = FAMILIES[Math.floorMod(category.hashCode(), FAMILIES.length)];
        // A STEP, not a new hue: siblings in one subsystem must stay visibly one family and still be
        // told apart where they touch. Three steps either side is enough for that and no more.
        int step = Math.floorMod(name.hashCode(), 7) - 3;
        return step >= 0 ? lighten(family, step * 0.06f) : darken(family, -step * 0.06f);
    }

    /** What a zone's colour family is keyed on — {@code paint} for {@code paint:tree}. */
    public static String categoryOf(String name) {
        int colon = name.indexOf(':');
        int dot = name.indexOf('.');
        int cut = colon < 0 ? dot : dot < 0 ? colon : Math.min(colon, dot);
        return cut <= 0 ? name : name.substring(0, cut);
    }

    private static int lighten(int argb, float amount) {
        return mix(argb, 0xFFFFFFFF, amount);
    }

    private static int darken(int argb, float amount) {
        return mix(argb, 0xFF000000, amount);
    }

    private static int mix(int from, int to, float t) {
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (from & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /**
     * Ten families, mid-tone and slightly muted, so light text reads on every one of them.
     *
     * <p>Fixed rather than theme-derived: they must be distinguishable from each other first, and a
     * theme that narrowed their range would collapse a chart into one colour. What the theme owns is
     * everything around them — the track, the text, the ring and the gaps.</p>
     */
    private static final int[] FAMILIES = {
            0xFF4F7FD1, // blue
            0xFF8A63C9, // violet
            0xFF2F9E7A, // green
            0xFFC48A2C, // amber
            0xFFC9566E, // rose
            0xFF2C93A8, // teal
            0xFFB0609F, // orchid
            0xFF7D9440, // olive
            0xFFC56A3B, // orange
            0xFF5E6BC4, // indigo
    };
}
