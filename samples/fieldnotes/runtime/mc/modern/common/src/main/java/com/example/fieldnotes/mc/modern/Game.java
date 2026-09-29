package com.example.fieldnotes.mc.modern;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;

/**
 * What Field Notes asks of Minecraft. Compiled once per version, and shipped once per loader node under
 * that node's package, since Forge, NeoForge and Fabric each run it under different names.
 */
public final class Game {

    private Game() {
    }

    /** The running version, as the game reports it. */
    public static String version() {
        return SharedConstants.getCurrentVersion().getName();
    }

    /** A {@code fieldnotes:} identifier, built the way this Minecraft builds one. */
    public static String id(String path) {
        //? if >=1.21 {
        /*return ResourceLocation.fromNamespaceAndPath("fieldnotes", path).toString();
        *///?} else {
        return new ResourceLocation("fieldnotes", path).toString();
        //?}
    }
}
