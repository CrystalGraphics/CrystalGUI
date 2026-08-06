package com.crystalgui.render.texture.svg;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Holds {@link SvgTriangulator}'s active-edge-table sweep to producing <b>exactly</b> what the quadratic
 * nested-loop version produced.
 *
 * <h3>Why identity and not merely "looks the same"</h3>
 *
 * <p>The mesh feeds a draw path that nudges each triangle by a fraction of a pixel to partition the seams
 * between trapezoids, so a difference of one triangle — or of two triangles' <em>order</em>, which decides
 * which one wins an overlap — surfaces as a hairline through a filled shape at some particular zoom and
 * nowhere else. That is a bug class this project has already paid for twice, and no screenshot comparison
 * at a handful of scales would catch it. Bit-for-bit equality is the only assertion that does, and the
 * rewrite was designed to be able to make it: every prune it adds is exact, so a skipped pair is one that
 * provably could not have contributed.</p>
 *
 * <p>{@link ReferenceTriangulator} is a frozen copy of the old implementation. It is deliberately not
 * factored to share anything with the live one — a shared helper drifting would make both agree and prove
 * nothing.</p>
 *
 * <h3>What to do when the mesh is meant to change</h3>
 *
 * <p>This test pins <em>identity</em>, so any deliberate change to what the tessellator emits — raising
 * {@code MAX_INTERSECTION_EDGES}, changing a cap, changing the fill rule walk — will fail it, correctly and
 * unhelpfully. <b>Delete this test and its reference together at that point.</b> Their whole value is the
 * claim "the sweep rewrite changed nothing", and that claim is spent the moment something else is allowed
 * to. Keeping them by regenerating the reference would preserve the shape of a guarantee with none of the
 * substance.</p>
 */
public class TriangulatorEquivalenceTest {

    /** Every shipped icon, tessellated exactly as the loader would. */
    @Test
    public void everyShippedIconTessellatesIdentically() throws IOException {
        List<Path> icons = shippedIcons();
        // A floor low enough to survive the icon set being swapped wholesale -- which happens -- but high
        // enough to catch the directory having moved and this comparing nothing at all.
        assertTrue("expected a shipped icon set, found " + icons.size(), icons.size() > 5);

        int fills = 0;
        for (Path icon : icons) {
            String source = Files.readString(icon, StandardCharsets.UTF_8);
            SvgScene scene = SvgResolver.resolve(SvgScanner.scan(source), 16);
            for (SvgScene.Node node : scene.nodes()) {
                if (node.fill() == null) continue;
                List<List<float[]>> rings = SvgGeometry.ringsOf(node.contours());
                boolean evenOdd = node.fill().evenOdd();
                for (float[] steps : new float[][]{{0f, 0f}, {0.4f, 0.4f}, {0.13f, 0f}, {0f, 0.13f}}) {
                    assertIdentical(icon.getFileName() + " steps=" + steps[0] + "," + steps[1],
                            rings, evenOdd, steps[0], steps[1], null);
                    fills++;
                }
            }
        }
        assertTrue("expected a real corpus, compared " + fills + " fills", fills > 20);
    }

    /**
     * The IntelliJ mark — the corpus's torture case, and the reason the sweep cuts bands at
     * self-intersections at all.
     */
    @Test
    public void selfIntersectingArtworkTessellatesIdentically() throws IOException {
        Path mark = resolve("src/test/resources/assets/crystalgui/ui/icons/IntelliJ_IDEA_Icon.svg");
        assertTrue("torture-test artwork is missing: " + mark.toAbsolutePath(), Files.exists(mark));

        SvgScene scene = SvgResolver.resolve(
                SvgScanner.scan(Files.readString(mark, StandardCharsets.UTF_8)), 16);
        int compared = 0;
        for (SvgScene.Node node : scene.nodes()) {
            if (node.fill() == null) continue;
            List<List<float[]>> rings = SvgGeometry.ringsOf(node.contours());
            assertIdentical("intellij nonzero", rings, false, 0f, 0f, null);
            assertIdentical("intellij evenodd", rings, true, 0f, 0f, null);
            compared++;
        }
        assertTrue("the mark resolved to no filled paths", compared > 0);
    }

