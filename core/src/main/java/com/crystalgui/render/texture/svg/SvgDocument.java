package com.crystalgui.render.texture.svg;

import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.util.io.CgIO;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.CgUiPaintContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * One {@code .svg} file, resolved to a cached list of draw operations.
 *
 * <h3>What it handles</h3>
 *
 * <ul>
 *   <li><b>Shapes</b> — {@code path} (the whole {@code d} grammar, via {@link SvgPath}), {@code rect}
 *       with rounded corners, {@code circle}, {@code ellipse}, {@code line}, {@code polyline},
 *       {@code polygon}.</li>
 *   <li><b>Structure</b> — arbitrarily nested {@code <g>}, {@code <svg>}, {@code <a>} and
 *       {@code <switch>}; {@code <defs>}/{@code <symbol>} held back from the picture; {@code <use>}
 *       pulling either of them back in.</li>
 *   <li><b>Transforms</b> — {@code translate}, {@code scale}, {@code rotate}, {@code matrix},
 *       {@code skewX}, {@code skewY}, composed down the tree, with stroke widths scaled to match.</li>
 *   <li><b>Paint</b> — {@code fill} and {@code stroke} as hex, {@code rgb()}, a name,
 *       {@code currentColor} or a gradient reference; {@code fill-rule}, {@code stroke-width},
 *       {@code stroke-linecap}, and all three opacities, each inheriting properly and each overridable
 *       by an inline {@code style}.</li>
 *   <li><b>Fills</b> — real interiors, with holes cut, under either fill rule. See
 *       {@link SvgTriangulator}.</li>
 *   <li><b>Gradients</b> — linear and radial, in either unit system, with {@code gradientTransform},
 *       {@code spreadMethod} and {@code href} stop inheritance. Realised by subdivision rather than by a
 *       paint-server shader; see {@link SvgGradient}.</li>
 * </ul>
 *
 * <h3>Where it approximates, on purpose</h3>
 *
 * <p>A gradient is sampled per triangle rather than per pixel, so it is a fine staircase rather than a
 * ramp — cut fine enough that each band moves the colour by at most two levels, and it costs no shader. A <b>stroked</b>
 * gradient does collapse to one colour: a stroke is not subdivided, and a per-segment ramp along a
 * two-unit-wide outline is not a thing anyone can see. Patterns, filters, masks and clip paths are ignored
 * outright — a clipped shape draws unclipped rather than vanishing, on the reasoning that a visible
 * approximation beats a silent hole.</p>
 *
 * <p>Group {@code opacity} multiplies into children instead of compositing the group as a unit; see
 * {@link SvgStyle}. Text is not rendered — an icon with live text is a font problem, not an SVG one.</p>
 *
 * <h3>Everything is computed once</h3>
 *
 * <p>Parsing, flattening, transforming, triangulating and dropping degenerate geometry all happen in the
 * constructor. What survives is {@link DrawOp} — flat {@code float[]}s of ready coordinates, in document
 * order — so a draw is a loop over primitives with two multiply-adds per vertex, no allocation and no
 * re-walking of anything.</p>
 *
 * <p><b>Document order is load-bearing, so the ops are not grouped by style.</b> Grouping every fill of a
 * colour together would batch better and would be wrong: painter's order is what decides which of two
 * overlapping shapes is on top, and in a logo built from stacked opaque polygons — the IntelliJ mark is
 * exactly this — reordering them changes the picture.</p>
 */
public final class SvgDocument {



