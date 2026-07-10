package com.crystalgui.mc.platform;

/**
 * CrystalGUI's mc1201 lifecycle service — one singleton shared across Fabric, Forge, and NeoForge.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>{@link #onReload()} — called by loader-specific resource-reload callbacks;
 *       triggers any CrystalGUI UI state invalidation that depends on resource packs.</li>
 *   <li>{@link #onContextDestroy()} — called by loader-specific shutdown hooks;
 *       cleans up CrystalGUI-owned state before the GL context is torn down.</li>
 * </ul>
 *
 * <p>This class does NOT register a {@code CgPlatformService} — that is CrystalGraphics'
 * responsibility and is handled by the CrystalGraphics loader (Fabric / Forge / NeoForge)
 * running alongside this mod.
 *
 * <p>No GL calls in constructors or static initializers.
 */
public final class CgPlatformService1201 {

    private static final CgPlatformService1201 INSTANCE = new CgPlatformService1201();

    public static CgPlatformService1201 getInstance() {
        return INSTANCE;
    }

    private CgPlatformService1201() {}

    /**
     * Called when Minecraft reloads resource packs.
     * Placeholder — wire CrystalGUI asset-reload hooks here as the UI engine matures.
     */
    public void onReload() {
        // TODO: invalidate stylesheet caches, reload UI fonts, etc.
    }

    /**
     * Called just before the GL context is destroyed (MC shutdown).
     * Placeholder — wire CrystalGUI teardown here as the UI engine matures.
     */
    public void onContextDestroy() {
        // TODO: release CrystalGUI GL resources (draw-list GPU buffers, atlas textures, etc.)
    }
}
