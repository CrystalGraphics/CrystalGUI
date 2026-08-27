package com.crystalgui.ui;

import com.crystalgui.style.sheet.StyleSheet;
import com.crystalgui.testsupport.UiTestBase;
import com.crystalgui.text.TextPoint;
import com.crystalgui.text.diagnostic.Diagnostic;
import com.crystalgui.text.view.RenderWhitespace;
import com.crystalgui.ui.elements.editor.TextEditor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * P6.1.7b — a frame must cost what is <b>on screen</b>, not what is in the document.
 *
 * <h3>Why a ratio and not a millisecond budget</h3>
 * <p>An absolute timing assertion is a flaky test on somebody else's machine. A <em>ratio</em> between two
 * documents measured back to back on the same machine in the same JVM is not: virtualisation means the
 * editor realises a screenful either way, so the large document should cost about the same as the small
 * one. It is the shape of the cost that is being pinned, and that is exactly the shape that broke.</p>
 *
 * <h3>What broke</h3>
 * <p>The harness went unresponsive after the view decorations landed, and two of the three causes were
 * per-frame work that scaled with the document rather than the viewport:</p>
 * <ul>
 *   <li>Indent guides asked {@code IndentLevels.guidesFor(doc, row, row, ...)} <b>once per visible
 *       row</b>. That form throws away the carry-forward the algorithm is built around, so every blank
 *       row restarted the search for the nearest content line above and below and rescanned the
 *       document — once per row, once per frame.</li>
 *   <li>{@code textOriginX()} read three values out of the cascade, and it is called for every line,
 *       guide, marker, caret and band. Measured at <b>78 style lookups per frame</b> on a 32-line
 *       document with every decoration switched off.</li>
 * </ul>
 */
public class EditorFrameCostTest extends UiTestBase {

    private TextEditor editor;
    private UIWindow window;

    private void build(int rows) {
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < rows; i++) {
            // Blank lines on purpose: they are the expensive case for indent guides, because a blank line
            // has no indent of its own and has to find the content lines either side of it.
            if (i % 4 == 3) document.append('\n');
            else document.append("    private static final int VALUE_").append(i).append(" = ").append(i).append(";\n");
        }
        editor = new TextEditor(document.toString());
        editor.layout(l -> l.width(400).height(300));
        editor.generalStyle(g -> g.fontSize(8f).lineHeight(1.25f));
        editor.setIndentGuidesVisible(true);
        editor.setRenderWhitespace(RenderWhitespace.BOUNDARY);

