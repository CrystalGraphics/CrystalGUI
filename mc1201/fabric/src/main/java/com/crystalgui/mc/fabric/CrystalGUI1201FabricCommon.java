package com.crystalgui.mc.fabric;

import com.crystalgui.mc.platform.Lifecycle1201;

import net.fabricmc.api.ModInitializer;

/**
 * Both sides. Separate from the client initialiser because a dedicated server runs this one and must
 * touch no client class -- the workspace and the connection table are server-side.
 */
public final class CrystalGUI1201FabricCommon implements ModInitializer {

    @Override
    public void onInitialize() {
        Lifecycle1201.bootstrap(NetworkChannel1201.get());
        CgUiFabricEvents.registerCommon();
    }
}
