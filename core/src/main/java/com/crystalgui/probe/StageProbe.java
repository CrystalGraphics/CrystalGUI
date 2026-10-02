package com.crystalgui.probe;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.render.CgQuadRenderer;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgPassRecorder;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.render.UiStages;

/**
 * A renderer of a mod's own on {@link UiStages#SCREEN} and {@link UiStages#HUD}, for an unattended run: a mark in the
 * top-left corner, over CrystalGUI's windows, drawn the way {@code UiStages}' example draws, and a count of what each
 * stage drew. {@link AutoTest} starts it with the desktop and states the counts beside each capture; {@code prodSmoke}
 * fails a run whose {@code SCREEN} never drew.
 */
final class StageProbe {

    /** Over CrystalGUI's windows. */
    private static final int ORDER = UiStages.COMPOSITOR + 1000;
    private static final int MARK = 0xFFFF00FF;

    private static boolean started;
    private static CgPassRecorder recorder;
    private static CgQuadRenderer quads;
    private static CgMaterial material;
    private static int screenDrew, hudDrew;

    private StageProbe() {
    }

    static void start() {
        if (started) return;
        started = true;
        UiStages.SCREEN.register(ORDER, frame -> {
            draw(frame);
            screenDrew++;
        });
        UiStages.HUD.register(ORDER, frame -> {
            draw(frame);
            hudDrew++;
        });
    }

    /** How often each stage drew the mark, as the line a run is judged by. */
    static String report() {
        return "screen stage drew: " + (screenDrew > 0) + " (" + screenDrew + " frames, hud " + hudDrew + ")";
    }

    private static void draw(CgStageFrame frame) {
        if (quads == null) {
            recorder = new CgPassRecorder();
            quads = CgQuadRenderer.create();
            quads.sink(recorder);
            material = CgMaterial.load("crystalgui:shaders/stage_probe.shader");
        }
        recorder.recordInto(frame.recording(), frame.target(), CgLoad.load(), frame.constants());
        quads.useMaterial(material);
        quads.begin();
        quads.quad().at(4f, 4f).size(12f, 12f).color(MARK).submit();
        quads.flush();
        quads.end();
        recorder.stop();
    }
}
