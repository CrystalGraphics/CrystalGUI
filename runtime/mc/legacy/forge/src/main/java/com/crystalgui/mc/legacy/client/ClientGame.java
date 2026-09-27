package com.crystalgui.mc.legacy.client;

import java.io.File;

import javax.annotation.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;

/**
 * The client-side members Minecraft renamed between 1.8.9 and 1.12.2, spelt once. Client-only: it names
 * {@link Minecraft}, which a dedicated server does not have.
 */
public final class ClientGame {

    private ClientGame() {
    }

    @Nullable
    public static WorldClient world() {
        //? if <1.9 {
        /*return Minecraft.getMinecraft().theWorld;
        *///?} else {
        return Minecraft.getMinecraft().world;
        //?}
    }

    @Nullable
    public static EntityPlayerSP player() {
        //? if <1.9 {
        /*return Minecraft.getMinecraft().thePlayer;
        *///?} else {
        return Minecraft.getMinecraft().player;
        //?}
    }

    /** A world is loaded and the player is in it. */
    public static boolean inWorld() {
        return world() != null && player() != null;
    }

    /** The game directory: {@code .minecraft}, or the instance's. */
    public static File gameDir() {
        //? if <1.12 {
        /*return Minecraft.getMinecraft().mcDataDir;
        *///?} else {
        return Minecraft.getMinecraft().gameDir;
        //?}
    }
}
