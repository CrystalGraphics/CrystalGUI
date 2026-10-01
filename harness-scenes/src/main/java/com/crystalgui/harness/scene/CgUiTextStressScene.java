package com.crystalgui.harness.scene;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.harness.trace.TraceReport;
import com.crystalgraphics.platform.input.CgSystemInput;
import com.crystalgui.style.StyleGroup;
import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.ui.dom.UIElement;
import com.crystalgui.ui.dom.UIDocument;
import com.crystalgui.widget.text.UIText;
import com.crystalgui.ui.input.FocusPolicy;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Load benchmark for {@code UIText}: {@value #LABEL_COUNT} labels, changing their text every frame.
 *
 * <h3>Why three update patterns and not one</h3>
 * <p>"UI text is slow" is not actionable — the cost could be shaping, re-wrapping, Taffy reflow, or
 * simply drawing more glyphs, and those have completely different fixes. Each mode holds all but one
 * of those constant, so the <em>differences between modes</em> localise the cost rather than merely
 * measuring it:
 *
 * <ul>
 *   <li>{@code STATIC} — text never changes. The floor: what already-shaped, already-wrapped labels
 *       cost to lay out and draw. Everything above this is the price of <em>changing</em> text
 *       rather than of having it.</li>
 *   <li>{@code SAME_LENGTH} — every label's text changes every frame, to a string of identical
 *       length and script. Invalidates the retained {@code CgShapedParagraph} (BiDi + HarfBuzz
 *       rerun) while leaving the measured box the same size, so Taffy has no reason to reflow.
 *       <b>STATIC → SAME_LENGTH is the cost of re-shaping.</b></li>
 *   <li>{@code VARYING_LENGTH} — text changes length, so each label pushes a different
 *       {@code !important} width/height and Taffy must revisit the tree.
 *       <b>SAME_LENGTH → VARYING_LENGTH is the cost of reflow.</b></li>
 * </ul>
 *
 * <p>The first run of this answered the question it was built for, and not the way the question was
 * asked: STATIC was already ~24 ms while the two deltas were ~1.4 ms and ~1.0 ms. Re-shaping a
 * hundred labels every frame is not what costs — <em>having</em> a hundred labels is. Keep that in
 * mind when reading a result: the interesting number is usually STATIC, not the deltas.
 *
 * <p>Runs for {@value #TOTAL_SECONDS} seconds total, {@value #MODE_SECONDS} per mode (the first
 * {@value #WARMUP_SECONDS} of each discarded as warmup), then writes a per-frame CSV and shuts down.
 *
 * <p>Deliberately separate from {@code CgUiTextScene}: that is a correctness fixture with a handful
 * of elements and specific visual cases (fallback fonts, wrapping, binding). Folding a stress panel
 * into it would make both jobs worse.
 */
public class CgUiTextStressScene implements InteractiveSceneLifecycle, CgSystemInput.Keyboard, CgSystemInput.Mouse {

    /** Logical-to-surface scale, as the harness's other new-engine scenes use. */
    private static final float SCALE = 2f;

    private static final Logger LOGGER = Logger.getLogger(CgUiTextStressScene.class.getName());

    /**
     * Far past what fits on screen, deliberately. The point here is the <em>shaping</em> load, and
     * {@code setTextMs} isolates that regardless of how much drawing happens around it.
     */
    private static final int LABEL_COUNT = 1000;

    /** Every label is exactly this many characters, so shaping cost per label is comparable. */
    private static final int LABEL_CHARS = 20;

    // Longer than the 100-label configuration needed: the per-element material-bind floor scales
    // with LABEL_COUNT (~0.2 ms each), so the frame rate drops to single digits and a 3-second mode
    // would yield ~15 frames. The bind cost is not what is being measured -- it just sets how long
    // we must run to collect a usable sample of it.
    /**
     * When false the scene runs layout only, never painting the labels.
     *
     * <p>Drawing costs one material bind per element (~0.2 ms each), so at {@value #LABEL_COUNT}
     * labels rendering contributes ~200 ms/frame and buries the shaping signal under something that
     * has nothing to do with text layout. Turning it off isolates shape + measure + Taffy, which is
     * what this benchmark exists to measure, and raises the frame rate enough to collect a real
     * sample rather than a dozen frames.
     *
     * <p>Set true to measure the full painted path instead — that is a CrystalGUI batching question,
     * not a text-layout one.
     */
    private static final boolean DRAW_LABELS = false;

    private static final double MODE_SECONDS = 3;
    private static final double WARMUP_SECONDS = 2.0;
    private static final double TOTAL_SECONDS = MODE_SECONDS * 3;

    private enum Mode {
        STATIC("text never changes (the cached floor)"),
        SAME_LENGTH("reshape every frame, no size change"),
        VARYING_LENGTH("reshape + Taffy reflow every frame");

        final String description;

        Mode(String description) {
            this.description = description;
        }
    }

    private static final String STYLE_SHEET = """
            .stress-cell  { padding: 2px; }
            .stress-label { color: #FFFFFF; font-size: 12; }
            """;

    private static final String[] CSV_HEADER = {
            "frame", "elapsedSec", "mode", "measured", "frameDtMs",
            "setTextMs", "paintFrameMs",
            "resolveGlyphsMs", "submitQuadsMs", "quadLoopMs", "sortKeysMs",
            "matDoBindMs", "dbStateSaveMs", "glFlushMs", "visScanMs",
            "glyphCount", "materialTransitions", "glFlushCount",
            "shapeRunsMs", "shapeReshaperMs", "shapeEllipsisMs", "shapeBidiMs", "shapeCollectMs", "shapeResolveRunsMs", "shapeHarfbuzzMs",
            "hbCreateMs", "hbFillMs", "hbShapeMs", "hbReadBackMs", "hbDestroyMs",
            "paragraphLayoutHit", "paragraphLayoutMiss"
    };

    private HarnessContext ctx;
    private UIDocument document;
    private final List<UIText> labels = new ArrayList<>(LABEL_COUNT);

    private Mode mode = Mode.STATIC;
    private double modeStartedAt = -1.0;
    private final List<Double> samples = new ArrayList<>();
    private final List<String> summary = new ArrayList<>();
    /** One profiler snapshot per mode; an accumulated tree across all three would hide the point. */
    private final List<String> trees = new ArrayList<>();
    private final List<String> csvRows = new ArrayList<>();
    private boolean warmupCleared = false;
    private boolean running = true;

    @Override
    public void init(HarnessContext ctx) {
        this.ctx = ctx;
        CgTrace.enable("crystalgraphics");
        this.document = new UIDocument().markFrameThread();
        this.document.boxes().setUiScale(SCALE);
        UIElement sceneRoot = buildStressPanel();
        // THE ROOT FILLS THE DOCUMENT. On the old engine the scene's root WAS the window's
        // root and took the window's size; here the DOCUMENT is the root and this is an
        // ordinary child, which sizes to its content -- so without this the scene lays out
        // at nothing and draws nothing. DEFAULT origin, so a scene sheet still wins.
        StyleGroup.defaultPipeline(sceneRoot.getStyle().getLayoutGroup(),
                l -> l.widthPercent(100f).heightPercent(100f));
        this.document.append(sceneRoot);
        document.styles().addStylesheet(StyleSheet.DEFAULT);
        document.styles().addStylesheet(StyleSheet.parse(STYLE_SHEET));
        csvRows.add(String.join(",", CSV_HEADER));
    }

    private UIElement buildStressPanel() {
        UIElement root = new UIElement()
                .layout(l -> l
                        .paddingAll(6)
                        .flexDirection(FlexDirection.ROW)
                        .flexWrap(FlexWrap.WRAP)
                        .alignItems(AlignItems.FLEX_START)
                        .gapAll(4))
                .setFocusPolicy(FocusPolicy.NONE);

        for (int i = 0; i < LABEL_COUNT; i++) {
            UIElement cell = new UIElement();
            cell.addClass("stress-cell");
            UIText label = new UIText(staticTextFor(i));
            label.addClass("stress-label");
            cell.append(label);
            root.append(cell);
            labels.add(label);
        }
        return root;
    }

    // Property.set is equality-suppressing, so STATIC performs no invalidation at all rather than
    // re-setting an identical value and relying on an early-out further down.

    /** Exactly {@value #LABEL_CHARS} characters: "Item 0042 Lorem ipsu". */
    private static String textOfExactLength(int value) {
        String s = String.format(Locale.ROOT, "Item %04d Lorem ipsum dolor", value);
        return s.substring(0, LABEL_CHARS);
    }

    private static String staticTextFor(int index) {
        return textOfExactLength(index);
    }

    /** Identical character count and script every frame, so the measured box size cannot change. */
    private static String sameLengthTextFor(int index, long frame) {
        return textOfExactLength((int) ((index + frame) % 10000));
    }

    /**
     * Length swings around {@link #LABEL_CHARS}, forcing a different measured width and therefore a
     * Taffy reflow. Kept centred on the same size so this mode differs from SAME_LENGTH only in
     * whether the box has to change, not in how much text there is to shape.
     */
    private static String varyingLengthTextFor(int index, long frame) {
        int value = (int) ((index + frame) % 10000);
        int len = LABEL_CHARS - 8 + (int) ((index + frame) % 17); // 12..28 chars
        String s = String.format(Locale.ROOT, "Item %04d Lorem ipsum dolor sit amet", value);
        return s.substring(0, Math.min(len, s.length()));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        double now = frame.getElapsedTime();
        if (modeStartedAt < 0) modeStartedAt = now;


        long f = frame.getFrameNumber();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "uiText.setText")) {
            for (int i = 0; i < labels.size(); i++) {
                switch (mode) {
                    case STATIC -> { /* deliberately nothing */ }
                    case SAME_LENGTH -> labels.get(i).setText(sameLengthTextFor(i, f));
                    case VARYING_LENGTH -> labels.get(i).setText(varyingLengthTextFor(i, f));
                }
            }
        }

        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, "document.paintFrame")) {
            if (DRAW_LABELS) {
                document.frame(frame.getDeltaTime(), ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);

                // AND THE PAINT. `paintFrame()` did both; `frame()` only advances, so a scene that
                // lost this half advanced perfectly and drew nothing.
                CgUiPaintContext paintContext = document.paintContext();
                paintContext.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());
                document.paint(paintContext);
                paintContext.endFrame();
            } else {
                document.update(ctx.getScreenWidth() / SCALE, ctx.getScreenHeight() / SCALE);
            }
        }

        double inMode = now - modeStartedAt;
        boolean measured = inMode >= WARMUP_SECONDS;

        // Snapshot then reset every frame, so every CSV row and every printed tree is ONE frame's
        // cost rather than an accumulation since the last reset. Accumulated rows are readable only
        // if you also know how many frames they span, which is exactly the kind of footgun that
        // turns a profile into a wrong conclusion.
        TraceReport report = TraceReport.lastFrame();
        recordRow(frame, measured, report);
        if (measured) samples.add(frame.getDeltaTime() * 1000.0);

        boolean lastFrameOfMode = inMode >= MODE_SECONDS;
        if (lastFrameOfMode) advanceMode(report);

        var context = document.paintContext();
        context.text().draw().at(0, 0)
                .text(mode.name() + (DRAW_LABELS ? " (painted) — " : " (layout only) — ") + mode.description
                        + String.format(Locale.ROOT, "  [%.1f/%.1fs]", now, TOTAL_SECONDS))
                .font(context.getFont().atSize(14)).submit();
        // Between frames this draws at once, on top; left queued it would flush inside the next frame's recording.
        context.flush();
    }

    private void advanceMode(TraceReport report) {
        summary.add(formatRow(mode, samples));
        System.out.println("[uitext-stress] " + summary.get(summary.size() - 1));
        trees.add("=== " + mode.name() + " — " + mode.description + " ===" + System.lineSeparator()
                + (report == null ? "(profiler disabled)" : report.format()));

        Mode[] all = Mode.values();
        int next = mode.ordinal() + 1;
        if (next >= all.length) {
            dump();
            running = false;
            return;
        }
        mode = all[next];
        modeStartedAt = -1.0;
        warmupCleared = false;
        samples.clear();
    }

    private void recordRow(FrameInfo frame, boolean measured, TraceReport report) {
        if (report == null) return;
        csvRows.add(String.format(Locale.ROOT,
                "%d,%.3f,%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.0f,%d,%d",
                frame.getFrameNumber(), frame.getElapsedTime(), mode.name(), measured ? 1 : 0,
                frame.getDeltaTime() * 1000.0,
                scope(report, "uiText.setText"),
                scope(report, "document.paintFrame"),
                scope(report, "resolveGlyphs"),
                scope(report, "submitBatchedQuads"),
                scope(report, "quadLoop"),
                scope(report, "sortKeys"),
                scope(report, "material.doBind"),
                scope(report, "doBind.stateSave"),
                scope(report, "glFlush"),
                scope(report, "visibilityScan"),
                sample(report, "draw.glyphCount"),
                counter(report, "materialTransition"),
                counter(report, "glFlush.count"))
                + String.format(Locale.ROOT, ",%.3f,%.3f,%.3f,%.3f,%.3f",
                scope(report, "shape.runs"),
                scope(report, "shape.reshaper"),
                scope(report, "shape.ellipsis"),
                scope(report, "shape.bidi"),
                scope(report, "shape.collectRuns"))
                + String.format(Locale.ROOT, ",%.3f,%.3f",
                scope(report, "shape.resolveRuns"),
                scope(report, "shape.harfbuzz"))
                + String.format(Locale.ROOT, ",%.3f,%.3f,%.3f,%.3f,%.3f",
                scope(report, "hb.bufferCreate"),
                scope(report, "hb.bufferFill"),
                scope(report, "hb.shape"),
                scope(report, "hb.readBack"),
                scope(report, "hb.bufferDestroy"))
                + String.format(Locale.ROOT, ",%d,%d",
                counter(report, "paragraphLayout.hit"),
                counter(report, "paragraphLayout.miss")));
    }

    /** Totals a scope by bare name — UI text draws do not sit under one fixed parent path. */
    private static double scope(TraceReport report, String name) {
        double total = 0;
        for (TraceReport.ScopeEntry e : report.scopes()) {
            if (e.name().equals(name)) total += e.totalNanos() / 1_000_000.0;
        }
        return total;
    }

    private static double sample(TraceReport report, String name) {
        var s = report.samples().get(name);
        return s == null ? 0 : s.last();
    }

    private static long counter(TraceReport report, String name) {
        Long v = report.counters().get(name);
        return v == null ? 0L : v;
    }

    private static String formatRow(Mode mode, List<Double> samples) {
        if (samples.isEmpty()) return mode.name() + " — no measured frames";
        double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double sum = 0;
        for (double v : sorted) sum += v;
        int n = sorted.length;
        return String.format(Locale.ROOT,
                "%-15s n=%-4d median %7.2f ms | mean %7.2f | p99 %7.2f | ~%4.1f fps",
                mode.name(), n, sorted[n / 2], sum / n,
                sorted[(int) (n * 0.99)], 1000.0 / sorted[n / 2]);
    }

    private void dump() {
        System.out.println();
        System.out.println("=== UIText stress: " + LABEL_COUNT + " labels ===");
        for (String row : summary) System.out.println("  " + row);
        System.out.println();
        System.out.println("  STATIC -> SAME_LENGTH  = cost of re-shaping " + LABEL_COUNT + " labels/frame");
        System.out.println("  SAME_LENGTH -> VARYING = cost of Taffy reflow on top of that");
        System.out.println();
        for (String tree : trees) {
            System.out.println(tree);
            System.out.println();
        }

        File dir = new File(ctx.getOutputDir());
        if (!dir.isDirectory() && !dir.mkdirs()) {
            LOGGER.warning("[uitext-stress] could not create " + dir);
            return;
        }
        File csv = new File(dir, "uitext-stress-profile.csv");
        try (PrintWriter w = new PrintWriter(new FileWriter(csv))) {
            for (String row : csvRows) w.println(row);
            LOGGER.info("[uitext-stress] wrote " + csvRows.size() + " rows to " + csv.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warning("[uitext-stress] failed writing CSV: " + e.getMessage());
        }
    }

    @Override
    public void dispose() {
        document = null;
        labels.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return true;
    }

    @Override
    public boolean consumeKeyboardEvent(CgSystemInput.Keyboard.Event event) {
        return document.input().consumeKeyboardEvent(event);
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        return document.input().consumeMouseEvent(event);
    }
}
