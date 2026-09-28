package com.crystalgui.mc.modern.platform;

/**
 * Shared constants for every modern loader node.
 * Kept in the common branch so every loader (Fabric, Forge, NeoForge) can import
 * without duplicating the strings.
 */
public final class CrystalGUI {

    /** Mod identifier — must match modId in fabric.mod.json, mods.toml, etc. */
    public static final String MODID = "crystalgui";

    /** Human-readable mod name used in log messages. */
    public static final String NAME = "CrystalGUI";

    private CrystalGUI() {}
}
