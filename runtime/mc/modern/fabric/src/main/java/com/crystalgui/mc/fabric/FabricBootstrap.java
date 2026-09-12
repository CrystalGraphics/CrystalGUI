package com.crystalgui.mc.fabric;

import com.crystalgui.mc.shared.Bootstrap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import static com.crystalgui.mc.modern.platform.CrystalGUI.MODID;

/**
 * What {@code fabric.mod.json} names, whatever Minecraft version the jar runs on.
 *
 * <p>Fabric constructs <b>every</b> entry point its descriptor names, so naming the variants there
 * directly would construct all of them — including one compiled against a Minecraft that is not
 * running. This is the single name in the descriptor; {@code variants.json} says which variant it
 * hands off to.</p>
 *
 * <p>Fabric fires its two entry points separately — {@code main} on both sides, {@code client} on a
 * client — which is why this implements both. The variant is resolved once; the second call finds it
 * already chosen.</p>
 */
public final class FabricBootstrap implements ModInitializer, ClientModInitializer {

    @Override
    public void onInitialize() {
        Bootstrap.startCommon(FabricBootstrap.class, MODID, "fabric", minecraftVersion(), null);
    }

    @Override
    public void onInitializeClient() {
        Bootstrap.startClient(FabricBootstrap.class, MODID, "fabric", minecraftVersion(), null);
    }

    /** First-party, never a guess: the loader's own metadata for the `minecraft` container. */
    private static String minecraftVersion() {
        return FabricLoader.getInstance()
                .getModContainer("minecraft")
                .orElseThrow(() -> new IllegalStateException("Fabric reports no `minecraft` mod container"))
                .getMetadata()
                .getVersion()
                .getFriendlyString();
    }
}
