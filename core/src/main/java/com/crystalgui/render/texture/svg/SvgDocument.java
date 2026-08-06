package com.crystalgui.render.texture.svg;

import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.util.profiling.CgProfiler;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.CgUiPaintContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

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

    /**
     * Narrower than this, in screen pixels, and the edge is drawn as a hard step instead.
     *
     * <h3>Half a pixel is where antialiasing stops carrying information</h3>
     *
     * <p>A coverage ramp narrower than half a pixel spans less than one sample spacing, so at most one
     * pixel can land inside it and there is no gradient left to resolve — the edge is decided by a single
     * sample either way. Tapering it therefore buys nothing, and it <b>costs</b> something: that one pixel
     * comes out at partial coverage, and partial coverages from abutting shapes composite as
     * {@code 1-(1-c)^n} rather than summing, so the shortfall shows as background leaking through.</p>
     *
     * <p>That is the whole of the grainy dark speckle on artwork built from many small abutting shapes. The
     * clamp against triangle height already stops a 1px band being smeared over a 0.2px wedge; this
     * finishes the job by refusing the residual sliver of a feather that is left.</p>
     *
     * <h3>Why it is safe for detail, which is what it was tested against</h3>
     *
     * <p>The obvious fear is thin features vanishing: a hairline with no coverage ramp is visible only if a
     * pixel centre happens to fall inside it. What makes it safe is that the threshold is compared against
     * the shape's <b>own height</b> and never against the zoom — anything half a pixel or wider keeps its
     * full feather, so only shapes already too small to antialias are ever snapped.</p>
     *
     * <p>Measured rather than assumed, on the IntelliJ mark at 0.75x — the most detail-dense icon shipped:
     * turning the cutoff on changes <b>8 pixels, by at most 29/255</b>, and nothing is lost or broken up.
     * Over the same step the colour wheel goes from grainy to clean. That ratio is the argument for the
     * number; it is not free, it is just very cheap.</p>
     */
    private static final float MINIMUM_FEATHER = 0.5f;




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
                         @Nullable boolean[] upper, @Nullable boolean[] outerWall, boolean opaque,
                         int argb, boolean currentColor,
                         float halfWidth, int cap, @Nullable int[] segmentCaps) {

        /** Whether triangle {@code i} carries a per-pixel ramp rather than a flat colour. */
        public boolean hasGradient(int triangle) {
            return gradients != null && coloursEnd != null;
        }
    }

    /** Retained so {@link #ops()} can tessellate on demand; see the laziness note there. */
    private final SvgScene scene;
    /** Null until {@link #ops()} builds it. Volatile for the double-checked read in that method. */
    private volatile List<DrawOp> ops;
    private final List<SvgPath.Polyline> outline;
    private final float width;
    private final float height;
    /** {@code minX, minY, maxX, maxY} over every contour — see {@link #boundsOf}. */
    private final float[] bounds;

    private SvgDocument(SvgScene scene) {
        this.scene = scene;
        List<SvgPath.Polyline> contours = new ArrayList<>();
        for (SvgScene.Node node : scene.nodes()) contours.addAll(node.contours());
        // Unmodifiable, because a document is cached and shared by every consumer drawing that icon --
        // one caller sorting or clearing what it got back would corrupt the picture for all of them.
        this.outline = Collections.unmodifiableList(contours);
        this.width = scene.width();
        this.height = scene.height();
        this.bounds = boundsOf(scene);
    }

    /**
     * The box every op actually occupies, for culling.
     *
     * <p><b>Measured, not taken from the viewBox.</b> Artwork routinely draws outside its own viewBox —
     * a stroke centred on the edge puts half its width beyond it — and a cull box that is too small is a
     * missing icon, which is the one failure mode worth engineering against here.</p>
     *
     * <p>Because it is measured, it needs <b>no slack</b>. {@code CgTextCuller} pads by a full layout
     * height because a text box is a layout construct that glyphs are not obliged to stay inside; this
     * box is the geometry itself, so there is nothing to overhang it. Strokes are expanded by their own
     * half-width, which is the only thing the raw points understate.</p>
     */
    private static float[] boundsOf(SvgScene scene) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (SvgScene.Node node : scene.nodes()) {
            float pad = node.stroke() == null ? 0f : node.stroke().halfWidth();
            for (SvgPath.Polyline contour : node.contours()) {
                for (float[] point : contour.points()) {
                    minX = Math.min(minX, point[0] - pad);
                    minY = Math.min(minY, point[1] - pad);
                    maxX = Math.max(maxX, point[0] + pad);
                    maxY = Math.max(maxY, point[1] + pad);
                }
            }
        }
        if (minX > maxX) return new float[]{0f, 0f, 0f, 0f};
        // Unioned with the viewBox, because the contours this is measured from are flattened at
        // PARSE_STEPS -- the coarsest resolution in the document. A three-segment approximation of an arc
        // sits INSIDE the true curve, so a box measured from it can be a fraction under the ink it is
        // meant to contain, and a cull box that is too small is a missing icon. The viewBox is the author's
        // own statement of where the artwork lives and costs nothing to include; the measured term is what
        // still catches a stroke hanging outside it.
        return new float[]{Math.min(minX, 0f), Math.min(minY, 0f),
                Math.max(maxX, scene.width()), Math.max(maxY, scene.height())};
    }

    /**
     * A cheap reject before any geometry is submitted.
     *
     * <p>The saving is not the draw call — one instanced draw of every icon on screen measured 3.8
     * <b>micro</b>seconds. It is the CPU submission loop that feeds it: {@code drawFill} costs about 90ns
     * per triangle, so an icon scrolled out of a tree, or the fifty-odd cells of a grid that a zoom has
     * pushed off screen, are paid for in full every frame for nothing.</p>
     */
    /**
     * The coarsest mesh whose faceting this draw size cannot resolve, or {@code this} when the icon is
     * large enough to need the reference one.
     *
     * <p>Keyed on DEVICE height, so the pose's scale counts: the same icon in the same layout box is a
     * different number of real pixels at {@code uiScale} 1 and 2, and picking a level from the logical
     * size alone would facet on a HiDPI display and nowhere else.</p>
     */
    private SvgDocument lodFor(CgUiPaintContext ctx, float scale) {
        if (tags == null) return this;
        float devicePx = Math.max(width, height) * scale * ctx.deviceScale();
        for (int i = 0; i < LOD_MAX_DEVICE_PX.length; i++) {
            if (devicePx > LOD_MAX_DEVICE_PX[i]) continue;
            SvgDocument cached = lods.get(LOD_STEPS[i]);
            if (cached != null) return cached;

            long frame = ctx.frameId();
            if (frame != lodBudgetFrame) {
                // Sampled on the way OUT of a frame, which is the only place the per-frame total is known.
                // An aggregate scope cannot answer "did the budget spread the work" -- 12ms of building
                // looks identical whether it landed on one frame or twenty. This records the distribution,
                // so the max IS the worst frame.
                if (lodNanosThisFrame > 0L) {
                    CgProfiler.sample("svg.lodMsPerFrame", lodNanosThisFrame / 1_000_000.0);
                }
                lodBudgetFrame = frame;
                lodNanosThisFrame = 0L;
            }
            // Out of budget: draw with whatever coarse mesh already exists and try again next frame.
            // Deliberately not a queue -- whatever is on screen next frame is what deserves the budget,
            // and a queue built from this frame's visibility would keep building icons a zoom has already
            // left behind.
            //
            // It used to fall back to `this`, the reference mesh, which was free because parse had already
            // built it. Now that ops() is lazy that fallback is the single MOST expensive mesh in the
            // document and drawing it would tessellate at REFERENCE_STEPS mid-frame, outside this very
            // budget -- so the deferral would cost more than the build it declined. Measured: parse got
            // 24 ms cheaper and the first frame got 25 ms DEARER, for no net gain.
            //
            // So: prefer any tier already built, and if there is none, build the requested one anyway.
            // Overshooting the budget by one coarse tier is strictly cheaper than the alternative.
            if (lodNanosThisFrame >= LOD_BUILD_BUDGET_NANOS) {
                SvgDocument fallback = coarsestBuilt();
                if (fallback != null) {
                    // Counted, so "the budget deferred work" is visible rather than inferred from its absence.
                    CgProfiler.count("svg.lodDeferred.count");
                    CgProfiler.sample("svg.lodDeferred", 1.0);
                    return fallback;
                }
                CgProfiler.count("svg.lodOverBudget.count");
            }
            long startedAt = System.nanoTime();
            // Scoped so the COST OF BUILDING one is visible separately from drawing with it: this runs on
            // the first frame a size is used, so a zoom crossing a threshold rebuilds every visible icon
            // in a single frame. That is the hitch worth knowing about, and it cannot be seen in a steady
            // state average.
            try (CgProfiler.Scope ignored = CgProfiler.scope("svg.lodBuild")) {
                CgProfiler.count("svg.lodBuild.count");
                CgProfiler.count("svg.lodBuild.steps" + LOD_STEPS[i]);
                CgProfiler.sample("svg.lodDevicePx", devicePx);
                CgProfiler.sample("svg.lodDeferred", 0.0);
                SvgDocument built = lods.computeIfAbsent(LOD_STEPS[i], steps -> {
                    SvgDocument tier = fromScene(SvgResolver.resolve(tags, steps));
                    // Forced HERE rather than left to the first draw. ops() is lazy now, and the whole
                    // point of this scope is that the build is charged against LOD_BUILD_BUDGET_NANOS --
                    // let the tessellation escape it and the budget silently stops spreading the work it
                    // exists to spread, which shows up as the zoom hitch it was written to remove.
                    tier.ops();
                    return tier;
                });
                lodNanosThisFrame += System.nanoTime() - startedAt;
                return built;
            }
        }
        return this;
    }

    /**
     * The cheapest already-built tier, or null when none exists yet.
     *
     * <p>What a budget-exhausted frame draws with. Coarsest rather than closest-to-ideal because the
     * point is to submit <em>something</em> without building, and a tier that is too coarse for one frame
     * of a zoom is a facet nobody sees at 60fps — whereas the alternative this replaced was a full
     * reference tessellation on the render thread.</p>
     */
    @Nullable
    private SvgDocument coarsestBuilt() {
        for (int steps : LOD_STEPS) {
            SvgDocument tier = lods.get(steps);
            if (tier != null) return tier;
        }
        return null;
    }

    private boolean cullable(CgUiPaintContext ctx, float x, float y, float scale, float extra) {
        if (scene.isEmpty()) return true;
        float x0 = x + bounds[0] * scale - extra;
        float y0 = y + bounds[1] * scale - extra;
        float x1 = x + bounds[2] * scale + extra;
        float y1 = y + bounds[3] * scale + extra;
        return !ctx.isVisible(x0, y0, x1 - x0, y1 - y0);
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

    /**
     * Loads and parses icons on a worker thread, so the first frame that draws them finds them ready.
     *
     * <h3>Why this exists, and why it beats making the parse faster</h3>
     *
     * <p>Loading the shipped set costs about 70 ms from cold, and roughly <b>85% of that is JIT warmup</b>
     * rather than work — the same parse warm is a few milliseconds. Optimising the code cannot reach the
     * bulk of it, and every millisecond that is left still lands on whichever frame first draws an icon.
     * Moving it off that frame removes all of it.</p>
     *
     * <p><b>Parsing is movable precisely because it touches no GL.</b> Everything from {@code CgIO} through
     * scanning, resolution and tessellation is arithmetic over strings and floats — which is the same
     * property that lets it run in {@code headlessTest} on a dedicated server, and the reason this is a
     * safe threading boundary rather than a hopeful one.</p>
     *
     * <h3>What is safe here, and what is not</h3>
     *
     * <p>A parsed document is effectively immutable — {@link #ops()} builds under a lock into a volatile
     * field, and the LOD map is concurrent — so sharing one across threads is fine. {@link #CACHE} is a
     * {@code ConcurrentHashMap}, and a racing double parse of the same path wastes work without being
     * wrong.</p>
     *
     * <p><b>Do not call {@link #render} from a worker.</b> That submits to the paint context and touches
     * GL; only loading and parsing belong here. And {@link #lodFor}'s budget is per-frame global state, so
     * tier builds stay on the render thread deliberately — this warms the parse, not the LOD ladder.</p>
     *
     * @param paths namespaced icon paths, exactly as {@link #of} takes them
     * @return completes when every path has been attempted; a path that fails to load is logged by
     *         {@link #load} and left out of the cache, as it would be on the render thread
     */
    public static CompletableFuture<Void> preload(Collection<String> paths) {
        List<CompletableFuture<Void>> pending = new ArrayList<>(paths.size());
        for (String path : paths) {
            if (CACHE.containsKey(path)) continue;
            pending.add(CompletableFuture.runAsync(() -> of(path), PreloadPool.INSTANCE));
        }
        return CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]));
    }

    /**
     * The worker pool preloading runs on, in a holder so it is created on first {@link #preload} and not
     * before.
     *
     * <p>A {@code static final} field here would spin up threads the moment this class is touched — which
     * on a dedicated server is every time an icon path is merely parsed, for a pool that will never be
     * handed a task. Class initialisation is the laziness, and it costs nothing to get right.</p>
     *
     *
     * <p>Daemon threads, so a process that exits mid-preload is not held open by icon parsing, and at most
     * a few of them: this is CPU-bound work competing with the render thread for cores, and the point is to
     * be finished before the first draw rather than to finish as fast as physically possible. Bounded at
     * two below the core count for the same reason {@code CgProfiler}-era measurements showed the render
     * thread starving when a pool took everything.</p>
     */
    private static final class PreloadPool {

        static final Executor INSTANCE = Executors.newFixedThreadPool(
                Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)),
                runnable -> {
                    Thread thread = new Thread(runnable, "cgui-svg-preload");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    /** Loads {@code "namespace:ui/icons/folder.svg"} through {@code CgIO}, or null when it is unreadable. */
    @Nullable
    public static SvgDocument load(String path) {
        String source;
        try (CgProfiler.Scope ignored = CgProfiler.scope("svg.loadSource")) {
            source = CgIO.loadSource(path);
        }
        if (source == null) {
            CrystalGuiCore.LOGGER.warn("Icon {} could not be read", path);
            return null;
        }
        try (CgProfiler.Scope ignored = CgProfiler.scope("svg.parse")) {
            CgProfiler.count("svg.parse.count");
            return parse(source);
        }
    }

    /**
     * Parses SVG text into a document.
     *
     * <h3>No profiler scopes in here, and they cannot be added</h3>
     *
     * <p>Parsing is pure geometry, so this is reachable from {@code headlessTest} — where CrystalGraphics
     * <em>core</em> is deliberately absent, and {@code CgProfiler} lives there. A scope inside a method body
     * still compiles and still passes {@code :core:test}; it fails at run time with
     * {@code NoClassDefFoundError}, in the one source set that exists to catch it. This method shipped
     * instrumented for exactly one session before that surfaced.</p>
     *
     * <p>{@link #load} is the profiled entry point and is <b>not</b> headless — it reads through {@code CgIO},
     * which is CrystalGraphics core already. Anything wanting a finer breakdown than {@code svg.parse}
     * should add it there, or temporarily, and take it back out.</p>
     */
    public static SvgDocument parse(String svg) {
        List<SvgScanner.Tag> tags = SvgScanner.scan(svg);
        SvgDocument document = fromScene(SvgResolver.resolve(tags, PARSE_STEPS));
        document.tags = tags;
        // The root IS the coarsest tier, registered as such so lodFor finds it rather than resolving a
        // second, identical copy of it.
        document.lods.put(PARSE_STEPS, document);
        return document;
    }

    /**
     * Curve resolution the reference mesh is built at — what {@link #ops()} and every test sees.
     *
     * <p>Chosen for a LARGE draw: an icon zoomed to fill a screen must not facet. That makes it far finer
     * than a file-tree row can resolve, which is what {@link #lodFor} exists to walk back.</p>
     */
    private static final int REFERENCE_STEPS = 16;

    /**
     * Coarse resolutions and the device height each is good for, largest first.
     *
     * <p><b>A mesh is cut at every flattened vertex</b>, so halving the flattening roughly halves the
     * triangle count — and {@link SvgTriangulator}'s own note is that "a 16px icon can display at most
     * sixteen bands; anything finer is subdivision no display can resolve". A file tree drawing 40 icons
     * at 16px was submitting ~18,000 triangles to fill 40 boxes sixteen pixels tall.</p>
     *
     * <p>The thresholds are deliberately generous — each level is used only well below the size where its
     * own faceting could reach a pixel — because the failure mode is a visibly polygonal icon and the
     * saving is already large at conservative settings.</p>
     */
    /**
     * Curve resolution {@link #parse} resolves at — the <b>coarsest</b> tier, not the reference one.
     *
     * <h3>Why parse builds the cheapest mesh rather than the best one</h3>
     *
     * <p>Parsing used to resolve at {@link #REFERENCE_STEPS}, the resolution a full-screen zoom needs.
     * Almost nothing draws at that size, so for every icon in a file tree the finest flattening in the
     * document was produced and then immediately replaced by a tier built independently from the retained
     * tags. Measured over the shipped set, that was <b>the larger half of a 120 ms load</b>, spent on
     * geometry that was never submitted.</p>
     *
     * <p>Resolving at the coarsest tier inverts it: parse produces the one mesh that is <em>always</em>
     * useful — something to draw on the first frame — and every finer tier is built on demand, by the
     * machinery that already existed for exactly that. {@link #REFERENCE_STEPS} is now simply the top
     * tier, reached when an icon really is drawn past {@code LOD_MAX_DEVICE_PX}'s last threshold.</p>
     *
     * <p><b>This is not a quality change.</b> Which mesh gets drawn at a given size is decided by
     * {@link #lodFor} and is unchanged; only the moment each one is built has moved.</p>
     */
    private static final int PARSE_STEPS = 3;

    private static final int[] LOD_MAX_DEVICE_PX = {24, 64, 160, Integer.MAX_VALUE};
    private static final int[] LOD_STEPS = {PARSE_STEPS, 5, 9, REFERENCE_STEPS};

    /**
     * The scanned document, retained so a coarser mesh can be built on demand.
     *
     * <p>Lazily, and only for the resolutions actually asked for: most icons are drawn at one size for
     * their whole life, so building every level up front would be strictly worse than not having levels
     * at all.</p>
     */
    /**
     * How long may be spent building coarser meshes in one frame, in nanoseconds.
     *
     * <p><b>The build is lazy, so without a budget it all lands on one frame.</b> Measured: 0.21ms per
     * icon and 12.07ms to build all 57 at once — a dropped frame, and it recurs every time a zoom crosses
     * a level threshold, which is precisely while the user is interacting.</p>
     *
     * <p>Spreading it costs nothing visually. An icon whose coarse mesh is not ready yet draws with the
     * REFERENCE mesh, which is the same picture with more triangles — so the only observable effect is
     * that the saving arrives over a few frames instead of all at once. That is the right trade: a steady
     * state reached a quarter of a second late is invisible, a dropped frame during a zoom is not.</p>
     *
     * <p>A time budget rather than a count, because per-icon cost varies nearly tenfold (0.21ms average
     * against a 1.87ms worst case) and a count would let a few complex icons blow through it anyway.</p>
     */
    private static final long LOD_BUILD_BUDGET_NANOS = 1_000_000L;

    /**
     * Render-thread only, hence plain statics: CrystalGUI paints from one thread, and a budget shared
     * across documents is the whole point — the stall comes from FIFTY-SEVEN of them building at once, so
     * a per-document limit would not bound anything.
     */
    private static long lodBudgetFrame = -1L;
    private static long lodNanosThisFrame;

    private List<SvgScanner.Tag> tags;
    private final Map<Integer, SvgDocument> lods = new ConcurrentHashMap<>();

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
        return new SvgDocument(scene);
    }

    /** The tessellation half of {@link #fromScene}, deferred — see {@link #ops()}. */
    private static List<DrawOp> buildOps(SvgScene scene) {
        List<DrawOp> ops = new ArrayList<>();
        for (SvgScene.Node node : scene.nodes()) {
            SvgScene.Fill fill = node.fill();
            if (fill != null) {
                SvgMesh mesh = SvgTessellator.tessellate(node.contours(), fill.evenOdd(), fill.paint());
                if (!mesh.isEmpty()) {
                    SvgScene.Paint paint = fill.paint();
                    int argb = paint instanceof SvgScene.Gradient ramp
                            ? ramp.argb() : ((SvgScene.Solid) paint).argb();
                    ops.add(new DrawOp(true, mesh.triangles(), mesh.colour0(), mesh.colour1(),
                            mesh.axes(), mesh.upper(), mesh.outerWall(), mesh.opaque(), argb,
                            paint.currentColor(), 0f, 0, null));
                }
            }

            SvgScene.Stroke stroke = node.stroke();
            if (stroke != null) {
                SvgGeometry.Segments segments = SvgGeometry.segmentsOf(
                        node.contours(), stroke.cap() & 3, (stroke.cap() >> 2) & 3);
                if (segments.data().length > 0) {
                    SvgScene.Solid paint = (SvgScene.Solid) stroke.paint();
                    ops.add(new DrawOp(false, segments.data(), null, null, null, null, null, true,
                            paint.argb(), paint.currentColor(), stroke.halfWidth(), stroke.cap(),
                            segments.caps()));
                }
            }
        }
        return ops;
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
        if (cullable(ctx, x, y, scale, 0f)) return;
        SvgDocument lod = lodFor(ctx, scale);
        if (lod != this) {
            lod.render(ctx, x, y, scale, tint);
            return;
        }
        for (DrawOp op : ops()) {
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
        // The override can be WIDER than the stroke the bounds were measured from, so it has to expand
        // the box -- otherwise a thick monochrome stroke gets culled at the viewport edge while still
        // partly on screen.
        if (cullable(ctx, x, y, scale, Math.max(0f, halfWidth))) return;
        SvgDocument lod = lodFor(ctx, scale);
        if (lod != this) {
            lod.renderMonochrome(ctx, x, y, scale, argb, halfWidth);
            return;
        }
        for (DrawOp op : ops()) {
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
        // Scoped per OP, never per triangle: a scope costs a nanoTime pair, and a fill is hundreds of
        // triangles, so per-triangle instrumentation would measure itself. The triangle count rides along
        // as a counter instead, which is what turns "drawFill is slow" into "drawFill is slow per triangle"
        // or "there are simply a lot of triangles".
        CgProfiler.Scope scope = CgProfiler.scope("svg.drawFill");
        CgProfiler.count("svg.fillTriangles", op.data().length / 6);
        try (CgProfiler.Scope ignored = scope) {
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
            // ...and only when that wall is REALLY the contour. A band cut into slices has one contour
            // edge at each end and seams in between, and handing a seam over as the silhouette feathers it
            // from one side while the neighbour steps hard there -- coverage never reaches 1, and every
            // slice boundary draws as a line down the shape. Only a radial gradient slices, so for
            // everything else outerWall is true throughout and nothing changes.
            boolean[] outer = op.outerWall();
            boolean contourWall = outer == null || outer[triangle];

            // A FEATHER MAY NEVER BE WIDER THAN THE SHAPE IT SOFTENS.
            //
            // The band is stated in screen pixels, which is right for a silhouette and catastrophic for a
            // sliver. Artwork built from many abutting shapes -- a colour wheel is 361 separate wedges --
            // puts each of them below a pixel once the icon is small: at 24px the disc's circumference is
            // ~75px over 361 wedges, so a wedge is 0.2px wide and was being softened over 1px. Its coverage
            // spreads to about 0.2, five neighbours composite to 1-0.8^5 = 0.67, and the missing third is
            // BACKGROUND showing through. That is the muddy, dark wheel at tab size, and it is why turning
            // the feather off entirely made it clean.
            //
            // Clamping to the triangle's own height against the edge being softened fixes it without
            // costing anything at sizes where the shape is bigger than the band -- there the clamp never
            // binds and the silhouette is antialiased exactly as before.
            float featherPx = 0f;
            if (contourWall) {
                float ax = t[i], ay = t[i + 1];
                float bx = t[i + 2], by = t[i + 3];
                float cx = t[i + 4], cy = t[i + 5];
                // The upper half softens p1->p2 (opposite p0), the lower half p2->p0 (opposite p1).
                float ex, ey, sx, sy, ox, oy;
                if (upper[triangle]) {
                    sx = bx; sy = by; ex = cx - bx; ey = cy - by; ox = ax; oy = ay;
                } else {
                    sx = cx; sy = cy; ex = ax - cx; ey = ay - cy; ox = bx; oy = by;
                }
                float edgeLength = (float) Math.sqrt(ex * ex + ey * ey);
                if (edgeLength > 1e-9f) {
                    float area2 = Math.abs(ex * (oy - sy) - ey * (ox - sx));
                    featherPx = Math.min(SILHOUETTE_FEATHER, area2 / edgeLength * scale);
                    // ...and below half a pixel, drop it entirely rather than taper. See MINIMUM_FEATHER.
                    if (featherPx < MINIMUM_FEATHER) featherPx = 0f;
                }
            }
            // EDGE_NONE means "antialias the WHOLE outline", not "antialias nothing" -- with a feather it
            // would soften all three edges of an interior cell, which is worse than the seam it is meant to
            // cure. A feather of zero is what makes every edge a hard step: stroke.glsl clamps the ramp to
            // 1e-6, so the smoothstep degenerates.
            out.cornerRadius(op.opaque() || upper[triangle] ? FILL_OFFSET : -FILL_OFFSET)
                    .silhouetteEdge(contourWall
                            ? (upper[triangle]
                                    ? CgVectorRenderer.EDGE_P1_P2 : CgVectorRenderer.EDGE_P2_P0)
                            : CgVectorRenderer.EDGE_NONE)
                    .feather(featherPx)
                    .submit();
        }
        }
    }

    private static void drawStroke(CgUiPaintContext ctx, DrawOp op,
                                   float x, float y, float scale, int argb, float halfWidth) {
        CgProfiler.count("svg.strokeSegments", op.data().length / 4);
        try (CgProfiler.Scope ignored = CgProfiler.scope("svg.drawStroke")) {
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
    }

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * The draw operations, in document order — <b>tessellated on first request, not at parse.</b>
     *
     * <h3>Why this is lazy</h3>
     *
     * <p>A document is parsed at {@code REFERENCE_STEPS}, the resolution a full-screen zoom needs. Almost
     * nothing draws at that size: {@link #lodFor} picks a coarser tier for anything under 160 device
     * pixels and builds it <em>independently</em> from the retained tags, so at a file tree's 16px every
     * icon threw the reference mesh away without ever submitting a triangle of it. Measured across the
     * shipped set, tessellating it was <b>17.3 ms of a 62 ms load</b>, spent on geometry nothing drew.</p>
     *
     * <p>Deferring it is safe precisely because {@link #bounds} no longer depends on it — see
     * {@link #boundsOf}. Were culling still reading a box measured off the triangles, the first
     * {@link #render} would force the build and this would save nothing at all.</p>
     *
     * <p>Double-checked on a volatile field. Two threads racing here build the same mesh twice and one
     * result is discarded, which is wasteful but not wrong; the alternative is holding a lock across a
     * tessellation on the render thread.</p>
     */
    public List<DrawOp> ops() {
        List<DrawOp> built = ops;
        if (built != null) return built;
        synchronized (this) {
            if (ops == null) ops = Collections.unmodifiableList(buildOps(scene));
            return ops;
        }
    }

    /** How many stroke segments a draw submits. */
    public int segmentCount() {
        int total = 0;
        for (DrawOp op : ops()) if (!op.fill()) total += op.data().length / 4;
        return total;
    }

    /** How many fill triangles a draw submits. */
    public int triangleCount() {
        int total = 0;
        for (DrawOp op : ops()) if (op.fill()) total += op.data().length / 6;
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

    /**
     * Whether the document has anything to draw.
     *
     * <p>Answered from the scene rather than from {@link #ops()}, so asking does not force the
     * tessellation the laziness exists to avoid — {@code isEmpty()} is exactly the sort of cheap-looking
     * query a caller puts in front of a draw, and routing it through the mesh would rebuild the parse
     * cost at the first guard.</p>
     *
     * <p>The two can differ in one direction: a scene whose every mesh degenerates to zero area has
     * contours but no ops, and this reports it non-empty. That is the safe direction — a caller that
     * draws anyway submits nothing.</p>
     */
    public boolean isEmpty() {
        return scene.isEmpty();
    }
}