    /**
     * How far every fill triangle is grown, in screen pixels, so its seams with its neighbours close.
     *
     * <h3>Overlap, and why the partition that replaced it was wrong</h3>
     *
     * <p>A fill is a strip of trapezoids and every internal edge is shared by two triangles, each
     * evaluating its own SDF. Growing both by a hair makes them overlap along that edge, so it is always
     * claimed — the cost being that a <b>translucent</b> fill composites the overlap twice and reads
     * lighter there.</p>
     *
     * <p>That cost is what motivated a partition instead: grow one side, shrink the other, so exactly one
     * claims the edge. <b>It does not work.</b> Growing by δ and shrinking by δ leaves both effective
     * boundaries on the <em>same line</em>, δ from the true edge — the same coincidence, relocated. A
     * pixel within the coverage ramp of it takes partial coverage from both and lands near 0.83. Worse,
     * the two triangles derive their distances from different vertex triples, so rounding puts them
     * either side of that line at slightly different places; at 700px a float ULP is already 6e-5,
     * comparable to the ramp. It shows up as sparse dark specks and short dashes along a seam rather than
     * a continuous line, because only pixels landing in that sliver are affected.</p>
     *
     * <p>So the scheme is chosen by what the fill can afford:</p>
     *
     * <ul>
     *   <li><b>Opaque — overlap.</b> Compositing a colour over itself is a no-op, so the doubled band
     *       costs nothing and the seam is always claimed. Exactly right.</li>
     *   <li><b>Translucent — partition.</b> An overlap would blend twice and read as a lighter line along
     *       every seam, which is what regexp's and hprof's pages showed. The partition's own weakness —
     *       a sliver where neither side fully claims — is confined to pixels landing within the coverage
     *       ramp of one line, and is far rarer than a seam on every edge.</li>
     * </ul>
     *
     * <p>Whichever is used, keep it <b>small</b>. The first overlap attempt used half a pixel, doubling a
     * full pixel of every seam, which is why it read as a bright line rather than a hairline.</p>
     *
     * <h3>Why a small constant, measured rather than reasoned</h3>
     *
     * <p>This used to scale with the coordinates being drawn, on the theory that the offset had to stay
     * tens of float ULPs wide at any zoom. That was compensating for a different bug — the fragment stage
     * reconstructed its sample point from an interpolated varying, so two instances sharing a seam
     * disagreed about where the pixel was by far more than any ULP. {@code gui_curve.shader} now takes the
     * point from {@code gl_FragCoord}, which is identical for every instance, and the scaling became a
     * liability: a larger offset is a wider doubled band, and on a translucent fill that band IS the
     * artefact.</p>
     *
     * <p>Measured on the GPU, counting one-pixel rows that differ from two agreeing neighbours by 3/255 or
     * more, over {@code javaOutsideSource} and {@code regexp} at two placements each:</p>
     *
     * <pre>
     *   interpolated varying, offset .005     25 and 37    -- the reported lines and dashes
     *   gl_FragCoord,         offset .005      0 and 29
     *   gl_FragCoord,         offset 0        281 and  3   -- ties: both sides land on 0.5 coverage
     *   gl_FragCoord,         offset .001      0 and  3    -- the residual 3 is real geometry
     * </pre>
     *
     * <p>Zero is not the answer even though it removes the doubled band: with no offset an axis-aligned
     * seam landing exactly on a row of pixel centres gives <em>both</em> sides a distance of zero, so both
     * return the smoothstep's midpoint and the row blends twice at half strength. That is the 281, and at
     * a high zoom on {@code regexp} it is 266 against 2. The offset exists to break that tie and needs to
     * be only comfortably larger than the SDF's own rounding, not larger than an interpolation error that
     * no longer happens.</p>
     */
    private static final float FILL_OFFSET = 0.001f;

    /**
     * Width of the antialiased band on a fill's outline, in <b>post-pose</b> units — i.e. screen pixels.
     *
     * <p>One pixel, which is what an analytic coverage ramp wants: the edge should go from fully covered
     * to fully uncovered across exactly the pixel it passes through, and no further. Wider reads as a
     * blurry icon rather than a smooth one.</p>
     *
     * <p>Only the outline gets it — see the note at the submission site. Internal seams stay a hard step,
     * which is why this can be turned on at all.</p>
     */
    private static final float SILHOUETTE_FEATHER = 1f;




    /** Path to parsed document; see {@link #of}. */
    private static final Map<String, SvgDocument> CACHE = new ConcurrentHashMap<>();

