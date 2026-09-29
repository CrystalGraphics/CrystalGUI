package com.crystalgui.harness;

import com.crystalgraphics.harness.HarnessExtension;
import com.crystalgraphics.harness.SceneRegistry;
import com.crystalgraphics.harness.config.SceneDescriptor;
import com.crystalgui.harness.scene.CgGpuTraceProbeScene;
import com.crystalgui.harness.scene.CgUiDesktopScene;
import com.crystalgui.harness.scene.CgUiGalleryScene;
import com.crystalgui.harness.scene.CgUiNewEngineGalleryScene;
import com.crystalgui.harness.scene.CgUiSpriteStressScene;
import com.crystalgui.harness.scene.CgUiStylingScene;
import com.crystalgui.harness.scene.CgUiSvgIconScene;
import com.crystalgui.harness.scene.CgUiTextGammaScene;
import com.crystalgui.harness.scene.CgUiTextScene;
import com.crystalgui.harness.scene.CgUiTextStressScene;
import com.crystalgui.harness.scene.CgUiTimelineScene;
import com.crystalgui.harness.scene.CgUiVisualLayersScene;
import com.crystalgui.harness.scene.RpgConsoleScene;
import com.crystalgui.style.theme.UiThemeManager;

import java.util.List;
import java.util.logging.Logger;

/**
 * CrystalGUI's scenes in the harness, and what Ctrl+R has to re-read for them.
 *
 * <p>The harness names no CrystalGUI type, so this is found through {@code ServiceLoader}; the
 * {@code harness-scenes} build puts it on {@code :gl-debug-harness:runHarness}' classpath.</p>
 */
public final class CrystalGuiHarness implements HarnessExtension {

    private static final Logger LOGGER = Logger.getLogger(CrystalGuiHarness.class.getName());

    @Override
    public void registerScenes(SceneRegistry reg) {
        reg.register(
            SceneDescriptor.builder("cgui-styling")
                .description("CrystalGUI stylesheet test: selectors, combinators, pseudo-classes, transitions")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiStylingScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-visual-layers")
                .description("CrystalGUI Visual Layers: opacity isolation + overflow:hidden mask/scissor, minimal side-by-side on/off comparisons")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiVisualLayersScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-text")
                .description("CrystalGUI UIText: auto-sizing, wrapping, font-family fallback, live bindTextTo (SPACE to cycle)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiTextScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-text-stress")
                .description("CrystalGUI UIText load benchmark: 100 labels, three update patterns (static / same-length / varying-length), prints a summary table")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiTextStressScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-text-gamma")
                .description("Text gamma and contrast: off, Chromium, strong, heavy, and the default fading strong into heavy by size, on the UI faces, dark and light panels, UI scale 1 and 2 (G cycles, S toggles scale)")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiTextGammaScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-new-gallery")
                .description("M6 NEW ENGINE: every ported widget in one scrolling column, over UIDocument + the box tree -- the counterpart to cgui-gallery, and the only thing that can see whether a ported widget actually DRAWS")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiNewEngineGalleryScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-desktop")
                .description("M6 NEW ENGINE: CrystalOS -- stacking windows, drag, resize, cascade, the taskbar, per-window modality, maximise, and CrystalEditor running as a window. The counterpart to cgui-new-gallery: that one answers whether a ported WIDGET draws, this one whether a ported WINDOW behaves")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.06f, 0.06f, 0.08f, 1.0f)
                .build(),
            () -> new CgUiDesktopScene()
        );

        reg.register(
            SceneDescriptor.builder("rpg-console")
                .description("RPG-Core's Status screen as a STYLESHEET FIXTURE: authors rpgcore:console and rpgcore:menu with no Minecraft client. Needs -Pharness.assetRoots pointing at the mod's src/main/resources; Ctrl+R re-reads the theme AND the sheets")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.02f, 0.10f, 0.14f, 1.0f)
                .build(),
            () -> new RpgConsoleScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-sprite-stress")
                .description("CrystalGUI 9-slice sprite stress: N sprite-backed cells in a grid, for measuring what a sprite costs to draw. -Dcrystalgui.spritestress.count / .cell / .rotate")
                .defaultWidth(1920)
                .defaultHeight(1080)
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiSpriteStressScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-svg-icon")
                .description("Every shipped icon in a labelled grid -- red = failed to load, amber = drew nothing. Scroll to scale.")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiSvgIconScene()
        );

        reg.register(
            SceneDescriptor.builder("gpu-trace-probe")
                .description("DIAGNOSTIC, exits on its own: GPU timer queries land in their frame within 3 frames and match a fence-waited control within 5%")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.09f, 0.09f, 0.11f, 1.0f)
                .build(),
            () -> new CgGpuTraceProbeScene()
        );

        reg.register(
            SceneDescriptor.builder("cgui-timeline")
                .description("CrystalGUI timeline primitives: 10,000 spans and 600 frame bars — wheel zoom, drag pan, frame stepping, range selection")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.09f, 0.09f, 0.11f, 1.0f)
                .build(),
            () -> new CgUiTimelineScene()
        );

        // The front door: every widget, one page each, with a live Ore <-> default theme toggle.
        // Deliberately no defaultWidth/defaultHeight — nothing reads SceneDescriptor's, and the
        // gallery's root is `width: 100%`, so `--width=1000 --height=700` gives it more room.
        reg.register(
            SceneDescriptor.builder("cgui-gallery")
                .description("CrystalGUI gallery: every widget, one page each, with a live theme toggle")
                .lifecycleMode(SceneDescriptor.LifecycleMode.INTERACTIVE)
                .category(SceneDescriptor.Category.SCENE)
                .needsFbo(false)
                .needsDepthBuffer(false)
                .clearColor(0.1f, 0.1f, 0.1f, 1.0f)
                .build(),
            () -> new CgUiGalleryScene()
        );
    }

    /**
     * Themes before anything else: a theme captures its token table at registration, so the stylesheets
     * CrystalGraphics' reload then re-reads (through {@code CgUiLifecycle}, a reload listener) would
     * otherwise re-substitute against the old table, and an edited token would change nothing.
     */
    @Override
    public void beforeReload() {
        int themes = UiThemeManager.getInstance().reloadFromDisk();
        LOGGER.info("[CrystalGuiHarness] Ctrl+R: reloaded " + themes + " theme file(s)");
    }

    @Override
    public List<String> shaderNamespaces() {
        return List.of("crystalgui");
    }
}
