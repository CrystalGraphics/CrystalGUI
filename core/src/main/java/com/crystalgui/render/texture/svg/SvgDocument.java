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
import java.util.Set;
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

    /** How many points a full circle or an ellipse is sampled at. */
    private static final int CIRCLE_STEPS = 32;

    /** {@code <use>} recursion depth. A file may legally reference a group that references another. */
    private static final int MAX_USE_DEPTH = 8;

    /**
     * How far a fill triangle is nudged, in screen pixels, to make the coverage partition exact.
     *
     * <h3>Why a fill needs this at all</h3>
     *
     * <p>A fill is a strip of trapezoids, each split into two triangles, and every internal edge is shared
     * by exactly two of them. A pixel centre landing <b>exactly</b> on a shared edge is at SDF distance
     * zero from both, so each reports half coverage and source-over yields {@code 1-(1-0.5)² = 0.75}
     * instead of 1. Measured at a <b>25% dip</b> on one row spanning the whole shape — and only when the
     * coincidence actually happens, which is why the line appears and disappears as a viewer zooms rather
     * than being reliably present.</p>
     *
     * <h3>Two obvious fixes, both wrong</h3>
     *
     * <p>Growing every triangle so neighbours overlap removes the dip and replaces it with a double blend:
     * correct for an opaque fill, and these are not — the IntelliJ set states {@code fill-opacity=".7"}
     * and {@code ".8"} on most shapes, so an overlap composites twice and draws a <em>lighter</em> line
     * along every internal edge, always, at every zoom. Snapping the draw to whole pixels is worse still:
     * this artwork sits on a half-unit grid, so an integer origin at scale 1 puts the band edges
     * <em>precisely</em> on pixel centres. Measured: 0.0000px away.</p>
     *
     * <h3>The partition, made exact</h3>
     *
     * <p>Each triangle is offset by ±this, by which half of its trapezoid it is: the upper half is grown,
     * the lower is shrunk. The two halves share a diagonal, so one claims it and the other does not; and
     * a band's lower half meets the next band's upper half, so every horizontal seam is likewise a shrunk
     * edge against a grown one. <b>Every internal edge therefore belongs to exactly one triangle</b> —
     * full coverage, once, with no gap and no double blend, at any zoom and any alpha.</p>
     *
     * <p>The magnitude only has to beat the shader's own coverage ramp, which is {@code 1e-4}px wide. A
     * thousandth of a pixel does that with three orders of margin and is not a size anything can show.</p>
     */
    private static final float FILL_OFFSET = 0.001f;

    /**
     * How long a gradient cell may be relative to its depth, before it is cut.
     *
     * <p>Purely a rasterisation bound — see the note in {@code emitLinearGradientFill}. A cell's colour is
     * constant along its length, so this trades triangles for fragments, and fragments are what a zoomed
     * icon runs out of first: a long diagonal cell's axis-aligned bounding box grows with the square of
     * its length while the cell itself grows linearly.</p>
     *
     * <p>Two, rather than one: square cells minimise the box but a 2:1 cell's box is only about a quarter
     * larger, for half the triangles.</p>
     */
    private static final float MAX_CELL_ASPECT = 2f;

    /**
     * Elements whose content is a definition, not a picture.
     *
     * <p>{@code defs} and {@code symbol} are the reason this set exists: their children are templates to
     * be pulled in by {@code <use>}, and drawing them where they sit paints every template on top of the
     * artwork at whatever coordinates it was authored at. The rest are things we cannot honour — a
     * {@code clipPath}'s geometry is a stencil, and drawing it as a shape adds an outline that is not in
     * the file at all.</p>
     */
    private static final Set<String> DEFINITIONS = Set.of(
            "defs", "symbol", "clipPath", "mask", "pattern", "marker", "filter",
            "linearGradient", "radialGradient", "title", "desc", "metadata", "style", "script");

    private static final Set<String> SHAPES = Set.of(
            "path", "rect", "circle", "ellipse", "line", "polyline", "polygon");

    private static final Set<String> CONTAINERS = Set.of("svg", "g", "a", "switch");

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
     */
    public record DrawOp(boolean fill, float[] data, @Nullable int[] colours, @Nullable boolean[] upper,
                         int argb, boolean currentColor, float halfWidth, int cap) {
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
        List<SvgScanner.Tag> tags = SvgScanner.scan(svg);
        return new Builder(tags).build();
    }

    // ── Building ────────────────────────────────────────────────────────────────────────────────────

    /**
     * The one-shot walk that turns tags into ops.
     *
     * <p>A class rather than a pile of static methods with a dozen parameters: the walk carries an
     * accumulating output, an id index and a gradient table through every level of recursion, and threading
     * those by hand is how a {@code <use>} ends up appending to the wrong list.</p>
     */
    private static final class Builder {

        private final List<SvgScanner.Tag> tags;
        private final Map<String, Integer> idIndex = new HashMap<>();
        private final Map<String, SvgGradient> servers = new HashMap<>();
        /** The same servers reduced to one colour each — what {@code SvgStyle} needs for strokes. */
        private final Map<String, Integer> gradients = new HashMap<>();
        private final List<DrawOp> ops = new ArrayList<>();
        private final List<SvgPath.Polyline> outline = new ArrayList<>();

        Builder(List<SvgScanner.Tag> tags) {
            this.tags = tags;
        }

        SvgDocument build() {
            float boxWidth = 24f, boxHeight = 24f, originX = 0f, originY = 0f;
            for (SvgScanner.Tag tag : tags) {
                if (!tag.name().equals("svg")) continue;
                String box = tag.get("viewBox");
                if (!box.isBlank()) {
                    String[] parts = box.trim().split("[\\s,]+");
                    if (parts.length == 4) {
                        // min-x and min-y, NOT ignored. A viewBox states an origin as well as a size, and
                        // artwork authored as "-2 -2 28 28" -- which is how a set gives itself padding --
                        // draws offset by exactly that origin otherwise.
                        originX = number(parts[0], 0f);
                        originY = number(parts[1], 0f);
                        boxWidth = number(parts[2], 24f);
                        boxHeight = number(parts[3], 24f);
                    }
                } else {
                    boxWidth = number(tag.get("width"), boxWidth);
                    boxHeight = number(tag.get("height"), boxHeight);
                }
                break;
            }

            indexIds();
            collectGradients();

            // The origin lands in the root transform rather than in a fix-up pass over the points, so
            // nothing downstream ever sees coordinates that are not already in the icon's own 0,0 space.
            SvgTransform root = new SvgTransform(1, 0, 0, 1, -originX, -originY);
            emitRange(0, tags.size(), SvgStyle.ROOT, root, 0);
            return new SvgDocument(ops, outline, boxWidth, boxHeight);
        }

        private void indexIds() {
            for (int i = 0; i < tags.size(); i++) {
                SvgScanner.Tag tag = tags.get(i);
                if (tag.kind() == SvgScanner.Kind.CLOSE) continue;
                String id = tag.get("id");
                if (!id.isEmpty()) idIndex.putIfAbsent(id, i);
            }
        }

        // ── Gradients ───────────────────────────────────────────────────────────────────────────────

        /**
         * Reads every paint server in the file, keeping the whole ramp and not only a colour.
         *
         * <p>Two passes over the same tags: the first builds each gradient from its own attributes and
         * stops, the second resolves {@code href} inheritance. Bounded iteration rather than recursion —
         * a file can reference itself in a cycle, and that must not be how we find out.</p>
         */
        private void collectGradients() {
            Map<String, String> inherits = new HashMap<>();
            for (int i = 0; i < tags.size(); i++) {
                SvgScanner.Tag tag = tags.get(i);
                if (tag.kind() == SvgScanner.Kind.CLOSE) continue;
                boolean linear = tag.name().equals("linearGradient");
                if (!linear && !tag.name().equals("radialGradient")) continue;
                String id = tag.get("id");
                if (id.isEmpty()) continue;

                String href = tag.has("href") ? tag.get("href") : tag.get("xlink:href");
                if (href.startsWith("#")) inherits.put(id, href.substring(1));

                Map<String, String> attributes = new HashMap<>(tag.attributes());
                // SvgGradient reads the element kind out of the same map as everything else, so that its
                // factory takes one argument instead of a boolean nobody can read at the call site.
                attributes.put("__tag__", tag.name());
                int end = tag.kind() == SvgScanner.Kind.OPEN ? matchingClose(i) : i;
                List<Stop> stops = readStops(i, end);
                float[] offsets = new float[stops.size()];
                int[] colours = new int[stops.size()];
                for (int s = 0; s < stops.size(); s++) {
                    offsets[s] = stops.get(s).offset();
                    colours[s] = stops.get(s).argb();
                }
                servers.put(id, SvgGradient.of(attributes, offsets, colours, null));
            }
            for (int pass = 0; pass < 4; pass++) {
                for (Map.Entry<String, String> entry : inherits.entrySet()) {
                    SvgGradient self = servers.get(entry.getKey());
                    SvgGradient source = servers.get(entry.getValue());
                    if (self == null || source == null || self.colours().length > 0) continue;
                    servers.put(entry.getKey(), new SvgGradient(self.radial(), self.userSpace(),
                            self.transform(), self.x1(), self.y1(), self.x2(), self.y2(),
                            source.offsets(), source.colours(), self.spread()));
                }
            }
            for (Map.Entry<String, SvgGradient> entry : servers.entrySet()) {
                if (entry.getValue().colours().length == 0) continue;
                gradients.put(entry.getKey(), entry.getValue().representativeColour());
            }
        }

        /**
         * One gradient stop.
         *
         * <p>A record rather than a {@code float[2]} because the second value is an ARGB int, and a float
         * cannot hold one: 24 bits of mantissa against 32 bits of colour. Opaque colours happen to survive
         * the round trip — {@code 0xFF000000} is exactly {@code -2^24} — and anything with a partial alpha
         * does not, so packing them would work on every stop in the shipped set and quietly shift the
         * colour of the first translucent one anybody adds.</p>
         */
        private record Stop(float offset, int argb) {
        }

        private List<Stop> readStops(int from, int to) {
            List<Stop> out = new ArrayList<>();
            for (int i = from + 1; i <= to && i < tags.size(); i++) {
                SvgScanner.Tag stop = tags.get(i);
                if (!stop.name().equals("stop") || stop.kind() == SvgScanner.Kind.CLOSE) continue;

                // stop-color lives in an attribute OR inside style="", and Illustrator exports use the
                // second exclusively -- reading only the attribute finds no stops at all in a file that is
                // full of them, and every gradient then falls back to grey.
                Map<String, String> declarations = new HashMap<>(stop.attributes());
                declarations.putAll(styleDeclarations(stop.get("style")));

                Integer colour = SvgColor.parseColor(declarations.get("stop-color"));
                if (colour == null) colour = 0xFF000000;
                String opacity = declarations.get("stop-opacity");
                if (opacity != null) colour = SvgColor.withOpacity(colour, number(opacity, 1f));

                String rawOffset = declarations.getOrDefault("offset", "0").trim();
                float offset = rawOffset.endsWith("%")
                        ? number(rawOffset.substring(0, rawOffset.length() - 1), 0f) / 100f
                        : number(rawOffset, 0f);
                out.add(new Stop(Math.max(0f, Math.min(1f, offset)), colour));
            }
            return out;
        }

        // ── The walk ────────────────────────────────────────────────────────────────────────────────

        private void emitRange(int from, int to, SvgStyle style, SvgTransform transform, int depth) {
            int i = from;
            while (i < to) {
                i = emitNode(i, style, transform, depth, false) + 1;
            }
        }

        /**
         * Draws one node and everything under it.
         *
         * @param forced the node was reached through {@code <use>}, so a {@code <symbol>} or a member of
         *               {@code <defs>} is now genuinely part of the picture
         * @return the index of the last token this node consumed
         */
        private int emitNode(int index, SvgStyle style, SvgTransform transform, int depth, boolean forced) {
            SvgScanner.Tag tag = tags.get(index);
            if (tag.kind() == SvgScanner.Kind.CLOSE) return index;

            int end = tag.kind() == SvgScanner.Kind.OPEN ? matchingClose(index) : index;
            String name = tag.name();
            if (DEFINITIONS.contains(name) && !forced) return end;

            SvgStyle childStyle = style.inherit(tag.attributes(), gradients);
            SvgTransform childTransform = SvgTransform.parse(tag.get("transform")).then(transform);

            if (name.equals("use")) {
                emitUse(tag, childStyle, childTransform, depth);
                return end;
            }
            if (SHAPES.contains(name)) {
                emitShape(tag, childStyle, childTransform);
                return end;
            }
            if (tag.kind() == SvgScanner.Kind.OPEN
                    && (CONTAINERS.contains(name) || forced || !DEFINITIONS.contains(name))) {
                // <symbol> and <defs> reached through <use> descend as ordinary groups, and so does any
                // element we do not recognise -- an unknown container that swallowed its children would
                // lose whole regions of a file for a tag we simply had not heard of.
                emitRange(index + 1, end, childStyle, childTransform, depth);
            }
            return end;
        }

        private void emitUse(SvgScanner.Tag tag, SvgStyle style, SvgTransform transform, int depth) {
            if (depth >= MAX_USE_DEPTH) return;
            String href = tag.has("href") ? tag.get("href") : tag.get("xlink:href");
            if (!href.startsWith("#")) return;
            Integer target = idIndex.get(href.substring(1));
            if (target == null) return;

            // x/y on a <use> are a translation, applied INSIDE its own transform -- a sprite sheet that
            // places twenty copies of one symbol depends on it, and dropping it stacks all twenty.
            float x = number(tag.get("x"), 0f);
            float y = number(tag.get("y"), 0f);
            SvgTransform placed = (x != 0f || y != 0f)
                    ? new SvgTransform(1, 0, 0, 1, x, y).then(transform)
                    : transform;
            emitNode(target, style, placed, depth + 1, true);
        }

        private void emitShape(SvgScanner.Tag tag, SvgStyle style, SvgTransform transform) {
            if (!style.fills() && !style.strokes()) return;
            List<SvgPath.Polyline> contours = geometryOf(tag);
            if (contours.isEmpty()) return;

            // Fill first, then stroke -- SVG's own painting order for a single element, and the reason a
            // stroked shape shows its full stroke rather than half of it hidden under the fill.
            //
            // Built BEFORE the contours are transformed, because a gradient has to be sampled in the space
            // it was stated in. userSpaceOnUse means the user space in effect where the gradient is
            // REFERENCED -- so a shape inside a scaled <g> has its paint scaled with it, and sampling after
            // the transform would read the ramp at coordinates it knows nothing about.
            if (style.fills()) emitFill(contours, style, transform);

            for (SvgPath.Polyline contour : contours) {
                for (float[] point : contour.points()) {
                    float px = transform.applyX(point[0], point[1]);
                    float py = transform.applyY(point[0], point[1]);
                    point[0] = px;
                    point[1] = py;
                }
            }
            outline.addAll(contours);

            if (style.strokes()) {
                float[] segments = segmentsOf(contours);
                if (segments.length > 0) {
                    ops.add(new DrawOp(false, segments, null, null, style.strokeArgb(),
                            style.stroke().currentColor(),
                            style.strokeHalfWidth() * transform.lengthScale(), style.cap()));
                }
            }
        }

        private void emitFill(List<SvgPath.Polyline> contours, SvgStyle style, SvgTransform transform) {
            List<List<float[]>> rings = new ArrayList<>(contours.size());
            for (SvgPath.Polyline contour : contours) rings.add(contour.points());

            SvgGradient gradient = style.fill().reference() == null
                    ? null : servers.get(style.fill().reference());
            if (gradient != null && gradient.colours().length < 2) gradient = null;

            float[] box = boundsOf(rings);
            float alpha = style.fillOpacity() * style.opacity();

            if (gradient != null && !gradient.radial()) {
                emitLinearGradientFill(rings, style, transform, gradient, box, alpha);
                return;
            }

            float[] spacing = gradient == null ? new float[]{0f, 0f} : gradient.sampleSpacing(box);
            SvgTriangulator.Fill mesh =
                    SvgTriangulator.fill(rings, style.evenOdd(), spacing[0], spacing[1]);
            float[] triangles = mesh.triangles();
            if (triangles.length == 0) return;

            int[] colours = gradient == null ? null : gradientColours(mesh, gradient, box, alpha);
            transformInPlace(triangles, transform);
            ops.add(new DrawOp(true, triangles, colours, mesh.upper(), style.fillArgb(),
                    style.fill().currentColor(), 0f, 0));
        }

        /**
         * A linear gradient, cut into strips that lie ALONG the ramp rather than across it.
         *
         * <h3>Why the frame is rotated</h3>
         *
         * <p>The scanline cuts in {@code y}. For a gradient running any other direction, a band therefore
         * spans a <em>range</em> of ramp positions, so no single colour is right for it — the flat colour
         * is correct only down the band's middle and wrong at both edges, and the error reverses at the
         * next band. That reads as visible strips with a smooth fade inside each one, which is exactly
         * what it is: the fade is the slices doing their job, and the strip edges are the bands failing
         * at theirs.</p>
         *
         * <p>Rotating the shape so the gradient points along {@code +y} makes every band an <b>iso-line
         * strip</b> — constant ramp position from end to end — so one flat colour per band is not an
         * approximation at all. The triangles are rotated back before they are stored, so nothing
         * downstream knows this happened.</p>
         *
         * <h3>It is also far cheaper</h3>
         *
         * <p>Cutting on both axes to chase a diagonal ramp costs N² cells for N bands of quality, and
         * only O(N) of them carry new colour. Cutting along the ramp needs no horizontal slicing at all,
         * so the cost drops to the bands themselves.</p>
         *
         * <p>Radial gradients keep the old path: their iso-lines are circles, and no rotation makes a
         * circle straight.</p>
         */
        private void emitLinearGradientFill(List<List<float[]>> rings, SvgStyle style,
                                            SvgTransform transform, SvgGradient gradient,
                                            float[] box, float alpha) {
            float[] axis = gradient.effectiveAxis(box);
            float dx = axis[2] - axis[0], dy = axis[3] - axis[1];
            float lengthSq = dx * dx + dy * dy;
            if (lengthSq < 1e-9f) return;
            float length = (float) Math.sqrt(lengthSq);
            float ux = dx / length, uy = dy / length;

            // R maps the ramp direction onto +y, so v is the projection along the axis and u runs across
            // it. Chosen over a general matrix because the inverse is the transpose and both are two
            // multiply-adds -- and because v IS the gradient parameter, unnormalised, which is the whole
            // reason for the change of frame.
            List<List<float[]>> rotated = new ArrayList<>(rings.size());
            for (List<float[]> ring : rings) {
                List<float[]> out = new ArrayList<>(ring.size());
                for (float[] p : ring) out.add(new float[]{uy * p[0] - ux * p[1], ux * p[0] + uy * p[1]});
                rotated.add(out);
            }

            float originV = ux * axis[0] + uy * axis[1];
            float minV = Float.MAX_VALUE, maxV = -Float.MAX_VALUE;
            for (List<float[]> ring : rotated) {
                for (float[] p : ring) {
                    minV = Math.min(minV, p[1]);
                    maxV = Math.max(maxV, p[1]);
                }
            }
            float t0 = (minV - originV) / length;
            float t1 = (maxV - originV) / length;
            int bands = gradient.bandCountFor(t0, t1);
            float stepV = (maxV - minV) / Math.max(1, bands);

            // SLICED ALONG THE BAND TOO -- for the GPU, not for the colour.
            //
            // A band is one ramp position from end to end, so cutting it changes nothing anyone can see.
            // What it changes is the shape of the triangles: rotated back into screen space a band is a
            // long thin DIAGONAL strip, and an instance rasterises the AXIS-ALIGNED bounding quad of its
            // triangle. For a diagonal strip spanning the icon that box is nearly the whole icon.
            //
            // Measured: the JetBrains mark asked the rasteriser for 55.7x its own area in fragments while
            // covering 1.44x -- close to 39x overdraw, which at full-screen zoom is a GPU pinned at 90%
            // and audible fans. Capping each cell's length against its height brings the box back down to
            // roughly the cell itself.
            float stepU = stepV * MAX_CELL_ASPECT;

            SvgTriangulator.Fill mesh = SvgTriangulator.fill(rotated, style.evenOdd(), stepU, stepV);
            float[] triangles = mesh.triangles();
            if (triangles.length == 0) return;

            int[] colours = new int[triangles.length / 6];
            for (int i = 0; i < colours.length; i++) {
                int at = i * 6;
                float v = (triangles[at + 1] + triangles[at + 3] + triangles[at + 5]) / 3f;
                colours[i] = SvgColor.withOpacity(
                        gradient.colourAt(gradient.spreadPublic((v - originV) / length)), alpha);
            }

            // Back out of the rotated frame, then through the element's own transform. Both are affine, so
            // the triangles stay triangles and the mesh stays valid.
            for (int i = 0; i < triangles.length; i += 2) {
                float u = triangles[i], v = triangles[i + 1];
                float x = uy * u + ux * v;
                float y = -ux * u + uy * v;
                triangles[i] = transform.applyX(x, y);
                triangles[i + 1] = transform.applyY(x, y);
            }
            ops.add(new DrawOp(true, triangles, colours, mesh.upper(), style.fillArgb(),
                    style.fill().currentColor(), 0f, 0));
        }

        private static void transformInPlace(float[] triangles, SvgTransform transform) {
            for (int i = 0; i < triangles.length; i += 2) {
                float px = transform.applyX(triangles[i], triangles[i + 1]);
                float py = transform.applyY(triangles[i], triangles[i + 1]);
                triangles[i] = px;
                triangles[i + 1] = py;
            }
        }

        /**
         * One colour per triangle, sampled once per <b>slice</b> rather than once per triangle.
         *
         * <p>The two halves of a slice are split along a diagonal, so their centroids sit on opposite
         * sides of it and pick up different colours. That is invisible on its own, and stops being
         * invisible the moment the draw dilates each triangle half a pixel to hide the seams between
         * slices: each half then overwrites a strip of the other along the shared diagonal, and whichever
         * is submitted second wins. The result is a diagonal hatch of the wrong colour across the whole
         * shape, strongest exactly where the gradient is steepest. Giving a slice one colour makes that
         * overlap land on itself.</p>
         */
        private static int[] gradientColours(SvgTriangulator.Fill mesh, SvgGradient gradient,
                                             float[] box, float alpha) {
            int[] slice = mesh.slice();
            float[] triangles = mesh.triangles();
            int count = slice.length;
            if (count == 0) return new int[0];

            int sliceCount = slice[count - 1] + 1;
            float[] sumX = new float[sliceCount];
            float[] sumY = new float[sliceCount];
            int[] samples = new int[sliceCount];
            for (int i = 0; i < count; i++) {
                int at = i * 6;
                for (int v = 0; v < 6; v += 2) {
                    sumX[slice[i]] += triangles[at + v];
                    sumY[slice[i]] += triangles[at + v + 1];
                }
                samples[slice[i]] += 3;
            }

            int[] resolved = new int[sliceCount];
            for (int s = 0; s < sliceCount; s++) {
                if (samples[s] == 0) continue;
                resolved[s] = SvgColor.withOpacity(
                        gradient.colourAt(sumX[s] / samples[s], sumY[s] / samples[s], box), alpha);
            }
            int[] out = new int[count];
            for (int i = 0; i < count; i++) out[i] = resolved[slice[i]];
            return out;
        }

        /** {@code minX, minY, width, height} — what an {@code objectBoundingBox} gradient resolves against. */
        private static float[] boundsOf(List<List<float[]>> rings) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (List<float[]> ring : rings) {
                for (float[] point : ring) {
                    minX = Math.min(minX, point[0]);
                    minY = Math.min(minY, point[1]);
                    maxX = Math.max(maxX, point[0]);
                    maxY = Math.max(maxY, point[1]);
                }
            }
            if (minX > maxX) return new float[]{0f, 0f, 1f, 1f};
            return new float[]{minX, minY, Math.max(1e-6f, maxX - minX), Math.max(1e-6f, maxY - minY)};
        }

        // ── Geometry ────────────────────────────────────────────────────────────────────────────────

        private static List<SvgPath.Polyline> geometryOf(SvgScanner.Tag tag) {
            List<SvgPath.Polyline> out = new ArrayList<>();
            switch (tag.name()) {
                case "path" -> out.addAll(SvgPath.parse(tag.get("d")));
                case "polyline" -> addPoints(out, tag.get("points"), false);
                case "polygon" -> addPoints(out, tag.get("points"), true);
                case "line" -> out.add(new SvgPath.Polyline(List.of(
                        new float[]{number(tag.get("x1"), 0f), number(tag.get("y1"), 0f)},
                        new float[]{number(tag.get("x2"), 0f), number(tag.get("y2"), 0f)}), false));
                case "rect" -> addRect(out, tag);
                case "circle" -> addEllipse(out, number(tag.get("cx"), 0f), number(tag.get("cy"), 0f),
                        number(tag.get("r"), 0f), number(tag.get("r"), 0f));
                case "ellipse" -> addEllipse(out, number(tag.get("cx"), 0f), number(tag.get("cy"), 0f),
                        number(tag.get("rx"), 0f), number(tag.get("ry"), 0f));
                default -> { }
            }
            return out;
        }

        private static void addPoints(List<SvgPath.Polyline> out, String raw, boolean closed) {
            if (raw.isBlank()) return;
            String[] numbers = raw.trim().split("[\\s,]+");
            List<float[]> points = new ArrayList<>();
            for (int i = 0; i + 1 < numbers.length; i += 2) {
                points.add(new float[]{number(numbers[i], 0f), number(numbers[i + 1], 0f)});
            }
            if (points.size() > 1) out.add(new SvgPath.Polyline(points, closed));
        }

        /** A rect, with {@code rx}/{@code ry} corners when it has them. */
        private static void addRect(List<SvgPath.Polyline> out, SvgScanner.Tag tag) {
            float x = number(tag.get("x"), 0f), y = number(tag.get("y"), 0f);
            float w = number(tag.get("width"), 0f), h = number(tag.get("height"), 0f);
            float rx = number(tag.get("rx"), 0f), ry = number(tag.get("ry"), 0f);
            if (w <= 0f || h <= 0f) return;
            if (ry == 0f) ry = rx;
            if (rx == 0f) rx = ry;
            rx = Math.min(rx, w / 2f);
            ry = Math.min(ry, h / 2f);

            if (rx <= 0f || ry <= 0f) {
                out.add(new SvgPath.Polyline(new ArrayList<>(List.of(
                        new float[]{x, y}, new float[]{x + w, y},
                        new float[]{x + w, y + h}, new float[]{x, y + h})), true));
                return;
            }
            // Expressed as a path so the corner arcs go through the one arc implementation rather than a
            // second, subtly different one here.
            String d = "M" + (x + rx) + " " + y
                    + " H" + (x + w - rx) + " A" + rx + " " + ry + " 0 0 1 " + (x + w) + " " + (y + ry)
                    + " V" + (y + h - ry) + " A" + rx + " " + ry + " 0 0 1 " + (x + w - rx) + " " + (y + h)
                    + " H" + (x + rx) + " A" + rx + " " + ry + " 0 0 1 " + x + " " + (y + h - ry)
                    + " V" + (y + ry) + " A" + rx + " " + ry + " 0 0 1 " + (x + rx) + " " + y + " Z";
            out.addAll(SvgPath.parse(d));
        }

        private static void addEllipse(List<SvgPath.Polyline> out, float cx, float cy, float rx, float ry) {
            if (rx <= 0f || ry <= 0f) return;
            List<float[]> points = new ArrayList<>();
            for (int i = 0; i < CIRCLE_STEPS; i++) {
                double t = 2 * Math.PI * i / CIRCLE_STEPS;
                points.add(new float[]{(float) (cx + rx * Math.cos(t)), (float) (cy + ry * Math.sin(t))});
            }
            out.add(new SvgPath.Polyline(points, true));
        }

        /**
         * Flattens runs into drawable segments.
         *
         * <p>Degenerate segments are dropped <b>here</b> rather than at draw time. A round cap on a segment
         * of no length paints a filled dot, so a path whose {@code z} closes onto the point it already
         * ended at would leave a speck at its start — and filtering per frame would pay for that check on
         * every draw of every icon forever. It is a property of the geometry, so it belongs with it.</p>
         */
        private static float[] segmentsOf(List<SvgPath.Polyline> contours) {
            List<float[]> found = new ArrayList<>();
            for (SvgPath.Polyline contour : contours) {
                List<float[]> points = contour.points();
                for (int i = 0; i + 1 < points.size(); i++) {
                    addSegment(found, points.get(i), points.get(i + 1));
                }
                if (contour.closed() && points.size() > 1) {
                    addSegment(found, points.get(points.size() - 1), points.get(0));
                }
            }
            float[] packed = new float[found.size() * 4];
            for (int i = 0; i < found.size(); i++) {
                System.arraycopy(found.get(i), 0, packed, i * 4, 4);
            }
            return packed;
        }

        private static void addSegment(List<float[]> out, float[] a, float[] b) {
            if (Math.abs(a[0] - b[0]) < 0.001f && Math.abs(a[1] - b[1]) < 0.001f) return;
            out.add(new float[]{a[0], a[1], b[0], b[1]});
        }

        // ── Tokens ──────────────────────────────────────────────────────────────────────────────────

        /**
         * The index of the {@code </name>} closing the tag at {@code open}.
         *
         * <p>Matched <b>by name</b>, not by a bare depth counter: real files carry stray closing tags and
         * unclosed ones, and a counter walks straight past the true close of a group into its siblings —
         * which silently reparents them and applies the wrong transform to the rest of the file.</p>
         */
        private int matchingClose(int open) {
            String name = tags.get(open).name();
            int depth = 0;
            for (int i = open + 1; i < tags.size(); i++) {
                SvgScanner.Tag tag = tags.get(i);
                if (!tag.name().equals(name)) continue;
                if (tag.kind() == SvgScanner.Kind.OPEN) depth++;
                else if (tag.kind() == SvgScanner.Kind.CLOSE) {
                    if (depth == 0) return i;
                    depth--;
                }
            }
            return tags.size() - 1;
        }
    }

    private static Map<String, String> styleDeclarations(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null) return out;
        for (String part : raw.split(";")) {
            int colon = part.indexOf(':');
            if (colon <= 0) continue;
            out.put(part.substring(0, colon).trim().toLowerCase(), part.substring(colon + 1).trim());
        }
        return out;
    }

    private static float number(String raw, float fallback) {
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
        int[] colours = op.colours();
        boolean[] upper = op.upper();
        for (int i = 0; i < t.length; i += 6) {
            int triangle = i / 6;
            ctx.triangle()
                    .points(x + t[i] * scale, y + t[i + 1] * scale,
                            x + t[i + 2] * scale, y + t[i + 3] * scale,
                            x + t[i + 4] * scale, y + t[i + 5] * scale)
                    .color(colours == null || flat ? argb : colours[triangle])
                    // Grown or shrunk by a thousandth of a pixel, alternating across every shared edge --
                    // see FILL_OFFSET. A NEGATIVE radius erodes, which is why stroke.glsl no longer clamps
                    // it to zero.
                    .cornerRadius(upper[triangle] ? FILL_OFFSET : -FILL_OFFSET)
                    // No feather: the offsets above make the partition exact, and any ramp would put the
                    // two sides of an internal edge back into partial coverage together.
                    .feather(0f)
                    .submit();
        }
    }

    private static void drawStroke(CgUiPaintContext ctx, DrawOp op,
                                   float x, float y, float scale, int argb, float halfWidth) {
        float[] s = op.data();
        for (int i = 0; i < s.length; i += 4) {
            ctx.curve()
                    .line(x + s[i] * scale, y + s[i + 1] * scale,
                            x + s[i + 2] * scale, y + s[i + 3] * scale)
                    .width(halfWidth)
                    .color(argb)
                    .cap(op.cap() == CgVectorRenderer.CAP_BUTT ? CgVectorRenderer.CAP_ROUND : op.cap())
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
