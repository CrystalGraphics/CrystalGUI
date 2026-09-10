/**
 * Tier 1 for LWJGL3 — what CrystalGUI needs from a window and a mouse on the 1.13+ targets, written
 * against LWJGL 3.2.2, {@code core} and CrystalGraphics' {@code platform}, and nothing else.
 *
 * <p>One compiled copy serves every 1.13+ target. The rule for anything added here: <b>a class lives
 * in the lowest tier whose dependencies it needs, and a class that needs one value from a higher tier
 * takes it as a constructor argument.</b> The GLFW window handle is the value case and arrives as a
 * {@code LongSupplier}; nothing here calls {@code Minecraft.getInstance()}.
 *
 * <p><b>A new cursor never touches this package.</b> {@code CursorBitmaps.artFor} in {@code core} is
 * the one keyword&rarr;picture table every platform reads; an adapter here caches one native per
 * {@link com.crystalgui.core.cursor.CursorArt} and enumerates no keywords of its own. The one table a
 * platform still owns is the set of shapes its own toolkit ships natively, keyed on
 * {@code CursorArt.name()}.
 *
 * <p>Pinned to LWJGL <b>3.2.2</b>, the oldest in the supported range (MC 1.13–1.16). A 3.2.2 call
 * runs on the 3.3.x a modern client ships; the reverse throws {@code NoSuchMethodError} on a client
 * this tier is supposed to serve.
 *
 * <p>The build enforces the boundary: importing {@code net.minecraft}, {@code com.mojang} or any
 * loader package fails {@code compileJava} with the offending files named.
 */
package com.crystalgui.mc.lwjgl3;
