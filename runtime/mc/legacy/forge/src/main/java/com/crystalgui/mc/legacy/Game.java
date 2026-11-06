package com.crystalgui.mc.legacy;

import java.io.File;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.server.MinecraftServer;
//? if <1.9 {
/*import net.minecraft.server.management.ServerConfigurationManager;
*///?} else {
import net.minecraft.server.management.PlayerList;
//?}
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * The server-side members Minecraft renamed between 1.8.9 and 1.12.2, spelt once, so the rest of the
 * host reads the same on every plateau. The client's come from CrystalGraphics' {@code CgWorldQueries} and
 * {@code CgPlayers}.
 */
public final class Game {

    private Game() {
    }

    /** The running server — integrated or dedicated — or null when there is none. */
    @Nullable
    public static MinecraftServer server() {
        return FMLCommonHandler.instance().getMinecraftServerInstance();
    }

    /** Everyone connected, or an empty list before the player list exists. */
    public static List<EntityPlayerMP> players(MinecraftServer server) {
        //? if <1.9 {
        /*ServerConfigurationManager list = server.getConfigurationManager();
        return list == null ? Collections.<EntityPlayerMP>emptyList() : list.playerEntityList;
        *///?} else {
        PlayerList list = server.getPlayerList();
        return list == null ? Collections.<EntityPlayerMP>emptyList() : list.getPlayers();
        //?}
    }

    /** The connection a player is currently reached through; null for a fake player. */
    @Nullable
    public static NetHandlerPlayServer handler(EntityPlayerMP player) {
        //? if <1.9 {
        /*return player.playerNetServerHandler;
        *///?} else {
        return player.connection;
        //?}
    }

    /** The entity a connection currently points at — replaced on every respawn. */
    @Nullable
    public static EntityPlayerMP player(NetHandlerPlayServer handler) {
        //? if <1.12 {
        /*return handler.playerEntity;
        *///?} else {
        return handler.player;
        //?}
    }

    /** The overworld's save directory, or null before a world has loaded. */
    @Nullable
    public static File overworldDirectory() {
        WorldServer overworld = DimensionManager.getWorld(0);
        return overworld == null ? null : overworld.getSaveHandler().getWorldDirectory();
    }
}
