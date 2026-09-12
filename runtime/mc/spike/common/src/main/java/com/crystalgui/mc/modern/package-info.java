/**
 * The vanilla-facing host for MC 1.13+ — tier 2: it may name {@code net.minecraft} and
 * {@code com.mojang}, and it may not name a loader.
 *
 * <p><b>"modern" is the era, not a version</b>, which is the whole point of the name: one source tree
 * compiled once per target, so a new Minecraft version is a row in the target list rather than a
 * directory. A class in here carries no version in its name for the same reason — a class named for a
 * version is a class nobody dares reuse. The sibling tree that <i>is</i> named for a version,
 * {@code runtime/mc/1710}, is honest about it: it covers exactly one.
 *
 * <p>Anything here that needs no Minecraft type belongs one tier down, in CrystalGraphics'
 * {@code runtime/lwjgl/3}, where it is compiled once and shared with every other target instead of
 * copied per era.
 */
package com.crystalgui.mc.modern;