    /**
     * Randomised contours, weighted toward the shapes the prunes could plausibly get wrong: crossings,
     * shared vertices, exactly-coincident crossing {@code x}, and horizontal runs.
     */
    @Test
    public void randomisedContoursTessellateIdentically() {
        Random random = new Random(0xC0FFEE);
        for (int trial = 0; trial < 400; trial++) {
            List<List<float[]>> rings = new ArrayList<>();
            int ringCount = 1 + random.nextInt(3);
            for (int r = 0; r < ringCount; r++) {
                int points = 3 + random.nextInt(14);
                List<float[]> ring = new ArrayList<>(points);
                for (int p = 0; p < points; p++) {
                    // Quantised, so vertices and crossings collide exactly rather than merely nearly --
                    // those ties are what the index tiebreak in sortByX exists for.
                    ring.add(new float[]{
                            Math.round(random.nextFloat() * 8f) / 2f,
                            Math.round(random.nextFloat() * 8f) / 2f});
                }
                rings.add(ring);
            }
            boolean evenOdd = random.nextBoolean();
            float stepX = random.nextBoolean() ? 0f : 0.1f + random.nextFloat();
            float stepY = random.nextBoolean() ? 0f : 0.1f + random.nextFloat();
            float[] cuts = random.nextBoolean() ? null
                    : new float[]{random.nextFloat() * 4f, random.nextFloat() * 4f};
            assertIdentical("trial " + trial, rings, evenOdd, stepX, stepY, cuts);
        }
    }

    /** Shapes with nothing to fill still have to agree — including on returning nothing. */
    @Test
    public void degenerateInputProducesTheSameEmptyResult() {
        assertIdentical("empty", List.of(), false, 0f, 0f, null);
        assertIdentical("two points", List.of(List.of(new float[]{0, 0}, new float[]{1, 1})),
                false, 0f, 0f, null);
        assertIdentical("horizontal only", List.of(List.of(
                new float[]{0, 0}, new float[]{1, 0}, new float[]{2, 0})), false, 0f, 0f, null);
        assertIdentical("repeated point", List.of(List.of(
                new float[]{1, 1}, new float[]{1, 1}, new float[]{1, 1})), false, 0f, 0f, null);
    }

    private static void assertIdentical(String what, List<List<float[]>> rings, boolean evenOdd,
                                        float stepX, float stepY, float[] cuts) {
        SvgTriangulator.Fill expected = ReferenceTriangulator.fill(rings, evenOdd, stepX, stepY, cuts);
        SvgTriangulator.Fill actual = SvgTriangulator.fill(rings, evenOdd, stepX, stepY, cuts);

        assertEquals(what + ": triangle count",
                expected.triangles().length / 6, actual.triangles().length / 6);
        // Zero delta: this is an identity claim, not a tolerance one.
        assertArrayEquals(what + ": vertices", expected.triangles(), actual.triangles(), 0f);
        assertArrayEquals(what + ": slice tags", expected.slice(), actual.slice());
        assertEquals(what + ": half count", expected.upper().length, actual.upper().length);
        for (int i = 0; i < expected.upper().length; i++) {
            assertEquals(what + ": upper/lower half of triangle " + i,
                    expected.upper()[i], actual.upper()[i]);
        }
    }

    private static List<Path> shippedIcons() throws IOException {
        Path root = resolve("src/main/resources/assets/crystalgui/ui/icons");
        assertTrue("icon root is missing: " + root.toAbsolutePath(), Files.isDirectory(root));
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".svg")).sorted().toList();
        }
    }

    /** Tests run with the module as the working directory locally and the repo root on some runners. */
    private static Path resolve(String relative) {
        Path direct = Path.of(relative);
        return Files.exists(direct) ? direct : Path.of("core").resolve(relative);
    }
}
