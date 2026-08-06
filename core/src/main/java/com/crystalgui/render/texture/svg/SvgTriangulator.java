package com.crystalgui.render.texture.svg;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Turns a set of closed contours into triangles, so a filled shape can be drawn.
 *
 * <h3>Trapezoid decomposition, not ear clipping</h3>
 *
 * <p>Ear clipping is the textbook answer and it is the wrong one here. It triangulates a <b>single simple
 * polygon</b> — and real artwork is neither single nor simple. A logo is several contours at once, its
 * counters and windows are holes that have to be <em>subtracted</em>, and exported paths self-intersect
 * routinely. Ear clipping fills a hole solid, which turns a ring into a disc and an "O" into a blob.</p>
 *
 * <p>So: slice the shape into horizontal bands at every vertex {@code y}, and within each band find where
 * the edges cross, sort those crossings, and apply the fill rule to decide which spans are inside. Each
 * inside span becomes a trapezoid — two triangles.</p>
 *
 * <p><b>This is exact, not an approximation.</b> The tempting objection is that horizontal bands must
 * stair-step a diagonal edge. They do not, because the bands are cut at <em>every</em> vertex: no vertex
 * can then fall inside a band, so within one band every edge is a straight run with no bend, and the
 * trapezoid's slanted sides lie exactly on it. Cutting at arbitrary intervals instead is what produces
 * stair-steps, and is the version of this algorithm people remember.</p>
 *
 * <h3>Both fill rules, for free</h3>
 *
 * <p>Once the crossings in a band are sorted, {@code evenodd} is "every other span" and {@code nonzero}
 * is "spans where the accumulated winding is not zero". They differ only in that walk, so supporting SVG's
 * actual default ({@code nonzero}) costs nothing — and getting it wrong inverts every hole in a file
 * authored with overlapping contours.</p>
 *
 * <h3>Self-intersection is handled, and it had to be</h3>
 *
 * <p>A crossing point is not a vertex, so nothing cuts a band there by default — and the band containing it
 * has its left and right walls trade places partway down. The trapezoid built from them is a bowtie: it
 * covers a wedge the shape does not occupy and leaves a matching wedge bare. On stacked artwork that reads
 * as smears of whatever is <em>underneath</em>, which looks like a paint bug rather than a geometry one.</p>
 *
 * <p>{@link #selfIntersections} finds every crossing and adds its {@code y} to the band cuts, which is all
 * it takes: below the crossing the edges are in one order and above it the other, so no band ever contains
 * a swap. Not an edge case worth skipping — the JetBrains mark has four polygons that each fold back over
 * themselves.</p>
 */
public final class SvgTriangulator {

    private SvgTriangulator() {
    }

    /** Contours below this many points enclose no area and are skipped rather than fed to the sweep. */
    private static final int MINIMUM_POINTS = 3;

    private static final float EPSILON = 1e-5f;

    /** Twice the area below which a triangle is treated as having none; see {@link #add}. */
    private static final float DEGENERATE_AREA = 1e-7f;

    /**
     * Ceilings on gradient subdivision, per shape.
     *
     * <p>Not defensive padding — a bound on what one document can cost the draw loop. The spacing comes
     * from the gradient's axis, and a file is free to state an axis a thousandth the size of the shape it
     * paints; without a cap that is a shape asking for a million triangles, every frame, for a ramp with
     * two visible colours in it.</p>
     */
    private static final int MAX_SLICES = 64;

    /**
     * The most cells one fill may be cut into, shared out across however many bands it has.
     *
     * <h3>Why the cap has to know the band count</h3>
     *
     * <p>The cuts are axis-aligned, so a gradient running <b>diagonally</b> is approximated by a grid and
     * asking for N bands of quality costs N² cells rather than N. Only O(N) of them carry new colour.</p>
     *
     * <p>Capping the two axes independently does not bound that. A curve-heavy outline already arrives
     * with hundreds of natural bands — one per flattened vertex — and giving each of them {@link
     * #MAX_SLICES} slices is what took {@code htaccess}'s feather to <b>30,406 triangles for one 16px
     * icon</b>. Dividing the budget by the bands that actually exist is the only place the product is
     * visible, which is why this lives here and not with the spacing that requested it.</p>
     *
     * <h3>Why 900, and why it is a ceiling rather than an answer</h3>
     *
     * <p>A mesh is built once and cached <em>scale-free</em>, so the number has to be chosen for the size
     * the artwork is actually drawn at. <b>A 16px icon can display at most sixteen bands</b> — anything
     * finer is subdivision no display can resolve. 900 cells is a 30×30 grid: smooth well past the sizes
     * a file tree or a toolbar uses, and enough headroom that a moderate zoom does not fall apart.</p>
     *
     * <p>It does fall apart eventually. Zoomed to several hundred pixels, a diagonal ramp facets into a
     * visible mosaic, and no CPU subdivision fixes that without a mesh per zoom level. The real answer
     * there is a paint-server shader — the instance record already carries {@code color0}/{@code color1}
     * and the fragment already mixes them, but a fill forces {@code t = 0} and there is nowhere in the
     * record to put a gradient axis. Widening it is a backend change, and one worth making only if
     * something needs large gradient artwork.</p>
     */
    private static final int MAX_CELLS = 3000;

    private static final int MAX_EXTRA_BANDS = 192;

    /** Above this edge count the pairwise crossing search is skipped; see {@link #selfIntersections}. */
    private static final int MAX_INTERSECTION_EDGES = 1200;

    /**
     * Fills a set of contours.
     *
     * <p>Every contour is implicitly closed, whether or not the path said {@code Z} — that is SVG's own
     * rule for filling, and it is why an unclosed subpath still paints a solid shape.</p>
     *
     * @param evenOdd {@code true} for {@code fill-rule: evenodd}, {@code false} for {@code nonzero}
     * @return {@code x,y} triples — six floats per triangle; empty for anything with no area
     */
    public static float[] fill(List<List<float[]>> contours, boolean evenOdd) {
        return fill(contours, evenOdd, 0f, 0f).triangles();
    }

    /**
     * A filled mesh, with each triangle tagged by the trapezoid slice it came from.
     *
     * <p>The tag exists so a caller can colour a gradient <b>per slice</b>. Colouring per triangle looks
     * identical and is not: the two halves of a slice are split along a diagonal, so their centroids sit on
     * opposite sides of it and pick up different colours. That stays invisible until something overdraws
     * the shared diagonal — which the draw does, by a half pixel, to hide the seams between slices — and
     * then it is a diagonal hatch across the whole shape, worst exactly where the gradient is steepest.</p>
     *
     * <p>A tag rather than "assume consecutive pairs", because a slice at a tip emits only <b>one</b>
     * triangle; see the degeneracy note in {@link #fill(List, boolean, float, float)}.</p>
     *
     * @param slice {@code slice[i]} is the slice index of the triangle at {@code triangles[i * 6]}
     * @param upper {@code true} when that triangle is the <b>upper</b> half of its trapezoid — the one
     *              touching the band's top edge. The lower half touches the bottom edge, and therefore the
     *              top edge of the band below. That alternation is what lets a caller give the two sides of
     *              every shared edge opposite sub-pixel offsets and get an exact coverage partition; see
     *              {@code SvgDocument.drawFill}
     */
    public record Fill(float[] triangles, int[] slice, boolean[] upper) {
    }

    /**
     * Fills a set of contours, cut fine enough that a flat colour per triangle passes for a gradient.
     *
     * <p>The two spacings are what keep that affordable. A gradient's colour varies along <em>one</em>
     * direction, so cutting uniformly wastes almost all of the triangles: a horizontal ramp passes
     * {@code stepY = 0} and a shape of any height stays at its handful of natural bands. See
     * {@link SvgGradient#sampleSpacing}.</p>
     *
     * <h3>Degenerate triangles are never emitted, and that is not tidiness</h3>
     *
     * <p>Wherever the shape comes to a point the trapezoid collapses to a triangle, so one of the two
     * halves has a repeated vertex and no area. Dropping it saves a draw — and, far more importantly,
     * {@code sdf_triangle} takes the triangle's winding from {@code sign(area)}, which for zero area is
     * {@code 0}, and its inside test then reads {@code 0 >= 0} at <b>every</b> point. A zero-area triangle
     * handed to the GPU therefore fills its whole axis-aligned bounding quad solid. It presents as
     * rectangular blocks of the wrong colour at every tip of every shape, and no CPU rasterisation of the
     * same mesh reproduces it, because an ordinary point-in-triangle test yields nothing at all.</p>
     *
     * @param stepX how far apart, in the contours' own units, two samples may sit across the shape before
     *              the step between them shows; {@code 0} for no horizontal subdivision
     * @param stepY the same down the shape; {@code 0} for no extra bands beyond the vertex cuts
     */
    public static Fill fill(List<List<float[]>> contours, boolean evenOdd,
                            float stepX, float stepY) {
        // Edges as a flat array: x0,y0,x1,y1 per edge. Horizontal edges are dropped at build time -- they
        // can never cross a band's midline, so keeping them only costs a test per band per edge.
        List<float[]> edges = new ArrayList<>();
        for (List<float[]> contour : contours) {
            if (contour.size() < MINIMUM_POINTS) continue;
            for (int i = 0; i < contour.size(); i++) {
                float[] a = contour.get(i);
                float[] b = contour.get((i + 1) % contour.size());
                if (Math.abs(a[1] - b[1]) < EPSILON) continue;
                edges.add(new float[]{a[0], a[1], b[0], b[1]});
            }
        }
        Fill empty = new Fill(new float[0], new int[0], new boolean[0]);
        if (edges.isEmpty()) return empty;

        float[] bands = bandBoundaries(edges, stepY);
        if (bands.length < 2) return empty;

        List<float[]> triangles = new ArrayList<>();
        List<Integer> slices = new ArrayList<>();
        List<Boolean> uppers = new ArrayList<>();
        int sliceIndex = 0;
        // Shared out now that the bands are known. A shape whose outline already supplies three hundred
        // bands gets a handful of slices each; a rectangle with two bands may have the lot.
        int sliceAllowance = Math.max(1, MAX_CELLS / Math.max(1, bands.length - 1));
        float[] crossX = new float[edges.size()];
        int[] crossDir = new int[edges.size()];
        int[] crossEdge = new int[edges.size()];

        for (int band = 0; band + 1 < bands.length; band++) {
            float top = bands[band];
            float bottom = bands[band + 1];
            if (bottom - top < EPSILON) continue;
            float middle = (top + bottom) * 0.5f;

            int found = 0;
            for (int e = 0; e < edges.size(); e++) {
                float[] edge = edges.get(e);
                float lo = Math.min(edge[1], edge[3]);
                float hi = Math.max(edge[1], edge[3]);
                if (middle < lo || middle >= hi) continue;
                crossX[found] = xAt(edge, middle);
                crossDir[found] = edge[3] > edge[1] ? 1 : -1;
                crossEdge[found] = e;
                found++;
            }
            if (found < 2) continue;
            sortByX(crossX, crossDir, crossEdge, found);

            int winding = 0;
            for (int i = 0; i + 1 < found; i++) {
                winding += evenOdd ? 1 : crossDir[i];
                boolean inside = evenOdd ? (winding & 1) == 1 : winding != 0;
                if (!inside) continue;

                float[] left = edges.get(crossEdge[i]);
                float[] right = edges.get(crossEdge[i + 1]);
                float lt = xAt(left, top), lb = xAt(left, bottom);
                float rt = xAt(right, top), rb = xAt(right, bottom);
                if (Math.abs(rt - lt) < EPSILON && Math.abs(rb - lb) < EPSILON) continue;

                // Sliced by interpolating BETWEEN the two walls rather than by cutting at fixed x. A
                // vertical cut through a slanted trapezoid produces pieces that are no longer trapezoids
                // wherever the cut leaves the shape partway down the band; lerping the walls keeps every
                // piece the same shape as the whole, so the slicing needs no clipping and no special case.
                float widest = Math.max(Math.abs(rt - lt), Math.abs(rb - lb));
                int count = stepX > 0f
                        ? Math.max(1, Math.min(Math.min(MAX_SLICES, sliceAllowance),
                                (int) Math.ceil(widest / stepX)))
                        : 1;
                for (int s = 0; s < count; s++) {
                    float a = (float) s / count, b = (float) (s + 1) / count;
                    float at = lt + (rt - lt) * a, ab = lb + (rb - lb) * a;
                    float bt = lt + (rt - lt) * b, bb = lb + (rb - lb) * b;
                    // Upper half first: it carries the band's TOP edge. The lower carries the bottom, which
                    // is the next band's top -- so "upper" alternates across every horizontal seam as well
                    // as across the diagonal the two of them share.
                    add(triangles, slices, uppers, sliceIndex, true, at, top, bt, top, bb, bottom);
                    add(triangles, slices, uppers, sliceIndex, false, at, top, bb, bottom, ab, bottom);
                    sliceIndex++;
                }
            }
        }

        float[] packed = new float[triangles.size() * 6];
        int[] tags = new int[triangles.size()];
        boolean[] halves = new boolean[triangles.size()];
        for (int i = 0; i < triangles.size(); i++) {
            System.arraycopy(triangles.get(i), 0, packed, i * 6, 6);
            tags[i] = slices.get(i);
            halves[i] = uppers.get(i);
        }
        return new Fill(packed, tags, halves);
    }

    /**
     * Appends one triangle, unless it has no area.
     *
     * <p>The area test is the load-bearing half — see the degeneracy note on {@link #fill(List, boolean,
     * float, float)}. Compared against a tolerance rather than exact zero, because a slice one ten-
     * thousandth of a unit wide is degenerate for every purpose that matters and its winding sign is
     * whatever the rounding decided.</p>
     */
    private static void add(List<float[]> out, List<Integer> slices, List<Boolean> uppers,
                            int slice, boolean upper,
                            float x0, float y0, float x1, float y1, float x2, float y2) {
        float area = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
        if (Math.abs(area) < DEGENERATE_AREA) return;
        out.add(new float[]{x0, y0, x1, y1, x2, y2});
        slices.add(slice);
        uppers.add(upper);
    }

    /**
     * Every {@code y} at which the shape's edge ordering can change, ascending.
     *
     * <p>Three sources, and all three are load-bearing:</p>
     *
     * <ol>
     *   <li><b>Vertex {@code y}s</b> — what makes the decomposition exact rather than stair-stepped. No
     *       vertex may fall inside a band, or an edge bends where the trapezoid cannot follow it.</li>
     *   <li><b>Self-intersection {@code y}s</b> — see {@link #selfIntersections}.</li>
     *   <li><b>Extra cuts at {@code stepY}</b>, for gradient subdivision. <b>Added to</b> the first two,
     *       never substituted for them.</li>
     * </ol>
     */
    private static float[] bandBoundaries(List<float[]> edges, float stepY) {
        int extra = 0;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        if (stepY > 0f) {
            for (float[] edge : edges) {
                minY = Math.min(minY, Math.min(edge[1], edge[3]));
                maxY = Math.max(maxY, Math.max(edge[1], edge[3]));
            }
            extra = Math.min(MAX_EXTRA_BANDS, (int) Math.ceil((maxY - minY) / stepY));
        }

        float[] crossings = selfIntersections(edges);
        float[] all = new float[edges.size() * 2 + Math.max(0, extra) + crossings.length];
        for (int i = 0; i < edges.size(); i++) {
            all[i * 2] = edges.get(i)[1];
            all[i * 2 + 1] = edges.get(i)[3];
        }
        int at = edges.size() * 2;
        for (int i = 0; i < extra; i++) all[at++] = minY + (maxY - minY) * (i + 0.5f) / extra;
        for (float y : crossings) all[at++] = y;

        Arrays.sort(all);
        float[] unique = new float[all.length];
        int count = 0;
        for (float value : all) {
            if (count == 0 || value - unique[count - 1] > EPSILON) unique[count++] = value;
        }
        return Arrays.copyOf(unique, count);
    }

    /**
     * Every {@code y} where two edges of the shape cross each other.
     *
     * <p><b>This is what makes a self-intersecting contour come out right, and it is not an edge case.</b>
     * Exported logo artwork is full of them: the JetBrains mark has four polygons of seven and eight points
     * that each fold back over themselves. Without a cut at the crossing, one band contains the swap — its
     * left and right walls trade places partway down — and the trapezoid built from them is a bowtie
     * covering a wedge of space the shape does not occupy, while leaving a matching wedge that it does
     * bare. On a stack of overlapping polygons that reads as smears of the colour <em>underneath</em>
     * showing through, which looks like a paint bug rather than a geometry one.</p>
     *
     * <p>Cutting there is enough. Below the crossing the two edges are in one order and above it the other;
     * within each band they never swap, so the ordinary sweep is exact again. There is no need to split the
     * edges themselves or to know where the crossing is in {@code x} — the per-band midpoint sort finds
     * that on its own.</p>
     */
    private static float[] selfIntersections(List<float[]> edges) {
        int count = edges.size();
        // O(n²), so bounded. Above this an exact sweep-line is the right structure, and a shape with that
        // many edges is a curve-heavy path whose crossings are sub-pixel anyway.
        if (count < 2 || count > MAX_INTERSECTION_EDGES) return new float[0];

        float[] found = new float[64];
        int size = 0;
        for (int i = 0; i < count; i++) {
            float[] a = edges.get(i);
            float ax = a[2] - a[0], ay = a[3] - a[1];
            for (int j = i + 1; j < count; j++) {
                float[] b = edges.get(j);
                float bx = b[2] - b[0], by = b[3] - b[1];
                float denominator = ax * by - ay * bx;
                if (Math.abs(denominator) < 1e-9f) continue;      // parallel, or both degenerate

                float dx = b[0] - a[0], dy = b[1] - a[1];
                float t = (dx * by - dy * bx) / denominator;
                float u = (dx * ay - dy * ax) / denominator;
                // Strictly interior on both: edges that merely share an endpoint are every adjacent pair in
                // the contour, and their shared vertex is already a band boundary.
                if (t <= EPSILON || t >= 1f - EPSILON || u <= EPSILON || u >= 1f - EPSILON) continue;

                if (size == found.length) found = Arrays.copyOf(found, size * 2);
                found[size++] = a[1] + ay * t;
            }
        }
        return Arrays.copyOf(found, size);
    }

    private static float xAt(float[] edge, float y) {
        float dy = edge[3] - edge[1];
        // Guarded even though horizontal edges never reach here: a denormal dy would otherwise produce an
        // infinity that propagates into the vertex buffer and takes the whole draw with it.
        if (Math.abs(dy) < 1e-9f) return edge[0];
        return edge[0] + (edge[2] - edge[0]) * (y - edge[1]) / dy;
    }

    /**
     * Insertion sort over the three parallel arrays.
     *
     * <p>Parallel primitive arrays rather than a list of crossing objects because this runs once per band
     * per fill, which is the only part of the whole loader that is genuinely hot — a logo with a few
     * hundred flattened vertices is a few hundred bands, and allocating a crossing object for each edge in
     * each of them is the difference between a load you notice and one you do not. Insertion sort because
     * a band typically has two crossings and rarely more than a dozen.</p>
     */
    private static void sortByX(float[] x, int[] dir, int[] edge, int count) {
        for (int i = 1; i < count; i++) {
            float keyX = x[i];
            int keyDir = dir[i], keyEdge = edge[i];
            int j = i - 1;
            while (j >= 0 && x[j] > keyX) {
                x[j + 1] = x[j];
                dir[j + 1] = dir[j];
                edge[j + 1] = edge[j];
                j--;
            }
            x[j + 1] = keyX;
            dir[j + 1] = keyDir;
            edge[j + 1] = keyEdge;
        }
    }
}