    /**
     * One batch of geometry sharing a colour and a mode.
     *
     * <p>{@code data} is triangles for a fill (six floats each) and segments for a stroke (four each) —
     * one field rather than two subtypes because the draw loop switches on {@code fill} exactly once per
     * op and then runs a tight loop, and a sealed hierarchy would buy a cast per op to say the same
     * thing.</p>
     *
     * @param colours     one ARGB per triangle for a gradient fill, parallel to {@code data}; null when the
     *                     whole op is one colour. A gradient is realised by cutting the fill fine enough
     *                     that a flat colour per triangle passes for a ramp — see {@link SvgGradient}
     * @param currentColor the paint was {@code currentColor}, so the consumer's tint decides it at draw
     *                     time. Late-bound rather than resolved here because a document is cached and
     *                     shared, and the same icon is routinely drawn in two colours in one frame
     * @param segmentCaps  one packed cap pair per stroke segment, so that an interior joint gets exactly
     *                     one round cap and not two — see {@link SvgGeometry#segmentsOf}. Null for a fill,
     *                     which has no ends
     */
    public record DrawOp(boolean fill, float[] data, @Nullable int[] colours,
                         @Nullable int[] coloursEnd, @Nullable float[] gradients,
                         @Nullable boolean[] upper, boolean opaque, int argb, boolean currentColor,
                         float halfWidth, int cap, @Nullable int[] segmentCaps) {

        /** Whether triangle {@code i} carries a per-pixel ramp rather than a flat colour. */
        public boolean hasGradient(int triangle) {
            return gradients != null && coloursEnd != null;
        }
    }

    private final List<DrawOp> ops;
    private final List<SvgPath.Polyline> outline;
    private final float width;
    private final float height;

    private SvgDocument(List<DrawOp> ops, List<SvgPath.Polyline> outline, float width, float height) {
        // Unmodifiable, because a document is cached and shared by every consumer drawing that icon --
        // one caller sorting or clearing what it got back would corrupt the picture for all of them.
        this.ops = Collections.unmodifiableList(ops);
        this.outline = Collections.unmodifiableList(outline);
        this.width = width;
        this.height = height;
    }

    /**
     * The shared, parsed document for a path — the call an icon consumer wants.
     *
     * <p>Cached, so drawing the same icon on fifty file-tree rows parses once. Safe to share because a
     * document is immutable and carries no tint: {@code currentColor} is resolved at draw time, which is
     * exactly what lets one cached instance back a selected row and an unselected one in the same frame.
     * Mirrors {@code StyleSheetRegistry.of}, including its limitation — <b>nothing invalidates this on a
     * resource reload</b>, so an edited {@code .svg} needs {@link #invalidateCache()} to reappear.</p>
     */
    @Nullable
    public static SvgDocument of(String path) {
        // computeIfAbsent is unusable here: a failed load must be null, and a null return removes nothing
        // from the map -- so an unreadable path would re-read and re-warn on every single draw.
        SvgDocument cached = CACHE.get(path);
        if (cached != null) return cached;
        SvgDocument loaded = load(path);
        if (loaded != null) CACHE.put(path, loaded);
        return loaded;
    }

    /** Drops every cached document. Not wired to resource reload yet; see {@link #of}. */
    public static void invalidateCache() {
        CACHE.clear();
    }

    /** Loads {@code "namespace:ui/icons/folder.svg"} through {@code CgIO}, or null when it is unreadable. */
    @Nullable
    public static SvgDocument load(String path) {
        String source = CgIO.loadSource(path);
        if (source == null) {
            CrystalGuiCore.LOGGER.warn("Icon {} could not be read", path);
            return null;
        }
        return parse(source);
    }

    public static SvgDocument parse(String svg) {
        return fromScene(SvgResolver.resolve(SvgScanner.scan(svg)));
    }

    // ---- Building ---------------------------------------------------------------------------------

