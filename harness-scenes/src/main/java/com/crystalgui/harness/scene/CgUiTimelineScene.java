package com.crystalgui.harness.scene;

import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.render.UiRecorder;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.input.FocusPolicy;
import com.crystalgui.widget.display.CounterTrack;
import com.crystalgui.widget.display.FrameStripTrack;
import com.crystalgui.widget.display.SpanTrack;
import com.crystalgui.widget.display.TimelineAxis;
import dev.vfyjxf.taffy.style.FlexDirection;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The profiler window's two navigation surfaces, under the load they exist to survive.
 *
 * <p><b>Ten thousand spans and six hundred frames.</b> That is the gate: a chart built to find a
 * dropped frame that drops frames while being read is worse than no chart, so the number here is
 * deliberately larger than any real frame produces. The status line prints this scene's OWN frame
 * time, median and worst — read it while dragging, not while idle.</p>
 *
 * <ul>
 *   <li><b>Wheel</b> over the flame chart zooms about the pointer.</li>
 *   <li><b>Drag</b> over it pans.</li>
 *   <li><b>Click</b> a bar to select it; the selection is named in the status line with its source.</li>
 *   <li><b>Click</b> a frame bar to select a frame; <b>drag</b> across the strip to select a range.</li>
 *   <li><b>Left/Right</b> step the selected frame — the primary gesture for comparing neighbours.</li>
 *   <li><b>R</b> resets the zoom, <b>G</b> regenerates with a new seed.</li>
 * </ul>
 */
