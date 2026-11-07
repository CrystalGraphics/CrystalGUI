package com.crystalgui.mc.forge;

import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import net.minecraftforge.api.distmarker.Dist;
//? if >=1.19 {
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
//?} elif >=1.17 {
/*import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
*///?} else {
/*import net.minecraftforge.fml.DeferredWorkQueue;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
*///?}
//? if >=1.21.6 {
/*import net.minecraftforge.eventbus.api.bus.BusGroup;
*///?} else {
import net.minecraftforge.eventbus.api.IEventBus;
//?}
//? if >=1.17 {
import net.minecraftforge.fml.CrashReportCallables;
//?} else {
/*import net.minecraftforge.fml.CrashReportExtender;
import net.minecraftforge.fml.common.ICrashCallable;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
*///?}
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
//? if <1.17 {
/*import com.crystalgraphics.platform.CgPlatform;
import com.crystalgui.core.provider.Providers;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
*///?}

import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * Everything Forge — the mod entry point and its {@link Events} subscriptions. The connections are
 * CrystalGraphics'.
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks. What is left is CrystalGUI's own, and every event body is one forward
 * into {@link LifecycleCrystalGUI}.</p>
 *
 * <p><b>No {@code @Mod} and no {@code @EventBusSubscriber} here.</b> One jar carries a Forge variant
 * per era, and Forge's scanner reads every class in it — two variants bearing the same annotation
 * are two mods of one id, which it refuses to load rather than choosing between. The single
 * annotated class is {@code ForgeBootstrap}, beside this one; it reads
 * {@code variants.json}, picks the row for the running Minecraft version, and constructs this.
 * Subscriptions that were annotations are {@link Events#register} calls now.</p>
 */
public final class CrystalGUIForge implements VariantEntry {

    /** @param context Forge's {@link FMLJavaModLoadingContext}, from the bootstrapper. */
    @Override
    public void start(Object context) {
        // WHICH VARIANT, in the crash report itself. One jar carries a host per loader, each relocated
        // under its own prefix, so a trace naming com.crystalgui.mc.forge.common.* is the only thing
        // that says which one ran -- and asking a reporter to work that out is asking them to know how
        // the jar is built. @see CrashVariant
        //? if >=1.17 {
        CrashReportCallables.registerCrashCallable(CrashVariant.label(NAME),
                () -> CrashVariant.report(CrystalGUIForge.class));
        //?} else {
        /*// Forge 29-31 keep callables in a plain list that ForgeMod iterates while mods construct on
        // parallel workers, so registering here races it: register on the main thread after setup.
        ((FMLJavaModLoadingContext) context).getModEventBus().addListener((FMLCommonSetupEvent event) ->
                DeferredWorkQueue.runLater(() -> CrashReportExtender.registerCrashCallable(new ICrashCallable() {
                    @Override
                    public String getLabel() {
                        return CrashVariant.label(NAME);
                    }

                    @Override
                    public String call() {
                        return CrashVariant.report(CrystalGUIForge.class);
                    }
                })));
        *///?}
        //? if <1.17 {
        /*// ModLauncher 5 lists no resource inside a mod file, so ServiceLoader finds no provider there.
        CgPlatform.provide(Providers.Copies.SERVICE, CrystalGUIForge::resourceCopies);
        *///?}
        LifecycleCrystalGUI.bootstrap();
        Events.register((FMLJavaModLoadingContext) context);
    }

    //? if <1.17 {
    /*private static List<Path> resourceCopies(String path) {
        List<Path> copies = new ArrayList<>();
        for (ModFileInfo file : FMLLoader.getLoadingModList().getModFiles()) {
            Path found = file.getFile().findResource(path);
            if (Files.exists(found)) copies.add(found);
        }
        return copies;
    }
    *///?}

    // -- Events -----------------------------------------------------------------

    /** Forge event subscription. Every body is one forward into {@link LifecycleCrystalGUI}. */
    public static final class Events {

        private Events() {}

        /** The client's buses, from the entry point rather than from an annotation. */
        public static void register(FMLJavaModLoadingContext context) {
            // Forge 56's EventBus 7 hands out one bus per mod's bus group
            //? if >=1.21.6 {
            /*BusGroup modBus = context.getModBusGroup();
            *///?} else {
            IEventBus modBus = context.getModEventBus();
            //?}

            // A SEPARATE CLASS, not a branch inside this one: naming a client-only event type in a
            // method of `Events` would resolve it when a dedicated server links this class.
            if (FMLEnvironment.dist == Dist.CLIENT) ClientBus.register(modBus);
        }

        /** Client-only, and a class of its own so a dedicated server never links one of these types. */
        public static final class ClientBus {

            private ClientBus() {}

            //? if >=1.21.6 {
            /*static void register(BusGroup modBus) {
                registerKeyMappings(modBus);
            }
            *///?} elif >=1.19 {
            static void register(IEventBus modBus) {
                modBus.addListener(ClientBus::onRegisterKeyMappings);
            }
            //?} else {
            /*static void register(IEventBus modBus) {
                modBus.addListener(ClientBus::onClientSetup);
            }
            *///?}

            // Forge 64 (26.1) moved its mod-bus events onto a static BUS of their own.
            //? if >=26.1 {
            /*private static void registerKeyMappings(BusGroup modBus) {
                RegisterKeyMappingsEvent.BUS.addListener(ClientBus::onRegisterKeyMappings);
            }
            *///?} elif >=1.21.6 {
            /*private static void registerKeyMappings(BusGroup modBus) {
                RegisterKeyMappingsEvent.getBus(modBus).addListener(ClientBus::onRegisterKeyMappings);
            }
            *///?}

            // The client's start, on the main thread: the key event from Forge 41, client setup before it -- which
            // Forge 29-31 reach through DeferredWorkQueue. The keys themselves are CrystalGraphics'.
            //? if >=1.19 {
            private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
                LifecycleCrystalGUI.bootstrapClient();
            }
            //?} elif >=1.17 {
            /*private static void onClientSetup(FMLClientSetupEvent event) {
                event.enqueueWork(LifecycleCrystalGUI::bootstrapClient);
            }
            *///?} else {
            /*private static void onClientSetup(FMLClientSetupEvent event) {
                DeferredWorkQueue.runLater(LifecycleCrystalGUI::bootstrapClient);
            }
            *///?}
        }
    }
}
