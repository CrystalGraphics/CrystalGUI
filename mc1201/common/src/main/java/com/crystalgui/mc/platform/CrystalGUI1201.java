package com.crystalgui.mc.platform;

/**
 * Shared constants for all CrystalGUI mc1201 loader subprojects.
 * Kept in mc1201:common so every loader (Fabric, Forge, NeoForge) can import
 * without duplicating the strings.
 */
public final class CrystalGUI1201 {

    /** Mod identifier — must match modId in fabric.mod.json, mods.toml, etc. */
    public static final String MODID = "crystalgui";

    /** Human-readable mod name used in log messages. */
    public static final String NAME = "CrystalGUI";

    /** Mod version string (kept in sync with gradle.properties). */
    public static final String VERSION = "1.0.0";

    private CrystalGUI1201() {}
}