    /**
     * Flattens a resolved scene into the draw ops the paint context submits.
     *
     * <p>All three stages are visible in one method on purpose -- {@link SvgResolver} answers the SVG
     * questions, {@link SvgTessellator} turns geometry and paint into triangles, and this turns triangles
     * into submissions. Each stage's output is a type the next one takes, so any of them can be exercised
     * without the others; that is the whole reason the walk is no longer a private class here.</p>
     *
     * <p>Fill before stroke, per node. SVG's own painting order for a single element, and the reason a
     * stroked shape shows its full stroke rather than half of it hidden under the fill.</p>
     */
    static SvgDocument fromScene(SvgScene scene) {
        List<DrawOp> ops = new ArrayList<>();
        List<SvgPath.Polyline> outline = new ArrayList<>();
        for (SvgScene.Node node : scene.nodes()) {
            outline.addAll(node.contours());

            SvgScene.Fill fill = node.fill();
            if (fill != null) {
                SvgMesh mesh = SvgTessellator.tessellate(node.contours(), fill.evenOdd(), fill.paint());
                if (!mesh.isEmpty()) {
                    SvgScene.Paint paint = fill.paint();
                    int argb = paint instanceof SvgScene.Gradient ramp
                            ? ramp.argb() : ((SvgScene.Solid) paint).argb();
                    ops.add(new DrawOp(true, mesh.triangles(), mesh.colour0(), mesh.colour1(),
                            mesh.axes(), mesh.upper(), mesh.opaque(), argb, paint.currentColor(),
                            0f, 0, null));
                }
            }

            SvgScene.Stroke stroke = node.stroke();
            if (stroke != null) {
                SvgGeometry.Segments segments = SvgGeometry.segmentsOf(
                        node.contours(), stroke.cap() & 3, (stroke.cap() >> 2) & 3);
                if (segments.data().length > 0) {
                    SvgScene.Solid paint = (SvgScene.Solid) stroke.paint();
                    ops.add(new DrawOp(false, segments.data(), null, null, null, null, true,
                            paint.argb(), paint.currentColor(), stroke.halfWidth(), stroke.cap(),
                            segments.caps()));
                }
            }
        }
        return new SvgDocument(ops, outline, scene.width(), scene.height());
    }

