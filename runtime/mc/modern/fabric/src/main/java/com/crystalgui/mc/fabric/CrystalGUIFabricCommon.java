package com.crystalgui.mc.fabric;

import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
*///?} else {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//?}

import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * Everything Fabric that runs on <b>both sides</b> — the common entry point and the {@link Events}
 * subscriptions. The connections are CrystalGraphics'.
 *
 * <p>Separate from {@link CrystalGUIFabric} because a dedicated server runs this one and must
 * touch no client class: the workspace is server-side. The client half of
 * {@code Events} is registered from there instead.</p>
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks.</p>
 */
public final class CrystalGUIFabricCommon implements VariantEntry {

    /** @param context null — Fabric hands an entry point nothing; {@code FabricBootstrap} passes it on. */
    @Override
    public void start(Object context) {
        // WHICH VARIANT, in the log rather than the crash report: Fabric Loader exposes no crash
        // callable, so unlike Forge and 1.7.10 there is nothing to register with. @see CrashVariant
        CrystalGuiCore.LOGGER.info("[cgui] {}: {}", CrashVariant.label(NAME),
                CrashVariant.report(CrystalGUIFabricCommon.class));
        LifecycleCrystalGUI.bootstrap();
    }

    // -- Events -----------------------------------------------------------------

    /** Fabric event subscription. Every body is one forward into {@link LifecycleCrystalGUI}. */
    static final class Events {

        private Events() {}

        static void registerClient() {
            LifecycleCrystalGUI.bootstrapClient();
            //? if >=26.1 {
            /*CgUiKeybinds.all().forEach(KeyMappingHelper::registerKeyMapping);
            *///?} else {
            CgUiKeybinds.all().forEach(KeyBindingHelper::registerKeyBinding);
            //?}
        }
    }
}
