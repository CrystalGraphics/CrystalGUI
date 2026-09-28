package com.example.fieldnotes.mc.forge;

import com.crystalgraphics.mc.shared.ForgeStart;
import com.example.fieldnotes.FieldNotes;

import net.minecraftforge.fml.common.Mod;

/**
 * What Forge constructs, whatever Minecraft is running: the jar's one {@code @Mod}, which picks this
 * jar's Forge variant for the running version and starts it. Names no Minecraft class, so one copy serves
 * every Forge node.
 */
@Mod(FieldNotes.MODID)
public final class ForgeBootstrap {

    public ForgeBootstrap() {
        ForgeStart.start(ForgeBootstrap.class, FieldNotes.MODID);
    }
}
