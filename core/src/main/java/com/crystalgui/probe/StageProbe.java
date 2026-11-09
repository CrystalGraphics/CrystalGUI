package com.crystalgui.probe;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.desktop.host.HostSession;

/**
 * A renderer of a mod's own on {@link CgRenderStage#SCREEN} and {@link CgRenderStage#HUD}, for an unattended run: a mark
 * in the top-left corner, over CrystalGUI's windows, and a count of what each
 * stage drew. {@link AutoTest} starts it with the desktop and states the counts beside each capture; {@code prodSmoke}
 * fails a run whose {@code SCREEN} never drew.
 */
final class StageProbe {

    /** Over CrystalGUI's windows. */
    private static final int ORDER = HostSession.COMPOSITOR + 1000;
    private static final int MARK = 0xFFFF00FF;

    private static boolean started;
    private static CgMaterial material;
    private static int screenDrew, hudDrew;

    private StageProbe() {
    }

    static void start() {
        if (started) return;
        started = true;
        CgRenderStage.SCREEN.register(ORDER, frame -> {
            draw(frame);
            screenDrew++;
        });
        CgRenderStage.HUD.register(ORDER, frame -> {
            draw(frame);
            hudDrew++;
        });
    }

    /** How often each stage drew the mark, as the line a run is judged by. */
    static String report() {
        return "screen stage drew: " + (screenDrew > 0) + " (" + screenDrew + " frames, hud " + hudDrew + ")";
    }

    private static void draw(CgStageFrame frame) {
        if (material == null) material = CgMaterial.load("crystalgui:shaders/stage_probe.shader");
        frame.canvas().quad(material).at(4f, 4f).size(12f, 12f).color(MARK).submit();
    }
}