public class CgUiTimelineScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    private static final float SCALE = 2f;

    /** Larger than any real frame records, which is the whole point of a gate. */
    private static final int SPANS = 10_000;
    private static final int FRAMES = 600;

    /** One frame's worth of nanoseconds for the synthetic tree — 16ms, so the numbers read as real. */
    private static final long FRAME_NANOS = 16_000_000L;

    private static final String[] ZONE_NAMES = {
            "frame", "style:cascade", "style:match", "layout:sync", "layout:compute",
            "paint:tree", "paint:layer", "paint:text", "paint:sprite", "input:dispatch",
            "animation:tick", "settle", "svg:raster", "gpu:flush", "buffer:map",
    };

    private UIDocument document;
    private TimelineAxis axis;
    private SpanTrack spans;
    private FrameStripTrack strip;
    private CounterTrack drawcalls;
    private CounterTrack layers;

    private String status = "";
    private String selection = "nothing selected";

    /** A ring of this scene's own frame times, so the gate is read off the screen it gates. */
    private final double[] ownFrames = new double[240];
    private int ownIndex;
    private int ownCount;

    private long seed = 20260921L;

    @Override
    public void init(HarnessContext ctx) {
        document = new UIDocument().markFrameThread();
        document.boxes().setUiScale(SCALE);

        UIElement root = build();
        StyleGroup.defaultPipeline(root.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        document.append(root);
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        document.styles().addStylesheet(StyleSheet.parse(STYLES));

        generate();
    }

    private static final String STYLES = """
            .timeline-scene { background: #16181Dff; padding: 12; }
            .timeline-heading { color: #9AA4B2ff; font-size: 9; }
            spantrack   { color: #0E1014ff; border-color: #E6EAF2ff; font-size: 8; width: 100%; }
            framestrip  { color: #4E9A5Dff; border-color: #C7D2E0ff; width: 100%; }
            countertrack { color: #5AA9E6ff; border-color: #9AA4B2ff; font-size: 9; width: 100%; }
            """;

    private UIElement build() {
        UIElement root = new UIElement()
                .layout(l -> l.flexDirection(FlexDirection.COLUMN).gapAll(8))
                .setFocusPolicy(FocusPolicy.NONE);
        root.addClass("timeline-scene");

        strip = new FrameStripTrack();
        strip.setRowHeight(56f);
        strip.onSelected(index -> {
            selection = "frame " + index;
            drawcalls.showSelected(index);
            layers.showSelected(index);
            // A NEW FRAME IS A NEW TREE, which is what the window will do for real: every track
            // below the strip is re-fed from the selected frame rather than filtered in place.
            generateSpansFor(index);
        });
        strip.onRangeSelected(range ->
                selection = "frames " + range.from() + ".." + range.to() + " (" + range.count() + ")");
        root.append(strip);

        // TWO COUNTER ROWS ON THE STRIP'S OWN COLUMNS -- the alignment is the whole affordance: the
        // drawcalls spike sits directly under the frame bar it belongs to.
        drawcalls = new CounterTrack();
        drawcalls.onSelected(index -> {
            strip.select(index);
            selection = "frame " + index + " (from drawcalls)";
        });
        root.append(drawcalls);
        layers = new CounterTrack();
        layers.onSelected(strip::select);
        root.append(layers);

        axis = new TimelineAxis();
        spans = new SpanTrack(axis);
        spans.setRowHeight(13f);
        spans.onSelected(span -> selection = String.format("%s  %.3fms  %s",
                span.name(), span.millis(), span.source() == null ? "" : span.source()));
        root.append(spans);

        return root;
    }

    // ── The synthetic capture ───────────────────────────────────────────────────────────────

    private void generate() {
        Random random = new Random(seed);
        long[] wall = new long[FRAMES];
        FrameStripTrack.Health[] health = new FrameStripTrack.Health[FRAMES];
        for (int i = 0; i < FRAMES; i++) {
            // A believable distribution: mostly good, a scatter of hitches, one stall.
            double millis = 5d + random.nextDouble() * 3d;
            if (random.nextInt(24) == 0) millis += random.nextDouble() * 14d;
            if (i == FRAMES / 3) millis = 62d;
            wall[i] = (long) (millis * 1_000_000d);
            health[i] = millis > 33d ? FrameStripTrack.Health.BAD
                    : millis > 16.6d ? FrameStripTrack.Health.WARN
                    : FrameStripTrack.Health.GOOD;
        }
        strip.setFrames(wall, health);

        // Counters that TRACK the frame times, so the rows visibly agree with the bars above -- and
        // one deliberate gap per row, because an unrecorded frame must not read as a measured zero.
        long[] calls = new long[FRAMES];
        long[] layerCounts = new long[FRAMES];
        for (int i = 0; i < FRAMES; i++) {
            if (i % 97 == 5) {
                calls[i] = CounterTrack.ABSENT;
                layerCounts[i] = CounterTrack.ABSENT;
                continue;
            }
            calls[i] = 20L + (long) (wall[i] / 1_000_000d * 6d);
            layerCounts[i] = 2L + (long) (wall[i] / 1_000_000d * 0.8d);
        }
        drawcalls.setSeries("drawcalls", calls);
        layers.setSeries("layers", layerCounts);

        strip.select(FRAMES / 3);           // open on the stall: the frame anyone opened this for
    }

    /** Builds a nested tree of {@link #SPANS} spans for one frame. */
    private void generateSpansFor(int frame) {
        Random random = new Random(seed * 31 + frame);
        List<SpanTrack.Span> built = new ArrayList<>(SPANS);
        build(built, random, 0, 0L, FRAME_NANOS, SPANS);
        spans.setSpans(built);
        axis.setExtent(0L, FRAME_NANOS);
        axis.showAll();
    }

    /**
     * Splits {@code [from, to)} into children and recurses, so the tree is genuinely nested rather
     * than a flat row per depth — the pathological case for picking is one span inside another.
     */
    private void build(List<SpanTrack.Span> out, Random random, int depth, long from, long to, int budget) {
        if (budget <= 0 || depth > 11 || to - from < 2_000L) return;
        int children = Math.min(budget, 2 + random.nextInt(4));
        long span = (to - from) / children;
        for (int i = 0; i < children && out.size() < SPANS; i++) {
            long start = from + i * span;
            long end = start + (long) (span * (0.55d + random.nextDouble() * 0.4d));
            String name = ZONE_NAMES[random.nextInt(ZONE_NAMES.length)];
            out.add(new SpanTrack.Span(name, depth, start, end,
                    "harness/CgUiTimelineScene.java:" + (100 + random.nextInt(200))));
            build(out, random, depth + 1, start, end, (budget - children) / children);
        }
    }

    // ── Frame ───────────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        long began = System.nanoTime();

        document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

        UiRecorder paint = document.recorder();
        paint.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
        document.paint(paint);
        paint.endFrame();

        record((System.nanoTime() - began) / 1_000_000d);

        paint.text().draw().at(0, 0).text(status)
                .font(paint.getFont().atSize(13)).submit();
        paint.text().draw().at(0, 16).text(selection)
                .font(paint.getFont().atSize(13)).submit();

        if (frame.getFrameNumber() == 8) ctx.getArtifactService().requestCapture("startup");
    }

    private void record(double millis) {
        ownFrames[ownIndex] = millis;
        ownIndex = (ownIndex + 1) % ownFrames.length;
        ownCount = Math.min(ownCount + 1, ownFrames.length);
        if (ownCount < 8) return;
        double[] sorted = Arrays.copyOf(ownFrames, ownCount);
        Arrays.sort(sorted);
        status = String.format(
                "timeline — %d spans, %d frames | this scene: %.2fms now, %.2fms p50, %.2fms p99 | wheel zoom · drag pan · ←/→ step · R reset · G reseed",
                spans.spans().size(), strip.frames(), millis,
                sorted[sorted.length / 2], sorted[(int) (sorted.length * 0.99d)]);
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        if (event.pressed()) {
            switch (event.key()) {
                case CgKeyCodes.KEY_LEFT -> {
                    strip.step(-1);
                    return true;
                }
                case CgKeyCodes.KEY_RIGHT -> {
                    strip.step(1);
                    return true;
                }
                case CgKeyCodes.KEY_R -> {
                    axis.showAll();
                    return true;
                }
                case CgKeyCodes.KEY_G -> {
                    seed++;
                    generate();
                    return true;
                }
                default -> {
                }
            }
        }
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }

    @Override
    public void dispose() {
        document = null;
    }

    @Override
    public boolean isRunning() {
        return true;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }
}
