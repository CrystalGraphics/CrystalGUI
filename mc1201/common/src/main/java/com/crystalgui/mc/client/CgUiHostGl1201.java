package com.crystalgui.mc.client;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.gl.state.CgGlState;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.opengl.GL13;

/**
 * The GL discipline around every CrystalGUI paint on 1.20.x, in one place because both paint paths --
 * the desktop's own {@link CgUiScreen1201} and the pinned-window overlay in {@link CgUiHud1201} -- owe
 * Minecraft exactly the same thing, and a rule written twice is a rule one of them will drift from.
 *
 * <p><b>There are TWO state shadows here, each blind to the other, and both eliminate redundant calls.</b>
 * Ours is {@code CgGlStateManager}; Minecraft's is Blaze3D's {@code GlStateManager}. CrystalGUI writes
 * through {@code CgGL} (raw GL), which Blaze3D never sees, so when we hand control back its cache is
 * describing a context that no longer exists -- and every disagreement is a call <em>it</em> decides not
 * to make. {@link #enter()} repairs our shadow on the way in, {@link #leave()} repairs Minecraft's on the
 * way out.
 */
public final class CgUiHostGl1201 {

    private CgUiHostGl1201() {}

    /**
     * Whether there is a GL context to paint into at all.
     *
     * <p>Shutdown is the case: {@code GameShuttingDownEvent} runs {@code destroyContext()} and the render
     * thread then draws the save-progress screen, so {@code beginFrame} bound a deleted material and
     * turned a clean quit into a crash report. Asked here rather than guarded in the engine — a deleted
     * material genuinely cannot be bound; what is wrong is asking it to.</p>
     */
    public static boolean contextIsLive() {
        return CgGraphicsLifecycle.isInitialized();
    }

    /**
     * Minecraft writes GL state behind CrystalGraphics' back every frame, so our shadow must be dropped
     * before we paint or each stale field is a bind we skip. Cheap: it drops trust, it does not read the
     * driver.
     */
    public static void enter() {
        CgGlState.invalidateAllIfPresent();
    }

    /**
     * Hands Minecraft back the state its own renderer assumes.
     *
     * <p><b>Blaze3D caches the ACTIVE TEXTURE UNIT and every unit's binding.</b>
     * {@code GlStateManager._activeTexture} early-returns when its cache already names the unit, and
     * {@code _bindTexture} early-returns per unit. So a texture Minecraft binds after us is silently
     * DROPPED whenever its cache still names it, while the driver holds whatever CrystalGUI last bound.
     * Minecraft then presents its framebuffer sampling OUR texture, and the whole window becomes a flat
     * fill -- white when what we left was the white pixel, dark when it was an empty layer target.
     *
     * <p>It reads as a compositing or animation fault and is neither: the drawing was never wrong, and a
     * {@code glReadPixels} of Minecraft's own target during a broken frame shows the UI intact. It
     * surfaces first on whatever painted last -- a context menu, a window animation -- because those are
     * simply the elements that leave a different texture bound.
     *
     * <p><b>Bounced through unit 1</b>, because setting unit 0 directly is elided by exactly the stale
     * cache being repaired. {@code _glUseProgram} is not cached and needs no such trick.
     *
     * <p>The 1.7.10 host has carried the same handoff since the identical white window was diagnosed
     * there; only the mechanism differs, and it differs in the half that matters -- on that loader the
     * repair is raw LWJGL because Minecraft's present is fixed-function and keeps no shadow of its own,
     * while here it MUST go through Blaze3D or Minecraft's cache is left describing the wrong world.
     */
    public static void leave() {
        RenderSystem.activeTexture(GL13.GL_TEXTURE1);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._glUseProgram(0);
        // The three lines above went through Blaze3D and not CgGL, so our own shadow cannot see them
        // either -- the same rule, pointing the other way.
        CgGlState.invalidateAllIfPresent();
    }
}
