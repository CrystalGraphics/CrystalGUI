package com.crystalgui.mc.modern.probe;

import java.util.Arrays;
import java.util.List;

import com.crystalgui.mc.modern.net.Connections;
import com.crystalgui.probe.ServerSmoke;

import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;

/**
 * The MC 1.20.x half of the dedicated-server smoke: five facts and a way to stop the server.
 *
 * <p>Every check, the report and the exit code are {@link ServerSmoke}'s.</p>
 */
public final class ServerSmokeModern {

    private ServerSmokeModern() {}

    /** @see ServerSmoke#enabled() */
    public static boolean enabled() {
        return ServerSmoke.enabled();
    }

    /** Called from each loader's server-STARTED event. */
    public static void run(@Nullable MinecraftServer server) {
        ServerSmoke.run(new Host(server));
    }

    /**
     * <b>The one class in {@code .probe} that a dedicated server loads</b>, and the anchor every other
     * class in there is enumerated from — so it must ship in the same container as them, and it
     * excludes itself from its own never-loaded set.
     */
    private static final class Host implements ServerSmoke.Host {

        @Nullable
        private final MinecraftServer server;

        Host(@Nullable MinecraftServer server) {
            this.server = server;
        }

        @Override
        public String label() {
            return "1.20.x";
        }

        @Override
        public boolean isDedicatedServer() {
            return server != null && server.isDedicatedServer();
        }

        @Override
        public boolean connectionsRegistered() {
            return Connections.isRegistered();
        }

        @Override
        public List<String> clientPackages() {
            // .probe TOO, since the probes moved out of .client. ConnectionProbeModern is registered
            // from client init only, and this enumeration is what LifecycleCrystalGUI's comment says
            // would catch that ever stopping being true. The smoke excludes itself.
            return Arrays.asList("com.crystalgui.mc.modern.client", "com.crystalgui.mc.modern.probe");
        }

        @Override
        public List<String> alsoNeverLoaded() {
            return Arrays.asList(
                    // Client-side content that does not live in the client package.
                    "com.crystalgui.mc.modern.example.MachineExampleClientModern",
                    // Naming this from a common path is the commonest spelling of the bug.
                    "net.minecraft.client.Minecraft");
        }

        @Override
        public void halt() {
            if (server != null) server.halt(false);
        }
    }
}