        UIElement root = new UIElement().layout(l -> l.width(400).height(400));
        root.addChild(editor);
        window = new UIWindow(Ui.of(root));
        window.getStyleEngine().addStylesheet(StyleSheet.DEFAULT);
        window.init(800, 600);
        settleFrames(5);
    }

    private void settleFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            editor.updateWindow();
            window.updateWithoutPainting();
        }
    }

    /** Steady-state nanoseconds per frame, after a warm-up long enough to be past class loading. */
    private long nanosPerFrame(int frames) {
        settleFrames(200);
        long start = System.nanoTime();
        settleFrames(frames);
        return (System.nanoTime() - start) / frames;
    }

    /** Nanoseconds per frame while the view is MOVING, which is a different question from a settled one. */
    private long nanosPerScrolledFrame(int frames, float pixelsPerFrame) {
        settleFrames(200);
        float top = 0f;
        long start = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            top += pixelsPerFrame;
            editor.setScrollImmediate(editor.getScrollLeft(), top);
            editor.updateWindow();
            window.updateWithoutPainting();
        }
        return (System.nanoTime() - start) / frames;
    }

    /** The same document with both per-frame decorations off. */
    private void buildPlain(int rows) {
        build(rows);
        editor.setIndentGuidesVisible(false);
        editor.setRenderWhitespace(RenderWhitespace.NONE);
        settleFrames(5);
    }

    /**
     * <b>Where a frame's time actually goes</b> — a measuring instrument, and it asserts nothing.
     *
     * <h3>Why nothing</h3>
     *
     * <p>These numbers move with test order. One comparison measured 727µs of avoidable work in an
     * isolated run and 978µs in one where ninety other tests had warmed the JVM first — the same code
     * both times. A threshold a JIT state can beat is not a regression guard, and this file's header
     * already says an absolute timing assertion is flaky on somebody else's machine. So this reports,
     * under {@code -Pbench}; the ratio test below is what ships.</p>
     *
     * <h3>What it found, and the two wrong answers on the way</h3>
     *
     * <p>Scrolling was reported as feeling like it "updates at 20 ticks". It is not a tick — wheel
     * deltas accumulate and emit once per frame, so the input path is already per-frame. It is cost: a
     * <b>settled</b> frame here, with nothing typed and nothing scrolled, spends over a millisecond
     * before any GL work at all.</p>
     *
     * <p>Two suspects were measured and cleared, which is the useful part of writing this down.
     * <b>The pooled element writes</b>: skipping them entirely changed nothing. <b>The two viewport
     * walks</b> ({@code guidesFor}, {@code activeGuideFor}): memoising them on their own inputs took
     * recomputes from 400 to 0 over 400 settled frames and moved the time by less than the noise — so
     * the memo was reverted rather than kept for a win that was not there.</p>
     *
     * <p>What did move it was hoisting {@code topOfViewLine} out of the per-line loop: <b>835µs to
     * 357µs</b> for the guides alone. That method is
     * {@code textOriginY() + viewLine * lineHeight() - scrollTop}, and both of those read the cascade —
     * once per view line, in <em>every</em> view part, on every frame. It is the same defect this file's
     * header records for {@code textOriginX()} ("78 style lookups per frame"), still present on the
     * other axis. The fix is a per-frame metric cache on the editor, which is its own change.</p>
     */
    /**
     * <b>Does a long scroll get worse as it goes?</b> — reported as "fast continuous scrolling for 3-5
     * seconds feels choppy", which is the shape of something that degrades rather than something slow.
     *
     * <p>Bucketed rather than averaged, because an average over a run that starts fast and ends slow
     * reports a middle that never happened.</p>
     */
    /**
     * <b>A frame in a long document costs the same wherever you have scrolled to.</b>
     *
     * <p>This is the one thing about scrolling that can be asserted without an absolute timing, and it is
     * the defect that was actually reported: not that scrolling was slow, but that "fast continuous
     * scrolling for 3-5 seconds" got choppy — i.e. it degraded as it travelled. On a 20,000-row document
     * a frame cost <b>5.2ms near the top and 18.8ms three thousand rows down</b>, a ramp straight through
     * the frame budget and into the ~27fps the report described.</p>
     *
     * <h3>What it was</h3>
     *
     * <p>{@code Rope.line(row)} was {@code slice(start, end).toString()} — and {@code Rope.slice} BUILDS
     * a document: two splits, each rebuilding a spine through {@code concat}, a whole tree of internal
     * nodes and summaries allocated to hand back one line and then discarded. A sampling profiler over the
     * scrolling frame put {@code Rope.split} at the top, above every layout and paint method in the
     * application. It is reached per row, per view part, per frame.</p>
     *
     * <p>The ramp came from the caller. {@code IndentGuidesPart} asks
     * {@code IndentLevels.activeGuideFor(document, caretRow, firstRow, lastRow, ..)}, and that walks
     * outward from the caret a row at a time until it leaves {@code [firstRow, lastRow]}. The caret does
     * not move while you scroll, so the walk runs from the caret all the way down to the viewport —
     * <em>O(scroll position)</em> line reads a frame. The comment at the call site claims it "costs the
     * viewport rather than the document"; the bound stops the walk once it ARRIVES at the window, it never
     * clamps where it starts. That walk is a faithful port and is left alone: with reads no longer doing
     * tree surgery it is cheap, and the ramp is gone.</p>
     *
     * <h3>Why a ratio, and why not the first bucket</h3>
     *
     * <p>An absolute threshold is a coin toss on somebody else's machine — this file's header says so and
     * {@code reportWhereAFrameGoes} is opt-in for that reason. A ratio calibrates itself. The comparison
     * skips the FIRST bucket deliberately: it carries the JIT warmup and reads high (4.3ms against the
     * 2.6ms the run settles to), which would mask exactly the regression this guards. Generous headroom —
     * it was 3.3x when broken and is ~1.0x now, so 2x fails the bug and forgives the noise.</p>
     */
    @Test
    public void scrollingDoesNotGetSlowerTheFurtherYouGo() {
        build(20000);
        settleFrames(200);

        // SEEK to a depth, then measure ordinary 30px scrolling there. Travelling the whole way would
        // need ~1200 frames to reach the depth the ramp is visible at; what is being asserted is the cost
        // of a frame AT a depth, and that can be sampled directly.
        long[] buckets = new long[5];
        for (int bucket = 0; bucket < buckets.length; bucket++) {
            float top = bucket * 60_000f;
            editor.setScrollImmediate(0f, top);
            for (int i = 0; i < 3; i++) {
                editor.updateWindow();
                window.updateWithoutPainting();
            }
            long start = System.nanoTime();
            for (int i = 0; i < 60; i++) {
                top += 30f;
                editor.setScrollImmediate(editor.getScrollLeft(), top);
                editor.updateWindow();
                window.updateWithoutPainting();
            }
            buckets[bucket] = (System.nanoTime() - start) / 60;
        }

        long warm = buckets[1];
        long deep = buckets[buckets.length - 1];
        assertTrue("a frame at scrollTop " + ((buckets.length - 1) * 60_000) + " cost " + deep / 1000
                        + "us against " + warm / 1000 + "us at 60000 -- scrolling degrades with depth",
                deep <= warm * 2);
    }

    /**
     * A poor-man's sampling profiler over the scrolling frame, comparing an EARLY window against a LATE
     * one. Written after three plausible causes were guessed and measured wrong in a row -- a sampler
     * names the method instead of asking me to.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void reportWhatGrowsDuringALongScroll() {
        Assume.assumeTrue("opt-in: run with -Pbench",
                Boolean.parseBoolean(System.getProperty("cgui.test.bench")));

        build(20000);
        settleFrames(200);

        final Thread painter = Thread.currentThread();
        final Map<String, Integer> early = new HashMap<>();
        final Map<String, Integer> late = new HashMap<>();
        final Map<String, Integer>[] into = new Map[] {early};
        final boolean[] sampling = {true};
        Thread sampler = new Thread(() -> {
            while (sampling[0]) {
                for (StackTraceElement frame : painter.getStackTrace()) {
                    String name = frame.getClassName() + "." + frame.getMethodName();
                    if (!name.startsWith("com.crystalgui")) continue;
                    into[0].merge(name, 1, Integer::sum);
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        sampler.start();

        float top = 0f;
        for (int i = 0; i < 1200; i++) {
            if (i == 1000) into[0] = late;
            top += 30f;
            editor.setScrollImmediate(editor.getScrollLeft(), top);
            editor.updateWindow();
            window.updateWithoutPainting();
        }
        sampling[0] = false;

        System.out.printf("%n  Sampled: frames 0-999 (early) vs 1000-1199 (late). 5x the frames early,%n");
        System.out.printf("  so a method whose cost is FLAT should read early ~5x late.%n");
        Set<String> names = new HashSet<>(early.keySet());
        names.addAll(late.keySet());
        names.stream()
                .sorted((a, b) -> Integer.compare(
                        late.getOrDefault(b, 0) * 5 - early.getOrDefault(b, 0),
                        late.getOrDefault(a, 0) * 5 - early.getOrDefault(a, 0)))
                .limit(24)
                .forEach(name -> System.out.printf("    early %5d   late %5d   %s%n",
                        early.getOrDefault(name, 0), late.getOrDefault(name, 0), name));
        System.out.println();
    }

    @Test
    public void reportLongScrollDegradation() {
        Assume.assumeTrue("opt-in: run with -Pbench",
                Boolean.parseBoolean(System.getProperty("cgui.test.bench")));

        build(20000);
        settleFrames(200);

        final int buckets = 8;
        final int framesPer = 150;
        final float pixels = 30f;
        System.out.printf("%n  20000-row document, %d frames of %.0fpx each, no GL%n",
                buckets * framesPer, pixels);
        float top = 0f;
        for (int bucket = 0; bucket < buckets; bucket++) {
            long realise = 0;
            long relayout = 0;
            for (int i = 0; i < framesPer; i++) {
                top += pixels;
                editor.setScrollImmediate(editor.getScrollLeft(), top);
                long t0 = System.nanoTime();
                editor.updateWindow();
                long t1 = System.nanoTime();
                window.updateWithoutPainting();
                realise += t1 - t0;
                relayout += System.nanoTime() - t1;
            }
            System.out.printf("    frames %4d-%4d   scrollTop %7.0f   updateWindow %6.0f us   window %6.0f us   elements %5d%n",
                    bucket * framesPer, (bucket + 1) * framesPer - 1, top,
                    realise / 1000.0 / framesPer, relayout / 1000.0 / framesPer, countElements(editor));
        }
    }

    /**
     * The same frame count, but oscillating over a SMALL window instead of travelling.
     *
     * <p>Distinguishes cost that grows with rows VISITED (a cache filling, new rows being measured)
     * from cost that grows with frames ELAPSED (something leaking per frame regardless of where).</p>
     */
    @Test
    public void reportOscillatingScroll() {
        Assume.assumeTrue("opt-in: run with -Pbench",
                Boolean.parseBoolean(System.getProperty("cgui.test.bench")));

        build(20000);
        settleFrames(200);
        System.out.printf("%n  20000 rows, oscillating between scrollTop 0 and 900%n");
        float top = 0f;
        float direction = 30f;
        for (int bucket = 0; bucket < 8; bucket++) {
            long start = System.nanoTime();
            for (int i = 0; i < 150; i++) {
                top += direction;
                if (top > 900f || top < 0f) { direction = -direction; top += direction; }
                editor.setScrollImmediate(editor.getScrollLeft(), top);
                editor.updateWindow();
                window.updateWithoutPainting();
            }
            System.out.printf("    frames %4d-%4d   %6.0f us/frame%n",
                    bucket * 150, (bucket + 1) * 150 - 1, (System.nanoTime() - start) / 150 / 1000.0);
        }
    }

    /** Every element in a subtree, so "the tree is growing" is measured rather than inferred. */
    private static int countElements(UIElement from) {
        int total = 1;
        for (UIElement child : from.getChildren()) total += countElements(child);
        return total;
    }

    @Test
    public void reportWhereAFrameGoes() {
        Assume.assumeTrue("opt-in: run with -Pbench",
                Boolean.parseBoolean(System.getProperty("cgui.test.bench")));

        build(4000);
        long both = nanosPerFrame(200);
        long bothScrolled = nanosPerScrolledFrame(200, 3f);

        build(4000);
        editor.setRenderWhitespace(RenderWhitespace.NONE);
        settleFrames(5);
        long guidesOnly = nanosPerFrame(200);

        buildPlain(4000);
        long neither = nanosPerFrame(200);
        long neitherScrolled = nanosPerScrolledFrame(200, 3f);

        System.out.printf("%n  4000-row document, no GL in any of these%n");
        System.out.printf("    guides + whitespace   settled %6.0f us   scrolling %6.0f us%n",
                both / 1000.0, bothScrolled / 1000.0);
        System.out.printf("    guides only           settled %6.0f us%n", guidesOnly / 1000.0);
        System.out.printf("    neither               settled %6.0f us   scrolling %6.0f us%n%n",
                neither / 1000.0, neitherScrolled / 1000.0);
    }

    @Test
    public void aFrameCostsWhatIsOnScreenNotWhatIsInTheDocument() {
        build(40);
        long small = nanosPerFrame(300);

        build(4000);
        long large = nanosPerFrame(300);

        // Deliberately generous. The point is to catch work that scales with the DOCUMENT — a hundredfold
        // more rows here — not to police a few per cent. Before the fix this ratio was unbounded.
        assertTrue("a 4000-row document costs " + (large / 1000) + "us/frame against "
                        + (small / 1000) + "us/frame for 40 rows: per-frame work is scaling with the "
                        + "document, not the viewport",
                large < small * 8L + 200_000L);
    }

    /**
     * <b>...and nor does it cost what is in the PROBLEMS list.</b>
     *
     * <h3>The one part that is honestly O(document), and what that cost</h3>
     *
     * <p>{@code ErrorStripePart} shows every problem in the file rather than the ones on screen — that is
     * what the groove is for, and it is the single place virtualisation cannot apply. It was placing every
     * mark on every frame, and {@code applySeverity} called {@code removeClass} three times and
     * {@code addClass} once on each: four {@code invalidateStyleMatch()} calls per problem per frame, so
     * the cascade re-ran selector matching over the whole list sixty times a second to arrive at the
     * classes those marks already had.</p>
     *
     * <p>Measured on a 2000-row document: <b>524µs a frame with no problems, 9.6ms with 500 and 33.7ms
     * with 2000</b> — about 18µs per problem per frame. Reported as a decompiled Minecraft class taking
     * 120fps to 55, which is what a few hundred unresolvable references in reconstructed code buys. With
     * each slot remembering what it already shows it is 1.8ms at 500 and 5.0ms at 2000, and the marginal
     * cost is 2.5µs.</p>
     *
     * <p>Generous for the reason the test above is: this is here to catch the class of defect — per-frame
     * work proportional to the problem count — and not to police a marginal cost. Before the fix this
     * ratio was 18x.</p>
     */
    @Test
    public void aFrameDoesNotCostWhatIsInTheProblemsList() {
        build(2000);
        long clean = nanosPerFrame(300);

        List<Diagnostic> problems = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            int row = i % 2000;
            problems.add(Diagnostic.error(new TextPoint(row, 0), new TextPoint(row, 3), "e" + i));
        }
        editor.diagnostics().setAll(problems);
        settleFrames(10);
        long marked = nanosPerFrame(300);

        assertTrue("500 problems cost " + (marked / 1000) + "us/frame against " + (clean / 1000)
                        + "us/frame with none: the error stripe is re-placing every mark every frame",
                marked < clean * 6L + 200_000L);
    }
}
