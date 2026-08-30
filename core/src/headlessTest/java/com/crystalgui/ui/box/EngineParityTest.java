package com.crystalgui.ui.box;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.Assume;
import org.junit.Test;

/**
 * The two engines' pictures of the SAME tree, compared pixel by pixel — 5.4's acceptance.
 *
 * <p>The pictures come from a GL run: {@code --mode=cgui-engine-parity} paints one fixed tree
 * through {@code UIWindow.paintFrame()} and through {@code Document.paint()} and writes a PNG of
 * each. This test compares whatever that run last wrote; with no PNGs on disk it is SKIPPED, and
 * says so — the assumption gates on the ENVIRONMENT (no GL run has happened), never on the answer,
 * which is the {@code locate} lesson. Run the harness, then this.</p>
 *
 * <p>The tolerance absorbs text anti-aliasing and SDF edge coverage — sub-level differences along
 * edges — and nothing else: a box drawn at the wrong place, the wrong size, unclipped or unfaded
 * moves whole regions and fails both bounds by orders of magnitude.</p>
 */
public class EngineParityTest {

    private static final File OUTPUT = new File("../gl-debug-harness/harness-output/cgui-engine-parity");

    @Test
    public void theTwoEnginesPaintTheSamePicture() throws IOException {
        File oldPng = newest("engine_old");
        File newPng = newest("engine_new");
        Assume.assumeTrue("no parity PNGs under " + OUTPUT.getAbsolutePath()
                + " -- run :gl-debug-harness:runHarness --args=\"--mode=cgui-engine-parity\" first",
                oldPng != null && newPng != null);

        BufferedImage a = ImageIO.read(oldPng);
        BufferedImage b = ImageIO.read(newPng);
        assertEquals("widths", a.getWidth(), b.getWidth());
        assertEquals("heights", a.getHeight(), b.getHeight());

        long totalDiff = 0;
        long badPixels = 0;
        long pixels = (long) a.getWidth() * a.getHeight();
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int pa = a.getRGB(x, y), pb = b.getRGB(x, y);
                int diff = channelDiff(pa, pb);
                totalDiff += diff;
                if (diff > 48) badPixels++;
            }
        }
        double meanPerChannel = totalDiff / (double) (pixels * 3);
        double badFraction = badPixels / (double) pixels;
        String summary = String.format("mean/channel %.3f, pixels off by >16/channel: %.4f%%",
                meanPerChannel, badFraction * 100);
        assertTrue("pictures diverge in the mean -- " + summary, meanPerChannel < 2.0);
        assertTrue("pictures diverge in patches -- " + summary, badFraction < 0.01);
    }

    /** Sum of absolute RGB channel differences, alpha ignored (the backbuffer's alpha is noise). */
    private static int channelDiff(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return dr + dg + db;
    }

    private static File newest(String tag) {
        File[] files = OUTPUT.listFiles((dir, name) -> name.contains(tag) && name.endsWith(".png"));
        if (files == null || files.length == 0) return null;
        File best = files[0];
        for (File f : files) if (f.lastModified() > best.lastModified()) best = f;
        return best;
    }
}
