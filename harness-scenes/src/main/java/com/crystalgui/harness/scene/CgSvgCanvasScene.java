package com.crystalgui.harness.scene;

import java.util.ArrayList;
import java.util.List;

import com.crystalgraphics.api.canvas.CgCanvas;
import com.crystalgraphics.api.paint.brush.CgBrush;
import com.crystalgraphics.api.vector.svg.CgSvg;
import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.ui.path.CgPathCache;

/**
 * {@code cgui-svg-icon}'s twin on CrystalGraphics' canvas: the same study strip at the same device pixels, so
 * {@code steps/UI7/truth.py} scores either capture, and the same icon grid at 32 px for the cost. P4.1's UI7 gate.
 *
 * <pre>{@code
 * --mode=ui-svg                                   # strip and grid, snapped
 * --mode=ui-svg -Dcrystalgui.uisvg.drift=true     # the grid unsnapped and drifting: the ladder's rasters, filtered
 * }</pre>
 */
public final class CgSvgCanvasScene implements InteractiveSceneLifecycle {

    private static final String[] FILETYPES = {
            "IntelliJ_IDEA_Icon", "anyType", "anyType_dark", "c", "c_dark", "css", "css_dark", "csv",
            "csv_dark", "editorConfig", "editorConfig_dark", "folder", "folder_dark", "font",
            "font_dark", "html", "html_dark", "image", "image_dark", "java", "javaScript",
            "javaScript_dark", "java_dark", "json", "json_dark", "manifest", "manifest_dark", "markdown",
            "markdown_dark", "python", "python_dark", "text", "text_dark", "typeScript",
            "typeScript_dark", "xhtml", "xhtml_dark", "xml", "xml_dark", "yaml", "yaml_dark", "shader"};
    private static final String[] CHROME = {"folder", "file-text", "image", "code", "package",
            "general/search/search", "logo"};

    /** The strip: {@code cgui-svg-icon}'s, so the two captures crop alike. */
    private static final String STUDY = "crystalgui:ui/icons/nodes/java/package_dark.svg";
    private static final int[] STUDY_SIZES = {16, 14, 12, 10, 8};
    private static final float UI_SCALE = 2f, MARGIN = 8f, STUDY_GAP = 12f;

    private static final float ICON = 32f, CELL_W = 62f, CELL_H = 50f;
    private static final int COLUMNS = 10;

    private final List<CgSvg> icons = new ArrayList<>();
    private CgSvg study;
    private CgRenderStage.Registration hud;
    private boolean running = true;
    private final boolean drift = Boolean.getBoolean("crystalgui.uisvg.drift");
    private int turns, missing;

    @Override
    public void init(HarnessContext ctx) {
        study = CgSvg.of(STUDY);
        for (String name : FILETYPES) add("crystalgui:ui/icons/filetypes/" + name + ".svg");
        for (String name : CHROME) add("crystalgui:ui/icons/" + name + ".svg");
        // The SCREEN stage, as cgui-svg-icon draws on: a 2D scene has no camera readout over the strip
        hud = CgRenderStage.SCREEN.register(CgRenderStage.VIEWPORT_WIDGETS, frame -> {
            turns++;
            draw(frame.canvas());
        });
    }

    private void add(String path) {
        CgSvg icon = CgSvg.of(path);
        // The list is cgui-svg-icon's, which marks a name no longer shipped red; here it is counted
        if (icon == null) missing++;
        else icons.add(icon);
    }

    private void draw(CgCanvas c) {
        // The strip, under the workbench's uiScale, at whole logical pixels
        c.save().scale(UI_SCALE, UI_SCALE);
        float x = MARGIN;
        for (int size : STUDY_SIZES) {
            c.svg(study, x, MARGIN, size, size);
            x += size + STUDY_GAP;
        }
        c.restore();

        // The grid, below the strip, in a theme colour where the icon asks for one
        float top = (MARGIN + STUDY_SIZES[0] + MARGIN) * UI_SCALE + 16f;
        c.save();
        if (drift) c.pixelSnap(false).translate((turns % 120) / 120f, (turns % 60) / 60f);
        for (int i = 0; i < icons.size(); i++) {
            float cx = MARGIN + (i % COLUMNS) * CELL_W, cy = top + (i / COLUMNS) * CELL_H;
            c.image(CgBrush.svg(icons.get(i)).color(0xFFE0E0E0), cx + (CELL_W - 6f - ICON) * 0.5f, cy, ICON, ICON);
        }
        c.restore();
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
    }

    @Override
    public void dispose() {
        if (hud != null) hud.close();
        System.out.println("[ui-svg] HUD turns: " + turns + ", icons " + icons.size() + " (" + missing + " missing), path cells claimed "
                + CgPathCache.SHARED.claims() + ", atlas resets " + CgPathCache.SHARED.resets());
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean uses3DCamera() {
        return false;
    }

    @Override
    public boolean shouldShutdownOnComplete() {
        return true;
    }
}
