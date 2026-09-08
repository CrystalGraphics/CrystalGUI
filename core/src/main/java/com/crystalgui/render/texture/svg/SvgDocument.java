package com.crystalgui.render.texture.svg;

import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.gl.render.CgVectorRenderer;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.util.profiling.CgProfiler;

import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.CgUiPaintContext;

import java.lang.annotation.Annotation;
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
     * Width of the antialiasing ramp on a stroke, in device pixels.
     *
     * <p>One pixel with a linear ramp is the exact area a straight edge covers of the pixel it crosses,
     * which is what the fill path computes analytically. The fragment stage runs once per pixel, so a
     * multisampled target never sees this ramp twice — measured: a pixel-aligned edge under 4x MSAA
     * reads exactly 0 / 1 with it, and a stroke with no ramp is simply aliased.</p>
     */
    private static final float STROKE_FEATHER = 1f;

    /** Path to parsed document; see {@link #of}. */
    private static final Map<String, SvgDocument> CACHE = new ConcurrentHashMap<>();

    /**
     * One batch of geometry sharing a colour and a mode.
     *
     * <p>{@code data} is cells for a fill (eight floats each, see {@link SvgTriangulator.Fill#quads}) and
     * segments for a stroke (four each) — one field rather than two subtypes because the draw loop
     * switches on {@code fill} exactly once per op and then runs a tight loop, and a sealed hierarchy
     * would buy a cast per op to say the same thing.</p>
     *
     * @param colours     one ARGB per cell for a gradient fill, parallel to {@code data}; null when the
     *                     whole op is one colour
     * @param edges       per cell, which edges are on the outline — see {@link SvgTriangulator.Fill#edges}.
     *                     Null for a stroke
     * @param currentColor the paint was {@code currentColor}, so the consumer's tint decides it at draw
     *                     time. Late-bound rather than resolved here because a document is cached and
     *                     shared, and the same icon is routinely drawn in two colours in one frame
     * @param segmentCaps  one packed cap pair per stroke segment, so that an interior joint gets exactly
     *                     one round cap and not two — see {@link SvgGeometry#segmentsOf}. Null for a fill,
     *                     which has no ends
     */
    public record DrawOp(boolean fill, float[] data, @Nullable int[] colours,
                         @Nullable int[] coloursEnd, @Nullable float[] gradients,
                         @Nullable int[] edges, boolean opaque,
                         int argb, boolean currentColor,
                         float halfWidth, int cap, @Nullable int[] segmentCaps) {
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
    /** Whether any paint in the file is {@code currentColor} — see {@link #usesCurrentColor}. */
    private final boolean usesCurrentColor;

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
        this.usesCurrentColor = anyCurrentColor(scene);
    }

    private static boolean anyCurrentColor(SvgScene scene) {
        for (SvgScene.Node node : scene.nodes()) {
            if (node.fill() != null && node.fill().paint().currentColor()) return true;
            if (node.stroke() != null && node.stroke().paint().currentColor()) return true;
        }
        return false;
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
        // Over the MAP, not over LOD_STEPS. The parse tier is keyed by PARSE_STEPS, which is deliberately
        // not one of the drawn tiers -- parse resolves as cheaply as possible to get something on the
        // first frame, while the coarsest tier anything actually DRAWS with is set by quality. Walking
        // LOD_STEPS instead made the one mesh that always exists invisible to this, so the budget-
        // exhausted path found no fallback and built anyway -- the exact overshoot it exists to avoid.
        SvgDocument coarsest = null;
        int coarsestSteps = Integer.MAX_VALUE;
        for (Map.Entry<Integer, SvgDocument> tier : lods.entrySet()) {
            if (tier.getKey() < coarsestSteps) {
                coarsestSteps = tier.getKey();
                coarsest = tier.getValue();
            }
        }
        return coarsest;
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
        // Registered under its own resolution so coarsestBuilt() can hand it to a budget-exhausted frame.
        // It is NOT one of the drawn tiers any more -- LOD_STEPS starts at 8, because 3 is too coarse for
        // a stroked circle -- so this is the first-frame mesh and the fallback, and nothing else.
        document.lods.put(PARSE_STEPS, document);
        return document;
    }

    /**
     * The document at a chosen curve resolution and nothing else — no tag retention, no LOD ladder.
     *
     * <p>A test seam: judging artwork at tile size means judging it at the tier the engine will draw it
     * at, and {@link #parse(String)} answers only the coarse first-frame mesh.</p>
     */
    static SvgDocument parse(String svg, int steps) {
        return fromScene(SvgResolver.resolve(SvgScanner.scan(svg), steps));
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

    /**
     * Curve resolution per tier — and the coarsest one is <b>not</b> {@link #PARSE_STEPS}.
     *
     * <h3>Why the coarsest DRAWN tier is 8 and not 3</h3>
     *
     * <p>Three steps was tuned against FILLED artwork, where it is genuinely free: a fill's silhouette is
     * a couple of pixels of coverage either way and a flattening error of a fifth of a pixel disappears
     * into it. Measured on a filled icon, 3 steps and 32 steps produce the same picture.</p>
     *
     * <p><b>A STROKED circle is not that shape.</b> The chords of the flattened polygon sit inside the
     * true circle, so the stroke's centreline moves inward by up to the sagitta — and against a 1px
     * stroke a fifth of a pixel is a fifth of the whole mark, which reads as a visibly polygonal ring
     * rather than as slight blur. Measured on {@code problems.svg}, a 1px stroked circle at 16px, peak
     * ring coverage by tier: <b>0.91 at 3 steps, 0.93 at 5, 0.96 at 9, 0.98 at 16</b> — and at 3 the
     * facets are plain to the eye. Eight puts the sagitta at ~0.05px across this tier's whole size
     * range, which is below anything a stroke can show.</p>
     *
     * <p>The tiers still climb with size because steps are counted per curve <em>segment</em>, so the
     * polygon is fixed while its error in device pixels grows with the drawn size.</p>
     *
     * <p>This costs no load time. {@link #PARSE_STEPS} is unchanged, so parse still produces the cheapest
     * possible mesh for the first frame; every tier here is built lazily under
     * {@code LOD_BUILD_BUDGET_NANOS}, and {@link #coarsestBuilt} keeps the parse tier reachable as the
     * fallback while they are being built.</p>
     */
    private static final int[] LOD_STEPS = {8, 10, 12, REFERENCE_STEPS};

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
                    ops.add(new DrawOp(true, mesh.quads(), mesh.colour0(), mesh.colour1(),
                            mesh.axes(), mesh.edges(), mesh.opaque(), argb,
                            paint.currentColor(), 0f, 0, null));
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
        if (ctx.svgRaster().accepts(this, x, y, scale)) {
            renderCached(ctx, x, y, scale, tint, false, 0f);
            return;
        }
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
        if (ctx.svgRaster().accepts(this, x, y, scale)) {
            renderCached(ctx, x, y, scale, argb, true, halfWidth);
            return;
        }
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
     * An icon-sized draw: fills from the raster cache, strokes direct, in the file's own order.
     *
     * <p>Rasterised from the tier a draw this size would have used, built now rather than under the
     * frame budget: the raster is kept, so an interim coarse tier would be kept with it.</p>
     */
    private void renderCached(CgUiPaintContext ctx, float x, float y, float scale, int argb,
                              boolean flat, float halfWidth) {
        SvgDocument tier = rasterTier(Math.max(width, height) * scale * ctx.deviceScale());
        List<DrawOp> ops = tier.ops();
        for (int i = 0; i < ops.size(); i++) {
            DrawOp op = ops.get(i);
            int colour = flat || op.currentColor() ? argb : op.argb();
            // A fill with colours of its own -- a gradient, a per-slice ramp -- bakes them into the
            // raster and is drawn untinted; everything else is white coverage under the tint.
            boolean baked = op.fill() && !flat && op.colours() != null;
            ctx.svgRaster().draw(tier, i, x, y, scale, baked ? 0xFFFFFFFF : colour, flat, halfWidth);
        }
    }

    /** The mesh tier for a draw {@code devicePx} tall, built synchronously. */
    private SvgDocument rasterTier(float devicePx) {
        if (tags == null) return this;
        for (int i = 0; i < LOD_MAX_DEVICE_PX.length; i++) {
            if (devicePx > LOD_MAX_DEVICE_PX[i]) continue;
            return lods.computeIfAbsent(LOD_STEPS[i], steps -> {
                SvgDocument built = fromScene(SvgResolver.resolve(tags, steps));
                built.ops();
                return built;
            });
        }
        return this;
    }

    /**
     * Submits fill op {@code op}'s cells for accumulation: every edge an exact area, the document origin
     * at {@code (x, y)} in the target's own pixels. What {@link com.crystalgui.render.SvgRasterCache}
     * rasterises through, under its additive material.
     *
     * @param flat every cell white; otherwise a fill with colours of its own keeps them and a plain
     *             one is white either way, since a flat colour is applied when the raster is drawn
     */
    public void accumulateFill(CgUiPaintContext ctx, int op, float x, float y, float scale, boolean flat) {
        DrawOp fill = ops().get(op);
        drawFill(ctx, fill, x, y, scale, 0xFFFFFFFF, flat || fill.colours() == null, true);
    }

    /**
     * Submits stroke op {@code op}'s segments with the document origin at {@code (x, y)} in the target's
     * own pixels, under an identity pose — so widths and the feather are device pixels as given.
     *
     * @param halfWidth in device pixels; {@code <= 0} for the file's own, scaled
     */
    public void accumulateStroke(CgUiPaintContext ctx, int op, float x, float y, float scale, float halfWidth) {
        DrawOp stroke = ops().get(op);
        drawStroke(ctx, stroke, x, y, scale, 0xFFFFFFFF,
                halfWidth > 0f ? halfWidth : stroke.halfWidth() * scale);
    }

    /** {@code minX, minY, maxX, maxY} of everything the document draws, in its own units. */
    public float[] bounds() {
        return bounds.clone();
    }

    /**
     * @param flat ignore any per-cell gradient colours and paint the whole op in {@code argb} — what
     *             {@link #renderMonochrome} means, and the reason the choice is a parameter rather than the
     *             presence of the array
     */
    private static void drawFill(CgUiPaintContext ctx, DrawOp op,
                                 float x, float y, float scale, int argb, boolean flat) {
        drawFill(ctx, op, x, y, scale, argb, flat, false);
    }

    /**
     * @param accumulate every edge is an exact area rather than only the outline ones, for cells that
     *                   are being summed into a coverage target instead of composited one by one
     */
    private static void drawFill(CgUiPaintContext ctx, DrawOp op,
                                 float x, float y, float scale, int argb, boolean flat, boolean accumulate) {
        // Scoped per OP, never per cell: a scope costs a nanoTime pair, and a fill is hundreds of cells,
        // so per-cell instrumentation would measure itself. The cell count rides along as a counter
        // instead, which is what turns "drawFill is slow" into "drawFill is slow per cell" or "there are
        // simply a lot of cells".
        CgProfiler.Scope scope = CgProfiler.scope("svg.drawFill");
        CgProfiler.count("svg.fillCells", op.data().length / 8);
        try (CgProfiler.Scope ignored = scope) {
        float[] q = op.data();
        int[] edges = op.edges();
        int[] start = op.colours();
        int[] end = op.coloursEnd();
        float[] axes = op.gradients();
        boolean ramp = !flat && start != null && end != null && axes != null;

        for (int i = 0; i < q.length; i += 8) {
            int cell = i / 8;
            // Every edge the tessellator marked as outline is antialiased by exact area, every seam is a
            // half-open step; the quad reading owes nothing to a feather, an offset or the sample count.
            CgVectorRenderer.Quad out = ctx.filledQuad()
                    .points(x + q[i] * scale, y + q[i + 1] * scale,
                            x + q[i + 2] * scale, y + q[i + 3] * scale,
                            x + q[i + 4] * scale, y + q[i + 5] * scale,
                            x + q[i + 6] * scale, y + q[i + 7] * scale)
                    .softEdges(accumulate ? 15 : edges[cell]);

            if (ramp) {
                // The axis is stored in the document's own units, so it moves and scales with the draw:
                // the origin like a point, and the direction by 1/scale because it already carries the
                // reciprocal length. Getting that inverse backwards makes the ramp shrink as the icon
                // grows, which reads as a gradient that is nearly flat at small sizes.
                int at = cell * 4;
                out.gradient(start[cell], end[cell],
                        x + axes[at] * scale, y + axes[at + 1] * scale,
                        axes[at + 2] / scale, axes[at + 3] / scale);
            } else {
                out.color(start == null || flat ? argb : start[cell]);
            }
            out.submit();
        }
        }
    }

    private static void drawStroke(CgUiPaintContext ctx, DrawOp op,
                                   float x, float y, float scale, int argb, float halfWidth) {
        CgProfiler.count("svg.strokeSegments", op.data().length / 4);
        try (CgProfiler.Scope ignored = CgProfiler.scope("svg.drawStroke")) {
        float[] s = op.data();
        float feather = STROKE_FEATHER / ctx.deviceScale();
        for (int i = 0; i < s.length; i += 4) {
            // Per SEGMENT, not per op: the caps were decided where the contour structure was still known,
            // so an interior joint gets one round cap and the stroke's real ends keep what the file asked
            // for. See SvgGeometry.segmentsOf.
            int[] caps = op.segmentCaps();
            int packed = caps == null ? op.cap() : caps[i / 4];
            ctx.curve()
                    // The feather is a logical distance the pose scales like a width; the ramp is
                    // stated in device pixels, so divide the scale out. It was zero here once, on the
                    // theory that MSAA already antialiased the edge -- it never did, the shader runs
                    // once per pixel, and a "crisp" stroke was an aliased one.
                    .feather(feather)
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

    /** How many fill cells a draw submits. */
    public int cellCount() {
        int total = 0;
        for (DrawOp op : ops()) if (op.fill()) total += op.data().length / 8;
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
     * Whether any fill or stroke in the file is {@code currentColor}.
     *
     * <p><b>This is the line between a CHROME MARK and ARTWORK, and it is readable off the file rather
     * than declared beside it.</b> A themed icon set (Feather, Lucide) is authored as {@code currentColor}
     * precisely so the cascade can colour it; a brand mark names every colour it uses and must never be
     * tinted. {@code WindowIcon} asks this to decide whether an icon needs a tile put under it or IS one
     * — a filled, coloured rounded square already, which a second square behind would only outline.</p>
     *
     * <p>Answered from the scene, like {@link #isEmpty}, so asking does not force the tessellation.</p>
     */
    public boolean usesCurrentColor() {
        return usesCurrentColor;
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
