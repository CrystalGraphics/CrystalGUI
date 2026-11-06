package com.crystalgui.mc.neoforge;

import com.crystalgraphics.mc.shared.CrashVariant;
import com.crystalgraphics.mc.shared.FmlSide;
import com.crystalgraphics.mc.shared.VariantEntry;
import com.crystalgui.core.CrystalGuiCore;
import com.crystalgui.mc.modern.client.CgUiKeybinds;
import com.crystalgui.mc.modern.platform.LifecycleCrystalGUI;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import static com.crystalgui.mc.modern.platform.CrystalGUI.NAME;

/**
 * Everything NeoForge — the mod entry point and its {@link Events} subscriptions. The connections are
 * CrystalGraphics'.
 *
 * <p>The engine is deliberately absent: CrystalGraphics loads as its own mod and owns the render,
 * reload and shutdown hooks. What is left is CrystalGUI's own, and every event body is one forward
 * into {@link LifecycleCrystalGUI}.</p>
 *
 * <p><b>No {@code @Mod} here.</b> One jar carries a NeoForge variant per era and the scanner reads
 * every class in it, so two variants bearing the same annotation are two mods of one id. The single
 * annotated class is {@code NeoForgeBootstrap}, beside this one, which reads
 * {@code variants.json} and constructs the row matching the running Minecraft version.</p>
 */
public final class CrystalGUINeoForge implements VariantEntry {

    /** @param context the {@code IEventBus} NeoForge handed {@code NeoForgeBootstrap}. */
    @Override
    public void start(Object context) {
        IEventBus modBus = (IEventBus) context;
        // WHICH VARIANT, in the log rather than the crash report: NeoForge exposes no crash callable --
        // CrashReportExtender is its own -- so unlike Forge and 1.7.10 there is nothing to register
        // with, and `latest.log` is the file a report is attached with anyway. @see CrashVariant
        CrystalGuiCore.LOGGER.info("[cgui] {}: {}", CrashVariant.label(NAME),
                CrashVariant.report(CrystalGUINeoForge.class));
        LifecycleCrystalGUI.bootstrap();
        Events.register(modBus);
    }

    // -- Events -----------------------------------------------------------------

    /** NeoForge event subscription. Every body is one forward into {@link LifecycleCrystalGUI}. */
    static final class Events {

        private Events() {}

        static void register(IEventBus modBus) {
            if (FmlSide.isClient(FMLLoader.class)) ClientBus.register(modBus);
        }

        /**
         * The client listeners, in a class a dedicated server never loads.
         *
         * <p><b>A guard around the CALL is not enough.</b> A method reference is an {@code invokedynamic} in
         * the method that writes it, so its parameter type resolves when that method RUNS, whatever branch
         * it sits in -- {@code RuntimeDistCleaner} then refuses {@code ScreenEvent} with
         * {@code BootstrapMethodError: Attempted to load class ... for invalid dist DEDICATED_SERVER} and
         * the mod never constructs. Only moving the references into another class defers it, because that
         * class is loaded on first use. Forge's twin gets this for free from
         * {@code @EventBusSubscriber(Dist.CLIENT)}; NeoForge subscribes by hand, so it must be said.</p>
         *
         * <p>The "client-only guard one level too high" defect {@code CgUiServerSmoke} was written for,
         * found by its 1.20.x twin on the first NeoForge boot.</p>
         */
        private static final class ClientBus {

            private ClientBus() {}

            static void register(IEventBus modBus) {
                modBus.addListener(ClientBus::onRegisterKeyMappings);
            }

            private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
                LifecycleCrystalGUI.bootstrapClient();
                CgUiKeybinds.all().forEach(event::register);
            }
        }
    }
}
