package com.crystalgui.harness.scene;

import com.crystalgraphics.harness.FrameInfo;
import com.crystalgraphics.harness.InteractiveSceneLifecycle;
import com.crystalgraphics.harness.config.HarnessContext;
import com.crystalgraphics.harness.scene.test.HarnessWorld;
import com.crystalgui.app.vfxlab.VfxLab;

/** An empty world with the VFX Lab open on the harness's desktop (the launcher opens it in any 3D scene). */
public final class VfxLabScene implements InteractiveSceneLifecycle {

    @Override
    public void init(HarnessContext ctx) {
        ctx.getCamera3D().moveCamera(0f, 4f, 14f);
        ctx.getCamera3D().setPitch(-8f);
        ctx.getUi().open(VfxLab.KIND);
    }

    @Override
    public void render(HarnessContext ctx, FrameInfo frame) {
        HarnessWorld.fire(ctx, ctx.getCamera3D().getViewMatrix(), ctx.getProjection());
    }

    @Override public void dispose() { }
    @Override public boolean isRunning() { return true; }
    @Override public boolean uses3DCamera() { return true; }
    @Override public boolean shouldShutdownOnComplete() { return false; }
}
