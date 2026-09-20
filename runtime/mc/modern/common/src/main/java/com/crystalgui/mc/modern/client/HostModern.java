package com.crystalgui.mc.modern.client;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgui.desktop.host.HostServices;
import com.crystalgui.mc.modern.net.Connections;
import com.crystalgui.net.protocol.ProtocolConnection;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * How MC 1.20.x answers the questions only a platform can. Nothing here decides anything.
 *
 * @see HostServices
 */
final class HostModern implements HostServices {

    /** Which desktop's record this is — per installation, not per world. @see HostServices#desktopId */
    private static final String DESKTOP_ID = "client";

    @Override
    public Path installationDirectory() {
        Minecraft mc = Minecraft.getInstance();
        // THE GAME DIRECTORY, not crystalgui/ inside it -- the engine adds that segment.
        return (mc == null ? new File(".") : mc.gameDirectory).toPath();
    }

    /**
     * The save directory in single-player, null on someone else's server.
     *
     * <p>{@code getSingleplayerServer()} is the whole test: it answers non-null exactly when this
     * client is also the server, which is when its own disk holds the world.</p>
     */
    @Override
    @Nullable
    public Path localWorldDirectory() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc == null ? null : mc.getSingleplayerServer();
        return server == null ? null : server.getWorldPath(LevelResource.ROOT);
    }

    /**
     * Device pixels per logical pixel, and <b>not</b> the player's GUI Scale.
     *
     * <p>It was that, and GUI Scale is the wrong input: it sizes 16px widgets and a bitmap font, so a
     * player who wants a readable inventory gets a desktop scaled to match and the two cannot both be
     * right.</p>
     */
    @Override
    public float uiScale() {
        return HostServices.DEFAULT_UI_SCALE;
    }

    @Override
    public int surfaceWidth() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getWidth();
    }

    @Override
    public int surfaceHeight() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getWindow() == null ? 0 : mc.getWindow().getHeight();
    }

    @Override
    public String desktopId() {
        return DESKTOP_ID;
    }

    @Override
    @Nullable
    public ProtocolConnection<Object> connection() {
        // Re-asked every frame, so a reconnect is a different object carrying the same workspace and
        // DesktopHost rebinds rather than rebuilds. Null means no server right now: supported.
        return Connections.client();
    }

    /** The game's language setting, {@code "ja_jp"} on 1.20.x. */
    @Override
    public Locale locale() {
        Minecraft mc = Minecraft.getInstance();
        return HostServices.gameLocale(mc == null ? null : mc.options.languageCode);
    }
}
