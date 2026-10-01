package com.crystalgui.harness.scene;

import com.crystalgraphics.api.PoseStack;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.platform.input.CgSystemInput;

import com.crystalgui.render.CgUiPaintContext;
import com.crystalgui.render.texture.CgUiSvg;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.render.texture.svg.SvgPath;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.harness.trace.TraceDump;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>Every shipped icon, drawn at once, in a labelled grid.</b> Scroll to scale.
 *
 * <h3>What this is for</h3>
 *
 * <p><b>Drag to pan, scroll to zoom.</b> Zoom is unbounded and anchored on the cursor, so a suspect cell
 * can be pushed up to any size without first hunting for it — which is the whole point when the artefact
 * being chased is a hairline.</p>
 *
 * <p>Diagnosis, not demonstration. An icon that fails to render is invisible in a file tree — the row
 * still lays out, the label still draws, and there is simply a gap where the glyph should be. Which icon
 * it was, and whether it failed to load or loaded and drew nothing, is not recoverable from that picture.
 * Here every icon is on screen with its name under it, and the two failure modes are told apart: a
 * <b>red</b> cell could not be loaded at all, an <b>amber</b> one parsed to zero draw operations.</p>
 *
 * <h3>Three passes, not three-per-icon</h3>
 *
 * <p>The cells, the icons and the labels are drawn in three separate loops rather than cell by cell, and
 * that is deliberate: quads, vector fills and text are three different materials, and
 * {@code CgUiPaintContext} flushes whenever it switches between them. Interleaved, this scene would cost
 * three material binds per icon; batched by kind it costs three in total.</p>
 *
 * <p>That is also the bug this scene exists downstream of. Alternating an icon and a label per row — which
 * is exactly what a file tree does — used to leave {@code activePath} claiming the curve path while
 * {@code CgTextRenderer} had quietly bound {@code text.shader}, so from the second row on the icons drew
 * against the text shader and the labels against the stroke shader. Glyph quads evaluated by a stroke SDF
 * come out as solid boxes.</p>
 *
 * <h3>The study strip</h3>
 *
 * <p>Above the grid, one icon at the sizes the workbench actually draws it — 16, 14, 12, 10 and 8 logical px,
 * under the workbench's own {@code uiScale} — through {@link CgUiSvg}, so the origin snap and every other
 * thing the drawable does on the way to the screen is exercised exactly as a file-tree row exercises it.
 * The grid draws {@link SvgDocument} raw and at no device scale, which is a different picture: it cannot
 * show a small-size defect that the drawable or the pose introduces. {@code plan/svg-fix/} holds the
 * true-pixel crops this strip is judged against; {@code -Dcrystalgui.svgicon.uiScale} sets the scale.</p>
 */
public class CgUiSvgIconScene implements InteractiveSceneLifecycle, CgSystemInput.Mouse {

    /**
     * Every file in {@code assets/crystalgui/ui/icons/filetypes/}.
     *
     * <p>Hand-written, and therefore able to go stale — which for a diagnostic scene is the right trade
     * only because a name that has been deleted shows up <b>red</b> rather than silently vanishing. There
     * is no directory listing through {@code CgIO}; a resource index would be the fix if this ever needed
     * to be authoritative.</p>
     */
    private static final String[] FILETYPES = {
            "IntelliJ_IDEA_Icon", "anyType", "anyType_dark", "c", "c_dark", "css", "css_dark", "csv",
            "csv_dark", "editorConfig", "editorConfig_dark", "folder", "folder_dark", "font",
            "font_dark", "html", "html_dark", "image", "image_dark", "java", "javaScript",
            "javaScript_dark", "java_dark", "json", "json_dark", "manifest", "manifest_dark", "markdown",
            "markdown_dark", "python", "python_dark", "text", "text_dark", "typeScript",
            "typeScript_dark", "xhtml", "xhtml_dark", "xml", "xml_dark", "yaml", "yaml_dark", "shader"};

    /** Feather (MIT) — the stroked, {@code currentColor} set, kept so both cases are on one screen. */
    private static final String[] CHROME = {"folder", "file-text", "image", "code", "package",
            "general/search/search"};

    /** The icon under study, and the sizes the workbench draws it at. See {@code plan/svg-fix/}. */
    private static final String STUDY_ICON = "crystalgui:nodes/java/package";
    private static final int[] DEFAULT_STUDY_SIZES = {16, 14, 12, 10, 8};
    private static final float STUDY_GAP = 12f;
    private static final float STUDY_LABEL_HEIGHT = 16f;

    private static final float MARGIN = 8f;
    private static final float LABEL_HEIGHT = 12f;
    private static final float GAP = 6f;

    /** The cell at zoom 1. Everything below is this multiplied by {@link #zoom}. */
    private static final float BASE_ICON = 32f;
    private static final float BASE_CELL_W = 62f;
    private static final float BASE_CELL_H = BASE_ICON + LABEL_HEIGHT + GAP;

