package com.crystalgui.mc.neoforge.lang;

import com.crystalgraphics.mc.shared.NeoForgeStart;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * The language mod's one {@code @Mod} class for every NeoForge, as {@code com.crystalgui.mc.neoforge.CrystalGUINeoForge}
 * is the host's: its own mod id and variant table, and the same selector. It runs nothing itself: being a mod NeoForge
 * lists is what lets CrystalGraphics read the jar's lifecycle listener ({@code docs/CGUI_LANGUAGE_STACK.md} § 1a).
 */
@Mod(CrystalGUILangNeoForge.MODID)
public final class CrystalGUILangNeoForge {

    public static final String MODID = "crystalgui_language";

    public CrystalGUILangNeoForge(IEventBus modBus) {
        NeoForgeStart.start(CrystalGUILangNeoForge.class, MODID, modBus);
    }
}