    static Map<String, String> styleDeclarations(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String part : raw.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            out.put(part.substring(0, colon).trim().toLowerCase(), part.substring(colon + 1).trim());
        }
        return out;
    }

    static float number(String raw, float fallback) {
        if (raw == null) return fallback;
        try {
            return Float.parseFloat(raw.trim());
        } catch (RuntimeException notANumber) {
            return fallback;
        }
    }

    // ── Drawing ─────────────────────────────────────────────────────────────────────────────────────

    /** Draws the icon at {@code (x, y)} in its own colours, with {@code currentColor} left black. */
    public void render(CgUiPaintContext ctx, float x, float y, float scale) {
        render(ctx, x, y, scale, 0xFF000000);
    }

    /**
     * Draws the icon at {@code (x, y)}, scaled, in its own colours.
     *
     * <p>On the document rather than in an {@code SvgRenderer}, because a renderer here would be a class
     * with no state: the geometry is the document's, the transform is the caller's, and the batching is
     * {@code CgVectorRenderer}'s. A stateless class between two things that already have the data is a
     * layer, not a seam.</p>
     *
     * <p><b>Retained mode was considered and does not help.</b> {@code retainedCurve()} lets a caller keep
     * a descriptor across frames and re-{@code submit} it — but {@code submit()} is precisely the per-item
     * cost, since it is what appends the instance record the GPU reads. Retained mode saves rebuilding one
     * descriptor object, not the N submits, so the only thing it would buy is the chance to set
     * {@code pose()} instead of transformed coordinates — and {@code AGENTS.md} is explicit that
     * {@code CgUiPaintContext} is the single place the {@code PoseStack} is applied, so writing a pose
     * ourselves would silently drop {@code uiScale} and any element transform.</p>
     *
     * @param tint what {@code currentColor} resolves to — the hook a monochrome icon set is themed through
     */
    public void render(CgUiPaintContext ctx, float x, float y, float scale, int tint) {
        for (DrawOp op : ops) {
            int argb = op.currentColor() ? tint : op.argb();
            if (op.fill()) {
                drawFill(ctx, op, x, y, scale, argb, false);
            } else {
                drawStroke(ctx, op, x, y, scale, argb, op.halfWidth() * scale);
            }
        }
    }

    /**
     * Draws the icon flat, in one colour and one stroke width, ignoring the file's own paint.
     *
     * <p>What a themed icon set wants: Feather and Lucide are authored as {@code currentColor} strokes and
     * the consumer decides both the colour and the weight, so honouring the file's {@code stroke-width}
     * would make an icon look progressively thinner as it grew.</p>
     *
     * @param halfWidth stroke half-width in <em>screen</em> pixels; pass {@code <= 0} to keep the file's
     *                  own widths scaled with the icon
     */
    public void renderMonochrome(CgUiPaintContext ctx, float x, float y, float scale,
                                 int argb, float halfWidth) {
        for (DrawOp op : ops) {
            if (op.fill()) {
                drawFill(ctx, op, x, y, scale, argb, true);
            } else {
                drawStroke(ctx, op, x, y, scale, argb,
                        halfWidth > 0f ? halfWidth : op.halfWidth() * scale);
            }
        }
    }

    /**
     * @param flat ignore any per-triangle gradient colours and paint the whole op in {@code argb} — what
     *             {@link #renderMonochrome} means, and the reason the choice is a parameter rather than the
     *             presence of the array
     */
    private static void drawFill(CgUiPaintContext ctx, DrawOp op,
                                 float x, float y, float scale, int argb, boolean flat) {
        float[] t = op.data();
        boolean[] upper = op.upper();
        int[] start = op.colours();
        int[] end = op.coloursEnd();
        float[] axes = op.gradients();
        boolean ramp = !flat && start != null && end != null && axes != null;

        for (int i = 0; i < t.length; i += 6) {
            int triangle = i / 6;
            CgVectorRenderer.Triangle out = ctx.triangle()
                    .points(x + t[i] * scale, y + t[i + 1] * scale,
                            x + t[i + 2] * scale, y + t[i + 3] * scale,
                            x + t[i + 4] * scale, y + t[i + 5] * scale);

            if (ramp) {
                // The axis is stored in the document's own units, so it moves and scales with the draw:
                // the origin like a point, and the direction by 1/scale because it already carries the
                // reciprocal length. Getting that inverse backwards makes the ramp shrink as the icon
                // grows, which reads as a gradient that is nearly flat at small sizes.
                int at = triangle * 4;
                out.gradient(start[triangle], end[triangle],
                        x + axes[at] * scale, y + axes[at + 1] * scale,
                        axes[at + 2] / scale, axes[at + 3] / scale);
            } else {
                out.color(start == null || flat ? argb : start[triangle]);
            }

            // OPAQUE OVERLAPS, TRANSLUCENT PARTITIONS -- see FILL_OFFSET. Overlap is exactly right when
            // compositing the colour over itself is a no-op, and only then.
            // ONLY THE OUTER WALL IS ANTIALIASED. SvgTriangulator splits every trapezoid the same way,
            // so which edge came from the contour is known rather than inferred: the upper half owns the
            // right wall (p1->p2) and the lower half the left wall (p2->p0). Everything else it touches --
            // the horizontal band cuts and the diagonal split -- is a seam shared with a neighbour, and
            // softening those would fade each one out from both sides into a visible line.
            out.cornerRadius(op.opaque() || upper[triangle] ? FILL_OFFSET : -FILL_OFFSET)
                    .silhouetteEdge(upper[triangle]
                            ? CgVectorRenderer.EDGE_P1_P2 : CgVectorRenderer.EDGE_P2_P0)
                    .feather(SILHOUETTE_FEATHER)
                    .submit();
        }
    }

    private static void drawStroke(CgUiPaintContext ctx, DrawOp op,
                                   float x, float y, float scale, int argb, float halfWidth) {
        float[] s = op.data();
        for (int i = 0; i < s.length; i += 4) {
            // Per SEGMENT, not per op: the caps were decided where the contour structure was still known,
            // so an interior joint gets one round cap and the stroke's real ends keep what the file asked
            // for. See SvgGeometry.segmentsOf.
            int[] caps = op.segmentCaps();
            int packed = caps == null ? op.cap() : caps[i / 4];
            ctx.curve()
                    .line(x + s[i] * scale, y + s[i + 1] * scale,
                            x + s[i + 2] * scale, y + s[i + 3] * scale)
                    .width(halfWidth)
                    .color(argb)
                    .cap(packed & 3, (packed >> 2) & 3)
                    .submit();
        }
    }

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────────

    /** The draw operations, in document order. */
    public List<DrawOp> ops() {
        return ops;
    }

    /** How many stroke segments a draw submits. */
    public int segmentCount() {
        int total = 0;
        for (DrawOp op : ops) if (!op.fill()) total += op.data().length / 4;
        return total;
    }

    /** How many fill triangles a draw submits. */
    public int triangleCount() {
        int total = 0;
        for (DrawOp op : ops) if (op.fill()) total += op.data().length / 6;
        return total;
    }

    /** Every run of points that reached the picture, in the icon's own coordinate space. */
    public List<SvgPath.Polyline> outline() {
        return outline;
    }

    /** The viewBox width — what a caller scales against. */
    public float width() {
        return width;
    }

    public float height() {
        return height;
    }

    public boolean isEmpty() {
        return ops.isEmpty();
    }
}