    /**
     * Fixed, rather than derived from the window width.
     *
     * <p>A grid that re-flows as you zoom moves every cell you are not looking at, and the one you
     * <em>are</em> looking at jumps out from under the cursor. Stable columns plus free panning is how
     * every map does it, and it is the difference between inspecting a cell and re-finding it.</p>
     */
    private static final int COLUMNS = 10;

    /** Frames discarded before profiling starts — lazy material compiles and first-touch allocations. */
    private static final int WARMUP_FRAMES = 30;

    private record Entry(String name, String path, SvgDocument document) {
        boolean missing() {
            return document == null;
        }

        boolean blank() {
            return document != null && document.isEmpty();
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    private CgUiSvg study;
    /** Logical sizes on the strip; {@code -Dcrystalgui.svgicon.sizes=24,20} overrides. */
    private int[] studySizes = DEFAULT_STUDY_SIZES;
    /** Logical-to-device scale the strip draws under — the workbench's {@code uiScale}. */
    private float studyScale;

    private float zoom = 1f;
    private float panX;
    private float panY;
    private boolean running = true;
    private int profileFrames;
    private boolean profileInteractive;
    private HarnessContext profileCtx;
    private boolean dragging;
    private int lastMouseX;
    private int lastMouseY;

    @Override
    public void init(HarnessContext ctx) {
        // Enabled BEFORE the loads, or the one-off parse cost is invisible -- which is exactly the number
        // "does opening a tree of icons stall the first frame" needs.
        profileFrames = Integer.getInteger("crystalgui.svgicon.profile", 0);
        // Interactive profiling: instrument everything but never stop the loop, so a human can drive the
        // zoom across every threshold and the dump covers the WHOLE session. A fixed frame count cannot
        // capture that -- the interesting events are the ones a person triggers.
        profileInteractive = Boolean.getBoolean("crystalgui.svgicon.profileLive");
        if (profileFrames > 0 || profileInteractive) CgTrace.enable("crystalgraphics");
        for (String name : FILETYPES) load("filetypes/" + name, name);
        for (String name : CHROME) load(name, name + " (feather)");
        profileCtx = ctx;
        studyScale = Float.parseFloat(System.getProperty("crystalgui.svgicon.uiScale", "2"));
        String sizes = System.getProperty("crystalgui.svgicon.sizes");
        if (sizes != null) studySizes = java.util.Arrays.stream(sizes.split(",")).mapToInt(Integer::parseInt).toArray();
        // The dark drawing, pinned: the workbench shows that one and the crops in plan/svg-fix are of it.
        study = CgUiSvg.ofIcon(STUDY_ICON);
        if (study != null) study.setVariantOverride(FileIconTheme.Variant.DARK);
        applyStartupOverrides(ctx.getScreenWidth(), ctx.getScreenHeight());
        panY += stripHeight();
        // Enabled BEFORE the first frame: the profiler is a no-op behind a volatile flag, so turning it on
        // mid-run would leave the warm-up frames uninstrumented and the totals unattributable.
        profileFrames = Integer.getInteger("crystalgui.svgicon.profile", 0);
        // Interactive profiling: instrument everything but never stop the loop, so a human can drive the
        // zoom across every threshold and the dump covers the WHOLE session. A fixed frame count cannot
        // capture that -- the interesting events are the ones a person triggers.
        profileInteractive = Boolean.getBoolean("crystalgui.svgicon.profileLive");
        if (profileFrames > 0 || profileInteractive) CgTrace.enable("crystalgraphics");
    }

    /**
     * Optional deterministic start state, so the startup capture can be aimed at a suspect cell.
     *
     * <p>A seam artifact is <b>zoom-dependent</b> — it appears when a sub-pixel seam happens to swallow a
     * pixel row, so it is present at some zooms and absent either side of them. Reproducing one therefore
     * means naming the zoom, and hunting for it by hand through an interactive window is exactly the loop
     * this exists to avoid. Absent these properties nothing changes: the grid still opens at 1x.</p>
     *
     * <pre>--args="--mode=cgui-svg-icon" -Dcrystalgui.svgicon.zoom=35 -Dcrystalgui.svgicon.focus=javaOutsideSource</pre>
     */
    private void applyStartupOverrides(int screenW, int screenH) {
        zoom = Float.parseFloat(System.getProperty("crystalgui.svgicon.zoom", "1"));
        // Additive, so they mean "and then shift by this" when a focus is named -- which is how the
        // whole of an icon larger than the window gets tiled without recomputing a cell position by hand.
        float offsetX = Float.parseFloat(System.getProperty("crystalgui.svgicon.panX", "0"));
        float offsetY = Float.parseFloat(System.getProperty("crystalgui.svgicon.panY", "0"));
        String focus = System.getProperty("crystalgui.svgicon.focus", "");
        if (focus.isEmpty()) {
            panX = offsetX;
            panY = offsetY;
            return;
        }
        // Centre the named cell rather than making the caller compute a pan: the cell's position is a
        // function of the zoom it is being viewed at, so the two overrides are not independent.
        for (int i = 0; i < entries.size(); i++) {
            if (!entries.get(i).name().equals(focus)) continue;
            // Land the ICON at the margin, not the cell: the icon is centred inside a cell far wider
            // than it, so aiming at the cell puts the artwork off the right edge at any real zoom.
            float cellW = BASE_CELL_W * zoom;
            float iconPx = BASE_ICON * zoom;
            // Centred, not margin-aligned, when asked: a seam artefact is decided by where the seam
            // falls between two pixel CENTRES, so the icon's sub-pixel offset is part of the repro and
            // "same zoom, different corner" is a different test.
            float restX = MARGIN, restY = MARGIN;
            if (Boolean.getBoolean("crystalgui.svgicon.center")) {
                restX = (screenW - iconPx) * 0.5f;
                restY = (screenH - iconPx) * 0.5f;
            }
            panX = -cellX(i, cellW) - (cellW - GAP * zoom - iconPx) * 0.5f + restX + offsetX;
            panY = -cellY(i, BASE_CELL_H * zoom) + restY + offsetY;
            return;
        }
    }

    private void load(String path, String label) {
        String resource = "crystalgui:ui/icons/" + path + ".svg";
        entries.add(new Entry(label, resource, SvgDocument.load(resource)));
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        CgRenderPipeline.getInstance().getFrameData().timeSecs = (float) frame.getElapsedTime();

        CgUiPaintContext paint = paintContext();
        paint.beginFrame(ctx.getScreenWidth(), ctx.getScreenHeight());

        drawStudyStrip(paint);

        float cellW = BASE_CELL_W * zoom;
        float cellH = BASE_CELL_H * zoom;
        float iconPx = BASE_ICON * zoom;

        // Pass 1 -- cell backgrounds, so a failure is visible rather than absent. Quad path.
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!entry.missing() && !entry.blank()) continue;
            paint.fillRect(cellX(i, cellW), cellY(i, cellH), cellW - GAP * zoom, cellH - GAP * zoom,
                    entry.missing() ? 0x60FF0000 : 0x60FFAA00);
        }

