package com.crystalgui.mc.legacy.probe;

import java.util.Arrays;
import java.util.List;

import com.crystalgui.mc.legacy.net.CgUiConnections;
import com.crystalgui.probe.ServerSmoke;

import net.minecraftforge.fml.common.FMLCommonHandler;
import com.crystalgui.mc.legacy.Game;
import net.minecraft.server.MinecraftServer;

/**
 * The Forge 1.8–1.12.2 half of the dedicated-server smoke: five facts and a way to stop the server.
 *
 * <p>Every check, the report and the exit code are {@link ServerSmoke}'s. This
 * host gained the enumerated client package and the JVM class-load reader with the move — both were
 * written for 1.20.x and are not era-specific, and the hand-written list they replace had already
 * rotted: it named a class W3 deleted, so it passed forever, while three that arrived later were never
 * checked at all.</p>
 */
public final class CgUiServerSmoke {

    private CgUiServerSmoke() {
    }

    /** Whether {@code -PcgServerSmoke} was passed. */
    public static boolean enabled() {
        return ServerSmoke.enabled();
    }

    /**
     * Called from {@code FMLServerStartedEvent} — the first moment the server is genuinely up, and late
     * enough that a mod which failed to load has already taken the process down with it.
     */
    public static void run() {
        ServerSmoke.run(new Host());
    }

    /**
     * <b>The one class in {@code .probe} that a dedicated server loads</b>, and the anchor every other
     * class in there is enumerated from — so it must ship in the same container as them, and it
     * excludes itself from its own never-loaded set.
     */
    private static final class Host implements ServerSmoke.Host {

        @Override
        public String label() {
            MinecraftServer server = Game.server();
            return "legacy " + (server == null ? "?" : server.getMinecraftVersion());
        }

        @Override
        public boolean isDedicatedServer() {
            MinecraftServer server = Game.server();
            return FMLCommonHandler.instance().getSide().isServer()
                    && server != null && server.isDedicatedServer();
        }

        @Override
        public boolean connectionsRegistered() {
            return CgUiConnections.isRegistered();
        }

        /** This node's shipped root, {@code ...mc.v1122}, read off the class: the jar relocates it. */
        private final String root = CgUiServerSmoke.class.getPackage().getName()
                .substring(0, CgUiServerSmoke.class.getPackage().getName().lastIndexOf('.'));

        @Override
        public List<String> clientPackages() {
            // .probe too: every class in there is client-only bar this smoke, which excludes itself.
            return Arrays.asList(root + ".client", root + ".probe");
        }

        @Override
        public List<String> alsoNeverLoaded() {
            return Arrays.asList(
                    // Client-side content outside those packages: the client entry is what keeps both
                    // unreachable from common code.
                    root + ".CrystalGUILegacyClient",
                    root + ".example.MachineExampleClientLegacy",
                    // Naming this from a common path is the commonest spelling of the bug.
                    "net.minecraft.client.Minecraft");
        }

        @Override
        public void halt() {
            MinecraftServer server = Game.server();
            if (server != null) server.initiateShutdown();
        }
    }
}
