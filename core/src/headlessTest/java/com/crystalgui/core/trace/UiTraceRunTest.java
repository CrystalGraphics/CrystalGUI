package com.crystalgui.core.trace;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceLog;
import com.crystalgraphics.trace.CgTraceReport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T4 and T5 end to end on the CrystalGUI side: a run writes a directory, the frame thread does no I/O,
 * and the trace exports to a file a viewer can open.
 *
 * <p>The artefacts are left in {@code core/build/trace-sample/latest/} on purpose, so the export can be
 * opened in {@code ui.perfetto.dev} by hand — the one check no test can make for itself.</p>
 */
public class UiTraceRunTest {

    /** Under build/, because these are artefacts of a test run and nothing should keep them. */
    private static final Path ROOT = Paths.get("build", "trace-sample");

    @Before
    public void fresh() throws Exception {
        CgTrace.resetForTesting();
        CgTraceLog.resetForTesting();
        if (Files.isDirectory(ROOT)) {
            try (Stream<Path> walk = Files.walk(ROOT)) {
                List<Path> all = walk.toList();
                for (int i = all.size() - 1; i >= 0; i--) Files.deleteIfExists(all.get(i));
            }
        }
        System.setProperty(UiTrace.DIR_PROPERTY, ROOT.toString());
    }

    @After
    public void closed() {
        System.clearProperty(UiTrace.DIR_PROPERTY);
        CgTraceLog.resetForTesting();
        CgTrace.resetForTesting();
    }

    /**
     * A run of frames with phases, counters and a chain.
     *
     * <p>Through the calls a real frame makes — the host's boundary, stamped phases, per-frame counts and
     * a chain — into the ring and out to a file. Counts are totalled per frame and written at the next
     * boundary, so a test that skipped {@link UiTrace#frameBegin()} would quietly record none.</p>
     */
    private void record() {
        UiTrace.useDirectoryProperty();
        FrameStats.get().hold();
        long chain = CgTrace.spanBegin(UiTrace.FLOW, "open file");
        for (int i = 0; i < 120; i++) {
            UiTrace.frameBegin();
            long paint = CgTrace.stamp(UiTrace.FRAME);
            burn();
            CgTrace.zoneDone(UiTrace.FRAME, "paint:tree", paint);
            long layout = CgTrace.stamp(UiTrace.FRAME);
            burn();
            CgTrace.zoneDone(UiTrace.FRAME, "frame:layout", layout);
            CgTrace.add(UiTrace.FRAME, "drawcalls", 31);
            CgTrace.add(UiTrace.FRAME, "layers", 17);
            UiTrace.frameEnd();
        }
        CgTrace.spanEnd(chain);
        UiTrace.frameBegin();
    }

    /** A little real work, so a zone has a duration the clock can see. */
    private static void burn() {
        long until = System.nanoTime() + 30_000L;
        while (System.nanoTime() < until) {
            // deliberate
        }
    }

    @Test
    public void aRunWritesItsOwnDirectoryAndNotTheGameLog() throws Exception {
        record();
        UiTrace.writeMeta();
        Path exported = UiTrace.export();
        FrameStats.get().release();
        CgTraceLog.stop();

        Path dir = ROOT.resolve(CgTraceLog.LATEST);
        assertTrue("no run directory", Files.isDirectory(dir));
        assertTrue("no trace.log", Files.isRegularFile(dir.resolve("trace.log")));
        assertTrue("no meta.json", Files.isRegularFile(dir.resolve("meta.json")));
        assertNotNull("nothing exported", exported);
        assertTrue("no trace.json", Files.isRegularFile(dir.resolve("trace.json")));

        // THE T4 GATE: what a run records reaches its own directory rather than the game console. The
        // chain is a span, so it is in the export; trace.log carries slow frames, and these are not.
        String json = new String(Files.readAllBytes(dir.resolve("trace.json")), StandardCharsets.UTF_8);
        assertTrue("the chain is not in the export", json.contains("open file"));
    }

    @Test
    public void theRunWritesTheReportAnAgentReads() throws Exception {
        record();
        Path written = UiTrace.writeReport(CgTraceReport.Tier.VERDICT);
        FrameStats.get().release();
        CgTraceLog.stop();

        assertNotNull("no report written", written);
        String report = new String(Files.readAllBytes(written), StandardCharsets.UTF_8);
        assertTrue(report, report.contains("VERDICT"));
        assertTrue("no zone tree", report.contains("paint:tree"));
        // JUMPABLE: the line that opened the zone, which is the difference between a fact and a step.
        assertTrue("no source location", report.contains("UiTraceRunTest.java:"));
        assertTrue("no counters", report.contains("drawcalls=31"));
        // TIERED: the verdict is read first and must stay readable.
        assertTrue("the verdict ran to " + report.split("\n", -1).length + " lines",
                report.split("\n", -1).length <= 25);
    }

    @Test
    public void theMetadataSaysWhatWasNotRecording() throws Exception {
        record();
        UiTrace.writeMeta();
        FrameStats.get().release();
        CgTraceLog.stop();

        String meta = new String(Files.readAllBytes(
                ROOT.resolve(CgTraceLog.LATEST).resolve("meta.json")), StandardCharsets.UTF_8);
        assertTrue(meta, meta.contains("\"crystalgui.frame\""));
        // THE ABSENCE IS THE POINT. A reader who cannot see which channels were off will read a gap in
        // the timeline as work that did not happen -- the one way a trace lies without being wrong.
        assertTrue("blame is not listed as off: " + meta, meta.contains("notRecording"));
        assertTrue(meta, meta.contains("crystalgui.blame"));
    }

    @Test
    public void theExportCarriesTheFramesTheZonesAndTheChain() throws Exception {
        record();
        UiTrace.export();
        FrameStats.get().release();
        CgTraceLog.stop();

        String json = new String(Files.readAllBytes(
                ROOT.resolve(CgTraceLog.LATEST).resolve("trace.json")), StandardCharsets.UTF_8);
        assertTrue(json.startsWith("{\"displayTimeUnit\":\"ms\""));
        assertTrue("no frame track", json.contains("\"name\":\"Frame 0\""));
        assertTrue("no zones", json.contains("\"name\":\"paint:tree\""));
        assertTrue("no counters", json.contains("\"ph\":\"C\""));
        assertTrue("no chain", json.contains("\"name\":\"open file\""));
        assertFalse("a trailing comma would make a viewer refuse the file", json.contains(",\n]}"));
    }
}