        // Pass 2 -- every icon. One material bind for the lot; see the class note.
        int missing = 0;
        int blank = 0;
        int cells = 0;
        int segments = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.missing()) {
                missing++;
                continue;
            }
            if (entry.blank()) {
                blank++;
                continue;
            }
            SvgDocument document = entry.document();
            // Fitted from the icon's OWN viewBox: this set is 16px and Feather's is 24, and scaling both
            // by one constant would draw one of them at two thirds the other's size.
            float scale = Math.min(iconPx / document.width(), iconPx / document.height());
            float left = cellX(i, cellW) + (cellW - GAP * zoom - document.width() * scale) * 0.5f;
            document.render(paint, left, cellY(i, cellH), scale, 0xFFE0E0E0);
            cells += document.cellCount();
            segments += document.segmentCount();
        }
        paint.flush();

        // Pass 3 -- labels. One switch to the text path for all of them.
        float labelPx = Math.max(7f, Math.min(48f, 9f * zoom));
        for (int i = 0; i < entries.size(); i++) {
            paint.text().draw().at(cellX(i, cellW), cellY(i, cellH) + iconPx)
                    .text(entries.get(i).name())
                    .font(paint.getFont().atSize((int) labelPx)).submit();
        }

        paint.text().draw().at(MARGIN, ctx.getScreenHeight() - 16f)
                .text(String.format("%d icons  --  %d missing (red)  %d blank (amber)  "
                                + "%d cells  %d segments  --  drag to pan, scroll to zoom (%.2fx)",
                        entries.size(), missing, blank, cells, segments, zoom))
                .font(paint.getFont().atSize(12)).submit();

        paint.endFrame();

        if (frame.getFrameNumber() == 5) {
            ctx.getArtifactService().requestCapture(System.getProperty("crystalgui.svgicon.capture", "startup"));
        }
        // One-shot mode: capture, then stop the loop, so a scripted run produces a PNG and exits
        // instead of leaving a window open waiting to be closed by hand.
        if (Boolean.getBoolean("crystalgui.svgicon.oneshot") && frame.getFrameNumber() >= 7) running = false;

        // Accumulate across frames rather than snapshotting each one: a single frame's numbers are noise
        // next to driver scheduling, and the totals divided by the frame count are what a per-frame cost
        // actually is. WARM-UP IS DISCARDED for the same reason -- the first frames pay lazy material
        // compilation and buffer allocation that never happen again.
        if (profileFrames > 0) {
            if (frame.getFrameNumber() == WARMUP_FRAMES) {
                // Dumped from the START: this window holds the one-off costs -- parsing every icon and
                // building whatever LOD meshes the first frames asked for -- which the steady-state
                // average is designed to exclude and which are exactly what a cold start pays.
                TraceDump.dump(new java.io.File(ctx.getOutputDir()), "svg-startup", 0L);
                profileFrom = CgTrace.currentFrameIndex() + 1;
            }
            if (frame.getFrameNumber() == WARMUP_FRAMES + profileFrames) {
                java.io.File out = TraceDump.dump(
                        new java.io.File(ctx.getOutputDir()), "svg-icons-" + profileFrames + "f", profileFrom);
                System.out.println("[profile] frames=" + profileFrames + " dump=" + out);
                running = false;
            }
        }
    }

    /** The first steady-state frame: after warm-up, whose costs the startup dump holds. */
    private long profileFrom;

    /** Device height of the study strip, which the grid starts below. */
    private float stripHeight() {
        return (MARGIN + studySizes[0] + MARGIN) * studyScale + STUDY_LABEL_HEIGHT;
    }

    /**
     * The icon under study at each size, drawn as the workbench draws it: through the drawable, under a
     * uniform {@code uiScale} pose, at integer logical coordinates. Not panned or zoomed — the whole
     * question is what the device pixels look like at the size the stylesheet chose.
     */
    private void drawStudyStrip(CgUiPaintContext paint) {
        if (study == null) return;
        PoseStack pose = paint.getPoseStack();
        pose.pushPose();
        pose.scale(studyScale, studyScale, 1f);
        float x = MARGIN;
        for (int size : studySizes) {
            study.draw(paint, 0f, 0f, x, MARGIN, size, size);
            x += size + STUDY_GAP;
        }
        pose.popPose();
        paint.flush();

        StringBuilder label = new StringBuilder(STUDY_ICON).append("  (dark)  uiScale=")
                .append(studyScale).append("   logical");
        for (int size : studySizes) label.append(' ').append(size);
        label.append("  =  device");
        for (int size : studySizes) label.append(' ').append(Math.round(size * studyScale));
        paint.text().draw().at(MARGIN, (MARGIN + studySizes[0] + MARGIN) * studyScale)
                .text(label.toString()).font(paint.getFont().atSize(12)).submit();
    }

    private float cellX(int index, float cellW) {
        return panX + MARGIN + (index % COLUMNS) * cellW;
    }

    private float cellY(int index, float cellH) {
        return panY + MARGIN + (index / COLUMNS) * cellH;
    }

    @Override
    public boolean consumeMouseEvent(CgSystemInput.Mouse.Event event) {
        if (event.button() >= 0) {
            dragging = event.state();
            lastMouseX = event.x();
            lastMouseY = event.y();
            return true;
        }

        float wheel = event.wheelDelta();
        if (wheel != 0f) {
            // ANCHORED ON THE CURSOR. Scaling about the origin instead sends whatever you were looking at
            // off-screen within two notches, which at high zoom means panning back to find it every time.
            // Solve for the pan that keeps the point under the pointer fixed: it is on the same grid
            // coordinate before and after, so pan' = mouse - (mouse - pan) * (zoom'/zoom).
            float previous = zoom;
            // A POSITIVE notch means the wheel rolled DOWN in this engine -- see ScrollerView, which is
            // the only statement of it. Taking the sign at face value here would zoom in on scroll-down.
            zoom *= wheel > 0 ? 0.9f : 1.1f;
            // Bounded only where the maths stops working: a zoom of zero collapses every cell onto one
            // point and is not recoverable by scrolling back.
            zoom = Math.max(0.02f, Math.min(400f, zoom));

            float ratio = zoom / previous;
            panX = event.x() - (event.x() - panX) * ratio;
            panY = event.y() - (event.y() - panY) * ratio;
            return true;
        }

        if (dragging) {
            panX += event.x() - lastMouseX;
            panY += event.y() - lastMouseY;
        }
        lastMouseX = event.x();
        lastMouseY = event.y();
        return true;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return false;
    }

    /** A 2D overlay scene: no world camera, so the harness must not install one. */
    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public void dispose() {
        if (profileInteractive && profileCtx != null) {
            java.io.File out = TraceDump.dump(
                    new java.io.File(profileCtx.getOutputDir()), "svg-lod-live", 0L);
            System.out.println("[profile] live dump=" + out);
        }
    }

    /** This scene's paint context: it has no document to ask for one. */
    private CgUiPaintContext paintContext;

    private CgUiPaintContext paintContext() {
        if (paintContext == null) paintContext = CgUiPaintContext.create();
        return paintContext;
    }
}
