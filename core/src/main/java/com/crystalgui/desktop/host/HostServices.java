package com.crystalgui.desktop.host;

import javax.annotation.Nullable;

import com.crystalgraphics.net.protocol.CgProtocolConnection;
import com.crystalgraphics.platform.input.CgSystemInput;

/**
 * <b>What only a host can say to the desktop</b>. Everything the game answers (directories, window, language, server,
 * connection) the desktop reads from CrystalGraphics' services itself ({@code CgGameDirectory}, {@code CgHostWindow},
 * {@code CgI18n}, {@code CgNetwork}); a host implements {@link #reinjectKey}, and overrides the rest only where it is
 * not a Minecraft client.
 *
 * <pre>{@code
 * HostSession.install(new HostServices() {
 *     public void reinjectKey(CgSystemInput.Keyboard.Event key) { screen.keyLeftByDesktop(key); }
 * }, CrystalEditor.KIND);
 * }</pre>
 *
 * <ul>
 *   <li>{@link #connection()} is read once per host entry, so it may return a different object, or null, at any time: a
 *       reconnect is a new connection carrying the same workspace.</li>
 *   <li>Not asked: what the window is called, which application opens, where a session goes. Every host answers
 *       those the same way, so the engine decides them.</li>
 * </ul>
 */
public interface HostServices {

    /**
     * The scale every host runs the desktop at. <b>Deliberately not the game's GUI Scale</b>, which sizes 16px
     * widgets and an 8px bitmap font; the shipped sheets are authored and measured at this.
     */
    float DEFAULT_UI_SCALE = 2f;

    /** What the arrangement of this host's desktop is saved under; {@code "client"} unless a host runs another kind. */
    default String desktopId() {
        return "client";
    }

    /** A connection of the host's own, for one that is not the game's; null, the default, means the game's. */
    @Nullable
    default CgProtocolConnection<Object> connection() {
        return null;
    }

    /**
     * Gives the game a key the desktop dispatched and did not take, as though the desktop had never seen it. Only a
     * desktop running on its own thread hands keys back, a frame late: it takes every key while it holds the keyboard,
     * since it cannot answer in time, and returns the ones it left. Render thread.
     *
     * <pre>{@code
     * public void reinjectKey(CgSystemInput.Keyboard.Event key) {
     *     Screen screen = minecraft.screen;
     *     if (screen instanceof CgUiScreen own) own.keyLeftByDesktop(key);   // Escape closes our own screen
     *     else if (screen != null) deliver(screen, key);                      // chat, an inventory: its own handling
     * }
     * }</pre>
     *
     * <ul>
     *   <li>A key carries either a key code ({@code character} 0) or a character ({@code key} {@code KEY_NONE}), as
     *       {@code CgSystemInput.Keyboard.Event} does; give each to the matching handler.</li>
     *   <li>Releases come back too, so a screen that tracks held keys sees both halves.</li>
     * </ul>
     */
    void reinjectKey(CgSystemInput.Keyboard.Event key);

    /**
     * Puts the desktop on screen, touching no window: what the Show Desktop key does. Nothing, the default, for a host
     * whose desktop is always up (the harness). Client thread.
     *
     * <pre>{@code
     * public void showDesktop() {
     *     if (HostSession.mayOpen()) minecraft.setScreen(new CgUiScreen());
     * }
     * }</pre>
     */
    default void showDesktop() {
    }
}
