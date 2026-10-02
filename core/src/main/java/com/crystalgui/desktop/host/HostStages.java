package com.crystalgui.desktop.host;

import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.render.UiStages;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * {@link HostSession}'s side of {@link UiStages}: fires a paint arm's stage, the compositor drawing in it at its order.
 * Kept apart so a class a server may load names no stage. Render thread.
 */
final class HostStages {

    private static boolean registered;
    private static final Matrix4f IDENTITY = new Matrix4f();
    private static final Matrix4f PROJECTION = new Matrix4f();
    /** The compositor's paint for the firing under way, until the stage takes it; null when it draws nothing. */
    @Nullable
    private static Runnable pending;
    private static boolean reported;

    private HostStages() {
    }

    /**
     * Fires {@code stage} over a {@code width x height} device-pixel surface, {@code scale} device pixels to a logical
     * one, with {@code compositor} drawing at {@link UiStages#COMPOSITOR}. False when the stage did not run it -- no
     * engine yet -- so the caller draws it itself.
     */
    static boolean fire(CgRenderStage stage, int width, int height, float scale, @Nullable Runnable compositor) {
        if (!registered) {
            registered = true;
            UiStages.SCREEN.register(UiStages.COMPOSITOR, HostStages::render);
            UiStages.HUD.register(UiStages.COMPOSITOR, HostStages::render);
        }
        float logical = Math.max(1e-3f, scale);
        CgHostFrame world = CgRenderStage.WORLD_OPAQUE.host();
        stage.host().set(world.partialTick(), width, height, world.mainFramebuffer()).view()
                .set(0d, 0d, 0d, IDENTITY, PROJECTION.setOrtho(0f, width / logical, height / logical, 0f, -1f, 1f));
        pending = compositor;
        try {
            stage.fire();
        } catch (RuntimeException | LinkageError failed) {
            // Somebody's renderer: logged once, and the game goes on.
            if (!reported) {
                reported = true;
                CrystalGuiCore.LOGGER.error("[cgui] a renderer on {} failed", stage, failed);
            }
        }
        boolean ran = pending == null;
        pending = null;
        return ran || compositor == null;
    }

    private static void render(CgStageFrame frame) {
        Runnable paint = pending;
        if (paint == null) return;
        pending = null;
        frame.callback("crystalgui", paint);
    }
}
