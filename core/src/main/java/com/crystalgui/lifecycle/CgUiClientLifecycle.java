package com.crystalgui.lifecycle;

import com.crystalgui.screen.CgUiScreen;
import com.crystalgraphics.gauntlet.CgGauntlet;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.mc.CgReloadListener;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.gauntlet.DesktopScriptTest;
import com.crystalgui.gauntlet.DesktopTest;
import com.crystalgui.gauntlet.OpenCloseTest;
import com.crystalgui.render.CgUiRenderer;
import com.crystalgui.render.UiPaintWarmup;
import com.crystalgui.render.texture.asset.CgUiSpriteRegistry;
import com.crystalgui.render.texture.asset.FileIconTheme;
import com.crystalgui.render.texture.svg.SvgDocument;
import com.crystalgui.style.StyleEngine;

/**
 * CrystalGUI's client lifecycle: the desktop, the loading screen's tasks, resource reloads and teardown, in one
 * explicit order.
 *
 * <p>Declared {@code client} in {@code META-INF/crystalgraphics/listeners}: the engine registers it on every client,
 * for reloads too, and a dedicated server never loads it.</p>
 */
public final class CgUiClientLifecycle implements CgLifecycleListener, CgReloadListener {

    /** The one listener; what the engine's discovery registers. */
    public static final CgUiClientLifecycle INSTANCE = new CgUiClientLifecycle();

    private CgUiClientLifecycle() {
    }

    /** The desktop, its keys, its tests for an unattended run, and CrystalGUI's start on a worker. */
    @Override
    public void onPreInit() {
        // Its stages, its input and its key, before any key can be pressed.
        CgUiScreen.install();
        CgUiStart.ahead();
        CgUiRenderer.precache();
        gauntlet();
    }

    /** The autotests we register. **/
    private void gauntlet() {
        CgGauntlet.register(DesktopTest.ID, DesktopTest::new);
        CgGauntlet.register(OpenCloseTest.ID, OpenCloseTest::new);
        CgGauntlet.register(DesktopScriptTest.ID, DesktopScriptTest::new);
    }
    
    @Override
    public void onContextInit(int width, int height) {
        CgUiRenderer.parseAhead();
    }

    /** The paint preload, then the desktop built and rehearsed behind the loading screen. */
    @Override
    public void onLoad(CgLoadPlan plan) {
        UiPaintWarmup preload = UiPaintWarmup.preload();
        plan.task("crystalgui:paint").render(task -> {
            // Behind the start: a paint class initialising beside its style classes could deadlock on one.
            if (!CgUiStart.done()) {
                task.nextFrame();
                return false;
            }
            return preload.step(task);
        }).start();
        // Built behind the screen, so opening it is a resume.
        plan.task("crystalgui:desktop").after("crystalgui:paint").render(task -> {
            if (CgUiScreen.isInstalled()) CgUiScreen.preload();
            return true;
        }).start();
        // Its first paint rehearsed into a hidden target, out of the open animation. After the engine's programs,
        // which its canvas's glass and text use.
        plan.task("crystalgui:desktop-warm").after("crystalgui:desktop", "crystalgraphics:programs").render(task -> {
            return !CgUiScreen.isInstalled() || CgUiScreen.rehearseStep();
        }).start();
    }

    /** The end of every host frame, title screen included: the desktop's tick (the game read, the next mode). */
    @Override
    public void onFrame(long frame) {
        CgUiScreen.tick();
    }

    /** F3+T and a resource pack change: re-reads the icons and stylesheets. Font families are CrystalGraphics'. */
    @Override
    public void onReload() {
        // Caches before the sheets: the restyle re-resolves every asset(...) and icon(...) against them.
        CgUiSpriteRegistry.clearCache();
        // Revalidated, not cleared: re-parsing every icon takes seconds to pick up the one that changed.
        int icons = SvgDocument.revalidate();
        FileIconTheme.invalidateCache();
        int sheets = StyleEngine.reloadStylesheets();
        CrystalGuiCore.LOGGER.info("CrystalGUI resource reload: {} stylesheet(s) re-read, {} icon(s) changed",
                sheets, icons);
    }
    
    /**
     * Frees every open view's textures, which no registry holds. Only at game shutdown, before CrystalGraphics
     * frees anything; caches and documents die with the process.
     */
    @Override
    public void onContextDestroy() {
        try {
            CgUiRenderer.contextDestroyed();
        } catch (Throwable t) {
            CrystalGuiCore.LOGGER.warn("CgUiClientLifecycle: failed to free the UI views' textures", t);
        }
    }
}
