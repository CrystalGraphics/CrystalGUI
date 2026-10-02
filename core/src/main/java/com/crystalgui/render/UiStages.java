package com.crystalgui.render;

import com.crystalgraphics.render.stage.CgRenderStage;

/**
 * Where CrystalGUI draws in a game's frame, as render stages anybody draws at: {@link #SCREEN} over a screen and
 * {@link #HUD} over the in-game HUD. CrystalGUI's own windows are a renderer on both at {@link #COMPOSITOR}; a lower
 * order draws under them, a higher one over.
 *
 * <pre>{@code
 * CgPassRecorder recorder = new CgPassRecorder();
 * CgQuadRenderer quads = CgQuadRenderer.create();
 * quads.sink(recorder);
 * CgMaterial material = CgMaterial.load("mymod:shaders/badge.shader");   // #pragma cg_use quad
 *
 * CgRenderStage.Registration badge = UiStages.HUD.register(UiStages.COMPOSITOR + 1, frame -> {
 *     recorder.recordInto(frame.recording(), frame.target(), CgLoad.load(), frame.constants());
 *     quads.useMaterial(material);
 *     quads.begin();
 *     quads.quad().at(8f, 8f).size(32f, 32f).color(0xFFFF8800).submit();   // CrystalGUI's logical pixels
 *     quads.flush();
 *     quads.end();
 *     recorder.stop();
 * });
 * badge.close();   // stops it
 * }</pre>
 *
 * <ul>
 *   <li>Fired by CrystalGUI's hosts, on a client, once a frame each, from the game's own screen and HUD hooks:
 *       {@code SCREEN} after any screen draws, CrystalGUI's or another mod's, and {@code HUD} after the HUD. Neither
 *       fires on a server, nor in a game without CrystalGUI.</li>
 *   <li>{@code frame.constants()} is an orthographic camera over the surface in CrystalGUI's logical pixels, origin top
 *       left; {@code frame.host().width()} and {@code height()} are device pixels.</li>
 *   <li>{@code frame.host().partialTick()} is the world's, from its last frame.</li>
 * </ul>
 */
public final class UiStages {

    /** Over a screen, once it has drawn: CrystalGUI's desktop, or any other screen with CrystalGUI's windows over it. */
    public static final CgRenderStage SCREEN = CgRenderStage.define("crystalgui:screen");

    /** Over the in-game HUD, once it has drawn: CrystalGUI's pinned windows sit here. */
    public static final CgRenderStage HUD = CgRenderStage.define("crystalgui:hud");

    /** The order CrystalGUI's own windows draw at on both. */
    public static final int COMPOSITOR = 0;

    private UiStages() {
    }
}
