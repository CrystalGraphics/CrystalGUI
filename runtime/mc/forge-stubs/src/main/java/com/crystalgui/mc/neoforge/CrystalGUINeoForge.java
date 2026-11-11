package com.crystalgui.mc.neoforge;

import com.crystalgraphics.mc.shared.NeoForgeStart;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * CrystalGUI's one {@code @Mod} class for every NeoForge the jar runs on: what makes it a mod NeoForge lists, so
 * CrystalGraphics reads its lifecycle listeners ({@code META-INF/crystalgraphics/listeners}), which start everything.
 * It runs nothing itself; {@link NeoForgeStart} refuses a version the jar does not support.
 *
 * <p>A second {@code @Mod} of this id anywhere in the jar is a second mod to NeoForge's scanner.</p>
 */
@Mod(CrystalGUINeoForge.MODID)
public final class CrystalGUINeoForge {

    public static final String MODID = "crystalgui";

    public CrystalGUINeoForge(IEventBus modBus) {
        NeoForgeStart.start(CrystalGUINeoForge.class, MODID, modBus);
    }
}
