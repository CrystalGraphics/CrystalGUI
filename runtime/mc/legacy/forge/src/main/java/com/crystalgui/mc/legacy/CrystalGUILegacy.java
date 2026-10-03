package com.crystalgui.mc.legacy;

import java.util.List;

import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.FmlEvents;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.mc.legacy.example.MachineExampleLegacy;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgui.mc.legacy.net.CgUiWorkspaceHost;
import com.crystalgui.mc.legacy.probe.CgUiServerSmoke;
import com.crystalgui.net.window.WindowProtocol;
import com.crystalgui.text.syntax.LanguageRegistry;

import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.ICrashCallable;

/**
 * CrystalGUI on Forge 1.8–1.12.2, both sides: the crash-report line, networking, the workspace and window
 * lifecycles, and the server smoke. Everything a screen needs is {@link CrystalGUILegacyClient}'s, which
 * only a client constructs — so a dedicated server never loads a class naming {@code GuiScreen}.
 *
 * <p>No platform registration: CrystalGraphics owns every {@code CgPlatformService} and registered it in
 * its own preInit, which FML runs first ({@code required-after:crystalgraphics}).</p>
 */
public final class CrystalGUILegacy implements VariantEntry {

    private static final String NAME = "CrystalGUI";

    @Override
    public void start(Object context) {
        FmlEvents events = (FmlEvents) context;
        events.on("FMLPreInitializationEvent", event ->
                FMLCommonHandler.instance().registerCrashCallable(new ICrashCallable() {
                    @Override public String getLabel() { return CrashVariant.label(NAME); }
                    @Override public String call() { return CrashVariant.report(CrystalGUILegacy.class); }
                }));
        events.on("FMLInitializationEvent", event -> init());
        events.on("FMLPostInitializationEvent", event -> announceLanguageTier());
        events.on("FMLServerStartedEvent", event -> {
            if (CgUiServerSmoke.enabled()) CgUiServerSmoke.run();
        });
        events.on("FMLServerStoppingEvent", event -> CgUiWorkspaceHost.reset());
    }

    /** CrystalGUI's protocols, both sides, contributed to CrystalGraphics' connections. */
    private static void init() {
        CgUiWorkspaceHost.register();
        WindowProtocol.register();
        CgNetwork.onPeerClosed(CgUiWorkspaceHost::forget);
        MachineExampleLegacy.registerCommon();
    }

    /**
     * Says which tier of the language stack is installed. From postInit: reading the registry bootstraps
     * the engines, which fix whether a {@code ScriptService} exists at that moment, and the language mod's
     * init runs after this one's.
     */
    private static void announceLanguageTier() {
        List<String> contributors = LanguageRegistry.contributors();
        if (contributors.isEmpty()) {
            CrystalGuiCore.LOGGER.info("{}: no language stack installed -- source files colour from core's "
                    + "built-in lexers and are not analysed. Install crystalgui_language for grammars, analysis "
                    + "and scripting.", NAME);
        } else {
            CrystalGuiCore.LOGGER.info("{}: language contributors: {}", NAME, contributors);
        }
    }
}
