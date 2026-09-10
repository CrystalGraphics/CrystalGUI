/**
 * Tier 1 for LWJGL2 — what CrystalGUI needs from a window and a mouse on the LWJGL2 targets, written
 * against LWJGL 2.9.4, {@code core} and CrystalGraphics' {@code platform}, and nothing else.
 *
 * <p>One compiled copy serves every LWJGL2 target. The rule for anything added here: <b>a class lives
 * in the lowest tier whose dependencies it needs, and a class that needs one value from a higher tier
 * takes it as a constructor argument.</b>
 *
 * <p><b>A new cursor never touches this package.</b> {@code CursorBitmaps.artFor} in {@code core} is
 * the one keyword&rarr;picture table every platform reads; an adapter here caches one native per
 * {@link com.crystalgui.core.cursor.CursorArt} and enumerates no keywords of its own. The copies of
 * that table drifted once — {@code slide-arrow} reached the LWJGL2 adapters and not GLFW,
 * {@code crosshair} the reverse — which is why it lives in exactly one place now.
 *
 * <p>The build enforces the boundary: importing {@code net.minecraft}, {@code com.mojang} or any
 * loader package fails {@code compileJava} with the offending files named.
 */
package com.crystalgui.mc.lwjgl2;
